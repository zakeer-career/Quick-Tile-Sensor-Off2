package com.example.tile

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import com.example.TilePluginLog
import rikka.shizuku.Shizuku

/**
 * Lightweight, transparent permission activity for the Quick Tile Companion.
 * Application ID: com.SensorsOff.tile
 *
 * Allows the companion APK to directly request Shizuku authorization
 * from its own package context when the user clicks the Quick Settings tile
 * without having yet granted Shizuku permissions to the companion package.
 *
 * Exits immediately upon result or failure; zero background daemons.
 */
class SensorsOffTilePermissionActivity : Activity() {

    companion object {
        private const val TAG = "CompanionPermActivity"
        const val REQUEST_CODE_SHIZUKU = 1001
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE_SHIZUKU) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            TilePluginLog.logLifecycle(
                this,
                "SHIZUKU_PERMISSION_RESULT",
                "Companion direct request: granted=$granted (code=$grantResult)"
            )
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            if (!Shizuku.pingBinder()) {
                TilePluginLog.logLifecycle(this, "SHIZUKU_PERMISSION_REQUEST", "Shizuku not running")
                finish()
                return
            }

            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                TilePluginLog.logLifecycle(this, "SHIZUKU_PERMISSION_REQUEST", "Already granted")
                finish()
                return
            }

            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
            TilePluginLog.logLifecycle(this, "SHIZUKU_PERMISSION_REQUEST", "Dispatched Shizuku.requestPermission(1001)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed requesting Shizuku permission: ${e.message}", e)
            TilePluginLog.logLifecycle(this, "SHIZUKU_PERMISSION_ERROR", e.message ?: "Unknown error")
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (ignored: Exception) {}
    }
}
