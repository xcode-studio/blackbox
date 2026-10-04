package dev.blackbox

import dev.blackbox.debug.HitCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HitConditionTest {
    private fun hits(condition: String) = (1..10).filter { HitCondition.matches(condition, it) }

    @Test
    fun `follows the tool contract's examples`() {
        assertEquals(listOf(5), hits("5"))
        assertEquals(listOf(8, 9, 10), hits(">= 8"))
        assertEquals(listOf(3, 6, 9), hits("% 3"))
        assertEquals(listOf(1, 2), hits("<3"))
        assertEquals(listOf(4), hits("== 4"))
    }

    @Test
    fun `knows what it can't apply`() {
        assertTrue(HitCondition.isValid(" >= 10 "))
        assertFalse(HitCondition.isValid("i > 3"))
        assertFalse(HitCondition.isValid(""))
        assertEquals(emptyList<Int>(), hits("% 0"))
    }
}
