package com.mitas.ppnam.station3aa

import com.mitas.ppnam.station3aa.SessionKeeper.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The 30-second liveness tick (desktop MQTT_CONTRACT.md §3, §9) and local session expiry. */
class SessionKeeperTest {

    private val now = Instant.parse("2026-09-25T12:00:00Z")

    private fun session(expiresAt: Instant?) = OperatorSession(
        operatorSessionId = "s1",
        operatorId = "op1",
        operatorName = "Operator One",
        role = "Operator",
        expiresAt = expiresAt,
    )

    @Test
    fun `signed in and connected sends a heartbeat`() {
        assertEquals(Action.HEARTBEAT, SessionKeeper.decide(true, session(now.plusSeconds(3600)), now))
    }

    @Test
    fun `login screen and connected refreshes presence`() {
        assertEquals(Action.REFRESH_PRESENCE, SessionKeeper.decide(true, null, now))
    }

    @Test
    fun `disconnected does nothing`() {
        assertEquals(Action.NONE, SessionKeeper.decide(false, null, now))
        assertEquals(Action.NONE, SessionKeeper.decide(false, session(null), now))
    }

    @Test
    fun `an expired session is cleared even while disconnected`() {
        assertEquals(Action.EXPIRE_SESSION, SessionKeeper.decide(false, session(now.minusSeconds(1)), now))
        assertEquals(Action.EXPIRE_SESSION, SessionKeeper.decide(true, session(now), now))
    }

    @Test
    fun `a session without an expiry never expires locally`() {
        assertFalse(session(null).isExpired(now))
        assertEquals(Action.HEARTBEAT, SessionKeeper.decide(true, session(null), now))
    }

    @Test
    fun `expiry is inclusive of the exact instant`() {
        assertTrue(session(now).isExpired(now))
        assertFalse(session(now.plusMillis(1)).isExpired(now))
    }
}
