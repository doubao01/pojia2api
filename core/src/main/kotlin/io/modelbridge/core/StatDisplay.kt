package io.modelbridge.core

import java.util.Locale

/** 3.9.3/3.10.0 `MainActivity.b()` / `h()` 的概览数字格式。 */
object StatDisplay {
    /** 大数字按 1000 / 1e6 / 1e9 分档，保留两位小数；小于 1000 保持整数。 */
    fun compact(value: Long): String = when {
        value >= 1_000_000_000L -> ratio(value, 1_000_000_000.0, "B")
        value >= 1_000_000L -> ratio(value, 1_000_000.0, "M")
        value >= 1_000L -> ratio(value, 1_000.0, "K")
        else -> value.toString()
    }

    /** 百分比为 0 或缺失时按原版显示破折号；否则保留一位小数。 */
    fun percent(value: Double?): String =
        if (value == null || value <= 0.0) "—" else "%.1f%%".format(Locale.US, value)

    private fun ratio(value: Long, unit: Double, suffix: String): String =
        "%.2f$suffix".format(Locale.US, value / unit)
}
