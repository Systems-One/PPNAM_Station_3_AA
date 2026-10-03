package com.mitas.ppnam.station3aa

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The selected-source card is a transient confirmation: it clears itself 15 s after the station
 * answered so the next operator is not misled by a stale pallet. The remaining time survives an
 * activity recreation (the countdown is anchored to elapsedRealtime, never restarted).
 */
class SourceCardPolicyTest {

    @Test
    fun `card stays on screen for fifteen seconds`() {
        assertEquals(15_000L, SourceCardPolicy.AUTO_HIDE_MS)
    }

    @Test
    fun `remaining time counts down from when the card was shown`() {
        assertEquals(15_000L, SourceCardPolicy.remainingMs(shownAtMs = 1_000L, nowMs = 1_000L))
        assertEquals(5_000L, SourceCardPolicy.remainingMs(shownAtMs = 1_000L, nowMs = 11_000L))
    }

    @Test
    fun `remaining time is zero once the fifteen seconds have passed`() {
        assertEquals(0L, SourceCardPolicy.remainingMs(shownAtMs = 1_000L, nowMs = 16_000L))
        assertEquals(0L, SourceCardPolicy.remainingMs(shownAtMs = 1_000L, nowMs = 60_000L))
    }

    @Test
    fun `a card that was never shown has nothing left to show`() {
        assertEquals(0L, SourceCardPolicy.remainingMs(shownAtMs = SourceCardPolicy.NOT_SHOWN, nowMs = 5_000L))
    }
}
