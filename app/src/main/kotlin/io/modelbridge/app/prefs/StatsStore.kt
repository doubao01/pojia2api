package io.modelbridge.app.prefs

import android.content.Context
import io.modelbridge.core.GatewayCounters

/** 原版 `D.d` 的 SharedPreferences "stats" 等价持久化。 */
class StatsStore(
    context: Context,
    tag: String = "modelbridge_stats",
) {
    private val prefs = context.getSharedPreferences(tag, Context.MODE_PRIVATE)

    fun load(): GatewayCounters.Snapshot =
        GatewayCounters().decode(prefs.getString(KEY, "{}") ?: "{}")

    fun save(snapshot: GatewayCounters.Snapshot) {
        val encoded = GatewayCounters().apply { restore(snapshot) }.encode()
        prefs.edit().putString(KEY, encoded).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "stats"
    }
}
