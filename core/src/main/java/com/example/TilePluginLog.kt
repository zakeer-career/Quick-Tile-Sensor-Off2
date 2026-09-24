package com.example

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Process
import android.service.quicksettings.Tile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Dedicated diagnostic logging system exclusively for the Quick Tile Companion (:tile).
 * Application ID: com.SensorsOff.tile
 *
 * Distinct Prefix: [TILE_PLUGIN]
 * Features:
 * - Zero background daemons, zero polling, zero keep-alives
 * - Complete companion TileService lifecycle tracing
 * - Granular Shizuku and Sensor Privacy Binder diagnostics
 * - Authoritative hardware state read and toggle verification tracing
 * - Explicit categorization of UNKNOWN state reasons
 * - Bounded local persistence surviving TileService recreation
 */
object TilePluginLog {

    const val TAG = "TILE_PLUGIN"
    const val COMPANION_PACKAGE = "com.SensorsOff.tile"
    const val LOG_PROVIDER_AUTHORITY = "com.SensorsOff.tile.logprovider"
    val LOG_PROVIDER_URI: Uri = Uri.parse("content://$LOG_PROVIDER_AUTHORITY/logs")

    private const val PREFS_NAME = "tile_companion_plugin_logs"
    private const val KEY_PERSISTED_ENTRIES = "persisted_companion_entries"
    private const val MAX_LOGS = 100

