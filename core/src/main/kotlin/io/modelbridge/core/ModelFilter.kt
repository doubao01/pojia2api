package io.modelbridge.core

/** 3.10.0 模型页「全部供应商」筛选的纯逻辑，不依赖 Android/Compose。 */
object ModelFilter {
    const val ALL_PROVIDERS = ""

    /** 按供应商筛选；空字符串或 null 表示全部供应商。 */
    fun <T> byProvider(items: List<T>, providerId: String?, providerOf: (T) -> String): List<T> {
        val selected = providerId?.trim().orEmpty()
        if (selected == ALL_PROVIDERS || selected.equals("全部供应商", ignoreCase = false)) return items
        return items.filter { providerOf(it) == selected }
    }

    fun <T> providerIds(items: List<T>, providerOf: (T) -> String): List<String> =
        items.map(providerOf).distinct()

    fun selectedLabel(providerId: String?): String =
        if (providerId.isNullOrBlank() || providerId == ALL_PROVIDERS) "全部供应商" else providerId
}
