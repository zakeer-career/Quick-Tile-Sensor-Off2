package com.example.tile

import android.app.Application
import com.example.TilePluginLog

/**
 * Application class for Quick Tile Companion APK (:tile).
 * Application ID: com.SensorsOff.tile
 *
 * Emits initial [TILE_PLUGIN] process start event for session tracking.
 * Zero background daemons, zero polling, zero wake locks.
 */
class SensorsOffTileApp : Application() {

    override fun onCreate() {
        super.onCreate()
        TilePluginLog.initialize(this)
        TilePluginLog.logProcessStart(this, "SensorsOffTileApp.onCreate")
    }
}
