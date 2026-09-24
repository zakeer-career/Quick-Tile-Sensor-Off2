package com.example

import android.app.Application
import android.util.Log

/**
 * Custom Application class for SensorsOff.
 * Initializes lightweight process logging and registers sticky Shizuku binder listeners.
 * Free of background daemons, persistent keep-alives, and speculative initialization.
 */
class SensorsOffApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.d("SensorsOffApp", "SensorsOff Application process initialized [PID: ${android.os.Process.myPid()}]")
        try {
            TileLogManager.initialize(this)
            ShizukuManager.initialize(this)
            TileLogManager.logLifecycleEvent(
                this,
                "Application",
                "Process Created",
                "Fresh process initialized (zero daemons/services running)"
            )
        } catch (e: Exception) {
            Log.e("SensorsOffApp", "Failed during application initialization", e)
        }
    }
}
