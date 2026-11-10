package io.modelbridge.app.prefs

import android.content.Context
import io.modelbridge.providers.account.AccountDailyCheckin

/** 原版 `pojia_checkin` 的等价持久化：按账号与自然日记录本地签到状态。 */
class DailyCheckinStore(
    context: Context,
    tag: String = "modelbridge_daily_checkin",
) {
    private val preferences = context.getSharedPreferences(tag, Context.MODE_PRIVATE)
    private val lock = Any()

    fun keys(nowMillis: Long): Set<String> = synchronized(lock) {
        AccountDailyCheckin.prune(read(), nowMillis)
    }

    fun isCheckedIn(providerId: String, accountId: String, nowMillis: Long): Boolean =
        AccountDailyCheckin.isCheckedIn(keys(nowMillis), providerId, accountId, nowMillis)

    fun markCheckedIn(providerId: String, accountId: String, nowMillis: Long) = synchronized(lock) {
        val next = AccountDailyCheckin.prune(read(), nowMillis).toMutableSet()
        next += AccountDailyCheckin.key(providerId, accountId, nowMillis)
        check(preferences.edit().putStringSet(KEY, next).commit()) { "签到状态无法保存" }
    }

    private fun read(): Set<String> =
        preferences.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    private companion object {
        const val KEY = "checked_in_keys"
    }
}
