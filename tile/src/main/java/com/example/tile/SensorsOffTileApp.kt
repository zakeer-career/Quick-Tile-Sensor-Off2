package com.example.tile

import android.app.Application
import com.example.TilePluginLog

/**
 * Application class for Quick Tile Companion APK (:tile).
 * Application ID: com.SensorsOff.tile
 *
 * Emits initial [TILE_PLUGIN] COMPANION_PROCESS_CREATED event directly
 * to the persistent log file upon process start.
 * Zero background daemons, zero polling, zero wake locks.
 */
class SensorsOffTileApp : Application() {

    override fun onCreate() {
        super.onCreate()
        TilePluginLog.initialize(this)
        TilePluginLog.logCompanionProcessCreated(this, "SensorsOffTileApp.onCreate")
    }
}
