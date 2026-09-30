package com.mitas.ppnam.station3aa

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Station 3 workflow envelope, pinned to the desktop's MQTT_CONTRACT.md 2.0.0 §4, §7-§9 and to
 * what ScannerMessageRouter actually accepts.
 */
class WorkflowProtocolTest {

    private val device = "scanner_5c64df8d86a8"
    private val ts = "2026-09-25T06:10:00.123Z"

    @Test
    fun `timestamp is UTC with millisecond precision and trailing Z`() {
        assertEquals("2026-09-25T06:10:00.123Z", WorkflowProtocol.timestamp(Instant.parse("2026-09-25T06:10:00.123456Z")))
        assertEquals("2026-09-25T06:10:00.000Z", WorkflowProtocol.timestamp(Instant.parse("2026-09-25T06:10:00Z")))
    }

    @Test
    fun `source scan request carries exactly ts deviceId session and scanValue`() {
        val req = WorkflowProtocol.sourceScanRequest(device, "sess-1", "PALLET-BARCODE-001", ts)
        assertEquals(setOf("ts", "deviceId", "operatorSessionId", "scanValue"), req.keySet())
        assertEquals(ts, req.getString("ts"))
        assertEquals(device, req.getString("deviceId"))
        assertEquals("sess-1", req.getString("operatorSessionId"))
        assertEquals("PALLET-BARCODE-001", req.getString("scanValue"))
    }

    @Test
    fun `heartbeat request carries only the workflow envelope`() {
        val req = WorkflowProtocol.heartbeatRequest(device, "sess-1", ts)
        assertEquals(setOf("ts", "deviceId", "operatorSessionId"), req.keySet())
    }

    @Test
    fun `workflow requests never carry auth envelope fields the station rejects`() {
        val req = WorkflowProtocol.sourceScanRequest(device, "sess-1", "X", ts)
        listOf("messageId", "schemaVersion", "timestampUtc", "correlationKey").forEach {
            assertFalse("$it must be absent", req.has(it))
        }
    }

    @Test
    fun `normalizeScan trims a scanner wedge newline`() {
        assertEquals("PALLET-001", WorkflowProtocol.normalizeScan("  PALLET-001\n"))
    }

    @Test
    fun `normalizeScan rejects blank, oversize and control characters`() {
        assertNull(WorkflowProtocol.normalizeScan(null))
        assertNull(WorkflowProtocol.normalizeScan("   "))
        assertNull(WorkflowProtocol.normalizeScan("A".repeat(501)))
        assertNull(WorkflowProtocol.normalizeScan("PAL\u0000LET"))
        assertEquals(500, WorkflowProtocol.normalizeScan("A".repeat(500))!!.length)
    }

    private fun sourceResponse(
        deviceId: String = device,
        requestType: String = "source_scan",
        requestTs: String = ts,
        scanValue: String = "PALLET-BARCODE-001",
    ) = JSONObject("""
        {"ts":"2026-09-25T06:10:00.223000Z","deviceId":"$deviceId","requestType":"$requestType",
         "requestTs":"$requestTs","scanValue":"$scanValue","accepted":true,
         "nextAction":"create_labels_on_desktop","reason":"Source selected.",
         "source":{"sourceStationId":"1","sourcePalletId":101,"palletCode":"PALLET-001",
                   "productCode":"1500000001","productDescription":"MASTERBATCH BLACK",
                   "batch":"BATCH-001","availableWeightKg":24.5}}
    """.trimIndent())

    @Test
    fun `response correlates on device, type, requestTs and scanValue`() {
        assertTrue(WorkflowProtocol.isResponseTo(sourceResponse(), device, "source_scan", ts, "PALLET-BARCODE-001"))
    }

    @Test
    fun `a late result for an older scan is not correlated`() {
        assertFalse(WorkflowProtocol.isResponseTo(sourceResponse(requestTs = "2026-09-25T06:09:00.000Z"), device, "source_scan", ts, "PALLET-BARCODE-001"))
        assertFalse(WorkflowProtocol.isResponseTo(sourceResponse(scanValue = "OTHER"), device, "source_scan", ts, "PALLET-BARCODE-001"))
    }

