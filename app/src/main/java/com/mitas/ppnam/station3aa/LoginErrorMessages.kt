package com.mitas.ppnam.station3aa

/** The five things an operator can be told about a failed login. */
enum class LoginErrorKind { INVALID_CREDENTIALS, BADGE_UNKNOWN, TIMEOUT, NOT_CONNECTED, STATION_REFUSED, STATION_ERROR }

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

    /**
     * The only codes that mean "the username or password is wrong" (Station 1/3 contract: a bad
     * proof, a bad client-final message, or the generic authentication failure). Every other
     * station code is a refusal for some other reason and must not be blamed on the operator.
     */
    private val credentialCodes = setOf("scram_proof_invalid", "scram_client_final_invalid", "authentication_failed")

    /** The station's own code for an unknown/inactive badge. */
    private const val CODE_BADGE_REJECTED = "badge_rejected"

    fun normalize(code: String): String = code.trim().lowercase()

    fun kindFor(stage: AuthStage, code: String): LoginErrorKind {
        val normalized = normalize(code)
        return when {
            normalized == CODE_TIMEOUT -> LoginErrorKind.TIMEOUT
            normalized == CODE_NOT_CONNECTED || normalized == CODE_PUBLISH_FAILED -> LoginErrorKind.NOT_CONNECTED
            // No usable code (local check, or a response without one): the generic wording.
            normalized.isEmpty() ->
                if (stage == AuthStage.BADGE) LoginErrorKind.BADGE_UNKNOWN else LoginErrorKind.STATION_ERROR
            stage == AuthStage.BADGE ->
                if (normalized == CODE_BADGE_REJECTED || normalized in credentialCodes) LoginErrorKind.BADGE_UNKNOWN
                else LoginErrorKind.STATION_REFUSED
            (stage == AuthStage.START || stage == AuthStage.PROOF) && normalized in credentialCodes ->
                LoginErrorKind.INVALID_CREDENTIALS
            stage == AuthStage.LOCAL || stage == AuthStage.OPERATOR_LIST -> LoginErrorKind.STATION_ERROR
            else -> LoginErrorKind.STATION_REFUSED
        }
    }

    fun kindFor(error: Throwable): LoginErrorKind =
        (error as? AuthFailure)?.let { kindFor(it.stage, it.code) } ?: LoginErrorKind.STATION_ERROR

    /** The code to quote for [LoginErrorKind.STATION_REFUSED] ("... refused by the station (code)"). */
    fun refusalCode(error: Throwable): String = normalize((error as? AuthFailure)?.code.orEmpty())
}
