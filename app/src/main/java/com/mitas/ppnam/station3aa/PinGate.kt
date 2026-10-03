package com.mitas.ppnam.station3aa

/**
 * Supervisor PIN gate rules, mirroring Station 2's SettingsViewModel: [MAX_ATTEMPTS] wrong PINs
 * lock the gate for [LOCKOUT_MS]. Pure Kotlin so the lockout is unit-testable; the activity
 * persists [failedAttempts] and [lockedOutUntilMs] (wall-clock ms) in SharedPreferences and
 * rebuilds the gate from them, so Back + reopen or a process restart cannot reset the counter.
 */
class PinGate(
    private val correctPin: String,
    failedAttempts: Int = 0,
    lockedOutUntilMs: Long = 0L,
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val lockoutMs: Long = LOCKOUT_MS,
) {
    companion object {
        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_MS = 30_000L
    }

    var failedAttempts: Int = failedAttempts
        private set

    var lockedOutUntilMs: Long = lockedOutUntilMs
        private set

    sealed class Outcome {
        object Unlocked : Outcome()
        /** Empty input is not an attempt (audit: empty Unlock used to burn one). */
        object Blank : Outcome()
        data class Incorrect(val attemptsLeft: Int) : Outcome()
        data class LockedOut(val remainingMs: Long) : Outcome()
    }

    fun remainingLockoutMs(nowMs: Long): Long = (lockedOutUntilMs - nowMs).coerceAtLeast(0L)

    fun isLockedOut(nowMs: Long): Boolean = remainingLockoutMs(nowMs) > 0

    fun submit(pin: String, nowMs: Long): Outcome {
        if (isLockedOut(nowMs)) return Outcome.LockedOut(remainingLockoutMs(nowMs))
        if (pin.isBlank()) return Outcome.Blank
        if (pin == correctPin) {
            failedAttempts = 0
            lockedOutUntilMs = 0L
            return Outcome.Unlocked
        }
        failedAttempts++
        if (failedAttempts >= maxAttempts) {
            failedAttempts = 0
            lockedOutUntilMs = nowMs + lockoutMs
            return Outcome.LockedOut(lockoutMs)
        }
        return Outcome.Incorrect(maxAttempts - failedAttempts)
    }
}
