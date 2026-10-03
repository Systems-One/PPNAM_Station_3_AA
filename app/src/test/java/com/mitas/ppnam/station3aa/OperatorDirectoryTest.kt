package com.mitas.ppnam.station3aa

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pre-login operator dropdown parsing (desktop MQTT_CONTRACT.md §4.1). */
class OperatorDirectoryTest {

    private fun list(operators: String) = JSONObject("""{"accepted":true,"operators":$operators}""")

    @Test
    fun `parses username and displayName in station order`() {
        val entries = OperatorDirectory.parse(list("""[{"username":"jsmith","displayName":"J Smith"},{"username":"mdlamini","displayName":"mdlamini"}]"""))
        assertEquals(listOf(OperatorEntry("jsmith", "J Smith"), OperatorEntry("mdlamini", "mdlamini")), entries)
    }

    @Test
    fun `entries without a username are ignored`() {
        val entries = OperatorDirectory.parse(list("""[{"displayName":"Ghost"},{"username":"  ","displayName":"Blank"},{"username":"ok"}]"""))
        assertEquals(listOf("ok"), entries.map { it.username })
    }

    @Test
    fun `empty displayName falls back to username`() {
        val entries = OperatorDirectory.parse(list("""[{"username":"jsmith","displayName":""}]"""))
        assertEquals("jsmith", entries.single().displayName)
    }

    @Test
    fun `an empty or missing list is an empty directory`() {
        assertTrue(OperatorDirectory.parse(list("[]")).isEmpty())
        assertTrue(OperatorDirectory.parse(JSONObject("""{"accepted":true}""")).isEmpty())
    }

    @Test
    fun `dropdown label shows the username only when it differs from the name`() {
        assertEquals("mdlamini", OperatorEntry("mdlamini", "mdlamini").toString())
        assertEquals("J Smith (jsmith)", OperatorEntry("jsmith", "J Smith").toString())
    }

    // --- on-device cache codec (SharedPreferences operator_directory/operators) ---

    @Test
    fun `encode then decode round-trips the list in order`() {
        val list = listOf(OperatorEntry("jsmith", "J Smith"), OperatorEntry("mdlamini", "mdlamini"))
        assertEquals(list, OperatorDirectory.decode(OperatorDirectory.encode(list)))
    }

    @Test
    fun `encode of an empty list decodes to an empty list`() {
        assertEquals("[]", OperatorDirectory.encode(emptyList()))
        assertTrue(OperatorDirectory.decode(OperatorDirectory.encode(emptyList())).isEmpty())
    }

    @Test
    fun `decode tolerates null blank garbage and non-array text`() {
        assertTrue(OperatorDirectory.decode(null).isEmpty())
        assertTrue(OperatorDirectory.decode("").isEmpty())
        assertTrue(OperatorDirectory.decode("   ").isEmpty())
        assertTrue(OperatorDirectory.decode("not json").isEmpty())
        assertTrue(OperatorDirectory.decode("{}").isEmpty())
        assertTrue(OperatorDirectory.decode("[]").isEmpty())
    }

    @Test
    fun `decode drops blank usernames and falls back displayName to username`() {
        val entries = OperatorDirectory.decode(
            """[{"displayName":"Ghost"},{"username":"  ","displayName":"Blank"},{"username":"jsmith","displayName":""},{"username":"ok","displayName":"O K"},"junk",null]"""
        )
        assertEquals(listOf(OperatorEntry("jsmith", "jsmith"), OperatorEntry("ok", "O K")), entries)
    }
}
