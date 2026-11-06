package io.modelbridge.app.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * 网关设置，字段与原应用「网关管家」的「我的」页一一对应。
 *
 * 原版把设置项散在 `D.d`（SharedPreferences 包装）里，这里集中成一个
 * 数据类，既方便界面绑定，也方便整包导出/导入。
 */
@Serializable
data class GatewaySettings(
    // ---------- 监听 ----------
    /** 监听端口。 */
    val port: Int = 8080,
    /** 允许局域网访问。true 时监听 0.0.0.0，false 只监听回环。 */
    val allowLan: Boolean = true,
    /** 访问密钥，空表示不校验。 */
    val accessKey: String = "",

    // ---------- 调度 ----------
    /** 单次请求的最大重试次数。 */
    val maxRetries: Int = 2,
    /** 连续失败多少次后进入冷却。 */
    val failureThreshold: Int = 3,
    /** 冷却时长（秒）。 */
    val cooldownSeconds: Int = 30,
    /** 请求超时（秒）。 */
    val requestTimeoutSeconds: Int = 120,
    /** 日志上限（条）。 */
    val logLimit: Int = 500,

    // ---------- 启动 ----------
    /** 开机自启。 */
    val autoStart: Boolean = false,
    /** 显示悬浮球。 */
    val showFloatingBall: Boolean = false,
    /** 悬浮球大小（dp，1–96）。 */
    val floatingBallSizeDp: Int = 48,

    // ---------- 进阶 ----------
    /** 去掉客户端指纹（转发时不带上游不需要的识别头）。 */
    val stripClientFingerprint: Boolean = false,
) {
    /** 有效端口范围。 */
    fun isValid(): Boolean = port in 1..65535
}

/**
 * 设置的持久化与观察。
 *
 * 值和原版一样存在 SharedPreferences 里，但键名统一加 `mb_` 前缀，
 * 避免与其它模块冲突。
 */
class SettingsStore(
    context: Context,
    private val tag: String = "modelbridge_settings",
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(tag, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<GatewaySettings> = _settings.asStateFlow()

    /** 当前值的快照。 */
    fun current(): GatewaySettings = _settings.value

    /** 写入一份完整设置。非法的端口会被拒，保持原值。 */
    fun update(value: GatewaySettings) {
        // 界面已限范围，这里兜底：将来若有别的入口写入，也不会存下非法端口
        val safe = if (value.isValid()) value else value.copy(port = current().port)
        prefs.edit().apply {
            putInt(K_PORT, safe.port)
            putBoolean(K_ALLOW_LAN, safe.allowLan)
            putString(K_ACCESS_KEY, safe.accessKey)
            putInt(K_MAX_RETRIES, safe.maxRetries)
            putInt(K_FAILURE_THRESHOLD, safe.failureThreshold)
            putInt(K_COOLDOWN, safe.cooldownSeconds)
            putInt(K_TIMEOUT, safe.requestTimeoutSeconds)
            putInt(K_LOG_LIMIT, safe.logLimit)
            putBoolean(K_AUTO_START, safe.autoStart)
            putBoolean(K_FLOATING, safe.showFloatingBall)
            putInt(K_FLOATING_SIZE, value.floatingBallSizeDp)
            putBoolean(K_STRIP_FP, safe.stripClientFingerprint)
        }.apply()
        _settings.value = safe
    }

    /** 局部修改。 */
    fun edit(block: (GatewaySettings) -> GatewaySettings) {
        update(block(_settings.value))
    }

    /** 重置为默认值。 */
    fun resetToDefaults() {
        prefs.edit().clear().apply()
        _settings.value = GatewaySettings()
    }

    private fun read(): GatewaySettings {
        val d = GatewaySettings()
        return GatewaySettings(
            port = prefs.getInt(K_PORT, d.port),
            allowLan = prefs.getBoolean(K_ALLOW_LAN, d.allowLan),
            accessKey = prefs.getString(K_ACCESS_KEY, d.accessKey) ?: "",
            maxRetries = prefs.getInt(K_MAX_RETRIES, d.maxRetries),
            failureThreshold = prefs.getInt(K_FAILURE_THRESHOLD, d.failureThreshold),
            cooldownSeconds = prefs.getInt(K_COOLDOWN, d.cooldownSeconds),
            requestTimeoutSeconds = prefs.getInt(K_TIMEOUT, d.requestTimeoutSeconds),
            logLimit = prefs.getInt(K_LOG_LIMIT, d.logLimit),
            autoStart = prefs.getBoolean(K_AUTO_START, d.autoStart),
            showFloatingBall = prefs.getBoolean(K_FLOATING, d.showFloatingBall),
            floatingBallSizeDp = prefs.getInt(K_FLOATING_SIZE, d.floatingBallSizeDp),
            stripClientFingerprint = prefs.getBoolean(K_STRIP_FP, d.stripClientFingerprint),
        )
    }

    private companion object {
        const val K_PORT = "mb_port"
        const val K_ALLOW_LAN = "mb_allow_lan"
        const val K_ACCESS_KEY = "mb_access_key"
        const val K_MAX_RETRIES = "mb_max_retries"
        const val K_FAILURE_THRESHOLD = "mb_failure_threshold"
        const val K_COOLDOWN = "mb_cooldown_seconds"
        const val K_TIMEOUT = "mb_request_timeout"
        const val K_LOG_LIMIT = "mb_log_limit"
        const val K_AUTO_START = "mb_auto_start"
        const val K_FLOATING = "mb_floating_ball"
        const val K_FLOATING_SIZE = "mb_floating_ball_size"
        const val K_STRIP_FP = "mb_strip_fingerprint"
    }
}
