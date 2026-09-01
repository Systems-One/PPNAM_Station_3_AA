package com.mitas.ppnam.station3aa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MQTT base standard §5: a missing or empty allowedTabs list means NO workflows enabled
 * (fail closed). Station 3 defines no workflow tabs yet, so no tab an operator's login can
 * list is ever offered — these tests pin the gating behaviour with sample wire values.
 */
class OperatorSessionTest {

    private fun session(tabs: List<String>) = OperatorSession(
        operatorSessionId = "s1",
        operatorId = "op1",
        operatorName = "Operator One",
        role = "Operator",
        allowedTabs = tabs,
    )

    @Test
    fun `an empty allowedTabs list enables nothing`() {
        val s = session(emptyList())
        assertFalse(s.canShow("tag_assignment"))
        assertFalse(s.canShow("offload"))
    }

    @Test
    fun `a missing allowedTabs list enables nothing`() {
        val s = OperatorSession(
            operatorSessionId = "s1",
            operatorId = "op1",
            operatorName = "Operator One",
            role = "Operator",
        )
        assertFalse(s.canShow("anything"))
    }

    @Test
    fun `only the listed workflows are enabled`() {
        val s = session(listOf("offload"))
        assertFalse(s.canShow("tag_assignment"))
        assertTrue(s.canShow("offload"))
    }

    @Test
    fun `tab matching is exact and case-sensitive`() {
        val s = session(listOf("offload"))
        assertFalse(s.canShow("Offload"))
        assertFalse(s.canShow("offload "))
    }
}
