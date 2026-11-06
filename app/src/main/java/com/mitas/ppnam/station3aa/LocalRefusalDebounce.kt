package com.mitas.ppnam.station3aa

/**
 * Suite rule S3: one trigger pull fires several reads of the same value, so a local refusal of
 * the same value again within [windowMs] is the same scan and plays nothing. Every repeat counts
 * as refused too, so a held trigger never buzzes again; a different value plays fail once.
 *
 * [nowMs] is a monotonic clock (`SystemClock.elapsedRealtime` on the device, a fake in tests).
 * Not thread-safe: owned by one screen on the main thread.
 */
class LocalRefusalDebounce(
    private val nowMs: () -> Long,
    private val windowMs: Long = WINDOW_MS,
) {
    private var lastValue: String? = null
    private var lastAtMs = 0L

    /** The sound for refusing [value] locally: [ScanSound.FAIL], or null for a repeat. */
    fun refuse(value: String): ScanSound? {
        val key = value.trim()
        val now = nowMs()
        val repeat = key == lastValue && now - lastAtMs < windowMs
        lastValue = key
        lastAtMs = now
        return if (repeat) null else ScanSound.FAIL
    }

    companion object {
        const val WINDOW_MS = 1_500L
    }
}
