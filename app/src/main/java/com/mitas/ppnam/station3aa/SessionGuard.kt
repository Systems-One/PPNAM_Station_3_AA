package com.mitas.ppnam.station3aa

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Process-wide owner of the inactivity auto sign-out (adapted from Station 1's SessionGuard):
 * after the configured minutes without a touch, key press or scan, the operator is signed out
 * and LoginActivity shows why (via OperatorSessionHolder.signedOutReason).
 *
 * Installed once from ScannerApp; activities only ever call [touch]. Navigation is NOT done
 * here: MainActivity and SettingsActivity already observe OperatorSessionHolder and return to
 * Login when the session is cleared while they are resumed — and the deadline can only fire
 * while one of them is resumed, because the Handler runs in the foreground and
 * onActivityResumed re-checks a deadline that passed in the background.
 *
 * Station-offline sign-out is deliberately absent: Station 3 keeps its full-screen overlay.
 */
object SessionGuard {

    private const val TAG = "SessionGuard"

    private lateinit var app: Application
    private val mainHandler = Handler(Looper.getMainLooper())
    private var monitor: InactivityMonitor? = null

    fun install(app: Application) {
        this.app = app
        monitor = InactivityMonitor(
            now = { SystemClock.elapsedRealtime() },
            schedule = { delay, r -> mainHandler.postDelayed(r, delay) },
            cancel = { r -> mainHandler.removeCallbacks(r) },
            onExpired = {
                val minutes = SettingsRepository(app).autoLogoutMinutes()
                signOut(app.resources.getQuantityString(R.plurals.signed_out_inactivity, minutes, minutes))
            },
        )

        // Start/stop the inactivity timer with the session itself.
        OperatorSessionHolder.addListener { session ->
            mainHandler.post { if (session == null) monitor?.stop() else applyTimeout() }
        }

        // A deadline that passed while the app was backgrounded is caught on the next resume.
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { checkNow() }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** Any operator interaction or scanner read. Safe from any thread. */
    fun touch() {
        mainHandler.post { monitor?.touch() }
    }

    fun checkNow() {
        monitor?.checkNow()
    }

    /** (Re)reads the configured timeout; called when a session starts and after Settings saves. */
    fun applyTimeout() {
        if (OperatorSessionHolder.session == null) return
        val minutes = SettingsRepository(app).autoLogoutMinutes()
        monitor?.start(AutoLogout.timeoutMs(minutes))
    }

    /** Idempotent: a second trigger racing the first finds no session and does nothing. */
    fun signOut(reason: String) {
        if (OperatorSessionHolder.session == null) return
        Log.i(TAG, "Signing out: $reason")
        AuthClient(app).logout(reason)
    }
}
