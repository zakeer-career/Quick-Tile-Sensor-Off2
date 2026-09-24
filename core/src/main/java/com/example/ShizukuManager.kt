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
 * Architecture & Compatibility Notice:
 * - These transaction codes correspond directly to method declarations in android.hardware.ISensorPrivacyManager.aidl across AOSP versions:
 *   * Android 12+ (API 31+): setSensorPrivacy is code 9, setToggleSensorPrivacy is code 10,
 *     isToggleSensorPrivacyEnabled is code 8, isCombinedToggleSensorPrivacyEnabled is code 7,
 *     isSensorPrivacyEnabled is code 6.
 *   * Android 11 (API 30): setSensorPrivacy is code 5, isSensorPrivacyEnabled is code 4.
 *   * Android 10 (API 29): setSensorPrivacy is code 4, isSensorPrivacyEnabled is code 3.
 *
 * Internal Android/OEM Interface Notice:
 * - ISensorPrivacyManager is an internal, non-SDK Android system service interface.
 * - AIDL transaction codes and method signatures are internal to Android and OEM-dependent.
 * - They are not guaranteed to be identical across every OEM vendor ROM (e.g., Xiaomi HyperOS/MIUI, Samsung OneUI, Transsion HiOS).
 * - The application does not claim universal compatibility.
 * - A successful Binder IPC or shell transaction alone does NOT prove state change.
 * - Operations are ONLY considered successful if an authoritative read-back of the GLOBAL sensor privacy state matches the requested state.
 */
object SensorPrivacyTransactions {
    const val DESCRIPTOR = "android.hardware.ISensorPrivacyManager"

    // Android 12+ (API 31+) ISensorPrivacyManager.aidl
    // void setSensorPrivacy(boolean enable) -> code 9
    // boolean isSensorPrivacyEnabled() -> code 6
    const val SET_GLOBAL_PRIVACY_S_PLUS = 9
    const val IS_GLOBAL_PRIVACY_S_PLUS = 6

    // Android 11 (API 30) ISensorPrivacyManager.aidl
    // void setSensorPrivacy(boolean enable) -> code 5
    // boolean isSensorPrivacyEnabled() -> code 4
    const val SET_GLOBAL_PRIVACY_R = 5
    const val IS_GLOBAL_PRIVACY_R = 4

    // Android 10 (API 29) ISensorPrivacyManager.aidl
    // void setSensorPrivacy(boolean enable) -> code 4
    // boolean isSensorPrivacyEnabled() -> code 3
    const val SET_GLOBAL_PRIVACY_Q = 4
    const val IS_GLOBAL_PRIVACY_Q = 3

    // Android 12+ (API 31+) Individual Sensor Toggles (kept strictly separate from global operations)
    // void setToggleSensorPrivacy(int userId, int source, int sensor, boolean enable) -> code 10
    // boolean isToggleSensorPrivacyEnabled(int toggleType, int sensor) -> code 8
    // boolean isCombinedToggleSensorPrivacyEnabled(int sensor) -> code 7
    const val SET_TOGGLE_PRIVACY = 10
    const val IS_TOGGLE_PRIVACY = 8
    const val IS_COMBINED_TOGGLE_PRIVACY = 7

    // Sensor Constants
    const val SENSOR_MICROPHONE = 1
    const val SENSOR_CAMERA = 2

    // Sources & Toggle Types
    const val TOGGLE_TYPE_SOFTWARE = 1
    const val SOURCE_QS_TILE = 1

    /**
     * Resolves the exact ISensorPrivacyManager transaction code for setSensorPrivacy(boolean)
     * strictly based on the device's Android API level.
     * Returns null if the Android version is unsupported (API < 29).
     */
    fun getSetGlobalCodeForSdk(sdkInt: Int = Build.VERSION.SDK_INT): Int? = when {
        sdkInt >= Build.VERSION_CODES.S -> SET_GLOBAL_PRIVACY_S_PLUS // API 31+ (Android 12, 13, 14, 15, 16)
        sdkInt == Build.VERSION_CODES.R -> SET_GLOBAL_PRIVACY_R      // API 30 (Android 11)
        sdkInt == Build.VERSION_CODES.Q -> SET_GLOBAL_PRIVACY_Q      // API 29 (Android 10)
        else -> null                                                // API < 29: ISensorPrivacyManager did not exist in AOSP
    }

