package com.mitas.ppnam.station3aa

import android.content.Context
import org.json.JSONArray
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
 *
 * The last accepted list is also kept on the device (SharedPreferences `operator_directory`,
 * key `operators`, JSON array — the same layout as Station 1) so the dropdown is populated
 * right after a restart, before or without an answer from the station.
 */
object OperatorDirectory {

    private const val PREFS_NAME = "operator_directory"
    private const val KEY_OPERATORS = "operators"

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

    /** Serialises the list as a JSON array of `{username, displayName}` for the prefs cache. */
    fun encode(list: List<OperatorEntry>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("username", it.username).put("displayName", it.displayName)) }
    }.toString()

    /**
     * Inverse of [encode]. Null, blank, non-JSON or non-array text decodes to an empty list — a
     * corrupt cache must never keep the login screen from appearing. Same row rules as [parse].
     */
    fun decode(text: String?): List<OperatorEntry> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            parse(JSONObject().put("operators", JSONArray(text)))
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Seeds [cached] from the on-device copy. Safe to call more than once. */
    fun load(context: Context) {
        cached = decode(prefs(context).getString(KEY_OPERATORS, null))
    }

    /** Replaces [cached] and the on-device copy. Only called with an accepted list. */
    fun update(context: Context, entries: List<OperatorEntry>) {
        cached = entries
        prefs(context).edit().putString(KEY_OPERATORS, encode(entries)).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
