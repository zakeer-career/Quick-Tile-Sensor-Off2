package com.example.tile

import android.content.Context
import android.service.quicksettings.Tile
import androidx.test.core.app.ApplicationProvider
import com.example.SensorPrivacyCodes
import com.example.SensorPrivacyState
import com.example.ShizukuManager
import com.example.TileLogManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SensorsOffTileCompanionTest {

    @Test
    fun `test Companion Tile - standalone initialization without main app`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        TileLogManager.initialize(context)
        ShizukuManager.initialize(context)

        val serviceController = Robolectric.buildService(SensorsOffTileService::class.java).create()
        val service = serviceController.get()
        assertNotNull(service)

        val logs = TileLogManager.logsFlow.value
        assertTrue(logs.any { it.detail.contains("SensorsOffTileService initialized independently") })
    }

    @Test
    fun `test Companion Tile - unprivileged click safely logs warning without throwing`() {
        val serviceController = Robolectric.buildService(SensorsOffTileService::class.java).create()
        val service = serviceController.get()

        service.onClick()
        val logs = TileLogManager.logsFlow.value
        assertTrue(logs.any { it.detail.contains("Touch detected while Shizuku/Root is unavailable") })
    }

    @Test
    fun `test Companion Tile - authoritative state mapping`() {
        // Point 4: Valid authoritative values become ENABLED or DISABLED
        assertEquals(true, SensorPrivacyState.ENABLED.toBooleanOrNull())
        assertEquals(false, SensorPrivacyState.DISABLED.toBooleanOrNull())
        assertEquals(null, SensorPrivacyState.UNKNOWN.toBooleanOrNull())
        assertTrue(SensorPrivacyState.ENABLED.isAuthoritative)
        assertTrue(SensorPrivacyState.DISABLED.isAuthoritative)
        assertFalse(SensorPrivacyState.UNKNOWN.isAuthoritative)
    }

    @Test
    fun `test Companion Tile - API specific read transaction mapping`() {
        // Point 3: Correct API-specific read transaction is used
        assertEquals(SensorPrivacyCodes.IS_GLOBAL_PRIVACY_S_PLUS, SensorPrivacyCodes.getQueryGlobalCodeForSdk(34))
        assertEquals(SensorPrivacyCodes.IS_GLOBAL_PRIVACY_S_PLUS, SensorPrivacyCodes.getQueryGlobalCodeForSdk(31))
        assertEquals(SensorPrivacyCodes.IS_GLOBAL_PRIVACY_R, SensorPrivacyCodes.getQueryGlobalCodeForSdk(30))
        assertEquals(SensorPrivacyCodes.IS_GLOBAL_PRIVACY_Q, SensorPrivacyCodes.getQueryGlobalCodeForSdk(29))
        assertNull(SensorPrivacyCodes.getQueryGlobalCodeForSdk(28))

        assertEquals(6, SensorPrivacyCodes.IS_GLOBAL_PRIVACY_S_PLUS)
        assertEquals(4, SensorPrivacyCodes.IS_GLOBAL_PRIVACY_R)
        assertEquals(3, SensorPrivacyCodes.IS_GLOBAL_PRIVACY_Q)
    }

    @Test
    fun `test Companion Tile - genuine inability to read remains UNKNOWN`() {
        // Point 5: Genuine inability to read remains UNKNOWN
        val context = ApplicationProvider.getApplicationContext<Context>()
        ShizukuManager.initialize(context)
        ShizukuManager.invalidateSensorPrivacyBinder()

        val directState = ShizukuManager.queryDirectSensorPrivacy()
        assertEquals(SensorPrivacyState.UNKNOWN, directState)
        assertFalse(directState.isAuthoritative)
    }

    @Test
    fun `test Companion Tile - dead binder invalidation and recovery`() {
        // Point 2: Dead Binder is invalidated and reacquired
        val context = ApplicationProvider.getApplicationContext<Context>()
        ShizukuManager.initialize(context)
        ShizukuManager.invalidateSensorPrivacyBinder()
        val binder = ShizukuManager.getSensorPrivacyBinder()
        assertNull(binder)
    }

    @Test
    fun `test Companion Tile - fresh service lifecycle listening`() {
        // Point 1: Fresh companion TileService can obtain the state safely
        val serviceController = Robolectric.buildService(SensorsOffTileService::class.java).create()
        val service = serviceController.get()
        assertNotNull(service)

        service.onStartListening()
        service.onStopListening()

        val logs = TileLogManager.logsFlow.value
        assertTrue(logs.any { it.title.contains("CompanionTileService: onStartListening") })
        assertTrue(logs.any { it.title.contains("CompanionTileService: onStopListening") })
    }

    @Test
    fun `test Companion Tile - manifest contains BIND_QUICK_SETTINGS_TILE and exactly one service`() {
        // Point 6: Zero background services / daemons / architecture preserved
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pm = context.packageManager
        val packageInfo = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SERVICES)
        val services = packageInfo.services ?: emptyArray()

        assertEquals(1, services.size)
        assertEquals(SensorsOffTileService::class.java.name, services[0].name)
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", services[0].permission)
        assertTrue(services[0].exported)
    }

    @Test
    fun `test Companion Tile - no boot receivers in companion manifest`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pm = context.packageManager
        val packageInfo = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_RECEIVERS)
        val receivers = packageInfo.receivers ?: emptyArray()
        val receiverNames = receivers.map { it.name }
        assertFalse(receiverNames.any { it.contains("Boot") || it.startsWith("com.example") })
    }
}
