package com.example.tile

import android.content.Context
import android.database.ContentObserver
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.example.LogLevel
import com.example.SensorPrivacyCodes
import com.example.SensorPrivacyState
import com.example.ShizukuManager
import com.example.TileLogManager
import com.example.TilePluginLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Independent Quick Settings Tile Service for SensorsOff Companion APK.
 * Features:
 * - 100% Standalone APK process running completely independently of the main app
 * - Zero background daemons, zero polling, zero wake locks, zero persistent services
 * - Authoritative hardware state confirmation via ISensorPrivacyManager Binder transactions
 * - Recreates cleanly across process death and SystemUI unbinds
 */
class SensorsOffTileService : TileService() {

    companion object {
        private const val TAG = "SensorsOffTileCompanion"
        private val sessionCounter = java.util.concurrent.atomic.AtomicLong(1)
    }

    private val instanceId: String = "tile-session-${sessionCounter.getAndIncrement()}"

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listeningJob: kotlinx.coroutines.Job? = null
    private val toggleMutex = Mutex()

    // Pre-cached visual assets - zero memory allocations during touch events
    @Volatile private var cachedActiveIcon: Icon? = null
    @Volatile private var cachedInactiveIcon: Icon? = null
    @Volatile private var cachedDisplayLabel: String = ""
    @Volatile private var cachedActiveSubtitle: String = "On"
    @Volatile private var cachedDisabledSubtitle: String = "Off"
    @Volatile private var cachedBlockMode: String = "global"

