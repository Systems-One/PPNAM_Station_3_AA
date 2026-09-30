package com.mitas.ppnam.station3aa

import org.json.JSONObject

/** One entry in the pre-login operator dropdown (desktop MQTT_CONTRACT.md §4.1). */
data class OperatorEntry(val username: String, val displayName: String) {
    /** What the dropdown shows — AutoCompleteTextView renders items with toString(). */
    override fun toString(): String =
        if (displayName == username) username else "$displayName ($username)"
}

/**
 * Parses `res/operator_list` and caches the last good list. The cache is what the contract
 * means by "on timeout/rejection/lookup failure, retain any cached list": a flaky station
 * shouldn't take the dropdown away from an operator who could see it a minute ago.
 */
object OperatorDirectory {

    @Volatile
    var cached: List<OperatorEntry> = emptyList()
        private set

    /** Entries without a username are dropped; an empty displayName falls back to username. */
    fun parse(response: JSONObject): List<OperatorEntry> {
        val array = response.optJSONArray("operators") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val row = array.optJSONObject(i) ?: return@mapNotNull null
            val username = row.optString("username", "").trim()
            if (username.isEmpty()) return@mapNotNull null
            val displayName = row.optString("displayName", "").trim().ifEmpty { username }
            OperatorEntry(username, displayName)
        }
    }

    fun update(entries: List<OperatorEntry>) {
        cached = entries
    }
}
