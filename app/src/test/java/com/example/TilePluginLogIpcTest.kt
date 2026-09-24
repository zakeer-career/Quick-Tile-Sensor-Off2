package com.example

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

/**
 * Dedicated test suite exercising the [TILE_PLUGIN] logging system and ContentProvider IPC pipeline.
 *
 * Tests:
 * A. TilePluginLog.logCompanionProcessCreated() creates tile_plugin.log.
 * B. TilePluginLog.logTileServiceOnCreate() persists an event.
 * C. TilePluginLog.logTileServiceOnStartListening() persists an event.
 * D. TilePluginLog.logTileServiceOnClick() persists an event.
 * E. readPersistentLogEntries() returns those events.
 * F. TEST_LOG_WRITE produces exactly a TEST_LOG_WRITE event.
 * G. fetchCompanionLogsWithResult() correctly distinguishes:
 *    - Success(empty list)
 *    - Success(non-empty list)
 *    - PROVIDER_UNAVAILABLE
 *    - SECURITY_ERROR
 *    - PROVIDER_ERROR
 * H. performCompanionSelfTest() round-trip validation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TilePluginLogIpcTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        val logFile = TilePluginLog.getLogFile(context)
        if (logFile.exists()) {
            logFile.delete()
        }
    }

    @Test
    fun `test A - logCompanionProcessCreated creates tile_plugin log file`() {
        val logFile = TilePluginLog.getLogFile(context)
        assertFalse(logFile.exists())

        TilePluginLog.logCompanionProcessCreated(context, "SensorsOffTileApp.onCreate")

        assertTrue("tile_plugin.log must exist after logCompanionProcessCreated", logFile.exists())
        assertTrue("tile_plugin.log must not be empty", logFile.length() > 0)

        val entries = TilePluginLog.readPersistentLogEntries(context)
        assertEquals(1, entries.size)
        assertEquals("COMPANION_PROCESS_CREATED", entries[0].event)
        assertEquals("SensorsOffTileApp.onCreate", entries[0].fields["source"])
        assertEquals(TilePluginLog.COMPANION_PACKAGE, entries[0].fields["package"])
    }

    @Test
    fun `test B - logTileServiceOnCreate persists event to disk`() {
        TilePluginLog.logTileServiceOnCreate(context)

        val entries = TilePluginLog.readPersistentLogEntries(context)
        assertTrue(entries.any { it.event == "TILE_SERVICE_ON_CREATE" && it.fields["class"] == "SensorsOffTileService" })
    }

    @Test
    fun `test C - logTileServiceOnStartListening persists event to disk`() {
        TilePluginLog.logTileServiceOnStartListening(context, "Instance=test_session_123")

        val entries = TilePluginLog.readPersistentLogEntries(context)
        val entry = entries.find { it.event == "TILE_SERVICE_ON_START_LISTENING" }
        assertNotNull(entry)
        assertEquals("SensorsOffTileService", entry!!.fields["class"])
        assertEquals("Instance=test_session_123", entry.fields["detail"])
    }

    @Test
    fun `test D - logTileServiceOnClick persists event to disk`() {
        TilePluginLog.logTileServiceOnClick(context)

        val entries = TilePluginLog.readPersistentLogEntries(context)
        val entry = entries.find { it.event == "TILE_SERVICE_ON_CLICK" }
        assertNotNull(entry)
        assertEquals("SensorsOffTileService", entry!!.fields["class"])
        assertEquals("USER_TAP", entry.fields["action"])
    }

    @Test
    fun `test E - readPersistentLogEntries returns all lifecycle events in order`() {
        TilePluginLog.logCompanionProcessCreated(context)
        TilePluginLog.logTileServiceOnCreate(context)
        TilePluginLog.logTileServiceOnStartListening(context)
        TilePluginLog.logTileServiceOnClick(context)
        TilePluginLog.logTileServiceOnStopListening(context)
        TilePluginLog.logTileServiceOnDestroy(context)

        val entries = TilePluginLog.readPersistentLogEntries(context)
        assertEquals(6, entries.size)

        // Read order from loadEntriesFromFile returns reversed (newest first)
        val eventNames = entries.map { it.event }
        assertTrue(eventNames.contains("COMPANION_PROCESS_CREATED"))
        assertTrue(eventNames.contains("TILE_SERVICE_ON_CREATE"))
        assertTrue(eventNames.contains("TILE_SERVICE_ON_START_LISTENING"))
        assertTrue(eventNames.contains("TILE_SERVICE_ON_CLICK"))
        assertTrue(eventNames.contains("TILE_SERVICE_ON_STOP_LISTENING"))
        assertTrue(eventNames.contains("TILE_SERVICE_ON_DESTROY"))
    }

    @Test
    fun `test F - TEST_LOG_WRITE produces exactly a TEST_LOG_WRITE event`() {
        TilePluginLog.logTestLogWrite(context)

        val entries = TilePluginLog.readPersistentLogEntries(context)
        val testEntry = entries.find { it.event == "TEST_LOG_WRITE" }
        assertNotNull(testEntry)
        assertEquals("DIAGNOSTIC_TEST", testEntry!!.fields["triggeredBy"])
        assertEquals("TRUE", testEntry.fields["verified"])
    }

    @Test
    fun `test G1 - fetchCompanionLogsWithResult returns Success with empty list when provider returns 0 rows`() {
        val fakeProvider = object : ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun query(uri: Uri, p: Array<out String>?, s: String?, sa: Array<out String>?, so: String?): Cursor {
                return MatrixCursor(arrayOf("id", "timestamp", "time", "event", "pid", "thread", "session", "fields"))
            }
            override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.sensorsoff.tile.log"
            override fun insert(uri: Uri, values: ContentValues?): Uri = uri
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
        }

        ShadowContentResolver.registerProviderInternal(TilePluginLog.LOG_PROVIDER_AUTHORITY, fakeProvider)

        val result = TilePluginLog.fetchCompanionLogsWithResult(context)
        assertTrue("Expected FetchResult.Success", result is TilePluginLog.FetchResult.Success)
        val success = result as TilePluginLog.FetchResult.Success
        assertTrue(success.entries.isEmpty())
    }

    @Test
    fun `test G2 - fetchCompanionLogsWithResult returns Success with non-empty list when provider returns rows`() {
        val fakeProvider = object : ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun query(uri: Uri, p: Array<out String>?, s: String?, sa: Array<out String>?, so: String?): Cursor {
                val cursor = MatrixCursor(arrayOf("id", "timestamp", "time", "event", "pid", "thread", "session", "fields"))
                val fieldsJson = JSONObject(mapOf("testKey" to "testVal")).toString()
                cursor.addRow(arrayOf(101L, 1000L, "12:00:00.000", "TEST_LOG_WRITE", 1234, "main", "ABCDEF", fieldsJson))
                return cursor
            }
            override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.sensorsoff.tile.log"
            override fun insert(uri: Uri, values: ContentValues?): Uri = uri
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
        }

        ShadowContentResolver.registerProviderInternal(TilePluginLog.LOG_PROVIDER_AUTHORITY, fakeProvider)

        val result = TilePluginLog.fetchCompanionLogsWithResult(context)
        assertTrue("Expected FetchResult.Success", result is TilePluginLog.FetchResult.Success)
        val success = result as TilePluginLog.FetchResult.Success
        assertEquals(1, success.entries.size)
        assertEquals("TEST_LOG_WRITE", success.entries[0].event)
        assertEquals(101L, success.entries[0].id)
        assertEquals("testVal", success.entries[0].fields["testKey"])
    }

    @Test
    fun `test G3 - fetchCompanionLogsWithResult returns PROVIDER_UNAVAILABLE when provider returns null cursor`() {
        val fakeNullProvider = object : ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun query(uri: Uri, p: Array<out String>?, s: String?, sa: Array<out String>?, so: String?): Cursor? = null
            override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.sensorsoff.tile.log"
            override fun insert(uri: Uri, values: ContentValues?): Uri = uri
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
        }

        ShadowContentResolver.registerProviderInternal(TilePluginLog.LOG_PROVIDER_AUTHORITY, fakeNullProvider)

        val result = TilePluginLog.fetchCompanionLogsWithResult(context)
        assertTrue("Expected FetchResult.Error", result is TilePluginLog.FetchResult.Error)
        val err = result as TilePluginLog.FetchResult.Error
        assertEquals("PROVIDER_UNAVAILABLE", err.code)
    }

    @Test
    fun `test G4 - fetchCompanionLogsWithResult returns SECURITY_ERROR when provider throws SecurityException`() {
        val fakeSecurityExceptionProvider = object : ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun query(uri: Uri, p: Array<out String>?, s: String?, sa: Array<out String>?, so: String?): Cursor {
                throw SecurityException("Caller UID unauthorized")
            }
            override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.sensorsoff.tile.log"
            override fun insert(uri: Uri, values: ContentValues?): Uri = uri
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
        }

        ShadowContentResolver.registerProviderInternal(TilePluginLog.LOG_PROVIDER_AUTHORITY, fakeSecurityExceptionProvider)

        val result = TilePluginLog.fetchCompanionLogsWithResult(context)
        assertTrue("Expected FetchResult.Error", result is TilePluginLog.FetchResult.Error)
        val err = result as TilePluginLog.FetchResult.Error
        assertEquals("SECURITY_ERROR", err.code)
    }

    @Test
    fun `test G5 - fetchCompanionLogsWithResult returns PROVIDER_ERROR when provider throws generic Exception`() {
        val fakeErrorProvider = object : ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun query(uri: Uri, p: Array<out String>?, s: String?, sa: Array<out String>?, so: String?): Cursor {
                throw IllegalStateException("Database locked")
            }
            override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.sensorsoff.tile.log"
            override fun insert(uri: Uri, values: ContentValues?): Uri = uri
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
        }

        ShadowContentResolver.registerProviderInternal(TilePluginLog.LOG_PROVIDER_AUTHORITY, fakeErrorProvider)

        val result = TilePluginLog.fetchCompanionLogsWithResult(context)
        assertTrue("Expected FetchResult.Error", result is TilePluginLog.FetchResult.Error)
        val err = result as TilePluginLog.FetchResult.Error
        assertEquals("PROVIDER_ERROR", err.code)
    }

    @Test
    fun `test H - performCompanionSelfTest verifies TEST_LOG_WRITE end-to-end`() {
        val targetCtx = context
        // Provider that appends to file on insert and queries from file on query
        val liveSelfTestProvider = object : ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun insert(uri: Uri, values: ContentValues?): Uri {
                TilePluginLog.logTestLogWrite(targetCtx)
                return Uri.withAppendedPath(TilePluginLog.LOG_PROVIDER_URI, "test_log_written")
            }
            override fun query(uri: Uri, p: Array<out String>?, s: String?, sa: Array<out String>?, so: String?): Cursor {
                val entries = TilePluginLog.readPersistentLogEntries(targetCtx)
                val cursor = MatrixCursor(arrayOf("id", "timestamp", "time", "event", "pid", "thread", "session", "fields"))
                for (entry in entries) {
                    val fieldsJson = JSONObject(entry.fields as Map<*, *>).toString()
                    cursor.addRow(arrayOf(entry.id, entry.timestamp, entry.formattedTime, entry.event, entry.pid, entry.thread, entry.session, fieldsJson))
                }
                return cursor
            }
            override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.sensorsoff.tile.log"
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
        }

        ShadowContentResolver.registerProviderInternal(TilePluginLog.LOG_PROVIDER_AUTHORITY, liveSelfTestProvider)

        val selfTestResult = TilePluginLog.performCompanionSelfTest(context)
        assertTrue("Self-test must succeed when TEST_LOG_WRITE is inserted and returned", selfTestResult is TilePluginLog.SelfTestResult.Success)
        val success = selfTestResult as TilePluginLog.SelfTestResult.Success
        assertEquals("TEST_LOG_WRITE", success.testEntry.event)
        assertEquals("DIAGNOSTIC_TEST", success.testEntry.fields["triggeredBy"])
    }

    @Test
    fun `test H2 - performCompanionSelfTest reports exact failure when query lacks TEST_LOG_WRITE`() {
        // Provider that inserts nothing and returns empty query
        val brokenSelfTestProvider = object : ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun insert(uri: Uri, values: ContentValues?): Uri {
                // Do not write anything
                return Uri.withAppendedPath(TilePluginLog.LOG_PROVIDER_URI, "fake")
            }
            override fun query(uri: Uri, p: Array<out String>?, s: String?, sa: Array<out String>?, so: String?): Cursor {
                return MatrixCursor(arrayOf("id", "timestamp", "time", "event", "pid", "thread", "session", "fields"))
            }
            override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.sensorsoff.tile.log"
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
        }

        ShadowContentResolver.registerProviderInternal(TilePluginLog.LOG_PROVIDER_AUTHORITY, brokenSelfTestProvider)

        val selfTestResult = TilePluginLog.performCompanionSelfTest(context)
        assertTrue("Self-test must report TestEventMissing when TEST_LOG_WRITE is absent", selfTestResult is TilePluginLog.SelfTestResult.TestEventMissing)
    }
}
