package com.example.tile

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.example.TilePluginLog
import org.json.JSONObject

/**
 * Lightweight ContentProvider in the Quick Tile Companion APK (:tile).
 * Allows the main SensorsOff app to query dedicated [TILE_PLUGIN] logs on-demand.
 *
 * Characteristics:
 * - On-demand query execution (zero background threads or services)
 * - Safe read-only log serving
 */
class TilePluginLogProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let { TilePluginLog.initialize(it) }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val ctx = context ?: return MatrixCursor(COLUMNS)
        TilePluginLog.initialize(ctx)

        val cursor = MatrixCursor(COLUMNS)
        val logs = TilePluginLog.logsFlow.value
        for (entry in logs) {
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

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        context?.let {
            TilePluginLog.clear(it)
            return 1
        }
        return 0
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
