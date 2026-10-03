package com.mitas.ppnam.station3aa

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The station's `reason` text ("SCRAM proof rejected.") and error codes are protocol detail;
 * operators get one of five fixed wordings chosen from WHERE the login failed and the code.
 */
class LoginErrorMessagesTest {

    @Test
    fun `a rejected proof is incorrect credentials`() {
        assertEquals(LoginErrorKind.INVALID_CREDENTIALS, LoginErrorMessages.kindFor(AuthStage.PROOF, "scram_proof_invalid"))
    }

    @Test
    fun `only the credential codes are incorrect credentials`() {
        for (code in listOf("scram_proof_invalid", "scram_client_final_invalid", "authentication_failed", " Authentication_Failed ")) {
            assertEquals(LoginErrorKind.INVALID_CREDENTIALS, LoginErrorMessages.kindFor(AuthStage.PROOF, code))
            assertEquals(LoginErrorKind.INVALID_CREDENTIALS, LoginErrorMessages.kindFor(AuthStage.START, code))
        }
    }

    @Test
    fun `any other station code is a refusal that quotes the code`() {
        assertEquals(LoginErrorKind.STATION_REFUSED, LoginErrorMessages.kindFor(AuthStage.START, "unknown_user"))
        assertEquals(LoginErrorKind.STATION_REFUSED, LoginErrorMessages.kindFor(AuthStage.PROOF, "authentication_unavailable"))
        assertEquals(LoginErrorKind.STATION_REFUSED, LoginErrorMessages.kindFor(AuthStage.START, "scram_challenge_expired"))
        assertEquals(
            "scram_challenge_expired",
            LoginErrorMessages.refusalCode(AuthFailure(AuthStage.START, " SCRAM_challenge_expired", "x")),
        )
    }

    @Test
    fun `a blank code gets the generic wording`() {
        assertEquals(LoginErrorKind.STATION_ERROR, LoginErrorMessages.kindFor(AuthStage.START, ""))
        assertEquals(LoginErrorKind.STATION_ERROR, LoginErrorMessages.kindFor(AuthStage.PROOF, "  "))
    }

    @Test
    fun `a rejected badge is badge unknown`() {
        assertEquals(LoginErrorKind.BADGE_UNKNOWN, LoginErrorMessages.kindFor(AuthStage.BADGE, "badge_rejected"))
        assertEquals(LoginErrorKind.STATION_REFUSED, LoginErrorMessages.kindFor(AuthStage.BADGE, "station_unavailable"))
        assertEquals(LoginErrorKind.BADGE_UNKNOWN, LoginErrorMessages.kindFor(AuthStage.BADGE, ""))
    }

    @Test
    fun `timeouts and broker problems are reported as such at every stage`() {
        for (stage in AuthStage.values()) {
            assertEquals(LoginErrorKind.TIMEOUT, LoginErrorMessages.kindFor(stage, LoginErrorMessages.CODE_TIMEOUT))
            assertEquals(LoginErrorKind.NOT_CONNECTED, LoginErrorMessages.kindFor(stage, LoginErrorMessages.CODE_NOT_CONNECTED))
            assertEquals(LoginErrorKind.NOT_CONNECTED, LoginErrorMessages.kindFor(stage, LoginErrorMessages.CODE_PUBLISH_FAILED))
        }
    }

    @Test
    fun `protocol-level codes are refusals, never wrong credentials`() {
        assertEquals(LoginErrorKind.STATION_REFUSED, LoginErrorMessages.kindFor(AuthStage.PROOF, "timestamp_stale"))
        assertEquals(LoginErrorKind.STATION_REFUSED, LoginErrorMessages.kindFor(AuthStage.START, "message_id_reused"))
        assertEquals(LoginErrorKind.STATION_REFUSED, LoginErrorMessages.kindFor(AuthStage.BADGE, "invalid_envelope"))
    }

    @Test
    fun `local checks and anything not an AuthFailure are a station error`() {
        assertEquals(LoginErrorKind.STATION_ERROR, LoginErrorMessages.kindFor(AuthStage.LOCAL, ""))
        assertEquals(LoginErrorKind.STATION_ERROR, LoginErrorMessages.kindFor(IllegalStateException("boom")))
        assertEquals(
            LoginErrorKind.INVALID_CREDENTIALS,
            LoginErrorMessages.kindFor(AuthFailure(AuthStage.PROOF, "scram_proof_invalid", "SCRAM proof rejected.")),
        )
    }
}
