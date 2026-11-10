package io.modelbridge.providers.account

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 账号可用性每日检测缓存。
 *
 * 静态依据：3.9.3 `p009j/C0040b.java:24-70,116-145` 以
 * `health/provider/uid` 存储 reachable、检测时间和原因；`Calendar` 比较
 * 同年同日，已检测当天直接复用缓存。此处用 Asia/Shanghai 自然日和
 * provider+accountId 键表达同一语义。
 */
class AccountHealthSchedule(
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val entries = LinkedHashMap<String, AccountHealth>()

    data class AccountHealth(
        val reachable: Boolean,
        val checkedAtMillis: Long,
        val message: String = "",
    )

    data class Decision(val due: Boolean, val cached: AccountHealth? = null)

    fun evaluate(providerId: String, accountId: String): Decision {
        val now = clockMillis()
        val key = "$providerId|$accountId"
        val cached = entries[key]
        if (cached != null && sameLocalDay(cached.checkedAtMillis, now)) {
            return Decision(due = false, cached = cached)
        }
        return Decision(due = true)
    }

    fun record(providerId: String, accountId: String, reachable: Boolean, message: String = "") {
        val now = clockMillis()
        val key = "$providerId|$accountId"
        entries[key] = AccountHealth(reachable, now, message)
        prune(now)
    }

    fun snapshot(): Map<String, AccountHealth> = entries.toMap()

    /** 只保留今日记录，避免进程长期驻留时状态无限增长。 */
    fun prune(nowMillis: Long = clockMillis()): Int {
        val before = entries.size
        val stale = entries.filterValues { value -> !sameLocalDay(value.checkedAtMillis, nowMillis) }
            .keys.toList()
        stale.forEach(entries::remove)
        return before - entries.size
    }

    private fun sameLocalDay(left: Long, right: Long): Boolean =
        synchronized(formatter) {
            formatter.format(Date(left)) == formatter.format(Date(right))
        }

    private companion object {
        private val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }
    }
}
