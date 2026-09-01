package com.mitas.ppnam.station3aa

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Station 3 defines no workflow tabs yet. When its workflows are contracted, their wire values
 * (as delivered in the login response's `allowedTabs`) get constants here, mirroring Station 1's
 * StationTab. Until then the fail-closed gating in [OperatorSession.canShow] simply has nothing
 * to enable — every operator sees no workflows.
 */
object StationTab

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
) {
    /**
     * Whether to OFFER [tab] in the UI. Presentation only — the station re-checks every
     * request server-side (ACTION_NOT_ALLOWED).
     *
     * Fails CLOSED (MQTT base standard §5): a login that arrived without allowedTabs, or with an
     * empty list, enables no workflows at all.
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

    fun set(session: OperatorSession) {
        this.session = session
        listeners.forEach { it(session) }
    }

    fun clear() {
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
