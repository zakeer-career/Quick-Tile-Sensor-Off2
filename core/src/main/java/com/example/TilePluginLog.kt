package com.example

import android.content.ContentValues
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
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
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
 * - Direct, synchronous append to a persistent package-private file ("tile_plugin.log")
 * - Bounded log retention with automatic multi-file rotation (tile_plugin.log, .1, .2 up to ~768 KB)
 * - Safe rotation without losing newest events
 * - Complete companion TileService lifecycle tracing with unique click operationId
 * - Pre-operation durability: logs operation start BEFORE dispatching Binder transactions
 * - Safe on-demand IPC via TilePluginLogProvider for com.SensorsOff
 * - Zero background daemons, zero polling, zero wake locks, zero persistent services
 */
object TilePluginLog {

    const val TAG = "TILE_PLUGIN"
    const val COMPANION_PACKAGE = "com.SensorsOff.tile"
    const val MAIN_APP_PACKAGE = "com.SensorsOff"
    const val LOG_PROVIDER_AUTHORITY = "com.SensorsOff.tile.logprovider"
    val LOG_PROVIDER_URI: Uri = Uri.parse("content://$LOG_PROVIDER_AUTHORITY/logs")
    val TEST_LOG_URI: Uri = Uri.parse("content://$LOG_PROVIDER_AUTHORITY/test_log")

    const val LOG_FILE_NAME = "tile_plugin.log"
    const val LOG_FILE_NAME_1 = "tile_plugin.log.1"
    const val LOG_FILE_NAME_2 = "tile_plugin.log.2"
    private const val SESSION_STATE_FILE = "companion_session.json"

    // Max 256 KB per file (max 3 files = 768 KB bounded storage)
    private const val MAX_LOG_FILE_BYTES = 256 * 1024L
    private const val MAX_IN_MEMORY_ENTRIES = 200

