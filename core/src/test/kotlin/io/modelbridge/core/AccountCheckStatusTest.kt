package io.modelbridge.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccountCheckStatusTest {
    @Test
    fun `status labels match 3_10_0 dex`() {
        assertEquals("连接正常", AccountCheckStatus.OK)
        assertEquals("连不上", AccountCheckStatus.FAIL)
        assertEquals("检测中…", AccountCheckStatus.PENDING)
        assertEquals("未检测", AccountCheckStatus.UNKNOWN)
        assertEquals("未启用", AccountCheckStatus.DISABLED)
    }

    @Test
    fun `failure keeps the reason but remains recognizable`() {
        assertEquals("连不上", AccountCheckStatus.failure(""))
        assertEquals("连不上：HTTP 401", AccountCheckStatus.failure(" HTTP 401 "))
        assertTrue(AccountCheckStatus.isReachable("连接正常"))
        assertFalse(AccountCheckStatus.isReachable("连不上：HTTP 401"))
        assertEquals("连接正常", AccountCheckStatus.cachedStatus(true))
        assertEquals("连不上", AccountCheckStatus.cachedStatus(false))
    }
}
