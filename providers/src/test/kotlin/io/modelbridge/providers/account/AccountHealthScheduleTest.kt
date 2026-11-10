package io.modelbridge.providers.account

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class AccountHealthScheduleTest {
    @Test
    fun `same provider account is checked once per local day`() {
        var now = utc("2026-11-10 02:30:00")
        val schedule = AccountHealthSchedule { now }

        assertTrue(schedule.evaluate("codebuddy", "a1").due)
        schedule.record("codebuddy", "a1", true, "可用")
        val cached = schedule.evaluate("codebuddy", "a1")

        assertFalse(cached.due)
        assertEquals(true, cached.cached?.reachable)
        assertEquals("可用", cached.cached?.message)
        assertTrue(schedule.evaluate("codebuddy", "a2").due)
        assertTrue(schedule.evaluate("qoder", "a1").due)
    }

    @Test
    fun `a new Asia Shanghai day requires a fresh check and prunes stale rows`() {
        var now = utc("2026-11-09 15:59:00")
        val schedule = AccountHealthSchedule { now }
        schedule.record("codebuddy", "a1", true, "昨日可用")
        schedule.record("qoder", "a2", false, "昨日失败")

        now = utc("2026-11-09 16:01:00")

        assertTrue(schedule.evaluate("codebuddy", "a1").due)
        assertNull(schedule.evaluate("codebuddy", "a1").cached)
        assertEquals(2, schedule.prune())
        assertTrue(schedule.snapshot().isEmpty())
    }

    @Test
    fun `record replaces the previous result for the same account`() {
        var now = utc("2026-11-10 02:30:00")
        val schedule = AccountHealthSchedule { now }
        schedule.record("codebuddy", "a1", true, "可用")

        now += 60_000
        schedule.record("codebuddy", "a1", false, "凭证被拒")

        val health = schedule.snapshot()["codebuddy|a1"]!!
        assertFalse(health.reachable)
        assertEquals("凭证被拒", health.message)
        assertEquals(now, health.checkedAtMillis)
    }

    private fun utc(value: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(value)!!.time
}
