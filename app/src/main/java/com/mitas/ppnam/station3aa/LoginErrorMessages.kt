package com.mitas.ppnam.station3aa

/** The five things an operator can be told about a failed login. */
enum class LoginErrorKind { INVALID_CREDENTIALS, BADGE_UNKNOWN, TIMEOUT, NOT_CONNECTED, STATION_ERROR }

/**
 * Maps a login failure to operator wording (audit S3-05 / group (f)): the station's free-text
 * reason ("SCRAM proof rejected.") and its error codes are protocol detail and never shown.
 * Pure Kotlin so the mapping is unit-tested; LoginActivity turns the kind into a string resource.
 */
object LoginErrorMessages {

    /** Local (non-station) codes AuthClient attaches to its own failures. */
    const val CODE_TIMEOUT = "timeout"
    const val CODE_NOT_CONNECTED = "not_connected"
    const val CODE_PUBLISH_FAILED = "publish_failed"

    /** Envelope/replay rejections: a device or clock problem, not a wrong password. */
    private val protocolCodes = setOf(
        "invalid_envelope", "message_id_reused", "timestamp_stale", "timestamp_invalid",
        "schema_version_unsupported", "unknown_request_type", "device_not_registered",
    )

    fun kindFor(stage: AuthStage, code: String): LoginErrorKind {
        val normalized = code.trim().lowercase()
        return when {
            normalized == CODE_TIMEOUT -> LoginErrorKind.TIMEOUT
            normalized == CODE_NOT_CONNECTED || normalized == CODE_PUBLISH_FAILED -> LoginErrorKind.NOT_CONNECTED
            normalized in protocolCodes || normalized.startsWith("timestamp") -> LoginErrorKind.STATION_ERROR
            stage == AuthStage.START || stage == AuthStage.PROOF -> LoginErrorKind.INVALID_CREDENTIALS
            stage == AuthStage.BADGE -> LoginErrorKind.BADGE_UNKNOWN
            else -> LoginErrorKind.STATION_ERROR
        }
    }

    fun kindFor(error: Throwable): LoginErrorKind =
        (error as? AuthFailure)?.let { kindFor(it.stage, it.code) } ?: LoginErrorKind.STATION_ERROR
}
