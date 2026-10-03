package com.mitas.ppnam.station3aa

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Base for every screen that can host a signed-in operator: each touch or key press counts as
 * activity for the inactivity auto sign-out. Scanner broadcasts don't pass through
 * onUserInteraction, so receivers call SessionGuard.touch() themselves.
 */
abstract class SessionActivity : AppCompatActivity() {

    /** Whether a session existed when this screen was created (Settings is reachable without one). */
    protected var signedInAtCreate = false
        private set

    /** Screens that need an operator return true; Settings only when it was opened signed in. */
    protected open fun requiresSession(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        signedInAtCreate = OperatorSessionHolder.session != null
    }

    /**
     * A session cleared while this screen was not resumed (background sign-out) is missed by the
     * screens' session listeners, which only navigate from the foreground, and a Login launch from
     * the Application context is blocked on Android 10+. Catch it on return; Login shows the
     * reason from OperatorSessionHolder.signedOutReason.
     */
    override fun onResume() {
        super.onResume()
        if (!isFinishing && requiresSession() && OperatorSessionHolder.session == null) {
            startActivity(Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        SessionGuard.touch()
    }
}
