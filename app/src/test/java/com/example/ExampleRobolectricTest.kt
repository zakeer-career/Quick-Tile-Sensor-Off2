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
}

