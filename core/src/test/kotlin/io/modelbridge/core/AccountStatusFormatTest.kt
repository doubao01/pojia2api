package io.modelbridge.core

import org.junit.jupiter.api.Assertions.assertEquals
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
}
