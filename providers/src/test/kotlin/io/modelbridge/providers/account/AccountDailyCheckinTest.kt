package io.modelbridge.providers.account

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class AccountDailyCheckinTest {
    @Test
    fun `same account and local date is idempotent while other accounts stay independent`() {
        val now = utcMillis("2026-11-10 02:30:00")
        val key = AccountDailyCheckin.key("codebuddy", "a1", now)
        val keys = setOf(key)

        assertEquals("2026-11-10|codebuddy|a1", key)
        assertTrue(AccountDailyCheckin.isCheckedIn(keys, "codebuddy", "a1", now))
        assertFalse(AccountDailyCheckin.isCheckedIn(keys, "codebuddy", "a2", now))
        assertFalse(AccountDailyCheckin.isCheckedIn(keys, "qoder", "a1", now))
    }

    @Test
    fun `a new Asia Shanghai calendar day clears yesterday`() {
        val yesterday = utcMillis("2026-11-09 15:59:00")
        val today = utcMillis("2026-11-09 16:01:00")
        val old = AccountDailyCheckin.key("codebuddy", "a1", yesterday)
        val current = AccountDailyCheckin.key("codebuddy", "a1", today)

        assertEquals("2026-11-09|codebuddy|a1", old)
        assertEquals("2026-11-10|codebuddy|a1", current)
        assertFalse(AccountDailyCheckin.isCheckedIn(setOf(old), "codebuddy", "a1", today))
        assertEquals(emptySet<String>(), AccountDailyCheckin.prune(setOf(old), today))
        assertEquals(setOf(current), AccountDailyCheckin.prune(setOf(old, current), today))
    }

    private fun utcMillis(value: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(value)!!.time
}
