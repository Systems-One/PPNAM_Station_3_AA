package com.mitas.ppnam.station3aa

/**
 * How long the "Source selected" card stays on the Master Batch screen after the station
 * answers a scan. The card confirms the lookup; the pack weight and labels happen on the
 * desktop, so once the operator has read it the card only risks being mistaken for the
 * *current* selection by whoever picks up the scanner next. It clears itself after
 * [AUTO_HIDE_MS]; a new scan hides it immediately anyway.
 */
object SourceCardPolicy {

    const val AUTO_HIDE_MS = 15_000L

    /** Sentinel for "the card is not showing" in saved state. */
    const val NOT_SHOWN = -1L

    /**
     * Milliseconds the card has left, given when it appeared and the current clock (both from
     * the same monotonic source, e.g. `SystemClock.elapsedRealtime()`). Never negative.
     */
    fun remainingMs(shownAtMs: Long, nowMs: Long): Long {
        if (shownAtMs == NOT_SHOWN) return 0L
        return (shownAtMs + AUTO_HIDE_MS - nowMs).coerceAtLeast(0L)
    }
}
