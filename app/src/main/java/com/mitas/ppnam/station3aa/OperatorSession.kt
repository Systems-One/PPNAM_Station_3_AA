package com.mitas.ppnam.station3aa

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Station 3's workflow tabs as delivered in the login response's `allowedTabs`, mirroring
 * Station 1's StationTab (desktop MQTT_CONTRACT.md §4). Gating fails closed: an operator whose
 * login omits a tab never sees it.
 */
object StationTab {
    /** Source pallet selection for Master Batch labelling. */
    const val MASTER_BATCH = "master_batch"
}

/**
 * The logged-in operator, mirroring Station 2 AA's OperatorSession (data/session). Held in memory
 * only — on process death the operator logs in again, exactly as on Station 2's handheld.
 */
data class OperatorSession(
    val operatorSessionId: String,
    val operatorId: String,
    val operatorName: String,
    /** Display and audit only — never branch authorisation on this. */
    val role: String,
    /** A UI display hint only — the station re-checks every request server-side. */
    val allowedActions: List<String> = emptyList(),
    /** A UI display hint only. */
    val allowedTabs: List<String> = emptyList(),
    val username: String = "",
    /** From `sessionExpiresAtUtc`; null if the station omitted it or sent something unparseable. */
    val expiresAt: Instant? = null,
) {
    /** Past its expiry — the station would reject it, so sign in again without asking. */
    fun isExpired(now: Instant = Instant.now()): Boolean = expiresAt?.let { !now.isBefore(it) } ?: false

    /**
     * Whether to OFFER [tab] in the UI. Presentation only — the station re-checks every
     * request server-side (ACTION_NOT_ALLOWED).
     *
     * Fails CLOSED (docs/Station3_MQTT_Contract_v1.md §4): a login that arrived without
     * allowedTabs, or with an empty list, enables no workflows at all.
     */
    fun canShow(tab: String): Boolean = tab in allowedTabs
}

/**
 * In-memory holder with the same role as Station 2's OperatorSessionHolder, in Station 1's
 * listener idiom (matching MqttManager) rather than Flows.
 */
object OperatorSessionHolder {

    @Volatile
    var session: OperatorSession? = null
        private set

    private val listeners = CopyOnWriteArrayList<(OperatorSession?) -> Unit>()

    /**
     * Why the last session ended when the operator didn't choose it (expiry, station rejection),
     * for the login screen to show once. Null after a deliberate logout.
     */
    @Volatile
    var signedOutReason: String? = null

    fun set(session: OperatorSession) {
        signedOutReason = null
        this.session = session
        listeners.forEach { it(session) }
    }

    fun clear(reason: String? = null) {
        if (session == null) return
        signedOutReason = reason
        session = null
        listeners.forEach { it(null) }
    }

    fun addListener(listener: (OperatorSession?) -> Unit) {
        listeners.add(listener)
        listener(session)
    }

    fun removeListener(listener: (OperatorSession?) -> Unit) {
        listeners.remove(listener)
    }

    fun currentSessionIdOrEmpty(): String = session?.operatorSessionId ?: ""
}
