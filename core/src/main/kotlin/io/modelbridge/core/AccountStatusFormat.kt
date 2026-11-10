package io.modelbridge.core

import java.text.SimpleDateFormat
import java.util.Date
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

    /**
     * 原版 `AccountCenterActivity` 的独立 token 行：
     * 有有效期时显示具体 MM-dd HH:mm；没有有效期时说明时间未知；
     * 已过期但可自动续期时显示原版提示。
     */
    fun tokenLabel(
        expiresAtMillis: Long,
        nowMillis: Long,
        locale: Locale = Locale.getDefault(),
    ): String = when {
        expiresAtMillis <= 0L -> "token 过期时间未知"
        expiresAtMillis <= nowMillis -> "token 已过期，将在使用时自动续期"
        else -> "token 有效期至 " + SimpleDateFormat("MM-dd HH:mm", locale).format(Date(expiresAtMillis))
    }
}
