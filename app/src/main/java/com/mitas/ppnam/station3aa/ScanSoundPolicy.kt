package com.mitas.ppnam.station3aa

/** The two suite scan sounds (UI_Design/sounds/README.md). */
enum class ScanSound { SUCCESS, FAIL }

/** What the Master Batch screen does with one raw scanner broadcast. */
enum class ReadVerdict(val sound: ScanSound?) {
    /** Nothing read, or the Settings shortcut tag (navigation, handled app-wide). */
    IGNORE(null),

    /** Submitted as a source scan. No sound now: the scanner service already beeped on the read. */
    TAKE(null),

    /** A user badge is never a source pallet: refused locally. */
    BADGE(ScanSound.FAIL),
}

/** Whether a source scan goes on the wire, or why the app refuses it first. */
sealed class SubmitDecision(val sound: ScanSound?) {
    /** Sent; it sounds on Station 3's answer. */
    data class Send(val scanValue: String) : SubmitDecision(null)

    /** Offline, not allowed, or a scan already pending. */
    data object Blocked : SubmitDecision(ScanSound.FAIL)

    /** Blank, too long or carrying control characters. */
    data object Invalid : SubmitDecision(ScanSound.FAIL)

    /** The client refused it because another scan is still pending. */
    data object Busy : SubmitDecision(ScanSound.FAIL)

    /**
     * Another read of the value already in flight: RFID readers fire several times per trigger.
     * It is the same scan, which sounds once, on its answer.
     */
    data object Repeat : SubmitDecision(null)
}

/**
 * Suite rule S1 for the Master Batch source select: which sound a scan makes. One sound per
 * scan — on Station 3's answer, or on the app's local refusal. Non-scan traffic (heartbeat,
 * login, navigation, settings) makes none. [ScanFeedback] plays what this decides.
 */
object ScanSoundPolicy {

    fun forOutcome(outcome: WorkflowOutcome): ScanSound? = when (outcome) {
        is WorkflowOutcome.SourceSelected -> ScanSound.SUCCESS
        is WorkflowOutcome.Rejected, WorkflowOutcome.TimedOut, is WorkflowOutcome.Failed -> ScanSound.FAIL
        WorkflowOutcome.HeartbeatOk -> null
    }

    /** [pendingValue] is the scan value still awaiting Station 3's answer, if any. */
    fun forSubmit(raw: String?, canScan: Boolean, pendingValue: String? = null): SubmitDecision {
        val value = WorkflowProtocol.normalizeScan(raw)
        if (!canScan) {
            return if (pendingValue != null && value == pendingValue) SubmitDecision.Repeat else SubmitDecision.Blocked
        }
        return if (value == null) SubmitDecision.Invalid else SubmitDecision.Send(value)
    }

    fun forRead(isRfid: Boolean, data: String?): ReadVerdict = when {
        data == null -> ReadVerdict.IGNORE
        data.trim() == ScannerApp.SETTINGS_RFID -> ReadVerdict.IGNORE
        isRfid && UserTagPolicy.isUserTag(data) -> ReadVerdict.BADGE
        else -> ReadVerdict.TAKE
    }
}
