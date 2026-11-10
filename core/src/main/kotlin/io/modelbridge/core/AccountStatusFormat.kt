package io.modelbridge.core

import java.util.Locale
import kotlin.math.ceil

/** 账号管理页展示用的凭据/区域摘要，不访问网络，也不修改账号。 */
object AccountStatusFormat {
    fun regionLabel(region: String, domain: String = ""): String {
        val value = region.trim().lowercase(Locale.ROOT)
        val host = domain.trim().lowercase(Locale.ROOT)
        return when {
            value == "global" || host.contains("workbuddy.ai") || host.contains("codebuddy.ai") -> "国际版"
            value == "cn" || host.isNotBlank() -> "国内版"
            else -> ""
        }
    }

    fun enabledLabel(enabled: Boolean): String = if (enabled) "启用中" else "未启用"

    /** 原版 `AccountCenterActivity` 显示「N 天后到期，自动续期…」。 */
    fun expiryLabel(expiresAtMillis: Long, nowMillis: Long): String {
        if (expiresAtMillis <= 0) return ""
        val remaining = expiresAtMillis - nowMillis
        if (remaining <= 0) return "凭证已失效"
        val days = ceil(remaining / 86_400_000.0).toLong().coerceAtLeast(1)
        return "$days 天后到期，自动续期…"
    }
}