    /**
     * Resolves the exact ISensorPrivacyManager transaction code for isSensorPrivacyEnabled()
     * strictly based on the device's Android API level.
     * Returns null if the Android version is unsupported (API < 29).
     */
    fun getQueryGlobalCodeForSdk(sdkInt: Int = Build.VERSION.SDK_INT): Int? = when {
        sdkInt >= Build.VERSION_CODES.S -> IS_GLOBAL_PRIVACY_S_PLUS // API 31+ (Android 12, 13, 14, 15, 16)
        sdkInt == Build.VERSION_CODES.R -> IS_GLOBAL_PRIVACY_R      // API 30 (Android 11)
        sdkInt == Build.VERSION_CODES.Q -> IS_GLOBAL_PRIVACY_Q      // API 29 (Android 10)
        else -> null                                                // API < 29: ISensorPrivacyManager did not exist in AOSP
    }

    fun getPreferredSetGlobalCode(): Int =
        getSetGlobalCodeForSdk() ?: SET_GLOBAL_PRIVACY_S_PLUS
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
            notifyTileServiceToUpdate(ctx)
            TileLogManager.logPrivilegeEvent(
                ctx,
                "Shizuku Connected",
                "Shizuku binder connected; tile state refresh requested.",
                LogLevel.SUCCESS
            )
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
     * Explicitly invalidates any cached sensor privacy IBinder reference.
     */
    fun invalidateSensorPrivacyBinder() {
        cachedSensorPrivacyBinder = null
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
        cachedSensorPrivacyBinder = null
        if (!isShizukuRunning() || !isShizukuAuthorized()) return null
        return try {
            val binder = SystemServiceHelper.getSystemService("sensor_privacy") ?: return null
            if (!binder.isBinderAlive) {
                return null
            }
            val wrapper = if (binder is ShizukuBinderWrapper) binder else ShizukuBinderWrapper(binder)
            cachedSensorPrivacyBinder = wrapper
            wrapper
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException acquiring sensor_privacy binder: ${e.message}")
            cachedSensorPrivacyBinder = null
            null
        } catch (e: RemoteException) {
            Log.w(TAG, "RemoteException acquiring sensor_privacy binder: ${e.message}")
            cachedSensorPrivacyBinder = null
            null
        } catch (e: Exception) {
            Log.d(TAG, "Could not acquire sensor_privacy binder via Shizuku: ${e.message}")
            cachedSensorPrivacyBinder = null
            null
        }
    }

    /**
     * Direct Parcel Binder query for global sensor privacy state.
     * Uses strict API-version-specific transaction code:
     * - Android 12+ (API 31+): Code 6 (isSensorPrivacyEnabled())
     * - Android 11 (API 30): Code 4 (isSensorPrivacyEnabled())
     * - Android 10 (API 29): Code 3 (isSensorPrivacyEnabled())
     * - Android <29: Unsupported (returns UNKNOWN)
     */
    fun queryDirectSensorPrivacy(): SensorPrivacyState {
        val pid = android.os.Process.myPid()
        val sdkInt = Build.VERSION.SDK_INT
        val txCode = SensorPrivacyCodes.getQueryGlobalCodeForSdk(sdkInt)
        if (txCode == null) {
            Log.d(TAG, "[$pid] SDK $sdkInt unsupported for global sensor privacy query")
            return SensorPrivacyState.UNKNOWN
        }

        val isRunning = isShizukuRunning()
        val isAuth = if (isRunning) isShizukuAuthorized() else false
        if (!isRunning || !isAuth) {
            Log.d(TAG, "[$pid] Shizuku unavailable (running=$isRunning, auth=$isAuth) for query tx $txCode")
            return SensorPrivacyState.UNKNOWN
        }

        var wrapper = getSensorPrivacyBinder()
        if (wrapper == null || !wrapper.isBinderAlive) {
            Log.d(TAG, "[$pid] sensor_privacy binder null or dead for query tx $txCode")
            invalidateSensorPrivacyBinder()
            return SensorPrivacyState.UNKNOWN
        }

        var result = executeQueryTransact(wrapper, txCode, sdkInt, pid)
        if (result != SensorPrivacyState.UNKNOWN) {
            return result
        }

        // If direct query failed (e.g. stale/dead binder), invalidate and reacquire once immediately
        invalidateSensorPrivacyBinder()
        val freshWrapper = getSensorPrivacyBinder()
        if (freshWrapper != null && freshWrapper !== wrapper && freshWrapper.isBinderAlive) {
            result = executeQueryTransact(freshWrapper, txCode, sdkInt, pid)
            if (result != SensorPrivacyState.UNKNOWN) {
                return result
            }
        }

        return SensorPrivacyState.UNKNOWN
    }

