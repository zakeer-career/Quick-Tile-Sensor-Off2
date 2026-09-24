package com.example.tile

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import android.util.Log
import com.example.TilePluginLog
import org.json.JSONObject

/**
 * Lightweight ContentProvider in the Quick Tile Companion APK (:tile).
 * Application ID: com.SensorsOff.tile
 * Authority: com.SensorsOff.tile.logprovider
 *
 * Reads persistent log file ("tile_plugin.log") directly on-demand.
 * Restricted to caller verification (calling UID matching same user / SensorsOff package).
 *
 * Characteristics:
 * - Direct read of persistent package-local log file
 * - Zero background threads or services
 * - Safe on-demand serving
 * - Test Log insertion trigger for verifying IPC pipeline
 */
class TilePluginLogProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let { ctx ->
            TilePluginLog.initialize(ctx)
            TilePluginLog.logProviderOnCreate(ctx)
        }
        return true
    }

    private fun checkCallingPermission(): Boolean {
        val ctx = context ?: return false
        val callingUid = Binder.getCallingUid()

        // Allow own process / companion's own UID
        if (callingUid == Process.myUid()) {
            return true
        }

        // Verify package associated with calling UID
        try {
            val pm = ctx.packageManager
            val packages = pm.getPackagesForUid(callingUid)
            if (packages != null) {
                for (pkg in packages) {
                    if (pkg == TilePluginLog.MAIN_APP_PACKAGE || pkg == TilePluginLog.COMPANION_PACKAGE) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("TilePluginLogProvider", "Caller UID verification error: ${e.message}")
        }

        // Reject all other callers
        return false
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val ctx = context ?: return MatrixCursor(COLUMNS)
        if (!checkCallingPermission()) {
            throw SecurityException("Unauthorized access to companion log provider")
        }

        // Read directly from the persistent package-private file
        val entries = TilePluginLog.readPersistentLogEntries(ctx)

        val cursor = MatrixCursor(COLUMNS)
        for (entry in entries) {
            val fieldsJson = JSONObject().apply {
                for ((k, v) in entry.fields) {
                    put(k, v)
                }
            }.toString()

            cursor.addRow(
                arrayOf(
                    entry.id,
                    entry.timestamp,
                    entry.formattedTime,
                    entry.event,
                    entry.pid,
                    entry.thread,
                    entry.session,
                    fieldsJson
                )
            )
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.sensorsoff.tile.log"

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val ctx = context ?: return null
        if (!checkCallingPermission()) {
            throw SecurityException("Unauthorized access to companion log provider")
        }

        // Diagnostic action: Write Test Log
        TilePluginLog.logTestLogWrite(ctx)
        return Uri.withAppendedPath(TilePluginLog.LOG_PROVIDER_URI, "test_log_written")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val ctx = context ?: return 0
        if (!checkCallingPermission()) {
            throw SecurityException("Unauthorized access to companion log provider")
        }

        TilePluginLog.clear(ctx)
        return 1
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private val COLUMNS = arrayOf(
            "id",
            "timestamp",
            "time",
            "event",
            "pid",
            "thread",
            "session",
            "fields"
        )
    }
}
