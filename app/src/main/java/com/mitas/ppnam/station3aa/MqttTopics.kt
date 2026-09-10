package com.mitas.ppnam.station3aa

/**
 * Per-station namespace topic structure per docs/Station3_MQTT_Contract_v1.md §1, which binds
 * the fleet base standard (Andriod/MQTT_BASE_README.md §1) to Station 3's namespace:
 *
 *   PPNAM/station_3                            station presence (retained, LWT)
 *   PPNAM/station_3/{deviceId}                 device presence (retained, LWT)
 *   PPNAM/station_3/{deviceId}/req/{suffix}    request  FROM device TO the station
 *   PPNAM/station_3/{deviceId}/res/{suffix}    response FROM station TO device
 *   PPNAM/station_3/res/{suffix}               station-initiated broadcast
 *
 * Presence lives on each participant's base topic node — there is no /status sub-topic.
 *
 * This app serves Station 3 only, so the station segment is a constant rather than a parameter.
 */
object MqttTopics {

    private const val STATION_BASE = "PPNAM/station_3"

    /** The station's base node — carries its retained online/offline presence payload. */
    fun stationPresence(): String = STATION_BASE

    /** This scanner's base node — carries its retained presence payload and Last Will. */
    fun devicePresence(deviceId: String): String {
        validateSegment(deviceId, "deviceId")
        return "$STATION_BASE/$deviceId"
    }

    fun deviceRequest(deviceId: String, suffix: String): String {
        validateSegment(deviceId, "deviceId")
        validateSegment(suffix, "suffix")
        return "$STATION_BASE/$deviceId/req/$suffix"
    }

    fun deviceResponse(deviceId: String, suffix: String): String {
        validateSegment(deviceId, "deviceId")
        validateSegment(suffix, "suffix")
        return "$STATION_BASE/$deviceId/res/$suffix"
    }

    /** Station-initiated broadcasts — `res` is a reserved segment, never a device id. */
    fun stationBroadcast(suffix: String): String {
        validateSegment(suffix, "suffix")
        return "$STATION_BASE/res/$suffix"
    }

    /** One subscription capturing all of this station's traffic, presence included. */
    fun stationWildcard(): String = "$STATION_BASE/#"

    // The contract forbids '/', '+' and '#' in a topic segment. A segment carrying one of these
    // would silently reshape the topic (or subscribe to a wildcard), so fail loudly instead.
    private fun validateSegment(value: String, name: String) {
        require(value.isNotBlank()) { "$name must not be blank" }
        require(value.none { it == '/' || it == '+' || it == '#' }) {
            "$name must not contain '/', '+' or '#': was '$value'"
        }
    }
}
