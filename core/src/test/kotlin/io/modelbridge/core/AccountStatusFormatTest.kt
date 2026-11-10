package io.modelbridge.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccountStatusFormatTest {
    @Test
    fun `region label maps cn global and domains`() {
        assertEquals("国内版", AccountStatusFormat.regionLabel("cn", "www.codebuddy.cn"))
        assertEquals("国际版", AccountStatusFormat.regionLabel("global", "www.workbuddy.ai"))
        assertEquals("国际版", AccountStatusFormat.regionLabel("", "foo.codebuddy.ai"))
        assertEquals("国内版", AccountStatusFormat.regionLabel("", "example.cn"))
        assertEquals("", AccountStatusFormat.regionLabel("", ""))
    }

    @Test
    fun `enabled label uses original wording`() {
        assertEquals("启用中", AccountStatusFormat.enabledLabel(true))
        assertEquals("未启用", AccountStatusFormat.enabledLabel(false))
    }

    @Test
    fun `expiry label rounds remaining time up to days`() {
        val now = 2_000_000_000_000L
        assertEquals("", AccountStatusFormat.expiryLabel(0, now))
        assertEquals("凭证已失效", AccountStatusFormat.expiryLabel(now - 1, now))
        assertEquals("1 天后到期，自动续期…", AccountStatusFormat.expiryLabel(now + 1, now))
        assertEquals("1 天后到期，自动续期…", AccountStatusFormat.expiryLabel(now + 86_400_000L, now))
        assertEquals("2 天后到期，自动续期…", AccountStatusFormat.expiryLabel(now + 86_400_001L, now))
    }

    @Test
    fun `token label matches account center wording`() {
        val now = 2_000_000_000_000L
        assertEquals("token 过期时间未知", AccountStatusFormat.tokenLabel(0, now))
        assertEquals(
            "token 已过期，将在使用时自动续期",
            AccountStatusFormat.tokenLabel(now - 1, now),
        )
        val formatted = AccountStatusFormat.tokenLabel(now + 60_000L, now, java.util.Locale.US)
        assertTrue(formatted.startsWith("token 有效期至 "))
        assertTrue(Regex("token 有效期至 \\d{2}-\\d{2} \\d{2}:\\d{2}").matches(formatted))
    }
}
