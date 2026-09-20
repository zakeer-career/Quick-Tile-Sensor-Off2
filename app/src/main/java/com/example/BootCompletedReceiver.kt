package com.example

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import android.util.Log

/**
 * Receiver that listens to system events like boot, update, or device unlock
 * and requests SystemUI to request listening / refresh the Quick Settings tile,
 * ensuring the tile is pre-warmed and never marked as unavailable.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        val action = intent?.action ?: "UNKNOWN_ACTION"
        Log.d("BootCompletedReceiver", "Received intent action: $action")
        try {
            TileLogManager.initialize(context)
            ShizukuManager.initialize(context)

            TileLogManager.log(
                context,
                LogCategory.SYSTEM,
                LogLevel.INFO,
                "Device boot trigger: $action"
            )

            // Pre-warm Quick Settings tile
            TileService.requestListeningState(
                context,
                ComponentName(context, SensorsOffTileService::class.java)
            )
        } catch (e: SecurityException) {
            Log.e("BootCompletedReceiver", "SecurityException processing boot event", e)
        } catch (e: Exception) {
            Log.e("BootCompletedReceiver", "Exception processing boot event", e)
        }
    }
}