    @Test
    fun `a result for another scanner or request type is not correlated`() {
        assertFalse(WorkflowProtocol.isResponseTo(sourceResponse(deviceId = "scanner_other"), device, "source_scan", ts, "PALLET-BARCODE-001"))
        assertFalse(WorkflowProtocol.isResponseTo(sourceResponse(requestType = "heartbeat_requested"), device, "source_scan", ts, "PALLET-BARCODE-001"))
    }

    @Test
    fun `heartbeat correlation ignores the absent scanValue`() {
        val hb = JSONObject("""{"deviceId":"$device","requestType":"heartbeat_requested","requestTs":"$ts","accepted":true,"nextAction":"ready"}""")
        assertTrue(WorkflowProtocol.isResponseTo(hb, device, "heartbeat_requested", ts, null))
    }

    @Test
    fun `accepted source context parses all fields in kg`() {
        val parsed = WorkflowProtocol.parse(sourceResponse())
        assertTrue(parsed.accepted)
        assertEquals(
            SourceContext("1", 101, "1500000001", 24.5, "PALLET-001", "MASTERBATCH BLACK", "BATCH-001"),
            parsed.source,
        )
    }

    @Test
    fun `optional source fields are null when omitted`() {
        val source = WorkflowProtocol.parseSource(JSONObject("""{"sourceStationId":"1","sourcePalletId":7,"productCode":"P","availableWeightKg":0}"""))
        assertNotNull(source)
        assertNull(source!!.palletCode)
        assertNull(source.productDescription)
        assertNull(source.batch)
    }

    @Test
    fun `a source missing a required field is unusable`() {
        assertNull(WorkflowProtocol.parseSource(JSONObject("""{"sourceStationId":"1","sourcePalletId":7,"availableWeightKg":1}""")))
        assertNull(WorkflowProtocol.parseSource(JSONObject("""{"sourceStationId":"1","productCode":"P","availableWeightKg":1}""")))
        assertNull(WorkflowProtocol.parseSource(JSONObject("""{"sourceStationId":"1","sourcePalletId":7,"productCode":"P"}""")))
    }

    @Test
    fun `business rejection has no source and keeps the machine code`() {
        val parsed = WorkflowProtocol.parse(JSONObject("""
            {"deviceId":"$device","requestType":"source_scan","requestTs":"$ts","scanValue":"X","accepted":false,
             "errorCode":"SOURCE_UNAVAILABLE","reason":"Source stock is empty or stale.","nextAction":"scan_source"}
        """.trimIndent()))
        assertFalse(parsed.accepted)
        assertNull(parsed.source)
        assertEquals("SOURCE_UNAVAILABLE", parsed.errorCode)
        assertEquals("Source stock is empty or stale.", parsed.reason)
    }

    @Test
    fun `rejection kinds follow the contract's error table`() {
        assertEquals(RejectionKind.SIGN_IN_AGAIN, WorkflowProtocol.rejectionKind("AUTHENTICATION_REQUIRED"))
        assertEquals(RejectionKind.SIGN_IN_AGAIN, WorkflowProtocol.rejectionKind("OPERATOR_SESSION_INVALID"))
        assertEquals(RejectionKind.NOT_ALLOWED, WorkflowProtocol.rejectionKind("ACTION_NOT_ALLOWED"))
        assertEquals(RejectionKind.SOURCE_REJECTED, WorkflowProtocol.rejectionKind("SOURCE_NOT_FOUND"))
        assertEquals(RejectionKind.SOURCE_REJECTED, WorkflowProtocol.rejectionKind("SOURCE_UNAVAILABLE"))
        assertEquals(RejectionKind.RETRY_LATER, WorkflowProtocol.rejectionKind("STATION_UNAVAILABLE"))
        assertEquals(RejectionKind.INVALID_REQUEST, WorkflowProtocol.rejectionKind("INVALID_PAYLOAD"))
    }

    @Test
    fun `codes are exact - lowercase or unknown codes never map to a success path`() {
        assertEquals(RejectionKind.INVALID_REQUEST, WorkflowProtocol.rejectionKind("operator_session_invalid"))
        assertEquals(RejectionKind.INVALID_REQUEST, WorkflowProtocol.rejectionKind("SOMETHING_NEW"))
        assertEquals(RejectionKind.INVALID_REQUEST, WorkflowProtocol.rejectionKind(""))
    }
}
