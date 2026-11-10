package io.modelbridge.core

/** 3.10.0 账号/上游连通状态的统一文案与判定。 */
object AccountCheckStatus {
    const val OK = "连接正常"
    const val FAIL = "连不上"
    const val PENDING = "检测中…"
    const val UNKNOWN = "未检测"
    const val DISABLED = "未启用"

    /** 带原因的检测结果也统一以「连不上」开头，便于 UI 着色和缓存判定。 */
    fun failure(reason: String): String =
        if (reason.isBlank()) FAIL else "$FAIL：${reason.trim()}"

    fun isReachable(status: String?): Boolean = status == OK

    /** 缓存和批量连通计数只需要布尔结果，不依赖具体失败文案。 */
    fun cachedStatus(reachable: Boolean): String = if (reachable) OK else FAIL
}
