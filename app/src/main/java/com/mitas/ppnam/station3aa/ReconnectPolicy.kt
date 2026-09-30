package com.mitas.ppnam.station3aa

import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3ConnAckException
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode
import kotlin.random.Random

/**
 * Broker reconnect timing and give-up rules (desktop MQTT_CONTRACT.md §10: Station 1's
 * exponential 1-30 second delay with jitter).
 */
object ReconnectPolicy {

    const val MIN_DELAY_MS = 1_000L
    const val MAX_DELAY_MS = 30_000L

    /**
     * Delay before reconnect attempt number [attempt] (0-based): 1s, 2s, 4s ... capped at 30s,
     * then jittered down by up to 25% so a fleet of scanners doesn't reconnect in lockstep
     * after a broker restart.
     */
    fun delayMs(attempt: Int, random: Random = Random.Default): Long {
        val exponent = attempt.coerceIn(0, 5) // 2^5s = 32s, already past the cap
        val base = (MIN_DELAY_MS shl exponent).coerceAtMost(MAX_DELAY_MS)
        val jitter = (base * 0.25 * random.nextDouble()).toLong()
        return (base - jitter).coerceAtLeast(MIN_DELAY_MS)
    }

    /**
     * True when the broker refused this handheld's credential. Retrying the same credential
     * can never succeed and just hammers the broker — the operator has to fix Settings.
     */
    fun isCredentialRejection(cause: Throwable?): Boolean {
        var t = cause
        while (t != null) {
            if (t is Mqtt3ConnAckException) {
                return t.mqttMessage.returnCode == Mqtt3ConnAckReturnCode.NOT_AUTHORIZED ||
                    t.mqttMessage.returnCode == Mqtt3ConnAckReturnCode.BAD_USER_NAME_OR_PASSWORD
            }
            t = t.cause
        }
        return false
    }
}
