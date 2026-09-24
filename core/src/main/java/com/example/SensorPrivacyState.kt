package com.example

/**
 * Authoritative sensor privacy state returned by ISensorPrivacyManager / Android system service.
 * UNKNOWN indicates that the authoritative state could not be verified via Binder or system service,
 * and must NEVER be treated as ENABLED or DISABLED.
 */
enum class SensorPrivacyState {
    /**
     * Sensor privacy is active: hardware sensors are OFF / blocked.
     */
    ENABLED,

    /**
     * Sensor privacy is inactive: hardware sensors are ON / accessible.
     */
    DISABLED,

    /**
     * Authoritative state could not be determined or verified.
     * Must never be interpreted as ENABLED or DISABLED.
     */
    UNKNOWN;

    val isAuthoritative: Boolean
        get() = this == ENABLED || this == DISABLED

    /**
     * Checks if this state matches the requested toggle state.
     * @param requestedSensorsOff true if the caller requested turning sensors OFF (privacy active), false otherwise.
     */
    fun matchesRequested(requestedSensorsOff: Boolean): Boolean = when (this) {
        ENABLED -> requestedSensorsOff
        DISABLED -> !requestedSensorsOff
        UNKNOWN -> false
    }

    fun toBooleanOrNull(): Boolean? = when (this) {
        ENABLED -> true
        DISABLED -> false
        UNKNOWN -> null
    }

    companion object {
        fun fromBoolean(value: Boolean?): SensorPrivacyState = when (value) {
            true -> ENABLED
            false -> DISABLED
            null -> UNKNOWN
        }
    }
}

/**
 * Explicit outcome for low-level ISensorPrivacyManager Binder transactions.
 * Important: TRANSACTION_ACCEPTED only indicates the IPC call was accepted by the remote service,
 * NOT that the sensor state has been confirmed. Read-back verification is always required.
 */
enum class BinderTransactionResult {
    /**
     * Binder transaction succeeded and returned without exception.
     */
    TRANSACTION_ACCEPTED,

    /**
     * Sensor privacy binder is null, dead, or unavailable.
     */
    BINDER_ERROR,

    /**
     * The transaction code or method is not supported by the current Android version or OEM.
     */
    UNSUPPORTED,

    /**
     * The transaction returned false from IBinder.transact() (rejected by kernel/remote).
     */
    TRANSACTION_ERROR,

    /**
     * An exception (RemoteException, SecurityException, etc.) occurred during transaction.
     */
    EXCEPTION;

    val isAccepted: Boolean
        get() = this == TRANSACTION_ACCEPTED
}

/**
 * Explicit result for sensor toggle operations, requiring authoritative read-back verification.
 */
sealed class SensorToggleResult {
    data class Success(
        val confirmedState: SensorPrivacyState,
        val latencyMs: Long
    ) : SensorToggleResult()

    data class Failure(
        val reason: String,
        val confirmedState: SensorPrivacyState = SensorPrivacyState.UNKNOWN,
        val latencyMs: Long = 0L
    ) : SensorToggleResult()

    val isSuccess: Boolean
        get() = this is Success
}
