package com.mitas.ppnam.station3aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Suite rule S3 (UI_Design/sounds/README.md): one trigger pull fires several reads of the same
 * value. The same value refused locally again within 1.5 s is the same scan and plays nothing;
 * a different value is a new scan and plays fail once.
 */
class LocalRefusalDebounceTest {

    private var now = 10_000L
    private val debounce = LocalRefusalDebounce(nowMs = { now })

    @Test
    fun `the first local refusal plays fail`() {
        assertEquals(ScanSound.FAIL, debounce.refuse("505055ABCDEF"))
    }

    @Test
    fun `repeat reads of the same refused value within 1_5 s play nothing`() {
        assertEquals(ScanSound.FAIL, debounce.refuse("505055ABCDEF"))
        now += 100
        assertNull(debounce.refuse("505055ABCDEF"))
        now += 400
        assertNull(debounce.refuse(" 505055ABCDEF\n"))
    }

    @Test
    fun `a held trigger keeps repeating silently, each repeat extending the window`() {
        assertEquals(ScanSound.FAIL, debounce.refuse("505055ABCDEF"))
        repeat(10) {
            now += 1_000
            assertNull(debounce.refuse("505055ABCDEF"))
        }
    }

    @Test
    fun `the same value refused again after 1_5 s of quiet is a new scan and plays fail`() {
        assertEquals(ScanSound.FAIL, debounce.refuse("505055ABCDEF"))
        now += 1_500
        assertEquals(ScanSound.FAIL, debounce.refuse("505055ABCDEF"))
    }

    @Test
    fun `a different value is a new scan and plays fail once`() {
        assertEquals(ScanSound.FAIL, debounce.refuse("505055ABCDEF"))
        now += 100
        assertEquals(ScanSound.FAIL, debounce.refuse("E2801170000099"))
        now += 100
        assertNull(debounce.refuse("E2801170000099"))
        // Going back to the first value is a new scan again.
        assertEquals(ScanSound.FAIL, debounce.refuse("505055ABCDEF"))
    }
}
