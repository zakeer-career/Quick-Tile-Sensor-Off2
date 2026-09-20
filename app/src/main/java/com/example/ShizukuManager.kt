package com.example

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * Structured result of shell command execution (Shizuku or Root).
 * Exposes process exit codes, stdout, stderr, and timeout status.
 */
data class CommandResult(
    val success: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    companion object {
        fun failure(message: String, exitCode: Int = -1): CommandResult =
            CommandResult(success = false, exitCode = exitCode, stdout = "", stderr = message)
    }
}

/**
 * Centralized Binder Transaction Codes for android.hardware.ISensorPrivacyManager.
 *
 * Compatibility Assumptions:
 * - ISensorPrivacyManager is an internal Android system service AIDL interface ("android.hardware.ISensorPrivacyManager").
 * - Transaction codes correspond directly to method declarations in ISensorPrivacyManager.aidl across AOSP versions:
 *   * Android 12+ (API 31+): setSensorPrivacy is code 9, setToggleSensorPrivacy is code 10,
 *     isToggleSensorPrivacyEnabled is code 8, isCombinedToggleSensorPrivacyEnabled is code 7,
 *     isSensorPrivacyEnabled is code 6.
 *   * Android 11 (API 30): setSensorPrivacy is code 5, isSensorPrivacyEnabled is code 4.
 *   * Android 10 (API 29): setSensorPrivacy is code 4, isSensorPrivacyEnabled is code 3.
 *
 * OEM Divergence & Fallback Behavior:
 * - Certain OEM ROMs (e.g., Xiaomi HyperOS/MIUI, Samsung OneUI, Transsion HiOS) may re-order internal AIDL methods.
 * - Because Binder transaction numbers may vary on heavily modified vendor trees:
 *   1. We attempt the version-preferred transaction code first.
 *   2. If a RemoteException or failure occurs, we iterate across known alternate version codes.
 *   3. If direct Binder IPC fails, we fall back to shell commands ('service call sensor_privacy <code...>').
 *   4. If shell IPC fails, we fall back to Settings.Global/Secure table modification.
 *   5. Critical: Operations are ONLY considered successful if an authoritative read-back of the sensor state
 *      matches the requested target state.
 */
object SensorPrivacyTransactions {
    const val DESCRIPTOR = "android.hardware.ISensorPrivacyManager"

    // Setters
    const val SET_GLOBAL_PRIVACY_S_PLUS = 9       // Android 12+ (API 31+)
    const val SET_GLOBAL_PRIVACY_R = 5            // Android 11 (API 30)
    const val SET_GLOBAL_PRIVACY_Q = 4            // Android 10 (API 29)
    const val SET_TOGGLE_PRIVACY = 10             // Android 12+: setToggleSensorPrivacy(int userId, int source, int sensor, boolean enable)

    // Getters
    const val IS_GLOBAL_PRIVACY_S_PLUS = 6        // Android 12+: boolean isSensorPrivacyEnabled()
    const val IS_GLOBAL_PRIVACY_R = 4             // Android 11
    const val IS_GLOBAL_PRIVACY_Q = 3             // Android 10
    const val IS_TOGGLE_PRIVACY = 8               // Android 12+: boolean isToggleSensorPrivacyEnabled(int toggleType, int sensor)
    const val IS_COMBINED_TOGGLE_PRIVACY = 7      // Android 12 fallback: boolean isCombinedToggleSensorPrivacyEnabled(int sensor)

    // Sensors
    const val SENSOR_MICROPHONE = 1
    const val SENSOR_CAMERA = 2

    // Sources & Toggle Types
    const val TOGGLE_TYPE_SOFTWARE = 1
    const val SOURCE_QS_TILE = 1

    fun getPreferredSetGlobalCode(): Int = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> SET_GLOBAL_PRIVACY_S_PLUS
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> SET_GLOBAL_PRIVACY_R
        else -> SET_GLOBAL_PRIVACY_Q
    }

    fun getAllSetGlobalCodes(): IntArray {
        val preferred = getPreferredSetGlobalCode()
        return intArrayOf(preferred, SET_GLOBAL_PRIVACY_S_PLUS, SET_GLOBAL_PRIVACY_R, SET_GLOBAL_PRIVACY_Q).distinct().toIntArray()
    }

    fun getAllQueryGlobalCodes(): IntArray = intArrayOf(
        IS_GLOBAL_PRIVACY_S_PLUS,
        IS_GLOBAL_PRIVACY_R,
        IS_GLOBAL_PRIVACY_Q
    )
}

/**
 * Backward compatibility alias for SensorPrivacyTransactions.
 */
typealias SensorPrivacyCodes = SensorPrivacyTransactions

object ShizukuManager {
    private const val TAG = "ShizukuManager"
    const val SHIZUKU_REQ_CODE = 1001

    @Volatile
    private var isBinderConnected: Boolean = false
    @Volatile
    private var listenerInitialized: Boolean = false
    @Volatile
    private var appContextRef: java.lang.ref.WeakReference<Context>? = null

    @Volatile
    private var cachedSensorPrivacyBinder: IBinder? = null

    private val stateOperationLock = ReentrantLock()

    /**
     * Validates whether the ISensorPrivacyManager Binder interface is active and accessible via Shizuku.
     */
    fun validateSensorPrivacyInterface(): Boolean {
        if (!isShizukuRunning() || !isShizukuAuthorized()) {
            return false
        }
        val binder = getSensorPrivacyBinder()
        val isValid = binder != null && binder.isBinderAlive
        if (!isValid) {
            Log.w(TAG, "Sensor privacy binder interface is not valid or not alive")
        }
        return isValid
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        isBinderConnected = true
        cachedSensorPrivacyBinder = null
        Log.i(TAG, "Shizuku binder received process-wide")
        appContextRef?.get()?.let { ctx ->
            CoroutineScope(Dispatchers.IO).launch {
                // Wait for Shizuku permission check to fully sync (up to 3 seconds)
                var count = 0
                while (count < 30 && (!isShizukuRunning() || !isShizukuAuthorized())) {
                    delay(100)
                    count++
                }
                autoGrantSecureSettings(ctx)
                notifyTileServiceToUpdate(ctx)
                TileLogManager.logPrivilegeEvent(
                    ctx,
                    "Shizuku Setup Complete",
                    "Shizuku setup completed fully. Tile auto-updated to operational state.",
                    LogLevel.SUCCESS
                )
            }
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        isBinderConnected = false
        cachedSensorPrivacyBinder = null
        Log.w(TAG, "Shizuku binder disconnected process-wide")
        appContextRef?.get()?.let { ctx ->
            notifyTileServiceToUpdate(ctx)
            TileLogManager.logPrivilegeEvent(ctx, "Shizuku Disconnected", "Shizuku IPC binder died", LogLevel.WARN)
        }
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Shizuku permission granted. Notifying tile and services.")
            appContextRef?.get()?.let { ctx ->
                autoGrantSecureSettings(ctx)
                notifyTileServiceToUpdate(ctx)
                TileLogManager.logPrivilegeEvent(
                    ctx,
                    "Shizuku Authorized",
                    "Permission granted by user. Tile auto-updated to operational state.",
                    LogLevel.SUCCESS
                )
            }
        }
    }

