package io.modelbridge.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StatDisplayTest {
    @Test
    fun `large counts use dex K M B suffixes`() {
        assertEquals("0", StatDisplay.compact(0))
        assertEquals("999", StatDisplay.compact(999))
        assertEquals("1.00K", StatDisplay.compact(1_000))
        assertEquals("1.23K", StatDisplay.compact(1_234))
        assertEquals("1.00M", StatDisplay.compact(1_000_000))
        assertEquals("2.50B", StatDisplay.compact(2_500_000_000L))
    }

    @Test
    fun `non positive or missing rates show dash`() {
        assertEquals("—", StatDisplay.percent(null))
        assertEquals("—", StatDisplay.percent(0.0))
        assertEquals("—", StatDisplay.percent(-1.0))
        assertEquals("98.7%", StatDisplay.percent(98.7))
    }
}
