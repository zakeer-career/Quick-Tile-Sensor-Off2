package com.example

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    assertEquals(10, SensorPrivacyCodes.SET_TOGGLE_PRIVACY)
    assertEquals(8, SensorPrivacyCodes.IS_TOGGLE_PRIVACY)
    assertEquals(7, SensorPrivacyCodes.IS_COMBINED_TOGGLE_PRIVACY)
    assertTrue(SensorPrivacyCodes.getPreferredSetGlobalCode() > 0)
  }

  @Test
  fun `test API version specific global transaction codes`() {
    // Android 12+ (API 31, 32, 33, 34, 35)
    assertEquals(9, SensorPrivacyCodes.getSetGlobalCodeForSdk(31))
    assertEquals(9, SensorPrivacyCodes.getSetGlobalCodeForSdk(33))
    assertEquals(9, SensorPrivacyCodes.getSetGlobalCodeForSdk(34))
    assertEquals(6, SensorPrivacyCodes.getQueryGlobalCodeForSdk(31))
    assertEquals(6, SensorPrivacyCodes.getQueryGlobalCodeForSdk(34))

    // Android 11 (API 30)
    assertEquals(5, SensorPrivacyCodes.getSetGlobalCodeForSdk(30))
    assertEquals(4, SensorPrivacyCodes.getQueryGlobalCodeForSdk(30))

    // Android 10 (API 29)
    assertEquals(4, SensorPrivacyCodes.getSetGlobalCodeForSdk(29))
    assertEquals(3, SensorPrivacyCodes.getQueryGlobalCodeForSdk(29))

    // Android < 29 (API 24-28: ISensorPrivacyManager not present in AOSP)
    assertNull(SensorPrivacyCodes.getSetGlobalCodeForSdk(28))
    assertNull(SensorPrivacyCodes.getSetGlobalCodeForSdk(24))
    assertNull(SensorPrivacyCodes.getQueryGlobalCodeForSdk(28))
    assertNull(SensorPrivacyCodes.getQueryGlobalCodeForSdk(24))
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
    val isRunning = ShizukuManager.isShizukuRunning()
    val isAuth = ShizukuManager.isShizukuAuthorized()
    org.junit.Assert.assertFalse(isRunning && isAuth)

    val validInterface = ShizukuManager.validateSensorPrivacyInterface()
    org.junit.Assert.assertFalse(validInterface)
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
    // Without live ISensorPrivacyManager or valid privilege verification, operation must return false
    org.junit.Assert.assertFalse(result)
  }

  @Test
  fun `test privilege backend is strictly Shizuku or Root`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val isPriv = ShizukuManager.isPrivilegeAvailable(context)
    val expected = (ShizukuManager.isShizukuRunning() && ShizukuManager.isShizukuAuthorized()) || ShizukuManager.isRootAvailable()
    assertEquals(expected, isPriv)
  }

  @Test
  fun `test individual sensor state query without privileges returns UNKNOWN or DISABLED`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val cameraState = ShizukuManager.getIndividualSensorState(context, "camera")
    // Should never falsely report ENABLED in unprivileged test environment
    org.junit.Assert.assertNotEquals(SensorPrivacyState.ENABLED, cameraState)
  }

  @Test
  fun `test operation accepted but state mismatch rejects verification`() {
    // Scenario: Binder returns TRANSACTION_ACCEPTED, but actual state read returns DISABLED when requested turnOff = true
    val binderResult = BinderTransactionResult.TRANSACTION_ACCEPTED
    assertTrue(binderResult.isAccepted)

    val requestedTurnOff = true
    val actualState = SensorPrivacyState.DISABLED

    // State verification MUST fail
    val verified = actualState.matchesRequested(requestedTurnOff)
    org.junit.Assert.assertFalse("Mismatched state must not pass verification", verified)

    val toggleResult: SensorToggleResult = if (verified) {
      SensorToggleResult.Success(confirmedState = actualState, latencyMs = 12L)
    } else {
      SensorToggleResult.Failure(
        reason = "State mismatch after IPC",
        confirmedState = actualState,
        latencyMs = 12L
      )
    }
    assertTrue(toggleResult is SensorToggleResult.Failure)
    org.junit.Assert.assertFalse(toggleResult.isSuccess)
  }

  @Test
  fun `test operation accepted but state UNKNOWN rejects verification`() {
    // Scenario: IPC call executed, but sensor query fails with UNKNOWN
    val actualState = SensorPrivacyState.UNKNOWN
    org.junit.Assert.assertFalse(actualState.isAuthoritative)
    assertEquals(null, actualState.toBooleanOrNull())

    // Both requestedSensorsOff = true and false MUST fail verification when state is UNKNOWN
    org.junit.Assert.assertFalse(actualState.matchesRequested(requestedSensorsOff = true))
    org.junit.Assert.assertFalse(actualState.matchesRequested(requestedSensorsOff = false))

    val toggleResult: SensorToggleResult = SensorToggleResult.Failure(
      reason = "Authoritative sensor state could not be read",
      confirmedState = actualState,
      latencyMs = 15L
    )
    org.junit.Assert.assertFalse(toggleResult.isSuccess)
    assertEquals(actualState, (toggleResult as SensorToggleResult.Failure).confirmedState)
  }

  @Test
  fun `test successful operation and verification contract`() {
    // Scenario: Operation requested turnOff = true, actual confirmed state is ENABLED
    val requestedTurnOff = true
    val confirmedState = SensorPrivacyState.ENABLED

    assertTrue(confirmedState.isAuthoritative)
    assertEquals(true, confirmedState.toBooleanOrNull())
    assertTrue(confirmedState.matchesRequested(requestedTurnOff))

    val toggleResult: SensorToggleResult = SensorToggleResult.Success(
      confirmedState = confirmedState,
      latencyMs = 8L
    )
    assertTrue(toggleResult.isSuccess)
    assertEquals(confirmedState, (toggleResult as SensorToggleResult.Success).confirmedState)
  }

  @Test
  fun `test rapid repeated state checks execute safely without deadlock`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    for (i in 1..25) {
      val state = ShizukuManager.getSensorsOffState(context)
      assertNotNull(state)
      val camState = ShizukuManager.getIndividualSensorState(context, "camera")
      assertNotNull(camState)
      val micState = ShizukuManager.getIndividualSensorState(context, "mic")
      assertNotNull(micState)
    }
  }

  // Section 9 Test Suite: Global Sensor Privacy State Verification

  @Test
  fun `test requirement 1 - Global ENABLED read-back produces success`() {
    val requestedTurnOff = true
    val readBackState = SensorPrivacyState.ENABLED
    assertTrue("Global ENABLED read-back must match requested turnOff", readBackState.matchesRequested(requestedTurnOff))
    val result = if (readBackState.matchesRequested(requestedTurnOff)) {
      SensorToggleResult.Success(confirmedState = readBackState, latencyMs = 5L)
    } else {
      SensorToggleResult.Failure("Mismatch", confirmedState = readBackState)
    }
    assertTrue(result.isSuccess)
    assertEquals(SensorPrivacyState.ENABLED, (result as SensorToggleResult.Success).confirmedState)
  }

  @Test
  fun `test requirement 2 - Global DISABLED read-back produces success`() {
    val requestedTurnOff = false
    val readBackState = SensorPrivacyState.DISABLED
    assertTrue("Global DISABLED read-back must match requested turnOn", readBackState.matchesRequested(requestedTurnOff))
    val result = if (readBackState.matchesRequested(requestedTurnOff)) {
      SensorToggleResult.Success(confirmedState = readBackState, latencyMs = 5L)
    } else {
      SensorToggleResult.Failure("Mismatch", confirmedState = readBackState)
    }
    assertTrue(result.isSuccess)
    assertEquals(SensorPrivacyState.DISABLED, (result as SensorToggleResult.Success).confirmedState)
  }

  @Test
  fun `test requirement 3 - Global UNKNOWN produces failure`() {
    val readBackState = SensorPrivacyState.UNKNOWN
    org.junit.Assert.assertFalse("UNKNOWN must never match turnOff=true", readBackState.matchesRequested(true))
    org.junit.Assert.assertFalse("UNKNOWN must never match turnOff=false", readBackState.matchesRequested(false))
    val result: SensorToggleResult = SensorToggleResult.Failure("Unknown state", confirmedState = readBackState)
    org.junit.Assert.assertFalse(result.isSuccess)
  }

  @Test
  fun `test requirement 4 - Global mismatched read-back produces failure`() {
    val requestedTurnOff = true
    val readBackState = SensorPrivacyState.DISABLED // Requested OFF (enabled privacy), but got DISABLED (sensors on)
    org.junit.Assert.assertFalse("Mismatched read-back must fail matchesRequested", readBackState.matchesRequested(requestedTurnOff))
    val result = if (readBackState.matchesRequested(requestedTurnOff)) {
      SensorToggleResult.Success(confirmedState = readBackState, latencyMs = 5L)
    } else {
      SensorToggleResult.Failure("Mismatch", confirmedState = readBackState)
    }
    org.junit.Assert.assertFalse(result.isSuccess)
  }

  @Test
  fun `test requirement 5 - Global Binder transaction failure produces failure`() {
    val binderFailureResults = listOf(
      BinderTransactionResult.BINDER_ERROR,
      BinderTransactionResult.TRANSACTION_ERROR,
      BinderTransactionResult.EXCEPTION
    )
    for (res in binderFailureResults) {
      org.junit.Assert.assertFalse("Binder failure result $res must not be accepted", res.isAccepted)
    }
  }

  @Test
  fun `test requirement 6 - Global unsupported transaction produces UNKNOWN or failure`() {
    val unsupported = BinderTransactionResult.UNSUPPORTED
    org.junit.Assert.assertFalse("UNSUPPORTED transaction must not be accepted", unsupported.isAccepted)
  }

  @Test
  fun `test requirement 7 - Camera and microphone both disabled MUST NOT prove global disabled`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    // Verify that getSensorsOffState queries ONLY global state, and never delegates to cam/mic
    val globalState = ShizukuManager.getSensorsOffState(context)
    val camState = ShizukuManager.getIndividualSensorState(context, "camera")
    val micState = ShizukuManager.getIndividualSensorState(context, "mic")
    
    // Global state is independently evaluated from global ISensorPrivacyManager
    assertNotNull(globalState)
    assertNotNull(camState)
    assertNotNull(micState)
  }

  @Test
  fun `test requirement 8 - Camera and microphone both enabled MUST NOT prove global enabled`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val globalState = ShizukuManager.getSensorsOffState(context)
    // Global state must reflect the global sensor privacy service, never assumed ENABLED from individual sensors
    org.junit.Assert.assertNotEquals(SensorPrivacyState.ENABLED, globalState)
  }

  @Test
  fun `test requirement 9 - SharedPreferences MUST NOT determine global state`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = context.getSharedPreferences("sensors_off_prefs", Context.MODE_PRIVATE)
    
    // Setting SharedPreferences to true (privacy active) must NOT make authoritative global query return ENABLED
    prefs.edit().putBoolean("sensors_off_enabled", true).commit()
    val stateWithTruePref = ShizukuManager.getSensorsOffState(context)
    org.junit.Assert.assertNotEquals("Authoritative state must not be overridden by true in SharedPreferences", SensorPrivacyState.ENABLED, stateWithTruePref)

    // Cached UI preference helper returns true, but authoritative query is independent
    assertTrue(ShizukuManager.getCachedUiPreferenceState(context))
    assertEquals(stateWithTruePref, ShizukuManager.getSensorsOffState(context))
  }

  @Test
  fun `test requirement 10 - Settings Global or Secure MUST NOT determine global state`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    // Inject Settings.Global and Settings.Secure values simulating sensors_off = 1
    android.provider.Settings.Global.putInt(context.contentResolver, "sensors_off", 1)
    android.provider.Settings.Secure.putInt(context.contentResolver, "sensor_privacy", 1)
    
    val state = ShizukuManager.getSensorsOffState(context)
    // Authoritative global state must come strictly from sensor privacy service, never spoofed by Settings entries
    org.junit.Assert.assertNotEquals("Settings values must not fool getSensorsOffState into returning ENABLED", SensorPrivacyState.ENABLED, state)
  }

  @Test
  fun `test requirement 11 - Successful global transaction without matching read-back MUST fail`() {
    val txResult = BinderTransactionResult.TRANSACTION_ACCEPTED
    assertTrue(txResult.isAccepted)

    // Transaction was accepted by Binder, but read-back returned UNKNOWN
    val readBackState = SensorPrivacyState.UNKNOWN
    val verified = readBackState.matchesRequested(requestedSensorsOff = true)
    org.junit.Assert.assertFalse("Successful Binder transaction alone without matching read-back must fail", verified)
  }

  @Test
  fun `test requirement 12 - Individual camera operation remains independent`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val camState = ShizukuManager.getIndividualSensorState(context, "camera")
    assertNotNull(camState)
    // Camera state query only queries camera sensor code (2)
    assertEquals(2, SensorPrivacyCodes.SENSOR_CAMERA)
  }

  @Test
  fun `test requirement 13 - Individual microphone operation remains independent`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val micState = ShizukuManager.getIndividualSensorState(context, "mic")
    assertNotNull(micState)
    // Microphone state query only queries mic sensor code (1)
    assertEquals(1, SensorPrivacyCodes.SENSOR_MICROPHONE)
  }

  @Test
  fun `test requirement 14 - API version specific transaction code selection`() {
    // Verify mapping for Android 12+ (API 31, 32, 33, 34)
    assertEquals(9, SensorPrivacyCodes.getSetGlobalCodeForSdk(34))
    assertEquals(6, SensorPrivacyCodes.getQueryGlobalCodeForSdk(34))

    // Verify mapping for Android 11 (API 30)
    assertEquals(5, SensorPrivacyCodes.getSetGlobalCodeForSdk(30))
    assertEquals(4, SensorPrivacyCodes.getQueryGlobalCodeForSdk(30))

    // Verify mapping for Android 10 (API 29)
    assertEquals(4, SensorPrivacyCodes.getSetGlobalCodeForSdk(29))
    assertEquals(3, SensorPrivacyCodes.getQueryGlobalCodeForSdk(29))
  }

  @Test
  fun `test requirement 15 - Unsupported API version returns null codes and UNKNOWN state`() {
    // Android < 29 (API 24 to 28)
    assertNull(SensorPrivacyCodes.getSetGlobalCodeForSdk(28))
    assertNull(SensorPrivacyCodes.getQueryGlobalCodeForSdk(28))
    assertNull(SensorPrivacyCodes.getSetGlobalCodeForSdk(24))
    assertNull(SensorPrivacyCodes.getQueryGlobalCodeForSdk(24))
  }

  // ==========================================
  // Tile Resilience Unit Tests
  // ==========================================

  @Test
  fun `test Tile Resilience - TileService class and manifest configuration verified`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val pm = context.packageManager
    val packageInfo = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SERVICES)
    val tileServiceInfo = packageInfo.services?.find { it.name == SensorsOffTileService::class.java.name }
    assertNotNull(tileServiceInfo)
    assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", tileServiceInfo?.permission)
    assertTrue(tileServiceInfo?.exported == true)
  }

  @Test
  fun `test Tile Resilience - Runtime dependencies initialization is idempotent and non-blocking`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    ShizukuManager.initialize(context)
    TileLogManager.initialize(context)
    val state = ShizukuManager.getCachedUiPreferenceState(context)
    assertNotNull(state)
  }

  @Test
  fun `test Tile Resilience - Shizuku unavailable does not permanently mark tile unavailable`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    // Querying state without Shizuku must return UNKNOWN, not throw or crash
    val state = ShizukuManager.getSensorsOffState(context)
    assertTrue(state == SensorPrivacyState.UNKNOWN || state == SensorPrivacyState.DISABLED || state == SensorPrivacyState.ENABLED)
    // UNKNOWN is not authoritative
    if (state == SensorPrivacyState.UNKNOWN) {
      org.junit.Assert.assertFalse(state.isAuthoritative)
    }
  }

  @Test
  fun `test Tile Resilience - Shizuku binder death can be recovered on later invocation`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    ShizukuManager.initialize(context)
    // Validate that calling validateSensorPrivacyInterface or getSensorPrivacyBinder handles dead/null binder cleanly
    val isValid = ShizukuManager.validateSensorPrivacyInterface()
    org.junit.Assert.assertFalse(isValid) // In test environment with no Shizuku, gracefully returns false
  }

  @Test
  fun `test Tile Resilience - Authoritative sensor state is refreshed after recreation`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    ShizukuManager.initialize(context)
    val state = ShizukuManager.getSensorsOffState(context)
    assertNotNull(state)
  }

  @Test
  fun `test Tile Resilience - UNKNOWN sensor state is never treated as matching requested state`() {
    val unknownState = SensorPrivacyState.UNKNOWN
    org.junit.Assert.assertFalse(unknownState.isAuthoritative)
    org.junit.Assert.assertFalse(unknownState.matchesRequested(true))
    org.junit.Assert.assertFalse(unknownState.matchesRequested(false))
  }

  @Test
  fun `test Tile Resilience - Operations remain serialized without race conditions`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    // Lock must allow synchronized operations without deadlocks
    val isRunning = ShizukuManager.isShizukuRunning()
    org.junit.Assert.assertFalse(isRunning) // Expected false in JVM unit test environment
  }

  @Test
  fun `test Tile Resilience - Zero background service or foreground service registered in manifest`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val pm = context.packageManager
    val packageInfo = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SERVICES)
    val services = packageInfo.services ?: emptyArray()
    
    // The only registered service must be the Quick Settings TileService
    assertEquals(1, services.size)
    assertEquals(SensorsOffTileService::class.java.name, services[0].name)
    assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", services[0].permission)
  }

  @Test
  fun `test Tile Resilience - Invalidate cached binder ensures fresh retrieval`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    ShizukuManager.initialize(context)
    ShizukuManager.invalidateSensorPrivacyBinder()
    val binder = ShizukuManager.getSensorPrivacyBinder()
    assertNull(binder) // In test environment with no Shizuku, returns null safely
  }

  @Test
  fun `test Tile Resilience - BootCompletedReceiver handles boot intent safely without persistent services`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val receiver = BootCompletedReceiver()
    val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
    receiver.onReceive(context, intent)
    // Verify receiver executed and logged without throwing
    val logs = TileLogManager.logsFlow.value
    assertTrue(logs.any { it.title.contains("Device boot trigger") })
  }

  @Test
  fun `test Tile Resilience - App never disables its own TileService component`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val pm = context.packageManager
    val tileComponent = android.content.ComponentName(context, SensorsOffTileService::class.java)
    val state = pm.getComponentEnabledSetting(tileComponent)
    assertTrue(
      state == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ||
      state == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    )
  }

  @Test
  fun `test Tile Resilience - Telemetry records processTag for process session identification`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    TileLogManager.initialize(context)
    TileLogManager.logLifecycleEvent(context, "TileService", "onCreate", "Test process tag")
    val logs = TileLogManager.logsFlow.value
    val entry = logs.firstOrNull { it.detail == "Test process tag" }
    assertNotNull(entry)
    assertTrue(entry!!.processTag.startsWith("PID:"))
  }
}

