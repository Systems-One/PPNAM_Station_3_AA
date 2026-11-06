package com.mitas.ppnam.station3aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Suite rule S1 (UI_Design/sounds/README.md) on the Master Batch source select: one sound per
 * scan, on Station 3's answer — success when the source is selected, fail when it is refused,
 * times out, fails to send, or the app refuses the scan locally. The raw scanner read, the
 * Settings shortcut tag and the heartbeat make no sound.
 */
class ScanSoundPolicyTest {

    private val source = SourceContext(
        sourceStationId = "S1",
        sourcePalletId = 42,
        productCode = "PE-100",
        availableWeightKg = 950.0,
    )

    private fun rejected(code: String) =
        WorkflowOutcome.Rejected(code, "reason", WorkflowProtocol.rejectionKind(code))

    // ---- the station's answer ----

    @Test
    fun `source selected sounds success`() {
        assertEquals(ScanSound.SUCCESS, ScanSoundPolicy.forOutcome(WorkflowOutcome.SourceSelected(source, "")))
    }

    @Test
    fun `source not found or unavailable sounds fail`() {
        assertEquals(ScanSound.FAIL, ScanSoundPolicy.forOutcome(rejected("SOURCE_NOT_FOUND")))
        assertEquals(ScanSound.FAIL, ScanSoundPolicy.forOutcome(rejected("SOURCE_UNAVAILABLE")))
    }

    @Test
    fun `station error sounds fail`() {
        assertEquals(ScanSound.FAIL, ScanSoundPolicy.forOutcome(rejected("STATION_UNAVAILABLE")))
        assertEquals(ScanSound.FAIL, ScanSoundPolicy.forOutcome(rejected("INVALID_PAYLOAD")))
        assertEquals(ScanSound.FAIL, ScanSoundPolicy.forOutcome(rejected("SOMETHING_NEW")))
    }

    @Test
    fun `not allowed sounds fail`() {
        assertEquals(ScanSound.FAIL, ScanSoundPolicy.forOutcome(rejected("ACTION_NOT_ALLOWED")))
    }

    @Test
    fun `scan refused for an ended session sounds fail`() {
        assertEquals(ScanSound.FAIL, ScanSoundPolicy.forOutcome(rejected("OPERATOR_SESSION_INVALID")))
    }

    @Test
    fun `timeout sounds fail`() {
        assertEquals(ScanSound.FAIL, ScanSoundPolicy.forOutcome(WorkflowOutcome.TimedOut))
    }

    @Test
    fun `send failure or incomplete source sounds fail`() {
        assertEquals(ScanSound.FAIL, ScanSoundPolicy.forOutcome(WorkflowOutcome.Failed("Not connected to the broker")))
        assertEquals(ScanSound.FAIL, ScanSoundPolicy.forOutcome(WorkflowOutcome.Failed("Station 3 sent an incomplete source")))
    }

    @Test
    fun `heartbeat is not a scan and makes no sound`() {
        assertNull(ScanSoundPolicy.forOutcome(WorkflowOutcome.HeartbeatOk))
    }

    // ---- local refusals before sending ----

    @Test
    fun `a valid scan while scanning is available is sent without a sound yet`() {
        val decision = ScanSoundPolicy.forSubmit(" PAL-0042\n", canScan = true)
        assertEquals(SubmitDecision.Send("PAL-0042"), decision)
        assertNull(decision.sound)
    }

    @Test
    fun `a scan while offline, not allowed or pending is refused locally with a fail`() {
        val decision = ScanSoundPolicy.forSubmit("PAL-0042", canScan = false)
        assertEquals(SubmitDecision.Blocked, decision)
        assertEquals(ScanSound.FAIL, decision.sound)
    }

    @Test
    fun `a different scan while one is pending is refused locally with a fail`() {
        val decision = ScanSoundPolicy.forSubmit("PAL-0043", canScan = false, pendingValue = "PAL-0042")
        assertEquals(SubmitDecision.Blocked, decision)
        assertEquals(ScanSound.FAIL, decision.sound)
    }

    @Test
    fun `a repeat read of the scan in flight is the same scan and makes no sound`() {
        // RFID readers fire several times per trigger; that scan sounds once, on its answer.
        val decision = ScanSoundPolicy.forSubmit(" PAL-0042\n", canScan = false, pendingValue = "PAL-0042")
        assertEquals(SubmitDecision.Repeat, decision)
        assertNull(decision.sound)
    }

    @Test
    fun `an unusable scan value is refused locally with a fail`() {
        val decision = ScanSoundPolicy.forSubmit("   ", canScan = true)
        assertEquals(SubmitDecision.Invalid, decision)
        assertEquals(ScanSound.FAIL, decision.sound)
        assertEquals(SubmitDecision.Invalid, ScanSoundPolicy.forSubmit("x".repeat(501), canScan = true))
    }

    @Test
    fun `a scan the client would not send because another is pending sounds fail`() {
        assertEquals(ScanSound.FAIL, SubmitDecision.Busy.sound)
    }

    // ---- raw reads ----

    @Test
    fun `a raw read the screen takes makes no sound (the scanner already beeped)`() {
        val rfid = ScanSoundPolicy.forRead(isRfid = true, data = "E2801170000002AAAA")
        val barcode = ScanSoundPolicy.forRead(isRfid = false, data = "PAL-0042")
        assertEquals(ReadVerdict.TAKE, rfid)
        assertEquals(ReadVerdict.TAKE, barcode)
        assertNull(rfid.sound)
    }

    @Test
    fun `a user badge is refused locally with a fail`() {
        val read = ScanSoundPolicy.forRead(isRfid = true, data = "505055ABCDEF")
        assertEquals(ReadVerdict.BADGE, read)
        assertEquals(ScanSound.FAIL, read.sound)
    }

    @Test
    fun `the Settings shortcut tag is navigation and makes no sound`() {
        val read = ScanSoundPolicy.forRead(isRfid = true, data = ScannerApp.SETTINGS_RFID)
        assertEquals(ReadVerdict.IGNORE, read)
        assertNull(read.sound)
    }

    @Test
    fun `an empty broadcast makes no sound`() {
        assertEquals(ReadVerdict.IGNORE, ScanSoundPolicy.forRead(isRfid = false, data = null))
    }
}
