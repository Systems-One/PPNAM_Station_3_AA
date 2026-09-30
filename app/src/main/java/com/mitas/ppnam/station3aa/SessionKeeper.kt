package com.mitas.ppnam.station3aa

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.time.Instant

/**
 * The scanner's 30-second liveness loop (desktop MQTT_CONTRACT.md §3, §9):
 *
 *  - signed in: send an authenticated `heartbeat_requested` every 30 s — MQTT keepalive alone
 *    is not an application heartbeat, and the desktop marks a scanner stale after 90 s;
 *  - on the login screen: refresh the scanner's own retained `online` presence every 30 s;
 *  - on every (re)connect while signed in: heartbeat immediately, which validates the existing
 *    session — a rejection signs the operator out via [WorkflowClient];
 *  - a session past its `sessionExpiresAtUtc` is cleared locally without a round trip.
 */
class SessionKeeper(context: Context) {

    private val appContext = context.applicationContext
    private val mqtt = MqttManager.getInstance(appContext)
    private val workflow = WorkflowClient.getInstance(appContext)
    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    companion object {
        private const val TAG = "SessionKeeper"
        const val INTERVAL_MS = 30_000L

        /** Pure decision for one tick — kept separate so it can be unit tested. */
        fun decide(connected: Boolean, session: OperatorSession?, now: Instant): Action = when {
            session != null && session.isExpired(now) -> Action.EXPIRE_SESSION
            !connected -> Action.NONE
            session != null -> Action.HEARTBEAT
            else -> Action.REFRESH_PRESENCE
        }
    }

    enum class Action { NONE, HEARTBEAT, REFRESH_PRESENCE, EXPIRE_SESSION }

    private val tick = object : Runnable {
        override fun run() {
            runOnce()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    private val connectionListener: (Boolean) -> Unit = { connected ->
        // Reconnect: restore confidence in the session straight away rather than up to 30 s later.
        if (connected && OperatorSessionHolder.session != null) handler.post { runOnce() }
    }

    private val sessionListener: (OperatorSession?) -> Unit = { session ->
        // A fresh login starts the heartbeat cadence now, so the desktop sees the scanner at once.
        if (session != null) {
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, INTERVAL_MS)
        }
    }

    fun start() {
        if (started) return
        started = true
        mqtt.addConnectionListener(connectionListener)
        OperatorSessionHolder.addListener(sessionListener)
        handler.postDelayed(tick, INTERVAL_MS)
    }

    private fun runOnce() {
        when (decide(mqtt.isConnected(), OperatorSessionHolder.session, Instant.now())) {
            Action.EXPIRE_SESSION -> {
                Log.i(TAG, "Operator session expired")
                OperatorSessionHolder.clear("Your session expired. Sign in again.")
            }
            Action.HEARTBEAT -> workflow.heartbeat { outcome ->
                if (outcome !is WorkflowOutcome.HeartbeatOk) Log.w(TAG, "Heartbeat: $outcome")
            }
            Action.REFRESH_PRESENCE -> mqtt.refreshPresence()
            Action.NONE -> Unit
        }
    }
}
