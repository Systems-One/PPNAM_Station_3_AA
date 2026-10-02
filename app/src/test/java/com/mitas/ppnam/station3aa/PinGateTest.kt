package com.mitas.ppnam.station3aa

import com.mitas.ppnam.station3aa.PinGate.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Supervisor PIN gate: 5 wrong attempts lock the gate for 30 s. The counters are plain fields
 * the activity persists, so Back + reopen or a process restart can no longer reset them
 * (audit group (c): verified bypass on Station 1, identical code here).
 */
class PinGateTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `correct PIN unlocks and resets the counter`() {
        val gate = PinGate("079545", failedAttempts = 3)
        assertEquals(Outcome.Unlocked, gate.submit("079545", t0))
        assertEquals(0, gate.failedAttempts)
        assertEquals(0L, gate.lockedOutUntilMs)
    }

    @Test
    fun `blank PIN is not an attempt`() {
        val gate = PinGate("079545")
        assertEquals(Outcome.Blank, gate.submit("", t0))
        assertEquals(Outcome.Blank, gate.submit("   ", t0))
        assertEquals(0, gate.failedAttempts)
    }

    @Test
    fun `wrong PIN counts down the attempts left`() {
        val gate = PinGate("079545")
        assertEquals(Outcome.Incorrect(attemptsLeft = 4), gate.submit("1111", t0))
        assertEquals(Outcome.Incorrect(attemptsLeft = 3), gate.submit("2222", t0))
        assertEquals(2, gate.failedAttempts)
    }

    @Test
    fun `fifth wrong PIN locks the gate for thirty seconds`() {
        val gate = PinGate("079545", failedAttempts = 4)
        assertEquals(Outcome.LockedOut(remainingMs = 30_000L), gate.submit("0000", t0))
        assertEquals(t0 + 30_000L, gate.lockedOutUntilMs)
        assertEquals(0, gate.failedAttempts)
        assertTrue(gate.isLockedOut(t0 + 29_999L))
        assertFalse(gate.isLockedOut(t0 + 30_000L))
    }

    @Test
    fun `the correct PIN is rejected while locked out`() {
        val gate = PinGate("079545", lockedOutUntilMs = t0 + 10_000L)
        assertEquals(Outcome.LockedOut(remainingMs = 10_000L), gate.submit("079545", t0))
        assertEquals(Outcome.LockedOut(remainingMs = 1L), gate.submit("079545", t0 + 9_999L))
    }

    @Test
    fun `persisted state keeps the lockout across a new instance`() {
        val first = PinGate("079545", failedAttempts = 4)
        first.submit("0000", t0)
        // The activity stores these two values and rebuilds the gate on the next onCreate.
        val reopened = PinGate("079545", first.failedAttempts, first.lockedOutUntilMs)
        assertEquals(Outcome.LockedOut(remainingMs = 25_000L), reopened.submit("079545", t0 + 5_000L))
    }

    @Test
    fun `after the lockout expires the gate works again`() {
        val gate = PinGate("079545", lockedOutUntilMs = t0 + 30_000L)
        assertEquals(0L, gate.remainingLockoutMs(t0 + 30_000L))
        assertEquals(Outcome.Unlocked, gate.submit("079545", t0 + 30_000L))
    }
}
