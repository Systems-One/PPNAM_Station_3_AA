package com.mitas.ppnam.station3aa

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Station 3's workflow requests (desktop MQTT_CONTRACT.md §7-§9): source selection and the
 * authenticated heartbeat. Sibling of [AuthClient], which owns the schema 4.1 auth family.
 *
 * Every round trip listens on both its own response suffix and res/request_rejected, and only
 * accepts a message that matches this request's deviceId, requestType, requestTs and (for scans)
 * scanValue — so a late answer to an older scan can never be shown as the current source.
 *
 * A SIGN_IN_AGAIN rejection clears the operator session here, centrally; screens observe
 * [OperatorSessionHolder] and return to login.
 */
class WorkflowClient private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val mqtt = MqttManager.getInstance(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** At most one source scan pending per scanner (§4). */
    private val scanInFlight = AtomicBoolean(false)
    private val heartbeatInFlight = AtomicBoolean(false)

    companion object {
        private const val TAG = "WorkflowClient"
        const val REQUEST_TIMEOUT_MS = 10_000L

        @Volatile
        private var INSTANCE: WorkflowClient? = null

        fun getInstance(context: Context): WorkflowClient =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: WorkflowClient(context).also { INSTANCE = it }
            }
    }

    val isScanPending: Boolean get() = scanInFlight.get()

    /**
     * Sends `req/source_scan`. Returns false (and never calls back) if a scan is already pending;
     * otherwise [onResult] fires exactly once on the main thread.
     */
    fun sourceScan(scanValue: String, onResult: (WorkflowOutcome) -> Unit): Boolean {
        if (!scanInFlight.compareAndSet(false, true)) return false
        val session = OperatorSessionHolder.session
        if (session == null) {
            scanInFlight.set(false)
            mainHandler.post { onResult(WorkflowOutcome.Failed("Sign in before scanning")) }
            return true
        }
        val ts = WorkflowProtocol.timestamp()
        val payload = WorkflowProtocol.sourceScanRequest(deviceId(), session.operatorSessionId, scanValue, ts)
        request(WorkflowProtocol.SOURCE_SCAN, WorkflowProtocol.SOURCE_CONTEXT, payload, ts, scanValue) { outcome ->
            scanInFlight.set(false)
            onResult(outcome)
        }
        return true
    }

    /** Sends `req/heartbeat_requested` unless one is already outstanding. */
    fun heartbeat(onResult: (WorkflowOutcome) -> Unit = {}) {
        val session = OperatorSessionHolder.session ?: return
        if (!heartbeatInFlight.compareAndSet(false, true)) return
        val ts = WorkflowProtocol.timestamp()
        val payload = WorkflowProtocol.heartbeatRequest(deviceId(), session.operatorSessionId, ts)
        request(WorkflowProtocol.HEARTBEAT, WorkflowProtocol.HEARTBEAT_RESULT, payload, ts, null) { outcome ->
            heartbeatInFlight.set(false)
            onResult(outcome)
        }
    }

    private fun deviceId(): String = DeviceIdentity.deviceId(appContext)

    private fun request(
        requestType: String,
        responseType: String,
        payload: JSONObject,
        requestTs: String,
        scanValue: String?,
        onResult: (WorkflowOutcome) -> Unit,
    ) {
        if (!mqtt.isConnected()) {
            mainHandler.post { onResult(WorkflowOutcome.Failed("Not connected to the broker")) }
            return
        }

        val device = deviceId()
        val sessionAtSend = payload.optString("operatorSessionId")
        val responseTopic = MqttTopics.deviceResponse(device, responseType)
        val rejectedTopic = MqttTopics.deviceResponse(device, WorkflowProtocol.REQUEST_REJECTED)
        val done = AtomicBoolean(false)

        lateinit var timeoutRunnable: Runnable
        lateinit var onMessage: (Mqtt3Publish) -> Unit

        fun finish(outcome: WorkflowOutcome) {
            if (!done.compareAndSet(false, true)) return
            mainHandler.removeCallbacks(timeoutRunnable)
            mqtt.unsubscribe(responseTopic, onMessage)
            mqtt.unsubscribe(rejectedTopic, onMessage)
            mainHandler.post {
                // Only clear the session this request was sent with — a rejection racing a fresh
                // login must not sign the new operator out.
                if (outcome is WorkflowOutcome.Rejected &&
                    outcome.kind == RejectionKind.SIGN_IN_AGAIN &&
                    OperatorSessionHolder.currentSessionIdOrEmpty() == sessionAtSend
                ) {
                    OperatorSessionHolder.clear(outcome.reason.ifBlank { "Session ended. Sign in again." })
                }
                onResult(outcome)
            }
        }

        timeoutRunnable = Runnable { finish(WorkflowOutcome.TimedOut) }

        onMessage = { publish ->
            val json = try {
                JSONObject(String(publish.payloadAsBytes, StandardCharsets.UTF_8))
            } catch (e: Exception) {
                Log.w(TAG, "Malformed payload on ${publish.topic}")
                null
            }
            if (json != null && WorkflowProtocol.isResponseTo(json, device, requestType, requestTs, scanValue)) {
                finish(toOutcome(WorkflowProtocol.parse(json), requestType))
            }
        }

        mqtt.subscribe(responseTopic, onMessage)
        mqtt.subscribe(rejectedTopic, onMessage)
        mainHandler.postDelayed(timeoutRunnable, REQUEST_TIMEOUT_MS)

        mqtt.publish(MqttTopics.deviceRequest(device, requestType), payload.toString()) { throwable ->
            if (throwable != null) {
                Log.w(TAG, "Publish failed for $requestType", throwable)
                finish(WorkflowOutcome.Failed("Could not reach Station 3"))
            }
        }
    }

    private fun toOutcome(response: WorkflowResponse, requestType: String): WorkflowOutcome = when {
        !response.accepted -> WorkflowOutcome.Rejected(
            errorCode = response.errorCode,
            reason = response.reason.ifBlank { response.errorCode.ifBlank { "Request rejected" } },
            kind = WorkflowProtocol.rejectionKind(response.errorCode),
        )
        requestType == WorkflowProtocol.HEARTBEAT -> WorkflowOutcome.HeartbeatOk
        response.source != null -> WorkflowOutcome.SourceSelected(response.source, response.reason)
        // Accepted but without the required source fields: never show that as a selection.
        else -> WorkflowOutcome.Failed("Station 3 sent an incomplete source")
    }
}

sealed class WorkflowOutcome {
    data class SourceSelected(val source: SourceContext, val reason: String) : WorkflowOutcome()
    object HeartbeatOk : WorkflowOutcome()
    data class Rejected(val errorCode: String, val reason: String, val kind: RejectionKind) : WorkflowOutcome()
    /** No correlated response within 10 s — show the timeout and allow retry (§4). */
    object TimedOut : WorkflowOutcome()
    /** Never left the device, or the response was unusable. */
    data class Failed(val message: String) : WorkflowOutcome()
}
