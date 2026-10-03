package com.mitas.ppnam.station3aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Inactivity auto sign-out setting rules, copied from Station 1 (whole minutes, 0 = never). */
class AutoLogoutTest {

    @Test
    fun `default is fifteen minutes`() {
        assertEquals(15, AutoLogout.DEFAULT_MINUTES)
    }

    @Test
    fun `parses whole minutes within range`() {
        assertEquals(0, AutoLogout.parseMinutes("0"))
        assertEquals(15, AutoLogout.parseMinutes(" 15 "))
        assertEquals(1440, AutoLogout.parseMinutes("1440"))
    }

    @Test
    fun `rejects blanks, negatives, decimals and out-of-range values`() {
        assertNull(AutoLogout.parseMinutes(""))
        assertNull(AutoLogout.parseMinutes("-1"))
        assertNull(AutoLogout.parseMinutes("1.5"))
        assertNull(AutoLogout.parseMinutes("1441"))
        assertNull(AutoLogout.parseMinutes("abc"))
    }

    @Test
    fun `timeout in milliseconds, zero means disabled`() {
        assertEquals(0L, AutoLogout.timeoutMs(0))
        assertEquals(0L, AutoLogout.timeoutMs(-3))
        assertEquals(60_000L, AutoLogout.timeoutMs(1))
        assertEquals(900_000L, AutoLogout.timeoutMs(15))
    }
}