    private fun executeQueryTransact(wrapper: IBinder, txCode: Int, sdkInt: Int, pid: Int): SensorPrivacyState {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SensorPrivacyCodes.DESCRIPTOR)
            val res = wrapper.transact(txCode, data, reply, 0)
            if (res) {
                reply.readException()
                val rawVal = reply.readInt()
                val isEnabled = rawVal != 0
                val state = if (isEnabled) SensorPrivacyState.ENABLED else SensorPrivacyState.DISABLED
                Log.d(TAG, "[$pid] Direct query tx $txCode (API $sdkInt) -> raw: $rawVal -> $state")
                state
            } else {
                Log.w(TAG, "[$pid] Direct query tx $txCode (API $sdkInt) returned false from transact")
                SensorPrivacyState.UNKNOWN
            }
        } catch (e: RemoteException) {
            Log.w(TAG, "[$pid] RemoteException in direct query tx $txCode (API $sdkInt): ${e.message}")
            invalidateSensorPrivacyBinder()
            SensorPrivacyState.UNKNOWN
        } catch (e: SecurityException) {
            Log.w(TAG, "[$pid] SecurityException in direct query tx $txCode (API $sdkInt): ${e.message}")
            SensorPrivacyState.UNKNOWN
        } catch (t: Throwable) {
            Log.w(TAG, "[$pid] Throwable in direct query tx $txCode (API $sdkInt): ${t.message}")
            SensorPrivacyState.UNKNOWN
        } finally {
            data.recycle()
            reply.recycle()
        }
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

    /**
     * Checks if any viable privilege is available to toggle sensor privacy.
     * True if Shizuku is authorized or Root SU is available.
     */
    fun isPrivilegeAvailable(context: Context? = null): Boolean {
        if (isShizukuRunning() && isShizukuAuthorized()) {
            return true
        }
        return isRootAvailable()
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

    /**
     * Executes direct low-level Binder transact calls across Shizuku IPC using strict AIDL codes.
     * Uses the exact API-version-specific transaction code:
     * - Android 12+ (API 31+): Code 9 (setSensorPrivacy(boolean))
     * - Android 11 (API 30): Code 5 (setSensorPrivacy(boolean))
     * - Android 10 (API 29): Code 4 (setSensorPrivacy(boolean))
     * - Android <29: Unsupported (returns UNSUPPORTED)
     *
     * Important: A successful transaction (TRANSACTION_ACCEPTED) only means the Binder IPC call
     * was accepted by the remote sensor_privacy service. It does NOT guarantee the hardware state changed.
     * Authoritative read-back verification against the real sensor state is always performed afterward.
     */
    fun invokeDirectSensorPrivacyTransact(turnOff: Boolean): BinderTransactionResult {
        val txCode = SensorPrivacyCodes.getSetGlobalCodeForSdk() ?: return BinderTransactionResult.UNSUPPORTED
        val wrapper = getSensorPrivacyBinder() ?: return BinderTransactionResult.BINDER_ERROR
        val targetVal = if (turnOff) 1 else 0

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SensorPrivacyCodes.DESCRIPTOR)
            data.writeInt(targetVal)
            val res = wrapper.transact(txCode, data, reply, 0)
            if (res) {
                try {
                    reply.readException()
                    Log.d(TAG, "Direct Binder transact code $txCode accepted by sensor_privacy service")
                    BinderTransactionResult.TRANSACTION_ACCEPTED
                } catch (e: SecurityException) {
                    Log.w(TAG, "Direct Binder code $txCode security exception: ${e.message}")
                    BinderTransactionResult.EXCEPTION
                } catch (e: RemoteException) {
                    Log.w(TAG, "Direct Binder code $txCode remote exception: ${e.message}")
                    BinderTransactionResult.EXCEPTION
                } catch (e: Exception) {
                    Log.w(TAG, "Direct Binder code $txCode exception: ${e.message}")
                    BinderTransactionResult.EXCEPTION
                }
            } else {
                BinderTransactionResult.TRANSACTION_ERROR
            }
        } catch (e: RemoteException) {
            Log.w(TAG, "RemoteException invoking code $txCode: ${e.message}")
            BinderTransactionResult.EXCEPTION
        } catch (t: Exception) {
            Log.w(TAG, "Exception invoking code $txCode: ${t.message}")
            BinderTransactionResult.EXCEPTION
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Executes direct low-level Binder transact for individual sensors (Camera, Mic) across Shizuku IPC.
     * Note: These are internal Android sensor privacy service transaction mappings and may vary by Android version/OEM.
     */
    fun invokeDirectIndividualSensorTransact(sensorId: String, turnOff: Boolean): BinderTransactionResult {
        val wrapper = getSensorPrivacyBinder() ?: return BinderTransactionResult.BINDER_ERROR
        val sensorCode = when (sensorId.lowercase()) {
            "camera" -> SensorPrivacyCodes.SENSOR_CAMERA
            "mic", "microphone" -> SensorPrivacyCodes.SENSOR_MICROPHONE
            else -> 0
        }
        if (sensorCode == 0) return BinderTransactionResult.UNSUPPORTED
        val targetVal = if (turnOff) 1 else 0

        // Direct Parcel Binder transaction via known code 10
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
                    BinderTransactionResult.TRANSACTION_ACCEPTED
                } catch (e: Exception) {
                    Log.w(TAG, "Exception in readException for $sensorId: ${e.message}")
                    BinderTransactionResult.EXCEPTION
                }
            } else {
                BinderTransactionResult.TRANSACTION_ERROR
            }
        } catch (e: RemoteException) {
            Log.w(TAG, "RemoteException in individual sensor transact $sensorId: ${e.message}")
            BinderTransactionResult.EXCEPTION
        } catch (t: Exception) {
            Log.w(TAG, "Exception in individual sensor transact $sensorId: ${t.message}")
            BinderTransactionResult.EXCEPTION
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Main method to toggle SensorsOff state using direct SensorPrivacyManager system calls:
     * 1. Direct AIDL Binder Transact via Shizuku (with explicit transaction outcome)
     * 2. Lean native shell command fallback using documented transaction codes
     * 3. Authoritative read-back verification against the Android sensor privacy service
     * 4. UI/diagnostic SharedPreferences persistence for verified states
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
            val directBinderResult = invokeDirectSensorPrivacyTransact(turnOff)
            var directVerified = false
            if (directBinderResult.isAccepted) {
                val stateAfterBinder = getSensorsOffState(context)
                if (stateAfterBinder.matchesRequested(turnOff)) {
                    directVerified = true
                }
            }

            // 2. Shell command fallback if direct Binder transact did not result in verified state
            if (!directVerified) {
                val txCode = SensorPrivacyCodes.getSetGlobalCodeForSdk()
                if (txCode != null) {
                    val res = executeTypedShellToggleGlobal(txCode, turnOff)
                    if (res.success) {
                        val stateAfterCmd = getSensorsOffState(context)
                        if (stateAfterCmd.matchesRequested(turnOff)) {
                            directVerified = true
                        }
                    }
                }
            }

            // 3. Authoritative read-back verification: verify actual hardware sensor state from Android sensor privacy service
            val confirmedState = getSensorsOffState(context)
            val verified = confirmedState.matchesRequested(turnOff)
            if (!verified) {
                Log.w(TAG, "Read-back verification failed for setSensorsOffState: requested=$turnOff, actual=$confirmedState. Success will NOT be claimed.")
                return false
            }

            // 4. Update local SharedPreferences with confirmed state for UI/history only
            val prefs = context.getSharedPreferences("sensors_off_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("sensors_off_enabled", turnOff)
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
            val directResult = invokeDirectIndividualSensorTransact(sensorId, turnOff)
            var directVerified = false
            if (directResult.isAccepted) {
                val stateAfterBinder = getIndividualSensorState(context, sensorId)
                if (stateAfterBinder.matchesRequested(turnOff)) {
                    directVerified = true
                }
            }

            val currentUserId = getCurrentUserId()
            if (!directVerified) {
                executeTypedShellToggleIndividual(SensorPrivacyCodes.SET_TOGGLE_PRIVACY, currentUserId, sensorCode, turnOff)
            }

            // 2. Authoritative read-back verification
            val confirmedState = getIndividualSensorState(context, sensorId)
            val verified = confirmedState.matchesRequested(turnOff)
            if (!verified) {
                Log.w(TAG, "Read-back verification failed for sensor $sensorId: requested=$turnOff, actual=$confirmedState. Success will NOT be claimed.")
                return false
            }

            // 3. Update local SharedPreferences
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
            val directAccepted = micDirect.isAccepted && camDirect.isAccepted
            var directVerified = false
            if (directAccepted) {
                val confirmedCam = getIndividualSensorState(context, "camera")
                val confirmedMic = getIndividualSensorState(context, "mic")
                if (confirmedCam.matchesRequested(turnOff) && confirmedMic.matchesRequested(turnOff)) {
                    directVerified = true
                }
            }

            // 2. Single combined native service call fallback
            if (!directVerified) {
                val currentUserId = getCurrentUserId()
                executeTypedShellToggleCamMic(currentUserId, turnOff)
            }

            // 3. Authoritative read-back verification
            val confirmedCam = getIndividualSensorState(context, "camera")
            val confirmedMic = getIndividualSensorState(context, "mic")
            val verified = confirmedCam.matchesRequested(turnOff) && confirmedMic.matchesRequested(turnOff)
            if (!verified) {
                Log.w(TAG, "Read-back verification failed for cam/mic: requested=$turnOff, cam=$confirmedCam, mic=$confirmedMic. Success will NOT be claimed.")
                return false
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
     * Authoritatively queries the current state for an individual sensor (Camera, Mic).
     * Returns:
     * - ENABLED: Authoritatively verified that the sensor is off / blocked.
     * - DISABLED: Authoritatively verified that the sensor is on / available.
     * - UNKNOWN: Authoritative state could not be determined. UNKNOWN must never be interpreted as ENABLED or DISABLED.
     *
     * Invariants:
     * - Camera queries camera state only.
     * - Microphone queries microphone state only.
     * - Global state is NOT mixed with individual sensor state.
     */
    fun getIndividualSensorState(
        context: Context,
        sensorId: String
    ): SensorPrivacyState {
        val sensorCode = when (sensorId.lowercase()) {
            "camera" -> SensorPrivacyCodes.SENSOR_CAMERA
            "mic", "microphone" -> SensorPrivacyCodes.SENSOR_MICROPHONE
            else -> 0
        }

        if (sensorCode > 0) {
            // Layer 0: Direct Parcel Binder query for individual toggle state via Shizuku
            val directQuery = queryDirectToggleSensorPrivacy(sensorCode)
            if (directQuery.isAuthoritative) {
                return directQuery
            }

            // Layer 1: Check native SensorPrivacyManager for camera / mic via reflection
            try {
                val spm = context.getSystemService("sensor_privacy")
                if (spm != null) {
                    initSpmReflection(spm.javaClass)
                    val mSensor = cachedMethodSensorPrivacyInt
                    if (mSensor != null) {
                        val isBlocked = mSensor.invoke(spm, sensorCode) as? Boolean
                        if (isBlocked != null) {
                            return if (isBlocked) SensorPrivacyState.ENABLED else SensorPrivacyState.DISABLED
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "SensorPrivacyManager reflection note for $sensorId: ${e.message}")
            }
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
        val tileClassName = if (packageName == "com.SensorsOff.tile") "com.example.tile.SensorsOffTileService" else "com.example.SensorsOffTileService"
        val appTileComponent = "custom($packageName/$tileClassName)"
        val aospTileComponent = "custom(com.android.settings/com.android.settings.development.qs.SensorPrivacyTileService)"
        val aospPlain = "sensor_privacy"

        val targetTile = if (addNativeAospTile) aospTileComponent else appTileComponent

        // Method 1: Android 13+ (API 33+) native request prompt (Zero risk, official API)
        if (!addNativeAospTile && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val sbm = context.getSystemService(android.app.StatusBarManager::class.java)
                if (sbm != null) {
                    val component = android.content.ComponentName(packageName, tileClassName)
                    val iconResId = context.resources.getIdentifier("ic_sensors_off", "drawable", packageName)
                    val icon = if (iconResId != 0) android.graphics.drawable.Icon.createWithResource(context, iconResId) else null
                    val labelResId = context.resources.getIdentifier("tile_label", "string", packageName)
                    val label = if (labelResId != 0) context.getString(labelResId) else "Sensors Off"
                    if (icon != null) {
                        sbm.requestAddTileService(
                            component,
                            label,
                            icon,
                            context.mainExecutor
                        ) { _ -> }
                    }
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

        return executeTypedSysUiTileInjection(context, targetTile, addNativeAospTile, appTileComponent, aospTileComponent, aospPlain, isShizuku)
    }

    private fun executeTypedSysUiTileInjection(
        context: Context,
        targetTile: String,
        addNativeAospTile: Boolean,
        appTileComponent: String,
        aospTileComponent: String,
        aospPlain: String,
        isShizuku: Boolean
    ): Pair<Boolean, String> {
        val runCmd = { cmd: String ->
            if (isShizuku) executeShizukuInternalCommand(cmd) else executeRootInternalCommand(cmd)
        }

        return try {
            val currentTilesOutput = runCmd("settings get secure sysui_qs_tiles").stdout.trim()
            if (currentTilesOutput.isBlank() || currentTilesOutput == "null") {
                return Pair(false, "Could not read sysui_qs_tiles.")
            }

            if (currentTilesOutput.contains(targetTile) || (addNativeAospTile && currentTilesOutput.contains(aospPlain))) {
                // Ensure SystemUI re-reads it
                runCmd("killall com.android.systemui")
                return Pair(true, "Tile is already in your Quick Settings list! Refreshed SystemUI.")
            }

            val newTiles = if (addNativeAospTile) {
                "$currentTilesOutput,$aospTileComponent,$aospPlain"
            } else {
                "$currentTilesOutput,$appTileComponent"
            }

            runCmd("settings put secure sysui_qs_tiles \"$newTiles\"")
            // Refresh SystemUI
            runCmd("killall com.android.systemui")

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
     * Authoritatively queries the current GLOBAL SensorsOff state from the Android sensor privacy service.
     * Returns:
     * - ENABLED: Authoritatively verified that GLOBAL sensor privacy is enabled (sensors off / blocked).
     * - DISABLED: Authoritatively verified that GLOBAL sensor privacy is disabled (sensors on / accessible).
     * - UNKNOWN: Authoritative global state could not be determined or verified. UNKNOWN must never be treated as ENABLED or DISABLED.
     *
     * Invariants:
     * - Global state is determined exclusively from the actual global sensor privacy service/Binder API.
     * - Camera and microphone states are NEVER used as proof of global sensor privacy state.
     * - SharedPreferences, Settings.Global, and Settings.Secure are NEVER used to calculate authoritative global state.
     */
    fun getSensorsOffState(context: Context): SensorPrivacyState {
        // Layer 0: Direct Parcel Binder query for global sensor privacy via Shizuku
        val directGlobal = queryDirectSensorPrivacy()
        if (directGlobal.isAuthoritative) {
            return directGlobal
        }

        // Layer 1: Check native Android SensorPrivacyManager global state directly via cached reflection
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
            }
        } catch (e: Exception) {
            Log.d(TAG, "SensorPrivacyManager reflection check: ${e.message}")
        }

        // Authoritative global state could not be determined. UNKNOWN must remain UNKNOWN.
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

    // Typed Shell Execution Helpers
    private fun executeTypedShellToggleGlobal(txCode: Int, turnOff: Boolean): CommandResult {
        val targetValue = if (turnOff) 1 else 0
        val cmd = "service call sensor_privacy $txCode i32 $targetValue"
        if (isShizukuRunning() && isShizukuAuthorized()) {
            val res = executeShizukuInternalCommand(cmd)
            if (res.success) return res
        }
        if (isRootAvailable()) {
            return executeRootInternalCommand(cmd)
        }
        return CommandResult.failure("No privilege available for shell global toggle")
    }

    private fun executeTypedShellToggleIndividual(txCode: Int, userId: Int, sensorCode: Int, turnOff: Boolean): CommandResult {
        val targetVal = if (turnOff) 1 else 0
        val cmd = "service call sensor_privacy $txCode i32 $userId i32 ${SensorPrivacyCodes.SOURCE_QS_TILE} i32 $sensorCode i32 $targetVal"
        if (isShizukuRunning() && isShizukuAuthorized()) {
            val res = executeShizukuInternalCommand(cmd)
            if (res.success) return res
        }
        if (isRootAvailable()) {
            return executeRootInternalCommand(cmd)
        }
        return CommandResult.failure("No privilege available for shell individual toggle")
    }

    private fun executeTypedShellToggleCamMic(userId: Int, turnOff: Boolean): CommandResult {
        val targetVal = if (turnOff) 1 else 0
        val cmd = "service call sensor_privacy ${SensorPrivacyCodes.SET_TOGGLE_PRIVACY} i32 $userId i32 ${SensorPrivacyCodes.SOURCE_QS_TILE} i32 ${SensorPrivacyCodes.SENSOR_CAMERA} i32 $targetVal ; service call sensor_privacy ${SensorPrivacyCodes.SET_TOGGLE_PRIVACY} i32 $userId i32 ${SensorPrivacyCodes.SOURCE_QS_TILE} i32 ${SensorPrivacyCodes.SENSOR_MICROPHONE} i32 $targetVal"
        if (isShizukuRunning() && isShizukuAuthorized()) {
            val res = executeShizukuInternalCommand(cmd)
            if (res.success) return res
        }
        if (isRootAvailable()) {
            return executeRootInternalCommand(cmd)
        }
        return CommandResult.failure("No privilege available for shell cam/mic toggle")
    }

    /**
     * Executes internal Shizuku shell commands.
     * Security Guarantee:
     * - Commands passed to this method are exclusively generated internally by the application.
     * - No user-controlled input can become or influence shell commands.
     * - Exit code is strictly validated, process timeouts are enforced, and streams are closed in finally blocks.
     * - Sensor state changes are always verified via authoritative read-back afterward.
     */
    private fun executeShizukuInternalCommand(command: String, timeoutMs: Long = 4000L): CommandResult {
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
     * Executes internal root SU commands.
     * Security Guarantee:
     * - Commands passed to this method are exclusively generated internally by the application.
     * - No user-controlled input can become or influence shell commands.
     * - Exit code is strictly validated, process timeouts are enforced, and streams are closed in finally blocks.
     * - Sensor state changes are always verified via authoritative read-back afterward.
     */
    private fun executeRootInternalCommand(command: String, timeoutMs: Long = 4000L): CommandResult {
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
            val packageName = context.packageName
            val tileClassName = if (packageName == "com.SensorsOff.tile") "com.example.tile.SensorsOffTileService" else "com.example.SensorsOffTileService"
            android.service.quicksettings.TileService.requestListeningState(
                context,
                android.content.ComponentName(packageName, tileClassName)
            )
            if (packageName != "com.SensorsOff.tile") {
                try {
                    android.service.quicksettings.TileService.requestListeningState(
                        context,
                        android.content.ComponentName("com.SensorsOff.tile", "com.example.tile.SensorsOffTileService")
                    )
                } catch (ignored: Exception) {}
            }
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
