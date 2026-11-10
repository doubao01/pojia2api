package io.modelbridge.providers.account

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 原版「今日已签到」是本地按账号+自然日记录的缓存，不代表服务端状态。
 * 这里把键的生成与判断做成纯 JVM 逻辑，Android 层只负责持久化字符串集合。
 *
 * 静态依据：3.9.3 `p006g/b.java:15-23`（yyyy-MM-dd）、`C0040b.java:125-150`
 * （provider+uid+同年同日）、`dev/pojia2api/App.java:130`（pojia_checkin）。
 */
object AccountDailyCheckin {
    private const val SEPARATOR = "|"
    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    /** 键格式：yyyy-MM-dd|providerId|accountId，便于按日期裁剪。 */
    fun key(providerId: String, accountId: String, nowMillis: Long): String =
        date(nowMillis) + SEPARATOR + providerId + SEPARATOR + accountId

    fun isCheckedIn(
        keys: Set<String>,
        providerId: String,
        accountId: String,
        nowMillis: Long,
    ): Boolean = key(providerId, accountId, nowMillis) in keys

    /** 只保留今天记录，避免长期运行后集合无限增长。 */
    fun prune(keys: Set<String>, nowMillis: Long): Set<String> {
        val today = date(nowMillis) + SEPARATOR
        return keys.filterTo(linkedSetOf()) { it.startsWith(today) }
    }

    fun date(nowMillis: Long): String = synchronized(formatter) {
        formatter.format(Date(nowMillis))
    }

    private val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = zone
    }
}
