package com.mitas.ppnam.station3aa

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.TextView

/**
 * One rule for "the operator pressed submit" on a text field (audit S3-04).
 *
 * Gboard sends the IME action id (Done/Go). The C72 keypad and a scanner-wedge suffix send a
 * hardware KEYCODE_ENTER, which reaches the editor-action listener with
 * actionId = IME_ACTION_UNSPECIFIED and a KeyEvent — once for ACTION_DOWN and again for
 * ACTION_UP. Submit on DOWN, consume the UP (otherwise the TextView treats it as "move focus to
 * the next view", which is what the audit saw: focus jumped to the button and nothing was sent).
 */
object EditorActions {

    enum class Decision { SUBMIT, CONSUME, IGNORE }

    private val submitActionIds = setOf(
        EditorInfo.IME_ACTION_DONE,
        EditorInfo.IME_ACTION_GO,
        EditorInfo.IME_ACTION_SEND,
        EditorInfo.IME_ACTION_SEARCH,
    )

    fun decide(actionId: Int, keyCode: Int?, keyAction: Int?, repeatCount: Int = 0): Decision {
        val isEnterKey = keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        return when {
            // A held Enter auto-repeats DOWN events (repeatCount > 0): swallow them, submit once.
            isEnterKey -> if (keyAction == KeyEvent.ACTION_DOWN && repeatCount == 0) Decision.SUBMIT else Decision.CONSUME
            actionId in submitActionIds -> Decision.SUBMIT
            else -> Decision.IGNORE
        }
    }
}

/** Installs the shared submit rule on a field. [action] runs on IME Done/Go or hardware Enter. */
fun TextView.onSubmit(action: () -> Unit) {
    setOnEditorActionListener { _, actionId, event ->
        when (EditorActions.decide(actionId, event?.keyCode, event?.action, event?.repeatCount ?: 0)) {
            EditorActions.Decision.SUBMIT -> { action(); true }
            EditorActions.Decision.CONSUME -> true
            EditorActions.Decision.IGNORE -> false
        }
    }
}
