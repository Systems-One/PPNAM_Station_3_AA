package com.mitas.ppnam.station3aa

import androidx.appcompat.app.AppCompatActivity

/**
 * Base for every screen that can host a signed-in operator: each touch or key press counts as
 * activity for the inactivity auto sign-out. Scanner broadcasts don't pass through
 * onUserInteraction, so receivers call SessionGuard.touch() themselves.
 */
abstract class SessionActivity : AppCompatActivity() {
    override fun onUserInteraction() {
        super.onUserInteraction()
        SessionGuard.touch()
    }
}
