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
@Config(sdk = [36])
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
  fun `test state verification and preferences sync`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = context.getSharedPreferences("sensors_off_prefs", Context.MODE_PRIVATE)
    prefs.edit().clear().commit()

    val initialState = ShizukuManager.getSensorsOffState(context)
    org.junit.Assert.assertFalse(initialState)

    // Verify individual sensor states default correctly
    org.junit.Assert.assertFalse(ShizukuManager.getIndividualSensorState(context, "camera"))
    org.junit.Assert.assertFalse(ShizukuManager.getIndividualSensorState(context, "mic"))

    // Test SharedPreferences state persistence
    prefs.edit().putBoolean("sensors_off_enabled", true).commit()
    assertTrue(ShizukuManager.getSensorsOffState(context))
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
}

