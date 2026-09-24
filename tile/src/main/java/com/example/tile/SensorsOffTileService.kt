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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Independent Quick Settings Tile Service for SensorsOff Companion APK.
 * Features:
 * - 100% Standalone APK process running completely independently of the main app
 * - Zero background daemons, zero polling, zero wake locks, zero persistent services
 * - Authoritative hardware state confirmation via ISensorPrivacyManager Binder transactions
 * - Event-driven reactivity using ContentObserver and Conflated Channel
 */
class SensorsOffTileService : TileService() {

    companion object {
        private const val TAG = "SensorsOffTileCompanion"
        private val sessionCounter = java.util.concurrent.atomic.AtomicLong(1)
    }

    private val instanceId: String = "tile-session-${sessionCounter.getAndIncrement()}"

    @Volatile private var pendingTargetState: Boolean? = null
    @Volatile private var pendingTargetExpiryTimeMs: Long = 0L

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listeningJob: kotlinx.coroutines.Job? = null
    private val toggleChannel = kotlinx.coroutines.channels.Channel<Pair<Boolean, Long>>(kotlinx.coroutines.channels.Channel.CONFLATED)

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
            val now = System.currentTimeMillis()
            if (pendingTargetState != null && now < pendingTargetExpiryTimeMs) {
                return
            }
            refreshTileImmediately()
        }
    }

    override fun onCreate() {
        super.onCreate()
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

        // Launch single serialized toggle consumer to eliminate concurrent shell process pileups
        serviceScope.launch(Dispatchers.IO) {
            for (initialItem in toggleChannel) {
                // Drain any queued clicks to only process the final target
                var currentItem = initialItem
                while (true) {
                    val next = toggleChannel.tryReceive().getOrNull() ?: break
                    currentItem = next
                }
                val (target, clickTime) = currentItem
                val executionStartTime = System.currentTimeMillis()

                val success = if (cachedBlockMode == "cam_mic") {
                    ShizukuManager.setCamMicSensorState(applicationContext, target, skipNotify = true)
                } else {
                    ShizukuManager.setSensorsOffState(applicationContext, target, skipNotify = true)
                }

                val elapsedMs = System.currentTimeMillis() - executionStartTime

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

                withContext(Dispatchers.Main) {
                    pendingTargetState = null
                    updateTileState(confirmedState)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
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
        toggleChannel.close()
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
        }
    }

    override fun onTileAdded() {
        super.onTileAdded()
        ShizukuManager.initialize(applicationContext)
        reloadVisualConfig()
        refreshTileImmediately()
        serviceScope.launch(Dispatchers.IO) {
            try {
                val currentState = if (cachedBlockMode == "cam_mic") {
                    ShizukuManager.getCamMicCombinedState(applicationContext)
                } else {
                    ShizukuManager.getSensorsOffState(applicationContext)
                }
                withContext(Dispatchers.Main) {
                    if (currentState.isAuthoritative) {
                        updateTileState(currentState)
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Tile added query note: ${e.message}")
                }
            }
        }
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
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
        TileLogManager.logLifecycleEvent(
            applicationContext,
            "CompanionTileService",
            "onStartListening",
            "Session: $instanceId (PID: $pid) | Quick Settings shade opened / tile listening started"
        )

        // Reconstruct / verify runtime dependencies on each listening cycle
        ShizukuManager.initialize(applicationContext)
        reloadVisualConfig()

        val now = System.currentTimeMillis()
        if (pendingTargetState != null && now < pendingTargetExpiryTimeMs) {
            refreshTileImmediately()
            return
        }

        // 1. Immediate UI refresh from cached UI state for instant responsiveness
        refreshTileImmediately()

        // 2. Fast asynchronous query to synchronize tile with real hardware state
        listeningJob = serviceScope.launch(Dispatchers.IO) {
            try {
                val isShizukuRunning = ShizukuManager.isShizukuRunning()
                val isShizukuAuthorized = if (isShizukuRunning) ShizukuManager.isShizukuAuthorized() else false
                val binder = ShizukuManager.getSensorPrivacyBinder()
                val isBinderAlive = binder?.isBinderAlive == true
                val sdkInt = Build.VERSION.SDK_INT
                val txCode = if (cachedBlockMode == "cam_mic") SensorPrivacyCodes.IS_TOGGLE_PRIVACY else SensorPrivacyCodes.getQueryGlobalCodeForSdk(sdkInt)

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

                val detailString = "PID: $pid | Session: $instanceId | Shizuku: [run=$isShizukuRunning, auth=$isShizukuAuthorized] | BinderAlive: $isBinderAlive | API: $sdkInt | TxCode: $txCode | Mode: $cachedBlockMode | State: $currentState"

                TileLogManager.logAuthoritativeState(
                    applicationContext,
                    currentState,
                    mappedTileState,
                    detailString
                )

                if (pendingTargetState == null || System.currentTimeMillis() >= pendingTargetExpiryTimeMs) {
                    withContext(Dispatchers.Main) {
                        if (currentState.isAuthoritative) {
                            updateTileState(currentState)
                        } else {
                            // If authoritative state is UNKNOWN or temporarily unavailable,
                            // maintain the tile in an interactive inactive state rather than disabling it
                            updateTileState(SensorPrivacyState.UNKNOWN)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Listening query note: ${e.message}")
                }
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        TileLogManager.logLifecycleEvent(
            applicationContext,
            "CompanionTileService",
            "onStopListening",
            "Quick Settings shade closed / tile listening stopped"
        )
        listeningJob?.cancel()
    }

    private fun refreshTileImmediately() {
        try {
            val now = System.currentTimeMillis()
            if (pendingTargetState != null && now < pendingTargetExpiryTimeMs) {
                updateTileState(pendingTargetState == true)
                return
            }

            val cachedState = if (cachedBlockMode == "cam_mic") {
                val prefs = applicationContext.getSharedPreferences("sensors_off_prefs", Context.MODE_PRIVATE)
                prefs.getBoolean("sensor_blocked_camera", false) || prefs.getBoolean("sensor_blocked_mic", false)
            } else {
                ShizukuManager.getCachedUiPreferenceState(applicationContext)
            }
            updateTileState(cachedState)
        } catch (e: Exception) {
            Log.e(TAG, "Error in immediate tile refresh", e)
        }
    }

    override fun onClick() {
        super.onClick()
        val clickTime = System.currentTimeMillis()

        // Re-ensure dependencies and config upon click
        ShizukuManager.initialize(applicationContext)
        reloadVisualConfig()

        TileLogManager.logLifecycleEvent(
            applicationContext,
            "CompanionTileService",
            "onClick",
            "User tapped Quick Settings tile"
        )

        // 1. Abort any background listening query
        listeningJob?.cancel()

        // 2. Determination of target state accounting for in-flight clicks
        val now = System.currentTimeMillis()
        val isCurrentlyActive = if (pendingTargetState != null && now < pendingTargetExpiryTimeMs) {
            pendingTargetState!!
        } else {
            (qsTile?.state ?: Tile.STATE_INACTIVE) == Tile.STATE_ACTIVE
        }
        val target = !isCurrentlyActive

        // 3. Privilege availability check - immediate on-demand check (zero polling/waiting)
        val hasPrivilege = ShizukuManager.isPrivilegeAvailable(applicationContext)
        if (!hasPrivilege) {
            TileLogManager.logTileEvent(
                applicationContext,
                "QS Tile Tap Event",
                "Touch detected while Shizuku/Root is unavailable.",
                LogLevel.WARN
            )
            updateTileState(SensorPrivacyState.UNKNOWN)
            return
        }

        // 4. Lock optimistic target state so rapid pull-down gestures don't flicker UI
        pendingTargetState = target
        pendingTargetExpiryTimeMs = System.currentTimeMillis() + 2000L

        // 5. Update UI state
        updateTileState(target)

        TileLogManager.logTileEvent(
            applicationContext,
            "QS Tile Tap Event",
            "Touch -> Updating tile state (Target: ${if (target) "ON" else "OFF"})",
            LogLevel.INFO
        )

        // 6. Asynchronous hardware toggle via conflated worker loop
        toggleChannel.trySend(Pair(target, clickTime))
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

        if (tile.state == targetState &&
            tile.label == cachedDisplayLabel &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || tile.subtitle == targetSubtitle)) {
            return
        }

        tile.state = targetState
        tile.label = cachedDisplayLabel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = targetSubtitle
        }
        targetIcon?.let { tile.icon = it }
        tile.updateTile()
    }

    private fun updateTileState(isSensorsOff: Boolean) {
        updateTileState(if (isSensorsOff) SensorPrivacyState.ENABLED else SensorPrivacyState.DISABLED)
    }
}