    private val idCounter = AtomicLong(System.currentTimeMillis())
    private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    private val fullDateFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US) }

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
        val rawMessage: String,
        val opId: String? = fields["op"]
    ) {
        fun toFormattedBlock(): String {
            val isoTime = try {
                fullDateFormat.get()?.format(Date(timestamp)) ?: formattedTime
            } catch (e: Exception) {
                formattedTime
            }
            return buildString {
                appendLine(isoTime)
                appendLine("[TILE_PLUGIN]")
                appendLine("session=$session")
                appendLine("pid=$pid")
                appendLine("package=$COMPANION_PACKAGE")
                appendLine("event=$event")
                appendLine("thread=$thread")
                appendLine("time=$formattedTime")
                if (opId != null) {
                    appendLine("op=$opId")
                }
                for ((k, v) in fields) {
                    if (k != "op") {
                        appendLine("$k=$v")
                    }
                }
            }.trimEnd()
        }

        fun toSingleLine(): String {
            val extra = fields.entries.joinToString(" ") { "${it.key}=${it.value}" }
            return "[TILE_PLUGIN] [$formattedTime] session=$session pid=$pid thread=$thread event=$event $extra".trim()
        }

        fun toJsonString(): String {
            val json = JSONObject()
            json.put("id", id)
            json.put("ts", timestamp)
            json.put("time", formattedTime)
            json.put("event", event)
            json.put("pid", pid)
            json.put("pkg", COMPANION_PACKAGE)
            json.put("thread", thread)
            json.put("session", session)
            if (opId != null) {
                json.put("op", opId)
            }
            val fieldsJson = JSONObject()
            for ((k, v) in fields) {
                fieldsJson.put(k, v)
            }
            json.put("fields", fieldsJson)
            return json.toString()
        }

        companion object {
            fun fromJsonString(jsonStr: String): Entry? {
                return try {
                    val obj = JSONObject(jsonStr)
                    val id = obj.optLong("id", 0L)
                    val ts = obj.optLong("ts", 0L)
                    val time = obj.optString("time", "")
                    val event = obj.optString("event", "UNKNOWN")
                    val p = obj.optInt("pid", 0)
                    val thread = obj.optString("thread", "main")
                    val session = obj.optString("session", "")
                    val op = if (obj.has("op")) obj.optString("op") else null
                    val fieldsObj = obj.optJSONObject("fields")
                    val fieldsMap = mutableMapOf<String, String>()
                    if (fieldsObj != null) {
                        val keys = fieldsObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            fieldsMap[k] = fieldsObj.getString(k)
                        }
                    }
                    if (op != null && !fieldsMap.containsKey("op")) {
                        fieldsMap["op"] = op
                    }
                    Entry(
                        id = id,
                        timestamp = ts,
                        formattedTime = time,
                        event = event,
                        pid = p,
                        thread = thread,
                        session = session,
                        fields = fieldsMap,
                        rawMessage = "",
                        opId = op ?: fieldsMap["op"]
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    private val _logsFlow = MutableStateFlow<List<Entry>>(emptyList())
    val logsFlow: StateFlow<List<Entry>> = _logsFlow.asStateFlow()

    private val fileLock = Any()
    @Volatile private var isInitialized = false

    fun getLogFile(context: Context): File = File(context.filesDir, LOG_FILE_NAME)
    private fun getLogFile1(context: Context): File = File(context.filesDir, LOG_FILE_NAME_1)
    private fun getLogFile2(context: Context): File = File(context.filesDir, LOG_FILE_NAME_2)
    private fun getSessionStateFile(context: Context): File = File(context.filesDir, SESSION_STATE_FILE)

    fun initialize(context: Context) {
        if (isInitialized) return
        synchronized(fileLock) {
            if (isInitialized) return
            isInitialized = true
            loadEntriesFromFile(context)
        }
    }

    /**
     * Reads all persistent log files (primary + rotated backups) in bounded order.
     */
    private fun loadEntriesFromFile(context: Context): List<Entry> {
        val files = listOf(getLogFile(context), getLogFile1(context), getLogFile2(context))
        val allEntries = mutableListOf<Entry>()

        for (file in files) {
            if (!file.exists()) continue
            try {
                file.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) {
                            val entry = Entry.fromJsonString(trimmed)
                            if (entry != null) {
                                allEntries.add(entry)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading log file ${file.name}: ${e.message}")
            }
        }

        // Sort reverse chronological (newest first)
        val sorted = allEntries.sortedByDescending { it.timestamp }.take(MAX_IN_MEMORY_ENTRIES)
        _logsFlow.value = sorted
        return sorted
    }

    /**
     * Appends an entry directly and synchronously to context.filesDir / "tile_plugin.log"
     * Follows strict synchronous append semantics:
     * 1. Open file in append mode
     * 2. Write complete event JSON line
     * 3. flush()
     * 4. close()
     */
    @Synchronized
    fun logEvent(
        context: Context,
        event: String,
        fields: Map<String, String> = emptyMap(),
        opId: String? = fields["op"]
    ) {
        val now = System.currentTimeMillis()
        val formatted = timeFormat.get()?.format(Date(now)) ?: ""
        val threadName = Thread.currentThread().name

        val mergedFields = if (opId != null && !fields.containsKey("op")) {
            fields + ("op" to opId)
        } else {
            fields
        }

        val entry = Entry(
            id = idCounter.incrementAndGet(),
            timestamp = now,
            formattedTime = formatted,
            event = event,
            pid = pid,
            thread = threadName,
            session = sessionId,
            fields = mergedFields,
            rawMessage = "",
            opId = opId ?: mergedFields["op"]
        )

        val block = entry.toFormattedBlock()
        Log.i(TAG, "\n$block")

        // Synchronous append with strict flush & close
        synchronized(fileLock) {
            try {
                val file = getLogFile(context)
                val parent = file.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }

                // Check size rotation
                if (file.exists() && file.length() >= MAX_LOG_FILE_BYTES) {
                    performSafeRotation(context)
                }

                val fos = FileOutputStream(file, true)
                val writer = OutputStreamWriter(fos, Charsets.UTF_8).buffered()
                try {
                    writer.write(entry.toJsonString())
                    writer.newLine()
                    writer.flush()
                } finally {
                    try { writer.close() } catch (ignored: Exception) {}
                    try { fos.close() } catch (ignored: Exception) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error writing to persistent companion log file: ${e.message}", e)
            }
        }

        // Update memory state flow
        val updated = (listOf(entry) + _logsFlow.value).take(MAX_IN_MEMORY_ENTRIES)
        _logsFlow.value = updated
    }

    /**
     * Bounded log rotation:
     * tile_plugin.log.1 -> tile_plugin.log.2
     * tile_plugin.log -> tile_plugin.log.1
     * Creates new empty tile_plugin.log
     */
    private fun performSafeRotation(context: Context) {
        try {
            val f0 = getLogFile(context)
            val f1 = getLogFile1(context)
            val f2 = getLogFile2(context)

            if (f2.exists()) {
                f2.delete()
            }
            if (f1.exists()) {
                f1.renameTo(f2)
            }
            if (f0.exists()) {
                f0.renameTo(f1)
            }

            // Write rotation diagnostic event to the fresh log
            val now = System.currentTimeMillis()
            val formatted = timeFormat.get()?.format(Date(now)) ?: ""
            val rotEntry = Entry(
                id = idCounter.incrementAndGet(),
                timestamp = now,
                formattedTime = formatted,
                event = "LOG_ROTATION",
                pid = pid,
                thread = Thread.currentThread().name,
                session = sessionId,
                fields = mapOf(
                    "reason" to "FILE_SIZE_LIMIT_EXCEEDED",
                    "maxFileBytes" to MAX_LOG_FILE_BYTES.toString(),
                    "rotatedTo" to LOG_FILE_NAME_1
                ),
                rawMessage = ""
            )

            val fos = FileOutputStream(f0, true)
            val writer = OutputStreamWriter(fos, Charsets.UTF_8).buffered()
            try {
                writer.write(rotEntry.toJsonString())
                writer.newLine()
                writer.flush()
            } finally {
                try { writer.close() } catch (ignored: Exception) {}
                try { fos.close() } catch (ignored: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Safe log rotation note: ${e.message}")
        }
    }

    // ==========================================
    // OEM KILL / PROCESS RESTART DETECTION
    // ==========================================

    /**
     * Called strictly from SensorsOffTileApp.onCreate() in companion process.
     * Generates a new session ID and checks if previous session had an unexpected termination.
     */
    fun onCompanionProcessCreated(context: Context, source: String = "SensorsOffTileApp.onCreate") {
        initialize(context)

        // Check previous session state
        val stateFile = getSessionStateFile(context)
        var previousSessionEndedUnexpectedly = false
        var previousSessionId = ""
        var previousPid = 0

        synchronized(fileLock) {
            try {
                if (stateFile.exists()) {
                    val content = stateFile.readText().trim()
                    if (content.isNotEmpty()) {
                        val json = JSONObject(content)
                        val cleanShutdown = json.optBoolean("cleanShutdown", false)
                        previousSessionId = json.optString("sessionId", "")
                        previousPid = json.optInt("pid", 0)

                        if (!cleanShutdown && previousSessionId.isNotEmpty() && previousSessionId != sessionId) {
                            previousSessionEndedUnexpectedly = true
                        }
                    }
                }

                // Record current session state as active (cleanShutdown = false until onDestroy)
                val currentJson = JSONObject().apply {
                    put("sessionId", sessionId)
                    put("pid", pid)
                    put("startTime", System.currentTimeMillis())
                    put("cleanShutdown", false)
                }
                stateFile.writeText(currentJson.toString())
            } catch (e: Exception) {
                Log.d(TAG, "Session state tracking note: ${e.message}")
            }
        }

        if (previousSessionEndedUnexpectedly) {
            logEvent(
                context = context,
                event = "PROCESS_RECREATED",
                fields = mapOf(
                    "term" to "PROCESS_RECREATED",
                    "previousSession" to previousSessionId,
                    "previousPid" to previousPid.toString(),
                    "reason" to "PREVIOUS_SESSION_TERMINATED_WITHOUT_CLEAN_SHUTDOWN"
                )
            )
        }

        logCompanionProcessCreated(context, source)
    }

    /**
     * Records clean shutdown when TileService.onDestroy() completes.
     */
    fun markCleanShutdown(context: Context) {
        synchronized(fileLock) {
            try {
                val stateFile = getSessionStateFile(context)
                val currentJson = JSONObject().apply {
                    put("sessionId", sessionId)
                    put("pid", pid)
                    put("cleanShutdown", true)
                    put("shutdownTime", System.currentTimeMillis())
                }
                stateFile.writeText(currentJson.toString())
            } catch (e: Exception) {
                Log.d(TAG, "Mark clean shutdown note: ${e.message}")
            }
        }
    }

    // ==========================================
    // MANDATORY FIRST EVENTS & LIFECYCLE LOGS
    // ==========================================

    fun logCompanionProcessCreated(context: Context, source: String = "SensorsOffTileApp.onCreate") {
        logEvent(
            context = context,
            event = "COMPANION_PROCESS_CREATED",
            fields = mapOf(
                "source" to source,
                "api" to Build.VERSION.SDK_INT.toString(),
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "package" to COMPANION_PACKAGE
            )
        )
    }

    fun logProviderOnCreate(context: Context) {
        logEvent(
            context = context,
            event = "PROVIDER_ON_CREATE",
            fields = mapOf(
                "class" to "TilePluginLogProvider",
                "authority" to LOG_PROVIDER_AUTHORITY,
                "package" to COMPANION_PACKAGE
            )
        )
    }

    fun logTileServiceOnCreate(context: Context) {
        logEvent(
            context = context,
            event = "TILE_SERVICE_ON_CREATE",
            fields = mapOf(
                "class" to "SensorsOffTileService"
            )
        )
    }

    fun logTileServiceOnStartListening(context: Context, extraDetail: String = "") {
        val fields = mutableMapOf(
            "class" to "SensorsOffTileService"
        )
        if (extraDetail.isNotBlank()) {
            fields["detail"] = extraDetail
        }
        logEvent(
            context = context,
            event = "TILE_SERVICE_ON_START_LISTENING",
            fields = fields
        )
    }

    fun logTileServiceOnClick(context: Context, opId: String? = null) {
        val fields = mutableMapOf(
            "class" to "SensorsOffTileService",
            "action" to "USER_TAP"
        )
        if (opId != null) {
            fields["op"] = opId
        }
        logEvent(
            context = context,
            event = "TILE_SERVICE_ON_CLICK",
            fields = fields,
            opId = opId
        )
    }

    fun logTileServiceOnStopListening(context: Context) {
        logEvent(
            context = context,
            event = "TILE_SERVICE_ON_STOP_LISTENING",
            fields = mapOf(
                "class" to "SensorsOffTileService"
            )
        )
    }

    fun logTileServiceOnDestroy(context: Context) {
        markCleanShutdown(context)
        logEvent(
            context = context,
            event = "TILE_SERVICE_ON_DESTROY",
            fields = mapOf(
                "class" to "SensorsOffTileService"
            )
        )
    }

    fun logLifecycle(context: Context, eventName: String, extraDetail: String = "", opId: String? = null) {
        val fields = mutableMapOf<String, String>()
        if (extraDetail.isNotBlank()) {
            fields["detail"] = extraDetail
        }
        if (opId != null) {
            fields["op"] = opId
        }
        logEvent(context, eventName, fields, opId = opId)
    }

    fun logTileClick(context: Context, currentState: String, requestedState: String, opId: String? = null) {
        val fields = mutableMapOf(
            "currentState" to currentState,
            "requestedState" to requestedState
        )
        if (opId != null) {
            fields["op"] = opId
        }
        logEvent(
            context,
            "tile_click",
            fields,
            opId = opId
        )
    }

    fun logTestLogWrite(context: Context) {
        val isoTime = try {
            fullDateFormat.get()?.format(Date()) ?: ""
        } catch (e: Exception) {
            ""
        }
        logEvent(
            context = context,
            event = "TEST_LOG_WRITE",
            fields = mapOf(
                "timestamp" to isoTime,
                "triggeredBy" to "DIAGNOSTIC_TEST",
                "verified" to "TRUE"
            )
        )
    }

    // ==========================================
    // DIAGNOSTIC EVENT LOGGING (SHIZUKU/BINDER/TOGGLE)
    // ==========================================

    fun logShizukuCheck(
        context: Context,
        running: Boolean,
        binderAlive: Boolean,
        permissionGranted: Boolean,
        permissionCheckResult: Int,
        binderStatus: String,
        opId: String? = null
    ) {
        val fields = mutableMapOf(
            "package" to COMPANION_PACKAGE,
            "running" to running.toString(),
            "binderAlive" to binderAlive.toString(),
            "permission" to if (permissionGranted) "GRANTED" else "DENIED",
            "permissionCheckResult" to permissionCheckResult.toString(),
            "binderStatus" to binderStatus
        )
        if (opId != null) fields["op"] = opId
        logEvent(
            context,
            "shizuku_check",
            fields,
            opId = opId
        )
    }

    fun logSensorPrivacyBinder(
        context: Context,
        result: String,
        binderAlive: Boolean,
        invalidated: Boolean = false,
        reacquired: Boolean = false,
        api: Int = Build.VERSION.SDK_INT,
        readTx: Int? = SensorPrivacyCodes.getQueryGlobalCodeForSdk(api),
        setTx: Int? = SensorPrivacyCodes.getSetGlobalCodeForSdk(api),
        opId: String? = null
    ) {
        val fields = mutableMapOf(
            "result" to result,
            "binderAlive" to binderAlive.toString(),
            "invalidated" to invalidated.toString(),
            "reacquired" to reacquired.toString(),
            "api" to api.toString(),
            "readTransaction" to (readTx?.toString() ?: "UNSUPPORTED"),
            "setTransaction" to (setTx?.toString() ?: "UNSUPPORTED")
        )
        if (opId != null) fields["op"] = opId
        logEvent(
            context,
            "sensor_privacy_binder",
            fields,
            opId = opId
        )
    }

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
        reason: String? = null,
        opId: String? = null
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
        if (opId != null) fields["op"] = opId

        logEvent(context, "authoritative_state_read", fields, opId = opId)
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
        reason: String? = null,
        opId: String? = null
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
        if (opId != null) fields["op"] = opId
        logEvent(context, "toggle", fields, opId = opId)
    }

    fun logStateUnknown(
        context: Context,
        reason: String,
        detail: String = "",
        opId: String? = null
    ) {
        val fields = mutableMapOf("reason" to reason)
        if (detail.isNotBlank()) fields["detail"] = detail
        if (opId != null) fields["op"] = opId
        logEvent(context, "state_unknown", fields, opId = opId)
    }

    fun logTileUpdate(
        context: Context,
        tileState: Int,
        sensorState: SensorPrivacyState,
        subtitle: String,
        label: String,
        reason: String = "",
        opId: String? = null
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
        if (opId != null) fields["op"] = opId
        logEvent(context, "tile_update", fields, opId = opId)
    }

    fun clear(context: Context) {
        synchronized(fileLock) {
            try {
                val f0 = getLogFile(context)
                val f1 = getLogFile1(context)
                val f2 = getLogFile2(context)
                if (f0.exists()) f0.delete()
                if (f1.exists()) f1.delete()
                if (f2.exists()) f2.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Failed deleting log file: ${e.message}")
            }
        }
        _logsFlow.value = emptyList()
        logEvent(context, "LOGS_CLEARED", mapOf("reason" to "USER_REQUEST"))
    }

    /**
     * Direct query reading persistent log entries from the companion file for Provider usage.
     */
    fun readPersistentLogEntries(context: Context): List<Entry> {
        synchronized(fileLock) {
            return loadEntriesFromFile(context)
        }
    }

    /**
     * Result of fetching companion logs from ContentProvider.
     */
    sealed class FetchResult {
        data class Success(val entries: List<Entry>) : FetchResult()
        data class Error(val code: String, val message: String) : FetchResult()
    }

    /**
     * Retrieves companion logs from the ContentProvider if running in main app,
     * with explicit error categorization. Does not report IPC failure as plugin dead.
     */
    fun fetchCompanionLogsWithResult(context: Context): FetchResult {
        try {
            val cursor = context.contentResolver.query(
                LOG_PROVIDER_URI,
                null,
                null,
                null,
                null
            ) ?: return FetchResult.Error("PROVIDER_UNAVAILABLE", "ContentProvider query returned null cursor (Companion provider unavailable)")

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
                            rawMessage = "",
                            opId = fieldsMap["op"]
                        )
                    )
                }
                return FetchResult.Success(list)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "ContentProvider SecurityException: ${e.message}")
            return FetchResult.Error("SECURITY_ERROR", "Permission denied accessing companion provider: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "ContentProvider query error: ${e.message}")
            return FetchResult.Error("PROVIDER_UNAVAILABLE", e.message ?: "ContentProvider unavailable")
        }
    }

    /**
     * Backward-compatible fetch function returning List<Entry>.
     */
    fun fetchCompanionLogs(context: Context): List<Entry> {
        return when (val result = fetchCompanionLogsWithResult(context)) {
            is FetchResult.Success -> result.entries
            is FetchResult.Error -> emptyList()
        }
    }

    /**
     * Result of companion self-test execution.
     */
    sealed class SelfTestResult {
        data class Success(val testEntry: Entry, val totalEntries: Int) : SelfTestResult()
        data class InsertFailed(val error: String) : SelfTestResult()
        data class QueryFailedAfterInsert(val error: String) : SelfTestResult()
        data class TestEventMissing(val returnedCount: Int) : SelfTestResult()
    }

    /**
     * Executes an explicit end-to-end companion self-test:
     * 1. Calls companion ContentProvider insert (TEST_LOG_URI) with ContentValues() (never null)
     * 2. Companion writes TEST_LOG_WRITE synchronously to disk
     * 3. Immediately queries the provider via fetchCompanionLogsWithResult()
     * 4. Verifies TEST_LOG_WRITE event is present in the returned list
     */
    fun performCompanionSelfTest(context: Context): SelfTestResult {
        val cv = ContentValues()
        cv.put("action", "test_log")
        cv.put("timestamp", System.currentTimeMillis())

        val insertedUri = try {
            context.contentResolver.insert(TEST_LOG_URI, cv)
        } catch (e: SecurityException) {
            return SelfTestResult.InsertFailed("SECURITY_ERROR: ${e.message}")
        } catch (e: Exception) {
            return SelfTestResult.InsertFailed("PROVIDER_UNAVAILABLE: ${e.message ?: "Insert failed"}")
        }

        if (insertedUri == null) {
            return SelfTestResult.InsertFailed("Provider returned null URI (Companion not installed or provider inactive)")
        }

        val fetchResult = fetchCompanionLogsWithResult(context)
        when (fetchResult) {
            is FetchResult.Error -> {
                return SelfTestResult.QueryFailedAfterInsert("${fetchResult.code}: ${fetchResult.message}")
            }
            is FetchResult.Success -> {
                val testEntry = fetchResult.entries.firstOrNull { it.event == "TEST_LOG_WRITE" }
                return if (testEntry != null) {
                    SelfTestResult.Success(testEntry, fetchResult.entries.size)
                } else {
                    SelfTestResult.TestEventMissing(fetchResult.entries.size)
                }
            }
        }
    }

    /**
     * Triggers companion to write a TEST_LOG_WRITE event via Provider with valid ContentValues.
     */
    fun triggerCompanionTestLog(context: Context): Boolean {
        return try {
            val cv = ContentValues()
            cv.put("action", "test_log")
            val uri = context.contentResolver.insert(TEST_LOG_URI, cv)
            uri != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed triggering test log write: ${e.message}")
            false
        }
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
            appendLine("Persistent Log    : filesDir/$LOG_FILE_NAME")
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
