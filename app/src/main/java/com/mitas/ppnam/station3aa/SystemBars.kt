package com.mitas.ppnam.station3aa

import android.app.Activity
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.core.view.WindowCompat

/**
 * Forces light (white) status bar icons, matching this app's always-dark background.
 * enableEdgeToEdge()'s own light/dark heuristic doesn't resolve consistently across every
 * screen, leaving status bar icons unreadable on some activities - this makes it explicit.
 */
fun Activity.forceLightStatusBarIcons() {
    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
}

/** Hides the soft keyboard so the result of a submit (error line, status row) is visible. */
fun Activity.hideKeyboard() {
    val view = currentFocus ?: window.decorView
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow(view.windowToken, 0)
}
