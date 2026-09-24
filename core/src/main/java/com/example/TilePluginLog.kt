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
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
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
 * - Bounded log retention with automatic size rotation (survives TileService destruction)
 * - Complete companion TileService lifecycle tracing:
 *     - COMPANION_PROCESS_CREATED
 *     - TILE_SERVICE_ON_CREATE
 *     - TILE_SERVICE_ON_START_LISTENING
 *     - TILE_SERVICE_ON_CLICK
 *     - TILE_SERVICE_ON_STOP_LISTENING
 *     - TILE_SERVICE_ON_DESTROY
 *     - TEST_LOG_WRITE
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
    private const val MAX_LOG_FILE_BYTES = 256 * 1024L // 256 KB max
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
        val rawMessage: String
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
                appendLine("event=$event")
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

        fun toJsonString(): String {
            val json = JSONObject()
            json.put("id", id)
            json.put("ts", timestamp)
            json.put("time", formattedTime)
            json.put("event", event)
            json.put("pid", pid)
            json.put("thread", thread)
            json.put("session", session)
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
                    val fieldsObj = obj.optJSONObject("fields")
                    val fieldsMap = mutableMapOf<String, String>()
                    if (fieldsObj != null) {
                        val keys = fieldsObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            fieldsMap[k] = fieldsObj.getString(k)
                        }
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
                        rawMessage = ""
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

    fun getLogFile(context: Context): File {
        return File(context.filesDir, LOG_FILE_NAME)
    }

    fun initialize(context: Context) {
        if (isInitialized) return
        synchronized(fileLock) {
            if (isInitialized) return
            isInitialized = true
            loadEntriesFromFile(context)
        }
    }

    private fun loadEntriesFromFile(context: Context): List<Entry> {
        val file = getLogFile(context)
        if (!file.exists()) {
            _logsFlow.value = emptyList()
            return emptyList()
        }

        val entries = mutableListOf<Entry>()
        try {
            file.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        val entry = Entry.fromJsonString(trimmed)
                        if (entry != null) {
                            entries.add(entry)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading log file: ${e.message}")
        }

        val reversed = entries.reversed().take(MAX_IN_MEMORY_ENTRIES)
        _logsFlow.value = reversed
        return reversed
    }

    /**
     * Appends an entry directly and synchronously to context.filesDir / "tile_plugin.log"
     */
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

        // 1. Synchronously append to persistent file
        synchronized(fileLock) {
            try {
                val file = getLogFile(context)
                val parent = file.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }

                // Check size rotation
                if (file.exists() && file.length() > MAX_LOG_FILE_BYTES) {
                    rotateLogFile(file)
                }

                FileOutputStream(file, true).bufferedWriter().use { writer ->
                    writer.write(entry.toJsonString())
                    writer.newLine()
                    writer.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error writing to persistent companion log file: ${e.message}", e)
            }
        }

        // 2. Update memory state flow
        val updated = (listOf(entry) + _logsFlow.value).take(MAX_IN_MEMORY_ENTRIES)
        _logsFlow.value = updated
    }

    private fun rotateLogFile(file: File) {
        try {
            val lines = file.readLines()
            val keep = lines.takeLast(lines.size / 2)
            file.bufferedWriter().use { writer ->
                for (l in keep) {
                    writer.write(l)
                    writer.newLine()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Log rotation note: ${e.message}")
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

    fun logTileServiceOnClick(context: Context) {
        logEvent(
            context = context,
            event = "TILE_SERVICE_ON_CLICK",
            fields = mapOf(
                "class" to "SensorsOffTileService",
                "action" to "USER_TAP"
            )
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
        logEvent(
            context = context,
            event = "TILE_SERVICE_ON_DESTROY",
            fields = mapOf(
                "class" to "SensorsOffTileService"
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

    fun logStateUnknown(
        context: Context,
        reason: String,
        detail: String = ""
    ) {
        val fields = mutableMapOf("reason" to reason)
        if (detail.isNotBlank()) fields["detail"] = detail
        logEvent(context, "state_unknown", fields)
    }

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
        synchronized(fileLock) {
            try {
                val file = getLogFile(context)
                if (file.exists()) {
                    file.delete()
                }
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
     * with explicit error categorization.
     */
    fun fetchCompanionLogsWithResult(context: Context): FetchResult {
        try {
            val cursor = context.contentResolver.query(
                LOG_PROVIDER_URI,
                null,
                null,
                null,
                null
            ) ?: return FetchResult.Error("PROVIDER_UNAVAILABLE", "ContentProvider query returned null cursor (Companion not installed or provider not found)")

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
                return FetchResult.Success(list)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "ContentProvider SecurityException: ${e.message}")
            return FetchResult.Error("SECURITY_ERROR", "Permission denied accessing companion provider: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "ContentProvider query error: ${e.message}")
            return FetchResult.Error("PROVIDER_ERROR", e.message ?: "Unknown error querying companion provider")
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
     * 1. Calls companion ContentProvider insert (TEST_LOG_URI)
     * 2. Companion writes TEST_LOG_WRITE synchronously to disk
     * 3. Immediately queries the provider via fetchCompanionLogsWithResult()
     * 4. Verifies TEST_LOG_WRITE event is present in the returned list
     */
    fun performCompanionSelfTest(context: Context): SelfTestResult {
        val insertedUri = try {
            context.contentResolver.insert(TEST_LOG_URI, null)
        } catch (e: SecurityException) {
            return SelfTestResult.InsertFailed("SECURITY_ERROR: ${e.message}")
        } catch (e: Exception) {
            return SelfTestResult.InsertFailed("PROVIDER_ERROR: ${e.message ?: "Insert failed"}")
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
     * Triggers companion to write a TEST_LOG_WRITE event via Provider.
     */
    fun triggerCompanionTestLog(context: Context): Boolean {
        return try {
            val uri = context.contentResolver.insert(TEST_LOG_URI, null)
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
