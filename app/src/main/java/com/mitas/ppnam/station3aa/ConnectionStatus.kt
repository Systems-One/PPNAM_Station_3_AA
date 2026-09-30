package com.mitas.ppnam.station3aa

/**
 * Mirrors Station 2's ConnectionStatus precedence (broker link, then station presence).
 * No ClockSkewed case here — Station 3's MQTT flow has no request/response round trip
 * to measure clock skew from.
 *
 * BROKER_REJECTED: the broker refused this handheld's credential. Distinct from RECONNECTING
 * because nothing will change until someone fixes the broker login in Settings.
 */
enum class ConnectionStatus { OFFLINE, RECONNECTING, STATION_OFFLINE, CONNECTED, BROKER_REJECTED }
