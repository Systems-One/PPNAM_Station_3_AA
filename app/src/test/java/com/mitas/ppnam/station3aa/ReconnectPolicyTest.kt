package com.mitas.ppnam.station3aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Station 1-style 1-30 s exponential reconnect with jitter (desktop MQTT_CONTRACT.md §10). */
class ReconnectPolicyTest {

    /** A Random whose nextDouble() is fixed, to pin jitter at its extremes. */
    private class FixedRandom(private val value: Double) : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextDouble(): Double = value
    }

    @Test
    fun `without jitter the delay doubles from 1s and caps at 30s`() {
        val none = FixedRandom(0.0)
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L),
            (0..7).map { ReconnectPolicy.delayMs(it, none) },
        )
    }

    @Test
    fun `jitter only shortens the delay, by at most a quarter, never below 1s`() {
        val max = FixedRandom(0.999_999)
        assertEquals(1_000L, ReconnectPolicy.delayMs(0, max))
        val capped = ReconnectPolicy.delayMs(10, max)
        assertTrue(capped in 22_500L..30_000L)
    }

    @Test
    fun `huge attempt counts do not overflow`() {
        assertEquals(30_000L, ReconnectPolicy.delayMs(Int.MAX_VALUE, FixedRandom(0.0)))
    }

    @Test
    fun `ordinary failures are not treated as a credential rejection`() {
        assertFalse(ReconnectPolicy.isCredentialRejection(null))
        assertFalse(ReconnectPolicy.isCredentialRejection(java.net.UnknownHostException("mqtt.sysone.co.za")))
        assertFalse(ReconnectPolicy.isCredentialRejection(RuntimeException("wrapped", java.io.IOException("reset"))))
    }
}