    // Real-time ContentObserver listening to native system sensor privacy state
    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            performAuthoritativeStateSync()
        }
    }

    override fun onCreate() {
        super.onCreate()
        TilePluginLog.initialize(applicationContext)
        TilePluginLog.logTileServiceOnCreate(applicationContext)
        TileLogManager.initialize(applicationContext)
        ShizukuManager.initialize(applicationContext)
        reloadVisualConfig()

        // Register ContentObserver for real-time reactivity without polling
        try {
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor("sensors_off"),
                false,
                settingsObserver
            )
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor("sensor_privacy"),
                false,
                settingsObserver
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException registering settings observer: ${e.message}")
        } catch (e: Exception) {
            Log.d(TAG, "ContentObserver registration note: ${e.message}")
        }

        TileLogManager.logLifecycleEvent(
            applicationContext,
            "CompanionTileService",
            "onCreate",
            "SensorsOffTileService initialized independently in standalone tile process"
        )
    }

    override fun onDestroy() {
        try {
            super.onDestroy()
        } catch (e: Throwable) {
            Log.d(TAG, "onDestroy super note: ${e.message}")
        }
        TilePluginLog.logTileServiceOnDestroy(applicationContext)
        try {
            contentResolver.unregisterContentObserver(settingsObserver)
        } catch (e: Exception) {
            Log.d(TAG, "ContentObserver unregister note: ${e.message}")
        }
        TileLogManager.logLifecycleEvent(
            applicationContext,
            "CompanionTileService",
            "onDestroy",
            "SensorsOffTileService unbinding cleanly / destroying instance"
        )
        listeningJob?.cancel()
        serviceScope.cancel()
    }

    private fun reloadVisualConfig() {
        try {
            cachedBlockMode = ShizukuManager.getTileBlockMode(applicationContext)
            val iconStyle = ShizukuManager.getTileIconStyle(applicationContext)
            val customLabel = ShizukuManager.getTileLabelText(applicationContext)
            val actSub = ShizukuManager.getTileActiveSubtitleText(applicationContext)
            val disSub = ShizukuManager.getTileDisabledSubtitleText(applicationContext)
            val customPath = ShizukuManager.getCustomIconPath(applicationContext)

            cachedDisplayLabel = if (customLabel.isNotBlank()) customLabel else getString(R.string.tile_label)
            cachedActiveSubtitle = if (actSub.isNotBlank() && !actSub.equals("Blocked", ignoreCase = true)) actSub else "On"
            cachedDisabledSubtitle = if (disSub.isNotBlank() && !disSub.equals("Available", ignoreCase = true)) disSub else "Off"

            if (iconStyle == "custom" && customPath != null) {
                val bitmap = android.graphics.BitmapFactory.decodeFile(customPath)
                if (bitmap != null) {
                    val customIcon = Icon.createWithBitmap(bitmap)
                    cachedActiveIcon = customIcon
                    cachedInactiveIcon = customIcon
                } else {
                    cachedActiveIcon = Icon.createWithResource(this, R.drawable.tile_icon_sensorsoff_active)
                    cachedInactiveIcon = Icon.createWithResource(this, R.drawable.tile_icon_sensorsoff_inactive)
                }
            } else {
                val (actRes, inactRes) = when (iconStyle) {
                    "shield" -> Pair(R.drawable.ic_shield_sensors, R.drawable.ic_shield_sensors)
                    "camera_off" -> Pair(R.drawable.ic_camera_off, R.drawable.ic_camera_off)
                    "mic_off" -> Pair(R.drawable.ic_mic_off, R.drawable.ic_mic_off)
                    "motion_off" -> Pair(R.drawable.ic_motion_sensors_off, R.drawable.ic_motion_sensors_off)
                    else -> Pair(R.drawable.tile_icon_sensorsoff_active, R.drawable.tile_icon_sensorsoff_inactive)
                }
                cachedActiveIcon = Icon.createWithResource(this, actRes)
                cachedInactiveIcon = Icon.createWithResource(this, inactRes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error caching visual configuration", e)
            cachedDisplayLabel = getString(R.string.tile_label)
            cachedActiveSubtitle = "On"
            cachedDisabledSubtitle = "Off"
            cachedActiveIcon = Icon.createWithResource(this, R.drawable.tile_icon_sensorsoff_active)
            cachedInactiveIcon = Icon.createWithResource(this, R.drawable.tile_icon_sensorsoff_inactive)
        }
    }

    override fun onTileAdded() {
        super.onTileAdded()
        TilePluginLog.logLifecycle(applicationContext, "TileService.onTileAdded")
        ShizukuManager.initialize(applicationContext)
        reloadVisualConfig()
        performAuthoritativeStateSync()
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        TilePluginLog.logLifecycle(applicationContext, "TileService.onTileRemoved")
        listeningJob?.cancel()
        TileLogManager.logTileEvent(
            applicationContext,
            "Tile Removed",
            "Tile removed from active Quick Settings shade; cleaned up instance state",
            LogLevel.DEBUG
        )
    }

    override fun onStartListening() {
        super.onStartListening()
        listeningJob?.cancel()

        val pid = android.os.Process.myPid()
        TilePluginLog.logTileServiceOnStartListening(applicationContext, "Instance=$instanceId")
        TileLogManager.logLifecycleEvent(
            applicationContext,
            "CompanionTileService",
            "onStartListening",
            "Session: $instanceId (PID: $pid) | Quick Settings shade opened / tile listening started"
        )

        // 1. Initialize Shizuku and reload visual configuration
        ShizukuManager.initialize(applicationContext)
        reloadVisualConfig()

        // 2. Perform ONE authoritative state read and update tile once
        performAuthoritativeStateSync()
    }

    override fun onStopListening() {
        super.onStopListening()
        TilePluginLog.logTileServiceOnStopListening(applicationContext)
        TileLogManager.logLifecycleEvent(
            applicationContext,
            "CompanionTileService",
            "onStopListening",
            "Quick Settings shade closed / tile listening stopped"
        )
        listeningJob?.cancel()
    }

    private fun performAuthoritativeStateSync() {
        listeningJob?.cancel()
        val pid = android.os.Process.myPid()

        listeningJob = serviceScope.launch(Dispatchers.IO) {
            try {
                val isShizukuRunning = ShizukuManager.isShizukuRunning()
                val isShizukuAuthorized = if (isShizukuRunning) ShizukuManager.isShizukuAuthorized() else false
                val binder = ShizukuManager.getSensorPrivacyBinder()
                val isBinderAlive = binder?.isBinderAlive == true
                val sdkInt = Build.VERSION.SDK_INT
                val txCode = if (cachedBlockMode == "cam_mic") SensorPrivacyCodes.IS_TOGGLE_PRIVACY else SensorPrivacyCodes.getQueryGlobalCodeForSdk(sdkInt)

                val permCheck = if (isShizukuRunning) {
                    runCatching { rikka.shizuku.Shizuku.checkSelfPermission() }.getOrDefault(-1)
                } else -1

                // Dedicated Shizuku & Binder Diagnostics
                TilePluginLog.logShizukuCheck(
                    context = applicationContext,
                    running = isShizukuRunning,
                    binderAlive = isBinderAlive,
                    permissionGranted = isShizukuAuthorized,
                    permissionCheckResult = permCheck,
                    binderStatus = if (binder != null) "ACQUIRED" else "UNAVAILABLE"
                )

                TilePluginLog.logSensorPrivacyBinder(
                    context = applicationContext,
                    result = if (binder != null) "CONNECTED" else "UNAVAILABLE",
                    binderAlive = isBinderAlive,
                    api = sdkInt,
                    readTx = txCode,
                    setTx = if (cachedBlockMode == "cam_mic") SensorPrivacyCodes.SET_TOGGLE_PRIVACY else SensorPrivacyCodes.getSetGlobalCodeForSdk(sdkInt)
                )

                val currentState = if (cachedBlockMode == "cam_mic") {
                    ShizukuManager.getCamMicCombinedState(applicationContext)
                } else {
                    ShizukuManager.getSensorsOffState(applicationContext)
                }

                val mappedTileState = when (currentState) {
                    SensorPrivacyState.ENABLED -> Tile.STATE_ACTIVE
                    SensorPrivacyState.DISABLED -> Tile.STATE_INACTIVE
                    SensorPrivacyState.UNKNOWN -> Tile.STATE_INACTIVE
                }

                // Explicit State Reason Detection
                val unknownReason = if (currentState == SensorPrivacyState.UNKNOWN) {
                    when {
                        !isShizukuRunning -> "SHIZUKU_NOT_RUNNING"
                        !isShizukuAuthorized -> "SHIZUKU_PERMISSION_DENIED"
                        binder == null -> "SENSOR_PRIVACY_BINDER_UNAVAILABLE"
                        !isBinderAlive -> "SENSOR_PRIVACY_BINDER_DEAD"
                        txCode == null -> "UNSUPPORTED_API"
                        else -> "TRANSACTION_FAILED"
                    }
                } else null

                TilePluginLog.logAuthoritativeStateRead(
                    context = applicationContext,
                    attempt = 1,
                    shizukuStatus = if (isShizukuRunning && isShizukuAuthorized) "RUNNING_AND_AUTHORIZED" else if (isShizukuRunning) "RUNNING_UNAUTHORIZED" else "NOT_RUNNING",
                    sensorBinderStatus = if (binder == null) "NULL" else if (isBinderAlive) "ALIVE" else "DEAD",
                    api = sdkInt,
                    transaction = txCode,
                    result = if (currentState.isAuthoritative) "SUCCESS" else "FAILURE",
                    parsedState = currentState.name,
                    finalState = currentState.name,
                    reason = unknownReason
                )

                if (unknownReason != null) {
                    TilePluginLog.logStateUnknown(
                        context = applicationContext,
                        reason = unknownReason,
                        detail = "Authoritative state resolution failed | Shizuku running=$isShizukuRunning, auth=$isShizukuAuthorized, binderAlive=$isBinderAlive"
                    )
                }

                val detailString = "PID: $pid | Session: $instanceId | Shizuku: [run=$isShizukuRunning, auth=$isShizukuAuthorized] | BinderAlive: $isBinderAlive | API: $sdkInt | TxCode: $txCode | Mode: $cachedBlockMode | State: $currentState"

                TileLogManager.logAuthoritativeState(
                    applicationContext,
                    currentState,
                    mappedTileState,
                    detailString
                )

                withContext(Dispatchers.Main) {
                    updateTileState(currentState)
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Listening query note: ${e.message}")
                    TilePluginLog.logStateUnknown(
                        context = applicationContext,
                        reason = "EXCEPTION",
                        detail = e.message ?: "Unknown exception during listening query"
                    )
                    withContext(Dispatchers.Main) {
                        updateTileState(SensorPrivacyState.UNKNOWN)
                    }
                }
            }
        }
    }

    override fun onClick() {
        super.onClick()
        val clickTime = System.currentTimeMillis()

        TilePluginLog.logTileServiceOnClick(applicationContext)
        TileLogManager.logLifecycleEvent(
            applicationContext,
            "CompanionTileService",
            "onClick",
            "User tapped Quick Settings tile"
        )

        // Abort background listening query if running
        listeningJob?.cancel()

        serviceScope.launch(Dispatchers.IO) {
            toggleMutex.withLock {
                // 1. Initialize/reacquire Shizuku and reload visual configuration
                ShizukuManager.initialize(applicationContext)
                reloadVisualConfig()

                // 2. Read authoritative sensor privacy state directly from hardware/Binder (never guess from stale qsTile.state)
                val authoritativeState = if (cachedBlockMode == "cam_mic") {
                    ShizukuManager.getCamMicCombinedState(applicationContext)
                } else {
                    ShizukuManager.getSensorsOffState(applicationContext)
                }

                // 3. Determine target state strictly based on authoritative state
                val target = when (authoritativeState) {
                    SensorPrivacyState.ENABLED -> false // Currently enabled -> target is disabled (sensors on)
                    SensorPrivacyState.DISABLED -> true // Currently disabled -> target is enabled (sensors off)
                    SensorPrivacyState.UNKNOWN -> {
                        // If state is UNKNOWN, do not guess target. Log and keep tile interactive.
                        TilePluginLog.logStateUnknown(
                            context = applicationContext,
                            reason = "STATE_UNKNOWN",
                            detail = "Touch detected but authoritative sensor state is UNKNOWN; keeping tile interactive"
                        )
                        TileLogManager.logTileEvent(
                            applicationContext,
                            "QS Tile Tap Warning",
                            "Authoritative sensor state is UNKNOWN; skipping toggle to prevent unintended state change",
                            LogLevel.WARN
                        )
                        withContext(Dispatchers.Main) {
                            updateTileState(SensorPrivacyState.UNKNOWN)
                        }
                        return@withLock
                    }
                }

                TilePluginLog.logTileClick(
                    context = applicationContext,
                    currentState = authoritativeState.name,
                    requestedState = if (target) "ENABLED" else "DISABLED"
                )

                val executionStartTime = System.currentTimeMillis()
                val isShizukuRunning = ShizukuManager.isShizukuRunning()
                val isShizukuAuthorized = if (isShizukuRunning) ShizukuManager.isShizukuAuthorized() else false
                val binder = ShizukuManager.getSensorPrivacyBinder()
                val isBinderAlive = binder?.isBinderAlive == true
                val sdkInt = Build.VERSION.SDK_INT
                val setTxCode = if (cachedBlockMode == "cam_mic") SensorPrivacyCodes.SET_TOGGLE_PRIVACY else SensorPrivacyCodes.getSetGlobalCodeForSdk(sdkInt)

                // 6. Perform toggle
                val success = if (cachedBlockMode == "cam_mic") {
                    ShizukuManager.setCamMicSensorState(applicationContext, target, skipNotify = true)
                } else {
                    ShizukuManager.setSensorsOffState(applicationContext, target, skipNotify = true)
                }

                val elapsedMs = System.currentTimeMillis() - executionStartTime

                // 7. Perform authoritative read-back verification
                val confirmedState = if (cachedBlockMode == "cam_mic") {
                    ShizukuManager.getCamMicCombinedState(applicationContext)
                } else {
                    ShizukuManager.getSensorsOffState(applicationContext)
                }

                val confirmedStateString = when (confirmedState) {
                    SensorPrivacyState.ENABLED -> "STATE_ACTIVE"
                    SensorPrivacyState.DISABLED -> "STATE_INACTIVE"
                    SensorPrivacyState.UNKNOWN -> "STATE_UNKNOWN"
                }

                val verificationStatus = if (confirmedState == SensorPrivacyState.UNKNOWN) {
                    "FAILED"
                } else if ((target && confirmedState == SensorPrivacyState.ENABLED) || (!target && confirmedState == SensorPrivacyState.DISABLED)) {
                    confirmedState.name
                } else {
                    "MISMATCH_${confirmedState.name}"
                }

                TilePluginLog.logToggle(
                    context = applicationContext,
                    currentState = authoritativeState.name,
                    requestedState = if (target) "ENABLED" else "DISABLED",
                    shizukuStatus = if (isShizukuRunning && isShizukuAuthorized) "RUNNING_AND_AUTHORIZED" else if (isShizukuRunning) "RUNNING_UNAUTHORIZED" else "NOT_RUNNING",
                    binderStatus = if (binder == null) "NULL" else if (isBinderAlive) "ALIVE" else "DEAD",
                    setTransaction = setTxCode,
                    setResult = if (success) "SUCCESS" else "FAILURE",
                    verification = verificationStatus,
                    finalState = confirmedState.name,
                    reason = if (confirmedState == SensorPrivacyState.UNKNOWN) "STATE_READ_FAILED" else null
                )

                if (confirmedState == SensorPrivacyState.UNKNOWN) {
                    TilePluginLog.logStateUnknown(
                        context = applicationContext,
                        reason = "TRANSACTION_FAILED",
                        detail = "Post-toggle state verification returned UNKNOWN"
                    )
                }

                TileLogManager.logTileEvent(
                    applicationContext,
                    if (success) "Tile Toggle Completed" else "Tile Toggle Verification Warning",
                    "Target: $target | Confirmed: $confirmedStateString | Success: $success | Elapsed: ${elapsedMs}ms | Total: ${System.currentTimeMillis() - clickTime}ms",
                    if (success) LogLevel.SUCCESS else LogLevel.WARN,
                    executionMs = elapsedMs
                )

                TileLogManager.updateTileDiagnostics(
                    applicationContext,
                    lastState = confirmedStateString,
                    lastAction = if (success) {
                        "Toggle to ${if (target) "ON" else "OFF"} (Result: $confirmedStateString)"
                    } else {
                        "Toggle to ${if (target) "ON" else "OFF"} Failed (State: $confirmedStateString)"
                    },
                    lastLatencyMs = elapsedMs,
                    blockMode = cachedBlockMode
                )

                // 8. Update tile from the verified result
                withContext(Dispatchers.Main) {
                    updateTileState(confirmedState)
                }
            }
        }
    }

    private fun updateTileState(state: SensorPrivacyState) {
        val tile = qsTile ?: return

        val targetState = when (state) {
            SensorPrivacyState.ENABLED -> Tile.STATE_ACTIVE
            SensorPrivacyState.DISABLED -> Tile.STATE_INACTIVE
            SensorPrivacyState.UNKNOWN -> Tile.STATE_INACTIVE
        }
        val targetIcon = when (state) {
            SensorPrivacyState.ENABLED -> cachedActiveIcon
            SensorPrivacyState.DISABLED -> cachedInactiveIcon
            SensorPrivacyState.UNKNOWN -> cachedInactiveIcon
        }
        val targetSubtitle = when (state) {
            SensorPrivacyState.ENABLED -> cachedActiveSubtitle
            SensorPrivacyState.DISABLED -> cachedDisabledSubtitle
            SensorPrivacyState.UNKNOWN -> "State Unknown"
        }

        val reason = if (state == SensorPrivacyState.UNKNOWN) "SHIZUKU_CHECK_OR_READ_UNKNOWN" else "STATE_${state.name}"
        TilePluginLog.logTileUpdate(
            context = applicationContext,
            tileState = targetState,
            sensorState = state,
            subtitle = targetSubtitle,
            label = cachedDisplayLabel,
            reason = reason
        )

        tile.state = targetState
        tile.label = cachedDisplayLabel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = targetSubtitle
        }
        targetIcon?.let { tile.icon = it }
        tile.updateTile()
    }
}