    private val idCounter = AtomicLong(System.currentTimeMillis())
    private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    private val fullDateFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US) }

    val pid: Int = Process.myPid()

    // Unique 6-character hex session ID generated per process lifecycle
    val sessionId: String by lazy {
        val randomBits = (System.currentTimeMillis() xor (pid.toLong() shl 16)) and 0xFFFFFF
        String.format(Locale.US, "%06X", randomBits)
    }

    data class Entry(
        val id: Long,
        val timestamp: Long,
        val formattedTime: String,
        val event: String,
        val pid: Int,
        val thread: String,
        val session: String,
        val fields: Map<String, String>,
        val rawMessage: String
    ) {
        fun toFormattedBlock(): String {
            return buildString {
                appendLine("[TILE_PLUGIN]")
                appendLine("event=$event")
                appendLine("session=$session")
                appendLine("pid=$pid")
                appendLine("thread=$thread")
                appendLine("time=$formattedTime")
                for ((k, v) in fields) {
                    appendLine("$k=$v")
                }
            }.trimEnd()
        }

        fun toSingleLine(): String {
            val extra = fields.entries.joinToString(" ") { "${it.key}=${it.value}" }
            return "[TILE_PLUGIN] [$formattedTime] session=$session pid=$pid thread=$thread event=$event $extra".trim()
        }
    }

    private val _logsFlow = MutableStateFlow<List<Entry>>(emptyList())
    val logsFlow: StateFlow<List<Entry>> = _logsFlow.asStateFlow()

    private var isInitialized = false

    fun initialize(context: Context) {
        if (isInitialized) return
        isInitialized = true
        loadFromPrefs(context)
    }

    private fun loadFromPrefs(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_PERSISTED_ENTRIES, null) ?: return
            val array = JSONArray(jsonStr)
            val list = mutableListOf<Entry>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optLong("id", System.currentTimeMillis())
                val ts = obj.optLong("timestamp", System.currentTimeMillis())
                val time = obj.optString("time", timeFormat.get()?.format(Date(ts)) ?: "")
                val event = obj.optString("event", "unknown")
                val p = obj.optInt("pid", pid)
                val thread = obj.optString("thread", "main")
                val sess = obj.optString("session", sessionId)
                val raw = obj.optString("raw", "")
                val fieldsObj = obj.optJSONObject("fields")
                val fieldsMap = mutableMapOf<String, String>()
                if (fieldsObj != null) {
                    val keys = fieldsObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        fieldsMap[key] = fieldsObj.getString(key)
                    }
                }
                list.add(
                    Entry(
                        id = id,
                        timestamp = ts,
                        formattedTime = time,
                        event = event,
                        pid = p,
                        thread = thread,
                        session = sess,
                        fields = fieldsMap,
                        rawMessage = raw
                    )
                )
            }
            _logsFlow.value = list
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load companion logs: ${e.message}")
        }
    }

    private fun saveToPrefs(context: Context, entries: List<Entry>) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val array = JSONArray()
            for (entry in entries.take(MAX_LOGS)) {
                val obj = JSONObject().apply {
                    put("id", entry.id)
                    put("timestamp", entry.timestamp)
                    put("time", entry.formattedTime)
                    put("event", entry.event)
                    put("pid", entry.pid)
                    put("thread", entry.thread)
                    put("session", entry.session)
                    put("raw", entry.rawMessage)
                    val fieldsObj = JSONObject()
                    for ((k, v) in entry.fields) {
                        fieldsObj.put(k, v)
                    }
                    put("fields", fieldsObj)
                }
                array.put(obj)
            }
            prefs.edit().putString(KEY_PERSISTED_ENTRIES, array.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist companion logs: ${e.message}")
        }
    }

    @Synchronized
    fun logEvent(
        context: Context,
        event: String,
        fields: Map<String, String> = emptyMap()
    ) {
        val now = System.currentTimeMillis()
        val formatted = timeFormat.get()?.format(Date(now)) ?: ""
        val threadName = Thread.currentThread().name

        val entry = Entry(
            id = idCounter.incrementAndGet(),
            timestamp = now,
            formattedTime = formatted,
            event = event,
            pid = pid,
            thread = threadName,
            session = sessionId,
            fields = fields,
            rawMessage = ""
        )

        val block = entry.toFormattedBlock()
        Log.i(TAG, "\n$block")

        val updated = (listOf(entry) + _logsFlow.value).take(MAX_LOGS)
        _logsFlow.value = updated
        saveToPrefs(context, updated)
    }

    // 2. Lifecycle Events
    fun logProcessStart(context: Context, source: String = "Application.onCreate") {
        logEvent(
            context,
            "process_start",
            mapOf(
                "source" to source,
                "api" to Build.VERSION.SDK_INT.toString(),
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}"
            )
        )
    }

    fun logLifecycle(context: Context, eventName: String, extraDetail: String = "") {
        val fields = mutableMapOf<String, String>()
        if (extraDetail.isNotBlank()) {
            fields["detail"] = extraDetail
        }
        logEvent(context, eventName, fields)
    }

    // 3. Shizuku Diagnostics
    fun logShizukuCheck(
        context: Context,
        running: Boolean,
        binderAlive: Boolean,
        permissionGranted: Boolean,
        permissionCheckResult: Int,
        binderStatus: String
    ) {
        logEvent(
            context,
            "shizuku_check",
            mapOf(
                "package" to COMPANION_PACKAGE,
                "running" to running.toString(),
                "binderAlive" to binderAlive.toString(),
                "permission" to if (permissionGranted) "GRANTED" else "DENIED",
                "permissionCheckResult" to permissionCheckResult.toString(),
                "binderStatus" to binderStatus
            )
        )
    }

    // 4. Sensor Privacy Binder Diagnostics
    fun logSensorPrivacyBinder(
        context: Context,
        result: String,
        binderAlive: Boolean,
        invalidated: Boolean = false,
        reacquired: Boolean = false,
        api: Int = Build.VERSION.SDK_INT,
        readTx: Int? = SensorPrivacyCodes.getQueryGlobalCodeForSdk(api),
        setTx: Int? = SensorPrivacyCodes.getSetGlobalCodeForSdk(api)
    ) {
        logEvent(
            context,
            "sensor_privacy_binder",
            mapOf(
                "result" to result,
                "binderAlive" to binderAlive.toString(),
                "invalidated" to invalidated.toString(),
                "reacquired" to reacquired.toString(),
                "api" to api.toString(),
                "readTransaction" to (readTx?.toString() ?: "UNSUPPORTED"),
                "setTransaction" to (setTx?.toString() ?: "UNSUPPORTED")
            )
        )
    }

    // 5. Authoritative State Read Diagnostics
    fun logAuthoritativeStateRead(
        context: Context,
        attempt: Int,
        shizukuStatus: String,
        sensorBinderStatus: String,
        api: Int,
        transaction: Int?,
        result: String,
        rawResult: String? = null,
        parsedState: String? = null,
        finalState: String,
        reason: String? = null
    ) {
        val fields = mutableMapOf(
            "attempt" to attempt.toString(),
            "shizukuStatus" to shizukuStatus,
            "sensorBinderStatus" to sensorBinderStatus,
            "api" to api.toString(),
            "transaction" to (transaction?.toString() ?: "NONE"),
            "result" to result,
            "finalState" to finalState
        )
        if (rawResult != null) fields["rawResult"] = rawResult
        if (parsedState != null) fields["parsedState"] = parsedState
        if (reason != null) fields["reason"] = reason

        logEvent(context, "authoritative_state_read", fields)
    }

    // 6. Toggle Diagnostics
    fun logTileClick(context: Context, currentState: String, requestedState: String) {
        logEvent(
            context,
            "tile_click",
            mapOf(
                "currentState" to currentState,
                "requestedState" to requestedState
            )
        )
    }

    fun logToggle(
        context: Context,
        currentState: String,
        requestedState: String,
        shizukuStatus: String,
        binderStatus: String,
        setTransaction: Int?,
        setResult: String,
        verification: String,
        finalState: String,
        reason: String? = null
    ) {
        val fields = mutableMapOf(
            "currentState" to currentState,
            "requestedState" to requestedState,
            "shizukuStatus" to shizukuStatus,
            "binderStatus" to binderStatus,
            "setTransaction" to (setTransaction?.toString() ?: "NONE"),
            "setResult" to setResult,
            "verification" to verification,
            "finalState" to finalState
        )
        if (reason != null) fields["reason"] = reason
        logEvent(context, "toggle", fields)
    }

    // 7. Explicit State Unknown Reason
    fun logStateUnknown(
        context: Context,
        reason: String,
        detail: String = ""
    ) {
        val fields = mutableMapOf("reason" to reason)
        if (detail.isNotBlank()) fields["detail"] = detail
        logEvent(context, "state_unknown", fields)
    }

    // 8. Tile UI Update
    fun logTileUpdate(
        context: Context,
        tileState: Int,
        sensorState: SensorPrivacyState,
        subtitle: String,
        label: String,
        reason: String = ""
    ) {
        val stateName = when (tileState) {
            Tile.STATE_ACTIVE -> "STATE_ACTIVE"
            Tile.STATE_INACTIVE -> "STATE_INACTIVE"
            Tile.STATE_UNAVAILABLE -> "STATE_UNAVAILABLE"
            else -> "STATE_$tileState"
        }
        val fields = mutableMapOf(
            "tileState" to stateName,
            "sensorState" to sensorState.name,
            "subtitle" to subtitle,
            "label" to label
        )
        if (reason.isNotBlank()) fields["reason"] = reason
        logEvent(context, "tile_update", fields)
    }

    fun clear(context: Context) {
        _logsFlow.value = emptyList()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_PERSISTED_ENTRIES).apply()
        logEvent(context, "logs_cleared", mapOf("reason" to "USER_REQUEST"))
    }

    /**
     * Retrieves companion logs from the ContentProvider if running in main app,
     * or from local storage if running in companion.
     */
    fun fetchCompanionLogs(context: Context): List<Entry> {
        // Try querying companion ContentProvider
        try {
            val cursor = context.contentResolver.query(
                LOG_PROVIDER_URI,
                null,
                null,
                null,
                null
            )
            if (cursor != null) {
                cursor.use { c ->
                    val list = mutableListOf<Entry>()
                    val colId = c.getColumnIndex("id")
                    val colTs = c.getColumnIndex("timestamp")
                    val colTime = c.getColumnIndex("time")
                    val colEvent = c.getColumnIndex("event")
                    val colPid = c.getColumnIndex("pid")
                    val colThread = c.getColumnIndex("thread")
                    val colSession = c.getColumnIndex("session")
                    val colFields = c.getColumnIndex("fields")

                    while (c.moveToNext()) {
                        val id = if (colId >= 0) c.getLong(colId) else 0L
                        val ts = if (colTs >= 0) c.getLong(colTs) else 0L
                        val time = if (colTime >= 0) c.getString(colTime) else ""
                        val event = if (colEvent >= 0) c.getString(colEvent) else ""
                        val p = if (colPid >= 0) c.getInt(colPid) else 0
                        val thread = if (colThread >= 0) c.getString(colThread) else ""
                        val sess = if (colSession >= 0) c.getString(colSession) else ""
                        val fieldsJson = if (colFields >= 0) c.getString(colFields) else null

                        val fieldsMap = mutableMapOf<String, String>()
                        if (!fieldsJson.isNullOrBlank()) {
                            val fObj = JSONObject(fieldsJson)
                            val keys = fObj.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                fieldsMap[k] = fObj.getString(k)
                            }
                        }

                        list.add(
                            Entry(
                                id = id,
                                timestamp = ts,
                                formattedTime = time,
                                event = event,
                                pid = p,
                                thread = thread,
                                session = sess,
                                fields = fieldsMap,
                                rawMessage = ""
                            )
                        )
                    }
                    if (list.isNotEmpty()) {
                        return list
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "ContentProvider query note: ${e.message}")
        }

        // Fallback to local memory / preferences
        initialize(context)
        return _logsFlow.value
    }

    /**
     * Builds full standalone export text for companion logs.
     */
    fun buildCompanionExportText(context: Context, entries: List<Entry>): String {
        return buildString {
            appendLine("==================================================")
            appendLine("       SensorsOff Quick Tile Companion Logs       ")
            appendLine("               [TILE_PLUGIN] ONLY                 ")
            appendLine("==================================================")
            appendLine("Package           : $COMPANION_PACKAGE")
            appendLine("Current Process   : PID $pid")
            appendLine("Active Session    : $sessionId")
            appendLine("Device            : ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
            appendLine("Generated At      : ${fullDateFormat.get()?.format(Date())}")
            appendLine("Total Entries     : ${entries.size}")
            appendLine("==================================================")
            appendLine("             COMPANION LOG RECORDS                ")
            appendLine("==================================================")
            if (entries.isEmpty()) {
                appendLine("[ NO COMPANION LOGS RECORDED ]")
                appendLine("Open Quick Settings shade or tap the companion tile to trigger diagnostic logs.")
            } else {
                for (entry in entries) {
                    appendLine(entry.toFormattedBlock())
                    appendLine("--------------------------------------------------")
                }
            }
        }
    }
}
