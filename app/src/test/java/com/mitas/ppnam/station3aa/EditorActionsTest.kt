package com.mitas.ppnam.station3aa

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import com.mitas.ppnam.station3aa.EditorActions.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One rule for "the operator pressed submit": an IME Done/Go/Send/Search action, or a hardware
 * Enter (what the C72 keypad and a scanner-wedge suffix send). Enter arrives as KEYCODE_ENTER
 * with a DOWN and then an UP event — submit once on DOWN, swallow the UP so the field neither
 * submits twice nor moves focus to the next view.
 */
class EditorActionsTest {

    @Test
    fun `IME Done, Go, Send and Search submit`() {
        assertEquals(Decision.SUBMIT, EditorActions.decide(EditorInfo.IME_ACTION_DONE, null, null))
        assertEquals(Decision.SUBMIT, EditorActions.decide(EditorInfo.IME_ACTION_GO, null, null))
        assertEquals(Decision.SUBMIT, EditorActions.decide(EditorInfo.IME_ACTION_SEND, null, null))
        assertEquals(Decision.SUBMIT, EditorActions.decide(EditorInfo.IME_ACTION_SEARCH, null, null))
    }

    @Test
    fun `IME Next and Unspecified without a key are ignored`() {
        assertEquals(Decision.IGNORE, EditorActions.decide(EditorInfo.IME_ACTION_NEXT, null, null))
        assertEquals(Decision.IGNORE, EditorActions.decide(EditorInfo.IME_ACTION_UNSPECIFIED, null, null))
    }

    @Test
    fun `hardware Enter submits on key down`() {
        assertEquals(
            Decision.SUBMIT,
            EditorActions.decide(EditorInfo.IME_ACTION_UNSPECIFIED, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN),
        )
        assertEquals(
            Decision.SUBMIT,
            EditorActions.decide(EditorInfo.IME_ACTION_UNSPECIFIED, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.ACTION_DOWN),
        )
    }

    @Test
    fun `the matching Enter key up is consumed without a second submit`() {
        assertEquals(
            Decision.CONSUME,
            EditorActions.decide(EditorInfo.IME_ACTION_UNSPECIFIED, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP),
        )
    }

    @Test
    fun `other keys are ignored`() {
        assertEquals(
            Decision.IGNORE,
            EditorActions.decide(EditorInfo.IME_ACTION_UNSPECIFIED, KeyEvent.KEYCODE_TAB, KeyEvent.ACTION_DOWN),
        )
    }
}
