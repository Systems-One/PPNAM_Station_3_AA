package com.mitas.ppnam.station3aa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Account Management badges start with hex 505055; source/pallet EPCs never do. */
class UserTagPolicyTest {

    @Test
    fun `badge EPC is a user tag`() {
        assertTrue(UserTagPolicy.isUserTag("50505501AABBCCDDEEFF001122334455"))
    }

    @Test
    fun `lower-case and padded badge EPC is a user tag`() {
        assertTrue(UserTagPolicy.isUserTag("  50505501aabbccddeeff001122334455 \r\n"))
    }

    @Test
    fun `pallet and legacy EPCs are not user tags`() {
        assertFalse(UserTagPolicy.isUserTag("E280689400005015ABCD1234"))
        assertFalse(UserTagPolicy.isUserTag("5A39F436A9F28F75D72A24205F22E81A"))
    }

    @Test
    fun `null and blank are not user tags`() {
        assertFalse(UserTagPolicy.isUserTag(null))
        assertFalse(UserTagPolicy.isUserTag(""))
        assertFalse(UserTagPolicy.isUserTag("   "))
    }
}
