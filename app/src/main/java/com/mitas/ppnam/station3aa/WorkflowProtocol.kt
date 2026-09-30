package com.mitas.ppnam.station3aa

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Station 3's lightweight workflow envelope (desktop MQTT_CONTRACT.md 2.0.0 §4, §7-§9):
 *
 *   req/source_scan          -> res/source_context
 *   req/heartbeat_requested  -> res/heartbeat_result
 *   either                   -> res/request_rejected (envelope/session failures)
 *
 * Requests carry ONLY `ts`, `deviceId`, `operatorSessionId` plus the workflow fields. Unlike the
 * schema 4.1 auth family there is no messageId/schemaVersion/timestampUtc — Station 3 rejects a
 * workflow request carrying any of them as INVALID_PAYLOAD. Responses are correlated on
 * deviceId + requestType + requestTs (+ scanValue for scans), never on a message id.
 */
object WorkflowProtocol {

    const val SOURCE_SCAN = "source_scan"
    const val SOURCE_CONTEXT = "source_context"
    const val HEARTBEAT = "heartbeat_requested"
    const val HEARTBEAT_RESULT = "heartbeat_result"
    const val REQUEST_REJECTED = "request_rejected"

    /** Station 3 caps a scan at 500 characters (ScannerMessageRouter). */
    const val MAX_SCAN_LENGTH = 500

    // Workflow `ts` accepts zero to seven fractional digits; milliseconds keep each request's ts
    // distinct enough to correlate on while staying readable in the desktop's message table.
    private val TS_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun timestamp(instant: Instant = Instant.now()): String = TS_FORMAT.format(instant)

    /**
     * The scan value to send, or null when the station would reject it: blank, too long, or
     * carrying control characters. Surrounding whitespace is trimmed — the station rejects a
     * value that differs from its trimmed form, and scanner wedges commonly append a newline.
     */
    fun normalizeScan(raw: String?): String? {
        val value = raw?.trim() ?: return null
        if (value.isEmpty() || value.length > MAX_SCAN_LENGTH) return null
        if (value.any { it.isISOControl() }) return null
        return value
    }

    fun sourceScanRequest(deviceId: String, operatorSessionId: String, scanValue: String, ts: String): JSONObject =
        base(deviceId, operatorSessionId, ts).put("scanValue", scanValue)

    fun heartbeatRequest(deviceId: String, operatorSessionId: String, ts: String): JSONObject =
        base(deviceId, operatorSessionId, ts)

    private fun base(deviceId: String, operatorSessionId: String, ts: String) = JSONObject()
        .put("ts", ts)
        .put("deviceId", deviceId)
        .put("operatorSessionId", operatorSessionId)

    /**
     * Whether [response] answers exactly the request identified by the other arguments. Late
     * results for an older scan, and results for another scanner, must be ignored (§4).
     * A null [scanValue] (heartbeat) skips that check; the station omits scanValue there.
     */
    fun isResponseTo(
        response: JSONObject,
        deviceId: String,
        requestType: String,
        requestTs: String,
        scanValue: String?,
    ): Boolean {
        if (response.optString("deviceId") != deviceId) return false
        if (response.optString("requestType") != requestType) return false
        if (response.optString("requestTs") != requestTs) return false
        if (scanValue != null && response.optString("scanValue") != scanValue) return false
        return true
    }

    fun parse(response: JSONObject): WorkflowResponse = WorkflowResponse(
        accepted = response.optBoolean("accepted", false),
        errorCode = response.optString("errorCode", ""),
        reason = response.optString("reason", ""),
        nextAction = response.optString("nextAction", ""),
        source = response.optJSONObject("source")?.let { parseSource(it) },
    )

    /** Null when a required field (§7) is missing — an accepted scan without them is unusable. */
    fun parseSource(json: JSONObject): SourceContext? {
        val stationId = json.optString("sourceStationId", "")
        val productCode = json.optString("productCode", "")
        if (stationId.isBlank() || productCode.isBlank()) return null
        if (!json.has("sourcePalletId") || !json.has("availableWeightKg")) return null
        val palletId = json.optLong("sourcePalletId", Long.MIN_VALUE)
        val kg = json.optDouble("availableWeightKg", Double.NaN)
        if (palletId == Long.MIN_VALUE || kg.isNaN()) return null
        return SourceContext(
            sourceStationId = stationId,
            sourcePalletId = palletId,
            productCode = productCode,
            availableWeightKg = kg,
            palletCode = json.optString("palletCode", "").ifBlank { null },
            productDescription = json.optString("productDescription", "").ifBlank { null },
            batch = json.optString("batch", "").ifBlank { null },
        )
    }

    /** What the client must do about a rejection — branched on the exact machine code (§9). */
    fun rejectionKind(errorCode: String): RejectionKind = when (errorCode) {
        "AUTHENTICATION_REQUIRED", "OPERATOR_SESSION_INVALID" -> RejectionKind.SIGN_IN_AGAIN
        "ACTION_NOT_ALLOWED" -> RejectionKind.NOT_ALLOWED
        "SOURCE_NOT_FOUND", "SOURCE_UNAVAILABLE" -> RejectionKind.SOURCE_REJECTED
        "STATION_UNAVAILABLE" -> RejectionKind.RETRY_LATER
        // INVALID_PAYLOAD and any code we don't know: a failure, never an unlock.
        else -> RejectionKind.INVALID_REQUEST
    }
}

data class WorkflowResponse(
    val accepted: Boolean,
    val errorCode: String,
    val reason: String,
    val nextAction: String,
    val source: SourceContext?,
)

/** A selected Station 1 source pallet. All weights are kg (§1). */
data class SourceContext(
    val sourceStationId: String,
    val sourcePalletId: Long,
    val productCode: String,
    val availableWeightKg: Double,
    val palletCode: String? = null,
    val productDescription: String? = null,
    val batch: String? = null,
)

enum class RejectionKind {
    /** Session missing/expired/closed/wrong device — clear it and open login. */
    SIGN_IN_AGAIN,
    /** This user may not scan sources — disable the action, don't retry. */
    NOT_ALLOWED,
    /** The scanned source can't be used — show the reason, allow another scan. */
    SOURCE_REJECTED,
    /** Backend/database trouble — keep unsuccessful, allow retry. */
    RETRY_LATER,
    /** Malformed request or unknown code. */
    INVALID_REQUEST,
}