    /**
     * Initializes process-wide Shizuku AIDL binder listeners.
     * Safe to call repeatedly from Application or Services.
     */
    fun initialize(context: Context) {
        appContextRef = java.lang.ref.WeakReference(context.applicationContext)
        if (listenerInitialized) return
        synchronized(this) {
            if (listenerInitialized) return
            listenerInitialized = true
            try {
                Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
                Shizuku.addBinderDeadListener(binderDeadListener)
                Shizuku.addRequestPermissionResultListener(permissionResultListener)
                Log.d(TAG, "Registered process-wide Shizuku binder and permission listeners")
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException registering Shizuku listeners: ${e.message}")
            } catch (e: IllegalStateException) {
                Log.w(TAG, "IllegalStateException registering Shizuku listeners: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register Shizuku binder listeners: ${e.message}")
            }
        }
    }

    /**
     * Suspends until the Shizuku IPC binder is connected and authorized,
     * or until timeoutMs expires. Essential for background TileService operations.
     */
    suspend fun awaitShizukuBinder(timeoutMs: Long = 600L): Boolean {
        if (isShizukuRunning() && isShizukuAuthorized()) return true
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (isShizukuRunning() && isShizukuAuthorized()) {
                return true
            }
            delay(40)
        }
        return isShizukuRunning() && isShizukuAuthorized()
    }

    fun isShizukuInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: RemoteException) {
            false
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    fun isShizukuAuthorized(): Boolean {
        return if (isShizukuRunning()) {
            try {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (e: SecurityException) {
                false
            } catch (e: Exception) {
                false
            }
        } else {
            false
        }
    }

    enum class RootState {
        UNKNOWN,
        AVAILABLE,
        UNAVAILABLE
    }

    @Volatile
    private var cachedRootState: RootState = RootState.UNKNOWN

    @Volatile private var cachedMethodGlobalPrivacy: Method? = null
    @Volatile private var cachedMethodAllSensorPrivacy: Method? = null
    @Volatile private var cachedMethodSensorPrivacyInt: Method? = null
    @Volatile private var spmReflectionInitialized = false

    private fun initSpmReflection(cls: Class<*>) {
        if (spmReflectionInitialized) return
        synchronized(this) {
            if (spmReflectionInitialized) return
            try {
                cachedMethodGlobalPrivacy = cls.methods.firstOrNull { it.name == "isSensorPrivacyEnabled" && it.parameterTypes.isEmpty() }?.apply { isAccessible = true }
                cachedMethodAllSensorPrivacy = cls.methods.firstOrNull { it.name == "isAllSensorPrivacyEnabled" && it.parameterTypes.isEmpty() }?.apply { isAccessible = true }
                cachedMethodSensorPrivacyInt = cls.methods.firstOrNull {
                    it.name == "isSensorPrivacyEnabled" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
                }?.apply { isAccessible = true }
            } catch (t: NoSuchMethodException) {
                Log.d(TAG, "SPM method not found: ${t.message}")
            } catch (t: SecurityException) {
                Log.d(TAG, "SPM reflection security denied: ${t.message}")
            } catch (t: Exception) {
                Log.d(TAG, "SPM reflection init note: ${t.message}")
            }
            spmReflectionInitialized = true
        }
    }

    @Volatile private var cachedShizukuNewProcessMethod: Method? = null
    @Volatile private var shizukuReflectionInitialized = false

    private fun getShizukuNewProcessMethod(): Method? {
        if (shizukuReflectionInitialized) return cachedShizukuNewProcessMethod
        synchronized(this) {
            if (shizukuReflectionInitialized) return cachedShizukuNewProcessMethod
            try {
                val m = Shizuku::class.java.declaredMethods.firstOrNull {
                    it.name == "newProcess" && it.parameterTypes.size == 3
                } ?: Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                m.isAccessible = true
                cachedShizukuNewProcessMethod = m
            } catch (t: NoSuchMethodException) {
                Log.w(TAG, "Shizuku newProcess method not found: ${t.message}")
            } catch (t: Exception) {
                Log.w(TAG, "Shizuku reflection method lookup failed: ${t.message}")
            }
            shizukuReflectionInitialized = true
            return cachedShizukuNewProcessMethod
        }
    }

    /**
     * Obtains the raw sensor_privacy IBinder using Shizuku's Binder Wrapper.
     * Direct Binder transactions execute with fast in-memory IPC using public android.os.IBinder APIs,
     * completely eliminating Android Hidden API linking errors.
     */
    fun getSensorPrivacyBinder(): IBinder? {
        val existing = cachedSensorPrivacyBinder
        if (existing != null && existing.isBinderAlive) {
            return existing
        }
        if (!isShizukuRunning() || !isShizukuAuthorized()) return null
        return try {
            val binder = SystemServiceHelper.getSystemService("sensor_privacy") ?: return null
            val wrapper = ShizukuBinderWrapper(binder)
            cachedSensorPrivacyBinder = wrapper
            wrapper
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException acquiring sensor_privacy binder: ${e.message}")
            null
        } catch (e: RemoteException) {
            Log.w(TAG, "RemoteException acquiring sensor_privacy binder: ${e.message}")
            null
        } catch (e: Exception) {
            Log.d(TAG, "Could not acquire sensor_privacy binder via Shizuku: ${e.message}")
            null
        }
    }

    /**
     * Direct Parcel Binder query for global sensor privacy state.
     * Note: Uses the Android sensor privacy system service through Binder.
     * This relies on internal service behavior that may vary by Android version/OEM.
     */
    fun queryDirectSensorPrivacy(): SensorPrivacyState {
        val wrapper = getSensorPrivacyBinder() ?: return SensorPrivacyState.UNKNOWN
        for (code in SensorPrivacyCodes.getAllQueryGlobalCodes()) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(SensorPrivacyCodes.DESCRIPTOR)
                val res = wrapper.transact(code, data, reply, 0)
                if (res) {
                    reply.readException()
                    val isEnabled = reply.readInt() != 0
                    return if (isEnabled) SensorPrivacyState.ENABLED else SensorPrivacyState.DISABLED
                }
            } catch (e: RemoteException) {
                Log.d(TAG, "RemoteException querying code $code: ${e.message}")
            } catch (e: SecurityException) {
                Log.d(TAG, "SecurityException querying code $code: ${e.message}")
            } catch (t: Exception) {
                Log.d(TAG, "Exception querying code $code: ${t.message}")
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
        return SensorPrivacyState.UNKNOWN
    }

    /**
     * Direct Parcel Binder query for individual toggle state (Mic=1, Camera=2).
     * Note: Uses the Android sensor privacy system service through Binder.
     * This relies on internal service behavior that may vary by Android version/OEM.
     */
    fun queryDirectToggleSensorPrivacy(sensorCode: Int): SensorPrivacyState {
        val wrapper = getSensorPrivacyBinder() ?: return SensorPrivacyState.UNKNOWN
        // Code 8: boolean isToggleSensorPrivacyEnabled(int toggleType, int sensor)
        val data8 = Parcel.obtain()
        val reply8 = Parcel.obtain()
        try {
            data8.writeInterfaceToken(SensorPrivacyCodes.DESCRIPTOR)
            data8.writeInt(SensorPrivacyCodes.TOGGLE_TYPE_SOFTWARE)
            data8.writeInt(sensorCode)
            if (wrapper.transact(SensorPrivacyCodes.IS_TOGGLE_PRIVACY, data8, reply8, 0)) {
                reply8.readException()
                val isEnabled = reply8.readInt() != 0
                return if (isEnabled) SensorPrivacyState.ENABLED else SensorPrivacyState.DISABLED
            }
        } catch (e: RemoteException) {
            Log.d(TAG, "RemoteException in toggle query code 8: ${e.message}")
        } catch (t: Exception) {
            Log.d(TAG, "Exception in toggle query code 8: ${t.message}")
        } finally {
            data8.recycle()
            reply8.recycle()
        }

        // Fallback Code 7: boolean isCombinedToggleSensorPrivacyEnabled(int sensor)
        val data7 = Parcel.obtain()
        val reply7 = Parcel.obtain()
        try {
            data7.writeInterfaceToken(SensorPrivacyCodes.DESCRIPTOR)
            data7.writeInt(sensorCode)
            if (wrapper.transact(SensorPrivacyCodes.IS_COMBINED_TOGGLE_PRIVACY, data7, reply7, 0)) {
                reply7.readException()
                val isEnabled = reply7.readInt() != 0
                return if (isEnabled) SensorPrivacyState.ENABLED else SensorPrivacyState.DISABLED
            }
        } catch (e: RemoteException) {
            Log.d(TAG, "RemoteException in toggle query code 7: ${e.message}")
        } catch (t: Exception) {
            Log.d(TAG, "Exception in toggle query code 7: ${t.message}")
        } finally {
            data7.recycle()
            reply7.recycle()
        }

        return SensorPrivacyState.UNKNOWN
    }

    fun getRootState(): RootState {
        if (cachedRootState != RootState.UNKNOWN) return cachedRootState
        val hasSuBinary = try {
            val paths = arrayOf(
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/system/sd/xbin/su",
                "/system/bin/failsafe/su",
                "/data/local/xbin/su",
                "/data/local/bin/su",
                "/data/local/su"
            )
            paths.any { File(it).exists() }
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }

        if (!hasSuBinary) {
            cachedRootState = RootState.UNAVAILABLE
            return RootState.UNAVAILABLE
        }

        // If invoked from the Main thread, trigger background check and return UNKNOWN
        if (Looper.myLooper() == Looper.getMainLooper()) {
            CoroutineScope(Dispatchers.IO).launch {
                refreshRootState()
            }
            return RootState.UNKNOWN
        }

        return refreshRootState()
    }

    fun refreshRootState(): RootState {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val line = process.inputStream.bufferedReader().use { it.readLine() }
            process.errorStream.bufferedReader().use { while (it.readLine() != null) {} }
            process.waitFor()
            val available = line != null && line.contains("uid=0")
            val state = if (available) RootState.AVAILABLE else RootState.UNAVAILABLE
            cachedRootState = state
            state
        } catch (e: java.io.IOException) {
            cachedRootState = RootState.UNAVAILABLE
            RootState.UNAVAILABLE
        } catch (e: SecurityException) {
            cachedRootState = RootState.UNAVAILABLE
            RootState.UNAVAILABLE
        } catch (e: Exception) {
            cachedRootState = RootState.UNAVAILABLE
            RootState.UNAVAILABLE
        }
    }

    fun isRootAvailable(): Boolean {
        return getRootState() == RootState.AVAILABLE
    }

    /**
     * Retrieves current Android User ID cleanly from Process.myUid() without hidden API reflection.
     * Standard across Android versions supporting multi-user profiles.
     */
    fun getCurrentUserId(): Int {
        return try {
            android.os.Process.myUid() / 100000
        } catch (t: Exception) {
            0
        }
    }

    fun hasSecureSettingsPermission(context: Context): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    }

    private val isAutoGranting = AtomicBoolean(false)

    /**
     * Attempts to auto-grant WRITE_SECURE_SETTINGS permission via Shizuku in the background.
     * Deduplicated with AtomicBoolean to prevent parallel process storms.
     */
    fun autoGrantSecureSettings(context: Context) {
        if (hasSecureSettingsPermission(context)) return
        if (!isShizukuRunning() || !isShizukuAuthorized()) return
        if (!isAutoGranting.compareAndSet(false, true)) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runShizukuCommand("pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
                if (hasSecureSettingsPermission(context)) {
                    Log.i(TAG, "Successfully auto-granted WRITE_SECURE_SETTINGS via Shizuku")
                    TileLogManager.logPrivilegeEvent(
                        context,
                        "WRITE_SECURE_SETTINGS Granted",
                        "App acquired WRITE_SECURE_SETTINGS via Shizuku! Fast direct settings operations enabled.",
                        LogLevel.SUCCESS
                    )
                }
            } catch (e: SecurityException) {
                Log.d(TAG, "SecurityException auto-granting WRITE_SECURE_SETTINGS: ${e.message}")
            } catch (e: Exception) {
                Log.d(TAG, "Auto-grant WRITE_SECURE_SETTINGS note: ${e.message}")
            } finally {
                isAutoGranting.set(false)
            }
        }
    }

    /**
     * Checks if any viable privilege is available to toggle sensor privacy.
     * True if WRITE_SECURE_SETTINGS is granted, Shizuku is authorized, or Root SU is available.
     */
    fun isPrivilegeAvailable(context: Context): Boolean {
        if (hasSecureSettingsPermission(context)) return true
        if (isShizukuRunning() && isShizukuAuthorized()) {
            autoGrantSecureSettings(context)
            return true
        }
        return isRootAvailable()
    }

    /**
     * On rooted devices, attempts to auto-start the Shizuku server daemon via root SU on boot.
     */
    fun tryAutoStartShizukuViaRoot(context: Context): Boolean {
        if (!isRootAvailable()) return false
        if (isShizukuRunning()) return true
        Log.i(TAG, "Attempting to auto-start Shizuku daemon via root SU...")
        val starterCmds = arrayOf(
            "/system/bin/sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh",
            "/data/user/0/moe.shizuku.privileged.api/files/starter",
            "/data/data/moe.shizuku.privileged.api/files/starter"
        )
        for (cmd in starterCmds) {
            try {
                runRootCommand(cmd)
                Thread.sleep(300)
                if (isShizukuRunning()) {
                    Log.i(TAG, "Shizuku successfully started via root command: $cmd")
                    TileLogManager.logPrivilegeEvent(context, "Shizuku Root Auto-Start", "Shizuku daemon started via root successfully", LogLevel.SUCCESS)
                    return true
                }
            } catch (e: java.io.IOException) {
                Log.d(TAG, "Starter command attempt failed: $cmd - ${e.message}")
            } catch (e: Exception) {
                Log.d(TAG, "Starter command attempt failed: $cmd - ${e.message}")
            }
        }
        return isShizukuRunning()
    }

    fun requestShizukuPermission() {
        if (isShizukuRunning()) {
            try {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(SHIZUKU_REQ_CODE)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException requesting Shizuku permission", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request Shizuku permission", e)
            }
        } else {
            Log.d(TAG, "Shizuku is not running yet")
        }
    }

    fun getAdbGrantCommand(context: Context): String {
        return "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
    }

    /**
     * Executes direct low-level Binder transact calls across Shizuku IPC.
     * Bypasses the shell, sub-processes, and ART runtime entirely.
     */
    fun invokeDirectSensorPrivacyTransact(turnOff: Boolean): Boolean {
        val wrapper = getSensorPrivacyBinder() ?: return false
        val targetVal = if (turnOff) 1 else 0

        // 1. Direct low-level Parcel Binder transact for global sensor privacy:
        val txCodes = SensorPrivacyCodes.getAllSetGlobalCodes()
        for (txCode in txCodes) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(SensorPrivacyCodes.DESCRIPTOR)
                data.writeInt(targetVal)
                val res = wrapper.transact(txCode, data, reply, 0)
                if (res) {
                    try {
                        reply.readException()
                        Log.d(TAG, "Direct Binder transact code $txCode succeeded fast")
                        return true
                    } catch (e: SecurityException) {
                        Log.d(TAG, "Direct Binder code $txCode security exception: ${e.message}")
                    } catch (e: RemoteException) {
                        Log.d(TAG, "Direct Binder code $txCode remote exception: ${e.message}")
                    } catch (e: Exception) {
                        Log.d(TAG, "Direct Binder code $txCode returned exception: ${e.message}")
                    }
                }
            } catch (e: RemoteException) {
                Log.d(TAG, "RemoteException invoking code $txCode: ${e.message}")
            } catch (t: Exception) {
                Log.d(TAG, "Exception invoking code $txCode: ${t.message}")
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        // 2. Fallback: Granular Mic (1) and Camera (2) toggle via transaction code 10:
        // void setToggleSensorPrivacy(int userId, int source, int sensor, boolean enable)
        var granularSuccess = true
        val currentUserId = getCurrentUserId()
        for (sensor in intArrayOf(SensorPrivacyCodes.SENSOR_MICROPHONE, SensorPrivacyCodes.SENSOR_CAMERA)) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(SensorPrivacyCodes.DESCRIPTOR)
                data.writeInt(currentUserId)
                data.writeInt(SensorPrivacyCodes.SOURCE_QS_TILE)
                data.writeInt(sensor)
                data.writeInt(targetVal)
                if (wrapper.transact(SensorPrivacyCodes.SET_TOGGLE_PRIVACY, data, reply, 0)) {
                    reply.readException()
                } else {
                    granularSuccess = false
                }
            } catch (e: RemoteException) {
                Log.d(TAG, "RemoteException in granular toggle for sensor $sensor: ${e.message}")
                granularSuccess = false
            } catch (t: Exception) {
                Log.d(TAG, "Exception in granular toggle for sensor $sensor: ${t.message}")
                granularSuccess = false
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        return granularSuccess
    }

    /**
     * Executes direct low-level Binder transact for individual sensors (Camera, Mic) across Shizuku IPC.
     */
    fun invokeDirectIndividualSensorTransact(sensorId: String, turnOff: Boolean): Boolean {
        val wrapper = getSensorPrivacyBinder() ?: return false
        val sensorCode = when (sensorId.lowercase()) {
            "camera" -> SensorPrivacyCodes.SENSOR_CAMERA
            "mic", "microphone" -> SensorPrivacyCodes.SENSOR_MICROPHONE
            else -> 0
        }
        if (sensorCode == 0) return false
        val targetVal = if (turnOff) 1 else 0

        // Direct Parcel Binder transaction via code 10
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        val currentUserId = getCurrentUserId()
        return try {
            data.writeInterfaceToken(SensorPrivacyCodes.DESCRIPTOR)
            data.writeInt(currentUserId)
            data.writeInt(SensorPrivacyCodes.SOURCE_QS_TILE)
            data.writeInt(sensorCode)
            data.writeInt(targetVal)
            val res = wrapper.transact(SensorPrivacyCodes.SET_TOGGLE_PRIVACY, data, reply, 0)
            if (res) {
                try {
                    reply.readException()
                    true
                } catch (e: Exception) {
                    Log.d(TAG, "Exception in readException for $sensorId: ${e.message}")
                    false
                }
            } else {
                false
            }
        } catch (e: RemoteException) {
            Log.d(TAG, "RemoteException in individual sensor transact $sensorId: ${e.message}")
            false
        } catch (t: Exception) {
            Log.d(TAG, "Exception in individual sensor transact $sensorId: ${t.message}")
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Main method to toggle SensorsOff state using direct SensorPrivacyManager system calls:
     * 1. Direct AIDL Binder Transact via Shizuku
     * 2. Direct WRITE_SECURE_SETTINGS ContentResolver write
     * 3. Lean native shell command fallback
     * 4. Asynchronous Settings table synchronization
     * 5. SharedPreferences persistence
     */
    fun setSensorsOffState(context: Context, turnOff: Boolean, skipNotify: Boolean = false): Boolean {
        stateOperationLock.lock()
        try {
            val targetValue = if (turnOff) 1 else 0
            Log.d(TAG, "Setting SensorsOff state to $targetValue (turnOff=$turnOff)")

            // Check privilege availability
            val isPrivileged = isPrivilegeAvailable(context)
            if (!isPrivileged) {
                Log.w(TAG, "Cannot execute setSensorsOffState: Neither Shizuku nor Root privilege is available")
                return false
            }

            // 1. Direct AIDL / Binder Transact via Shizuku
            val directBinderSuccess = invokeDirectSensorPrivacyTransact(turnOff)

            // 2. Shell command fallback if direct Binder transact did not report success
            if (!directBinderSuccess) {
                val txCodes = SensorPrivacyCodes.getAllSetGlobalCodes()
                var shellSuccess = false
                for (txCode in txCodes) {
                    val fastCommand = "service call sensor_privacy $txCode i32 $targetValue"
                    if (isShizukuRunning() && isShizukuAuthorized()) {
                        try {
                            val res = runShizukuCommand(fastCommand)
                            if (res.success) {
                                shellSuccess = true
                                break
                            } else {
                                Log.w(TAG, "Shizuku shell command with code $txCode returned exit code ${res.exitCode}: ${res.stderr}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Shizuku execution failed for code $txCode", e)
                        }
                    }

                    if (!shellSuccess && isRootAvailable()) {
                        try {
                            val res = runRootCommand(fastCommand)
                            if (res.success) {
                                shellSuccess = true
                                break
                            } else {
                                Log.w(TAG, "Root command with code $txCode returned exit code ${res.exitCode}: ${res.stderr}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Root SU execution failed for code $txCode", e)
                        }
                    }
                }
            }

            // 3. Authoritative read-back verification: verify actual hardware sensor state
            var confirmedState = getSensorsOffState(context)
            if (!confirmedState.matchesRequested(turnOff)) {
                try {
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                confirmedState = getSensorsOffState(context)
            }

            val verified = confirmedState.matchesRequested(turnOff)
            if (!verified) {
                Log.w(TAG, "Read-back verification failed for setSensorsOffState: requested=$turnOff, actual=$confirmedState. Success will NOT be claimed.")
                return false
            }

            // 4. Synchronize Settings ONLY after authoritative verification succeeds
            val hasSecureSettings = hasSecureSettingsPermission(context)
            if (hasSecureSettings) {
                try {
                    Settings.Global.putInt(context.contentResolver, "sensors_off", targetValue)
                    Settings.Secure.putInt(context.contentResolver, "sensor_privacy", targetValue)
                } catch (e: Exception) {
                    Log.d(TAG, "Settings write note: ${e.message}")
                }
            }

            // 5. Update local SharedPreferences with confirmed state
            val prefs = context.getSharedPreferences("sensors_off_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("sensors_off_enabled", turnOff)
                .putBoolean("sensor_blocked_camera", turnOff)
                .putBoolean("sensor_blocked_mic", turnOff)
                .putBoolean("sensor_blocked_motion", turnOff)
                .putBoolean("sensor_blocked_gyro", turnOff)
                .putBoolean("sensor_blocked_proximity", turnOff)
                .putBoolean("sensor_blocked_light", turnOff)
                .apply()

            if (!skipNotify) {
                notifyTileServiceToUpdate(context)
            }

            return true
        } finally {
            stateOperationLock.unlock()
        }
    }

    /**
     * Toggles an individual hardware sensor (Camera, Mic, Motion, etc.)
     */
    fun setIndividualSensorState(context: Context, sensorId: String, turnOff: Boolean, skipNotify: Boolean = false): Boolean {
        stateOperationLock.lock()
        try {
            Log.d(TAG, "Setting individual sensor '$sensorId' blocked state to $turnOff")
            val sensorCode = when (sensorId.lowercase()) {
                "camera" -> SensorPrivacyCodes.SENSOR_CAMERA
                "mic", "microphone" -> SensorPrivacyCodes.SENSOR_MICROPHONE
                else -> 0
            }

            // Android's SensorPrivacyManager only supports individual toggles for Camera (2) and Microphone (1).
            // Non-toggleable sensors (motion, gyro, proximity, light) are only affected by global Sensors Off.
            if (sensorCode == 0) {
                Log.w(TAG, "Individual sensor '$sensorId' is not supported by AOSP SensorPrivacyManager")
                return false
            }

            val isPrivileged = isPrivilegeAvailable(context)
            if (!isPrivileged) {
                Log.w(TAG, "Cannot execute setIndividualSensorState: Neither Shizuku nor Root privilege is available")
                return false
            }

            val targetVal = if (turnOff) 1 else 0

            // 1. Direct AIDL / Parcel Binder Transact via Shizuku
            val directSuccess = invokeDirectIndividualSensorTransact(sensorId, turnOff)

            val currentUserId = getCurrentUserId()
            var shellSuccess = false
            if (!directSuccess) {
                val fastCmd = "service call sensor_privacy ${SensorPrivacyCodes.SET_TOGGLE_PRIVACY} i32 $currentUserId i32 ${SensorPrivacyCodes.SOURCE_QS_TILE} i32 $sensorCode i32 $targetVal"
                if (isShizukuRunning() && isShizukuAuthorized()) {
                    try {
                        val res = runShizukuCommand(fastCmd)
                        shellSuccess = res.success
                        if (!shellSuccess) {
                            Log.w(TAG, "Shizuku individual sensor toggle command failed: ${res.stderr}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Shizuku individual sensor toggle failed", e)
                    }
                }

                if (!shellSuccess && isRootAvailable()) {
                    try {
                        val res = runRootCommand(fastCmd)
                        shellSuccess = res.success
                        if (!shellSuccess) {
                            Log.w(TAG, "Root individual sensor toggle command failed: ${res.stderr}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Root individual sensor toggle failed", e)
                    }
                }
            }

            // 2. Authoritative read-back verification
            var confirmedState = getIndividualSensorState(context, sensorId)
            if (!confirmedState.matchesRequested(turnOff)) {
                try {
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                confirmedState = getIndividualSensorState(context, sensorId)
            }

            val verified = confirmedState.matchesRequested(turnOff)
            if (!verified) {
                Log.w(TAG, "Read-back verification failed for sensor $sensorId: requested=$turnOff, actual=$confirmedState. Success will NOT be claimed.")
                return false
            }

            // 3. Update Secure Settings ONLY after verification succeeds
            val hasSecureSettings = hasSecureSettingsPermission(context)
            if (hasSecureSettings) {
                try {
                    if (sensorId.equals("camera", ignoreCase = true)) {
                        Settings.Secure.putInt(context.contentResolver, "sensor_privacy_camera", targetVal)
                    } else if (sensorId.equals("mic", ignoreCase = true) || sensorId.equals("microphone", ignoreCase = true)) {
                        Settings.Secure.putInt(context.contentResolver, "sensor_privacy_microphone", targetVal)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Secure settings individual sensor write note: ${e.message}")
                }
            }

            // 4. Update local SharedPreferences
            val prefs = context.getSharedPreferences("sensors_off_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("sensor_blocked_$sensorId", turnOff).apply()

            // Explicitly request SystemUI to update the Quick Settings tile immediately
            if (!skipNotify) {
                notifyTileServiceToUpdate(context)
            }

            return true
        } finally {
            stateOperationLock.unlock()
        }
    }

    /**
     * Simultaneously toggles camera and microphone in a single operation.
     */
    fun setCamMicSensorState(context: Context, turnOff: Boolean, skipNotify: Boolean = false): Boolean {
        stateOperationLock.lock()
        try {
            val targetVal = if (turnOff) 1 else 0
            val isPrivileged = isPrivilegeAvailable(context)
            if (!isPrivileged) {
                Log.w(TAG, "Cannot execute setCamMicSensorState: Neither Shizuku nor Root privilege is available")
                return false
            }

            // 1. Direct Parcel Binder transact for both sensors
            val micDirect = invokeDirectIndividualSensorTransact("mic", turnOff)
            val camDirect = invokeDirectIndividualSensorTransact("camera", turnOff)
            val directSuccess = micDirect && camDirect

            // 2. Single combined native service call fallback
            if (!directSuccess) {
                val currentUserId = getCurrentUserId()
                val fastCmd = "service call sensor_privacy 10 i32 $currentUserId i32 1 i32 1 i32 $targetVal ; service call sensor_privacy 10 i32 $currentUserId i32 1 i32 2 i32 $targetVal"
                var shellSuccess = false
                if (isShizukuRunning() && isShizukuAuthorized()) {
                    try {
                        val res = runShizukuCommand(fastCmd)
                        shellSuccess = res.success
                        if (!shellSuccess) {
                            Log.w(TAG, "Shizuku cam/mic toggle command failed: ${res.stderr}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Shizuku combined cam_mic toggle failed", e)
                    }
                }
                if (!shellSuccess && isRootAvailable()) {
                    try {
                        val res = runRootCommand(fastCmd)
                        shellSuccess = res.success
                        if (!shellSuccess) {
                            Log.w(TAG, "Root cam/mic toggle command failed: ${res.stderr}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Root combined cam_mic toggle failed", e)
                    }
                }
            }

            // 3. Authoritative read-back verification
            var confirmedCam = getIndividualSensorState(context, "camera")
            var confirmedMic = getIndividualSensorState(context, "mic")
            if (!confirmedCam.matchesRequested(turnOff) || !confirmedMic.matchesRequested(turnOff)) {
                try {
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                confirmedCam = getIndividualSensorState(context, "camera")
                confirmedMic = getIndividualSensorState(context, "mic")
            }

            val verified = confirmedCam.matchesRequested(turnOff) && confirmedMic.matchesRequested(turnOff)
            if (!verified) {
                Log.w(TAG, "Read-back verification failed for cam/mic: requested=$turnOff, cam=$confirmedCam, mic=$confirmedMic. Success will NOT be claimed.")
                return false
            }

            // 4. Update Secure Settings ONLY after verification succeeds
            val hasSecureSettings = hasSecureSettingsPermission(context)
            if (hasSecureSettings) {
                try {
                    Settings.Secure.putInt(context.contentResolver, "sensor_privacy_camera", targetVal)
                    Settings.Secure.putInt(context.contentResolver, "sensor_privacy_microphone", targetVal)
                } catch (e: Exception) {
                    Log.d(TAG, "Secure settings cam/mic write note: ${e.message}")
                }
            }

            val prefs = context.getSharedPreferences("sensors_off_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("sensor_blocked_camera", turnOff)
                .putBoolean("sensor_blocked_mic", turnOff)
                .apply()

            if (!skipNotify) {
                notifyTileServiceToUpdate(context)
            }

            return true
        } finally {
            stateOperationLock.unlock()
        }
    }

    /**
     * Authoritatively queries the current state for an individual sensor.
     * Returns:
     * - ENABLED: Authoritatively verified that the sensor is off / blocked.
     * - DISABLED: Authoritatively verified that the sensor is on / available.
     * - UNKNOWN: Authoritative state could not be determined. UNKNOWN must never be interpreted as ENABLED or DISABLED.
     */
    fun getIndividualSensorState(
        context: Context,
        sensorId: String,
        knownGlobalState: SensorPrivacyState? = null
    ): SensorPrivacyState {
        // If all sensors are confirmed off globally, this sensor is off
        val globalState = knownGlobalState ?: getSensorsOffState(context)
        if (globalState == SensorPrivacyState.ENABLED) {
            return SensorPrivacyState.ENABLED
        }

        // Layer 0: Direct Parcel Binder query via Shizuku
        val sensorCode = when (sensorId.lowercase()) {
            "camera" -> SensorPrivacyCodes.SENSOR_CAMERA
            "mic", "microphone" -> SensorPrivacyCodes.SENSOR_MICROPHONE
            else -> 0
        }
        if (sensorCode > 0) {
            val directQuery = queryDirectToggleSensorPrivacy(sensorCode)
            if (directQuery.isAuthoritative) {
                return directQuery
            }
        }

        // Layer 1: Check native SensorPrivacyManager for camera / mic via reflection
        try {
            val spm = context.getSystemService("sensor_privacy")
            if (spm != null) {
                initSpmReflection(spm.javaClass)
                val mSensor = cachedMethodSensorPrivacyInt
                if (mSensor != null) {
                    if (sensorId.equals("camera", ignoreCase = true)) {
                        val cam = mSensor.invoke(spm, SensorPrivacyCodes.SENSOR_CAMERA) as? Boolean
                        if (cam != null) {
                            return if (cam) SensorPrivacyState.ENABLED else SensorPrivacyState.DISABLED
                        }
                    } else if (sensorId.equals("mic", ignoreCase = true) || sensorId.equals("microphone", ignoreCase = true)) {
                        val mic = mSensor.invoke(spm, SensorPrivacyCodes.SENSOR_MICROPHONE) as? Boolean
                        if (mic != null) {
                            return if (mic) SensorPrivacyState.ENABLED else SensorPrivacyState.DISABLED
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "SensorPrivacyManager reflection note for $sensorId: ${e.message}")
        }

        // If global state was authoritatively disabled and sensor is non-toggleable, return DISABLED
        if (globalState == SensorPrivacyState.DISABLED && sensorCode == 0) {
            return SensorPrivacyState.DISABLED
        }

        return SensorPrivacyState.UNKNOWN
    }

    /**
     * Authoritatively checks whether camera and microphone are blocked.
     */
    fun getCamMicCombinedState(context: Context): SensorPrivacyState {
        val cam = getIndividualSensorState(context, "camera")
        val mic = getIndividualSensorState(context, "mic")
        return when {
            cam == SensorPrivacyState.ENABLED || mic == SensorPrivacyState.ENABLED -> SensorPrivacyState.ENABLED
            cam == SensorPrivacyState.DISABLED && mic == SensorPrivacyState.DISABLED -> SensorPrivacyState.DISABLED
            else -> SensorPrivacyState.UNKNOWN
        }
    }

    fun getTileIconStyle(context: Context): String {
        val prefs = context.getSharedPreferences("sensors_off_prefs", Context.MODE_PRIVATE)
        return prefs.getString("tile_icon_style", "aosp") ?: "aosp"
    }

    fun getTileLabelText(context: Context): String {
        val prefs = context.getSharedPreferences("sensors_off_prefs", Context.MODE_PRIVATE)
        val value = prefs.getString("tile_label_text", "Sensors Off")
        return if (!value.isNullOrBlank()) value else "Sensors Off"
    }

    fun getTileActiveSubtitleText(context: Context): String {
        val prefs = context.getSharedPreferences("sensors_off_prefs", Context.MODE_PRIVATE)
        return prefs.getString("tile_active_subtitle", "On") ?: "On"
    }

    fun getTileDisabledSubtitleText(context: Context): String {
        val prefs = context.getSharedPreferences("sensors_off_prefs", Context.MODE_PRIVATE)
        val text = prefs.getString("tile_disabled_subtitle", "Off") ?: "Off"
        return if (text.isBlank() || text.equals("Available", ignoreCase = true) || text.equals("Blocked", ignoreCase = true)) "Off" else text
    }

    fun getTileBlockMode(context: Context): String {
        val prefs = context.getSharedPreferences("sensors_off_prefs", Context.MODE_PRIVATE)
        return prefs.getString("tile_block_mode", "global") ?: "global"
    }

    fun getShowExperimentalToggles(context: Context): Boolean {
        val prefs = context.getSharedPreferences("sensors_off_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("show_experimental_sensor_toggles", false)
    }

    fun setShowExperimentalToggles(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("sensors_off_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("show_experimental_sensor_toggles", enabled).apply()
    }

    fun getCustomIconPath(context: Context): String? {
        val prefs = context.getSharedPreferences("sensors_off_prefs", Context.MODE_PRIVATE)
        val path = prefs.getString("custom_icon_path", null)
        if (path != null && File(path).exists()) {
            return path
        }
        return null
    }

    fun saveTileSettings(
        context: Context,
        iconStyle: String,
        labelText: String,
        activeSubtitleText: String = "",
        disabledSubtitleText: String = "",
        blockMode: String = "global",
        customIconPath: String? = null
    ) {
        val prefs = context.getSharedPreferences("sensors_off_prefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putString("tile_icon_style", iconStyle)
            .putString("tile_label_text", labelText.ifBlank { "Sensors Off" })
            .putString("tile_active_subtitle", activeSubtitleText)
            .putString("tile_disabled_subtitle", disabledSubtitleText)
            .putString("tile_block_mode", blockMode)

        if (customIconPath != null) {
            editor.putString("custom_icon_path", customIconPath)
        }
        editor.apply()
        notifyTileServiceToUpdate(context)
    }

    suspend fun saveCustomTileIconFromUri(context: Context, uri: android.net.Uri): String? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        return@withContext try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            val originalBitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (originalBitmap == null) return@withContext null

            val targetSize = 48
            val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(originalBitmap, targetSize, targetSize, true)

            val monochromeBitmap = android.graphics.Bitmap.createBitmap(targetSize, targetSize, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(monochromeBitmap)
            val paint = android.graphics.Paint()
            val colorMatrix = android.graphics.ColorMatrix().apply { setSaturation(0f) }
            paint.colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
            canvas.drawBitmap(scaledBitmap, 0f, 0f, paint)

            val destFile = File(context.filesDir, "custom_tile_icon.png")
            val outputStream = java.io.FileOutputStream(destFile)
            monochromeBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, outputStream)
            outputStream.flush()
            outputStream.close()

            val savedPath = destFile.absolutePath
            val prefs = context.getSharedPreferences("sensors_off_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("custom_icon_path", savedPath).apply()
            Log.d(TAG, "Custom tile icon saved to $savedPath")
            savedPath
        } catch (e: java.io.IOException) {
            Log.e(TAG, "IOException saving custom tile icon from Uri", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save custom tile icon from Uri", e)
            null
        }
    }

    /**
     * Injects the Quick Settings tile directly into the active QS shade via Shizuku/Root
     * and prompts SystemUI via official StatusBarManager API (Android 13+).
     */
    fun addTileToQuickSettings(context: Context, addNativeAospTile: Boolean = false): Pair<Boolean, String> {
        val packageName = context.packageName
        val appTileComponent = "custom($packageName/$packageName.SensorsOffTileService)"
        val aospTileComponent = "custom(com.android.settings/com.android.settings.development.qs.SensorPrivacyTileService)"
        val aospPlain = "sensor_privacy"

        val targetTile = if (addNativeAospTile) aospTileComponent else appTileComponent

        // Method 1: Android 13+ (API 33+) native request prompt (Zero risk, official API)
        if (!addNativeAospTile && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val sbm = context.getSystemService(android.app.StatusBarManager::class.java)
                if (sbm != null) {
                    val component = android.content.ComponentName(context, SensorsOffTileService::class.java)
                    val icon = android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_sensors_off)
                    sbm.requestAddTileService(
                        component,
                        context.getString(R.string.tile_label),
                        icon,
                        context.mainExecutor
                    ) { _ -> }
                }
            } catch (t: SecurityException) {
                Log.w(TAG, "StatusBarManager.requestAddTileService security denied", t)
            } catch (t: Exception) {
                Log.w(TAG, "StatusBarManager.requestAddTileService fallback", t)
            }
        }

        // Method 2: Via Shizuku or Root direct injection into sysui_qs_tiles
        val isShizuku = isShizukuRunning() && isShizukuAuthorized()
        val isRoot = isRootAvailable()

        if (!isShizuku && !isRoot) {
            return Pair(false, "Shizuku authorization or Root required to inject Quick Settings tile directly.")
        }

        val runCommand = { cmd: String ->
            if (isShizuku) runShizukuCommand(cmd) else runRootCommand(cmd)
        }

        return try {
            val currentTilesOutput = runCommand("settings get secure sysui_qs_tiles").stdout.trim()
            if (currentTilesOutput.isBlank() || currentTilesOutput == "null") {
                return Pair(false, "Could not read sysui_qs_tiles.")
            }

            if (currentTilesOutput.contains(targetTile) || (addNativeAospTile && currentTilesOutput.contains(aospPlain))) {
                // Ensure SystemUI re-reads it
                runCommand("killall com.android.systemui")
                return Pair(true, "Tile is already in your Quick Settings list! Refreshed SystemUI.")
            }

            val newTiles = if (addNativeAospTile) {
                "$currentTilesOutput,$aospTileComponent,$aospPlain"
            } else {
                "$currentTilesOutput,$appTileComponent"
            }

            runCommand("settings put secure sysui_qs_tiles \"$newTiles\"")
            // Refresh SystemUI
            runCommand("killall com.android.systemui")

            TileLogManager.logTileEvent(
                context,
                "Tile Injected",
                "Successfully injected $targetTile into sysui_qs_tiles",
                LogLevel.SUCCESS
            )
            Pair(true, "Successfully added to Quick Settings!")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject Quick Settings tile", e)
            Pair(false, "Failed to inject tile: ${e.message}")
        }
    }

    // App Theme Preferences Storage
    fun getAppThemeMode(context: Context): String {
        val prefs = context.getSharedPreferences("sensors_off_prefs", Context.MODE_PRIVATE)
        return prefs.getString("app_theme_mode", "system") ?: "system"
    }

    fun saveAppThemeMode(context: Context, mode: String) {
        val prefs = context.getSharedPreferences("sensors_off_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("app_theme_mode", mode).apply()
    }

    // App Launcher Re-branding Aliases
    fun getAppLauncherAlias(context: Context): String {
        val prefs = context.getSharedPreferences("sensors_off_prefs", Context.MODE_PRIVATE)
        return prefs.getString("app_launcher_alias", "MainActivityDefault") ?: "MainActivityDefault"
    }

    fun setAppLauncherAlias(context: Context, aliasName: String) {
        val pm = context.packageManager
        val packageName = context.packageName

        val aliases = listOf(
            "$packageName.MainActivityDefault",
            "$packageName.MainActivityMinimal",
            "$packageName.MainActivityDiscrete"
        )

        for (alias in aliases) {
            try {
                val componentName = android.content.ComponentName(context, alias)
                val newState = if (alias == "$packageName.$aliasName") {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                pm.setComponentEnabledSetting(
                    componentName,
                    newState,
                    PackageManager.DONT_KILL_APP
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException setting activity alias for $alias", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set activity alias component state for $alias", e)
            }
        }

        val prefs = context.getSharedPreferences("sensors_off_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("app_launcher_alias", aliasName).apply()
    }

    /**
     * Authoritatively queries the current SensorsOff state from the Android sensor privacy service.
     * Returns:
     * - ENABLED: Authoritatively verified that sensors are off / blocked.
     * - DISABLED: Authoritatively verified that sensors are on / available.
     * - UNKNOWN: Authoritative state could not be determined or verified. UNKNOWN must never be treated as ENABLED or DISABLED.
     */
    fun getSensorsOffState(context: Context): SensorPrivacyState {
        // Layer 0: Direct Parcel Binder query via Shizuku
        val directGlobal = queryDirectSensorPrivacy()
        if (directGlobal == SensorPrivacyState.ENABLED) return SensorPrivacyState.ENABLED

        val camDirect = queryDirectToggleSensorPrivacy(SensorPrivacyCodes.SENSOR_CAMERA)
        val micDirect = queryDirectToggleSensorPrivacy(SensorPrivacyCodes.SENSOR_MICROPHONE)
        if (camDirect == SensorPrivacyState.ENABLED && micDirect == SensorPrivacyState.ENABLED) {
            return SensorPrivacyState.ENABLED
        }
        if (directGlobal == SensorPrivacyState.DISABLED &&
            (camDirect == SensorPrivacyState.DISABLED || camDirect == SensorPrivacyState.UNKNOWN) &&
            (micDirect == SensorPrivacyState.DISABLED || micDirect == SensorPrivacyState.UNKNOWN)) {
            return SensorPrivacyState.DISABLED
        }
        if (camDirect == SensorPrivacyState.DISABLED && micDirect == SensorPrivacyState.DISABLED) {
            return SensorPrivacyState.DISABLED
        }

        // Layer 1: Check native Android SensorPrivacyManager directly via cached reflection
        try {
            val spm = context.getSystemService("sensor_privacy")
            if (spm != null) {
                initSpmReflection(spm.javaClass)
                
                // 1. isSensorPrivacyEnabled()
                cachedMethodGlobalPrivacy?.let { m ->
                    val res = m.invoke(spm) as? Boolean
                    if (res != null) {
                        return if (res) SensorPrivacyState.ENABLED else SensorPrivacyState.DISABLED
                    }
                }
                
                // 2. isAllSensorPrivacyEnabled()
                cachedMethodAllSensorPrivacy?.let { m ->
                    val res = m.invoke(spm) as? Boolean
                    if (res != null) {
                        return if (res) SensorPrivacyState.ENABLED else SensorPrivacyState.DISABLED
                    }
                }

                // 3. isSensorPrivacyEnabled(int sensor) - 1: Mic, 2: Camera
                cachedMethodSensorPrivacyInt?.let { m ->
                    val mic = m.invoke(spm, SensorPrivacyCodes.SENSOR_MICROPHONE) as? Boolean
                    val cam = m.invoke(spm, SensorPrivacyCodes.SENSOR_CAMERA) as? Boolean
                    if (mic == true && cam == true) return SensorPrivacyState.ENABLED
                    if (mic == false && cam == false) return SensorPrivacyState.DISABLED
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "SensorPrivacyManager reflection check: ${e.message}")
        }

        // Authoritative state could not be determined
        return SensorPrivacyState.UNKNOWN
    }

    /**
     * Non-authoritative cached preference for UI display only.
     */
    fun getCachedUiPreferenceState(context: Context): Boolean {
        val prefs = context.getSharedPreferences("sensors_off_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("sensors_off_enabled", false)
    }

    fun getCachedSensorsOffState(context: Context): Boolean = getCachedUiPreferenceState(context)

    fun runShizukuCommand(command: String, timeoutMs: Long = 4000L): CommandResult {
        var shizukuProcess: java.lang.Process? = null
        try {
            val targetMethod = getShizukuNewProcessMethod()
                ?: return CommandResult.failure("Shizuku newProcess method unavailable")
            val p = targetMethod.invoke(null, arrayOf("sh", "-c", command), null, null) as? java.lang.Process
                ?: return CommandResult.failure("Failed to instantiate Shizuku process")
            shizukuProcess = p

            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            val stdoutThread = Thread {
                try {
                    p.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stdoutBuilder.append(line).append("\n")
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Shizuku stdout read note: ${e.message}")
                }
            }

            val stderrThread = Thread {
                try {
                    p.errorStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stderrBuilder.append(line).append("\n")
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Shizuku stderr read note: ${e.message}")
                }
            }

            stdoutThread.start()
            stderrThread.start()

            val completed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            } else {
                p.waitFor()
                true
            }

            if (!completed) {
                try { p.destroy() } catch (e: Exception) { Log.d(TAG, "Process destroy note: ${e.message}") }
                try { p.destroyForcibly() } catch (e: Exception) { Log.d(TAG, "Process destroyForcibly note: ${e.message}") }
                return CommandResult.failure("Shizuku command timed out after ${timeoutMs}ms", exitCode = -2)
            }

            try { stdoutThread.join(400) } catch (e: InterruptedException) { Thread.currentThread().interrupt() } catch (e: Exception) { Log.d(TAG, "stdout thread join note: ${e.message}") }
            try { stderrThread.join(400) } catch (e: InterruptedException) { Thread.currentThread().interrupt() } catch (e: Exception) { Log.d(TAG, "stderr thread join note: ${e.message}") }

            val exitCode = try { p.exitValue() } catch (e: Exception) { -1 }
            val stdout = stdoutBuilder.toString()
            val stderr = stderrBuilder.toString()
            val success = (exitCode == 0)

            if (!success) {
                Log.w(TAG, "Shizuku command exited with code $exitCode. stderr: $stderr")
            }

            return CommandResult(
                success = success,
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException executing Shizuku command: $command", e)
            return CommandResult.failure("SecurityException: ${e.message}")
        } catch (e: java.io.IOException) {
            Log.e(TAG, "IOException executing Shizuku command: $command", e)
            return CommandResult.failure("IOException: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error executing Shizuku command: $command", e)
            return CommandResult.failure("Exception: ${e.message}")
        } finally {
            try { shizukuProcess?.inputStream?.close() } catch (e: Exception) { Log.d(TAG, "Shizuku inputStream close note: ${e.message}") }
            try { shizukuProcess?.errorStream?.close() } catch (e: Exception) { Log.d(TAG, "Shizuku errorStream close note: ${e.message}") }
            try { shizukuProcess?.outputStream?.close() } catch (e: Exception) { Log.d(TAG, "Shizuku outputStream close note: ${e.message}") }
        }
    }

    /**
     * Executes root commands safely with strict argument isolation and timeout handling.
     */
    fun runRootCommand(command: String, timeoutMs: Long = 4000L): CommandResult {
        var rootProcess: java.lang.Process? = null
        try {
            val p = Runtime.getRuntime().exec("su")
            rootProcess = p
            DataOutputStream(p.outputStream).use { os ->
                os.writeBytes("$command\n")
                os.writeBytes("exit\n")
                os.flush()
            }

            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            val stdoutThread = Thread {
                try {
                    p.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stdoutBuilder.append(line).append("\n")
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Root stdout read note: ${e.message}")
                }
            }

            val stderrThread = Thread {
                try {
                    p.errorStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stderrBuilder.append(line).append("\n")
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Root stderr read note: ${e.message}")
                }
            }

            stdoutThread.start()
            stderrThread.start()

            val completed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            } else {
                p.waitFor()
                true
            }

            if (!completed) {
                try { p.destroy() } catch (e: Exception) { Log.d(TAG, "Process destroy note: ${e.message}") }
                try { p.destroyForcibly() } catch (e: Exception) { Log.d(TAG, "Process destroyForcibly note: ${e.message}") }
                return CommandResult.failure("Root command timed out after ${timeoutMs}ms", exitCode = -2)
            }

            try { stdoutThread.join(400) } catch (e: InterruptedException) { Thread.currentThread().interrupt() } catch (e: Exception) { Log.d(TAG, "stdout thread join note: ${e.message}") }
            try { stderrThread.join(400) } catch (e: InterruptedException) { Thread.currentThread().interrupt() } catch (e: Exception) { Log.d(TAG, "stderr thread join note: ${e.message}") }

            val exitCode = try { p.exitValue() } catch (e: Exception) { -1 }
            val stdout = stdoutBuilder.toString()
            val stderr = stderrBuilder.toString()
            val success = (exitCode == 0)

            if (!success) {
                Log.w(TAG, "Root command exited with code $exitCode. stderr: $stderr")
            }

            return CommandResult(
                success = success,
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr
            )
        } catch (e: java.io.IOException) {
            Log.e(TAG, "IOException executing Root command: $command", e)
            return CommandResult.failure("IOException: ${e.message}")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException executing Root command: $command", e)
            return CommandResult.failure("SecurityException: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error executing Root command: $command", e)
            return CommandResult.failure("Exception: ${e.message}")
        } finally {
            try { rootProcess?.inputStream?.close() } catch (e: Exception) { Log.d(TAG, "Root inputStream close note: ${e.message}") }
            try { rootProcess?.errorStream?.close() } catch (e: Exception) { Log.d(TAG, "Root errorStream close note: ${e.message}") }
            try { rootProcess?.outputStream?.close() } catch (e: Exception) { Log.d(TAG, "Root outputStream close note: ${e.message}") }
        }
    }

    /**
     * Sends an explicit signal to Android SystemUI to refresh the Quick Settings tile
     * whenever settings or sensor states change inside the app.
     */
    fun notifyTileServiceToUpdate(context: Context) {
        try {
            android.service.quicksettings.TileService.requestListeningState(
                context,
                android.content.ComponentName(context, SensorsOffTileService::class.java)
            )
            TileLogManager.logTileEvent(
                context,
                "SystemUI Sync Dispatched",
                "Invoked TileService.requestListeningState() -> SystemUI tile invalidate requested",
                LogLevel.DEBUG
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException in requestListeningState: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not requestListeningState for tile: ${e.message}")
            TileLogManager.logTileEvent(
                context,
                "SystemUI Sync Warning",
                "requestListeningState failed: ${e.message}",
                LogLevel.WARN
            )
        }
    }
}
