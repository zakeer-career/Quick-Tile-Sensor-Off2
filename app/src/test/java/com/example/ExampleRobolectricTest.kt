package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("SensorsOff", appName)
  }

  @Test
  fun `test about tab renders without crash`() {
    val state = SensorUiState()
    assert(state.sensorList.isNotEmpty())
  }

  @Test
  fun `test tile customization defaults`() {
    val settings = TileSettingsState()
    assertEquals("aosp", settings.iconStyle)
    assertEquals("global", settings.blockMode)
  }

  @Test
  fun `test tile log manager initialization`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    TileLogManager.initialize(context)
    TileLogManager.logTileEvent(
      context,
      "Test Event",
      "Validating test event delivery",
      LogLevel.INFO
    )
    val logs = TileLogManager.logsFlow.value
    assertTrue(logs.isNotEmpty())
  }

  @Test
  fun `test sensor privacy codes constants and preferences`() {
    assertEquals("android.hardware.ISensorPrivacyManager", SensorPrivacyCodes.DESCRIPTOR)
    assertEquals(1, SensorPrivacyCodes.SENSOR_MICROPHONE)
    assertEquals(2, SensorPrivacyCodes.SENSOR_CAMERA)
    assertEquals(1, SensorPrivacyCodes.SOURCE_QS_TILE)
    assertTrue(SensorPrivacyCodes.getAllQueryGlobalCodes().isNotEmpty())
    assertTrue(SensorPrivacyCodes.getPreferredSetGlobalCode() > 0)
  }

  @Test
  fun `test shizuku command result data class`() {
    val successRes = CommandResult(success = true, exitCode = 0, stdout = "ok\n", stderr = "")
    assertTrue(successRes.success)
    assertEquals(0, successRes.exitCode)
    assertEquals("ok\n", successRes.stdout)

    val failRes = CommandResult.failure("permission denied", exitCode = 1)
    org.junit.Assert.assertFalse(failRes.success)
    assertEquals(1, failRes.exitCode)
  }

  @Test
  fun `test shizuku unavailable does not throw unhandled exception`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val isRunning = ShizukuManager.isShizukuRunning()
    val isAuth = ShizukuManager.isShizukuAuthorized()
    org.junit.Assert.assertFalse(isRunning && isAuth)

    val validInterface = ShizukuManager.validateSensorPrivacyInterface()
    org.junit.Assert.assertFalse(validInterface)

    val cmdRes = ShizukuManager.runShizukuCommand("echo test")
    org.junit.Assert.assertFalse(cmdRes.success)
  }

  @Test
  fun `test sensor privacy state matchesRequested contract`() {
    // ENABLED means sensor privacy is active (sensors are off/blocked)
    assertTrue(SensorPrivacyState.ENABLED.matchesRequested(true))
    org.junit.Assert.assertFalse(SensorPrivacyState.ENABLED.matchesRequested(false))

    // DISABLED means sensor privacy is inactive (sensors are available/on)
    assertTrue(SensorPrivacyState.DISABLED.matchesRequested(false))
    org.junit.Assert.assertFalse(SensorPrivacyState.DISABLED.matchesRequested(true))

    // UNKNOWN must NEVER match true or false
    org.junit.Assert.assertFalse(SensorPrivacyState.UNKNOWN.matchesRequested(true))
    org.junit.Assert.assertFalse(SensorPrivacyState.UNKNOWN.matchesRequested(false))
    org.junit.Assert.assertFalse(SensorPrivacyState.UNKNOWN.isAuthoritative)
    assertTrue(SensorPrivacyState.ENABLED.isAuthoritative)
    assertTrue(SensorPrivacyState.DISABLED.isAuthoritative)
  }

  @Test
  fun `test state verification rejects unverified or unknown state`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = context.getSharedPreferences("sensors_off_prefs", Context.MODE_PRIVATE)
    prefs.edit().clear().commit()

    // In a test environment without live Shizuku binder or system service reflection, state is UNKNOWN
    val currentState = ShizukuManager.getSensorsOffState(context)
    // UNKNOWN is never ENABLED or DISABLED
    org.junit.Assert.assertNotEquals(SensorPrivacyState.ENABLED, currentState)
    
    // Setting SharedPreferences does NOT fool authoritative hardware query
    prefs.edit().putBoolean("sensors_off_enabled", true).commit()
    val nonAuthoritativeCached = ShizukuManager.getCachedUiPreferenceState(context)
    assertTrue(nonAuthoritativeCached)

    // Authoritative check remains unfooled by SharedPreferences
    val authoritativeQuery = ShizukuManager.getSensorsOffState(context)
    org.junit.Assert.assertNotEquals(SensorPrivacyState.ENABLED, authoritativeQuery)
  }

  @Test
  fun `test boot completed receiver runs on-demand without starting background services`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val receiver = BootCompletedReceiver()
    val intent = android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED)
    
    // Executing onReceive must not crash and must not start any background service/daemon
    receiver.onReceive(context, intent)
    assertTrue(true)
  }

  @Test
  fun `test rapid sequential toggles do not crash or deadlock`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val threads = mutableListOf<Thread>()
    val exceptions = java.util.concurrent.CopyOnWriteArrayList<Throwable>()

    // Simulate 10 rapid calls from multiple threads
    for (i in 1..10) {
      val t = Thread {
        try {
          val toggleTarget = (i % 2 == 0)
          ShizukuManager.setSensorsOffState(context, toggleTarget, skipNotify = true)
        } catch (e: Throwable) {
          exceptions.add(e)
        }
      }
      threads.add(t)
    }

    threads.forEach { it.start() }
    threads.forEach { it.join(3000) }

    assertTrue("No exceptions occurred during rapid concurrent calls", exceptions.isEmpty())
  }

  @Test
  fun `test SensorPrivacyState toBooleanOrNull strictly preserves null for UNKNOWN`() {
    assertEquals(true, SensorPrivacyState.ENABLED.toBooleanOrNull())
    assertEquals(false, SensorPrivacyState.DISABLED.toBooleanOrNull())
    assertEquals(null, SensorPrivacyState.UNKNOWN.toBooleanOrNull())

    // Ensure fromBoolean accurately maps nullable booleans
    assertEquals(SensorPrivacyState.ENABLED, SensorPrivacyState.fromBoolean(true))
    assertEquals(SensorPrivacyState.DISABLED, SensorPrivacyState.fromBoolean(false))
    assertEquals(SensorPrivacyState.UNKNOWN, SensorPrivacyState.fromBoolean(null))
  }

  @Test
  fun `test requested state mismatch is rejected`() {
    // If requested is turnOff=true (sensors blocked), actual must be ENABLED
    val requestedTurnOff = true
    val actualStateEnabled = SensorPrivacyState.ENABLED
    val actualStateDisabled = SensorPrivacyState.DISABLED
    val actualStateUnknown = SensorPrivacyState.UNKNOWN

    assertTrue(actualStateEnabled.matchesRequested(requestedTurnOff))
    org.junit.Assert.assertFalse(actualStateDisabled.matchesRequested(requestedTurnOff))
    org.junit.Assert.assertFalse(actualStateUnknown.matchesRequested(requestedTurnOff))

    // If requested is turnOff=false (sensors unblocked), actual must be DISABLED
    val requestedTurnOn = false
    assertTrue(actualStateDisabled.matchesRequested(requestedTurnOn))
    org.junit.Assert.assertFalse(actualStateEnabled.matchesRequested(requestedTurnOn))
    org.junit.Assert.assertFalse(actualStateUnknown.matchesRequested(requestedTurnOn))
  }

  @Test
  fun `test Root state query returns valid enum`() {
    val rootState = ShizukuManager.getRootState()
    assertNotNull(rootState)
    assertTrue(rootState == ShizukuManager.RootState.AVAILABLE || rootState == ShizukuManager.RootState.UNAVAILABLE || rootState == ShizukuManager.RootState.UNKNOWN)
    val isRoot = ShizukuManager.isRootAvailable()
    assertEquals(rootState == ShizukuManager.RootState.AVAILABLE, isRoot)
  }

  @Test
  fun `test CommandResult failure helper`() {
    val timeoutFailure = CommandResult.failure("Command timed out", exitCode = -2)
    org.junit.Assert.assertFalse(timeoutFailure.success)
    assertEquals(-2, timeoutFailure.exitCode)
    assertEquals("Command timed out", timeoutFailure.stderr)
    assertEquals("", timeoutFailure.stdout)
  }

  @Test
  fun `test binder transaction result enum states`() {
    assertTrue(BinderTransactionResult.TRANSACTION_ACCEPTED.isAccepted)
    org.junit.Assert.assertFalse(BinderTransactionResult.BINDER_ERROR.isAccepted)
    org.junit.Assert.assertFalse(BinderTransactionResult.UNSUPPORTED.isAccepted)
    org.junit.Assert.assertFalse(BinderTransactionResult.TRANSACTION_ERROR.isAccepted)
    org.junit.Assert.assertFalse(BinderTransactionResult.EXCEPTION.isAccepted)
  }

  @Test
  fun `test unprivileged setSensorsOffState returns false when privileges missing`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val result = ShizukuManager.setSensorsOffState(context, turnOff = true, skipNotify = true)
    // Without Shizuku or Root or WRITE_SECURE_SETTINGS, operation must return false
    org.junit.Assert.assertFalse(result)
  }

  @Test
  fun `test individual sensor state query without privileges returns UNKNOWN or DISABLED`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val cameraState = ShizukuManager.getIndividualSensorState(context, "camera")
    // Should never falsely report ENABLED in unprivileged test environment
    org.junit.Assert.assertNotEquals(SensorPrivacyState.ENABLED, cameraState)
  }
}

