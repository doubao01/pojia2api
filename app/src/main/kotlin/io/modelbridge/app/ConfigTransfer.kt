package io.modelbridge.app

import android.content.Context
import android.net.Uri
import io.modelbridge.app.prefs.GatewaySettings
import io.modelbridge.core.Account
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 配置的整包导出/导入，对应原版「我的 → 配置」里的
 * 「导出配置 / 导入配置」。
 *
 * 文件格式是 JSON，包含设置与账号两部分：
 * ```json
 * { "version": 1, "settings": {...}, "accounts": [...] }
 * ```
 *
 * 系统文件选择器必须由 Activity 的 result API 启动，这里只负责
 * 组装/解析内容；[ConfigTransferBridge] 是 Activity 注册的回调桥。
 */
class ConfigTransfer(private val context: Context) {

    /**
     * 导出配置：组装 JSON 并唤起保存界面。
     *
     * @return 给界面显示的一句话结果
     */
    fun exportConfig(settings: GatewaySettings, accounts: List<Account>): String {
        val payload = ConfigBundle(
            version = EXPORT_VERSION,
            settings = settings,
            accounts = accounts,
        )
        return runCatching {
            pendingJson = JSON.encodeToString(ConfigBundle.serializer(), payload)
            val launch = ConfigTransferBridge.exportRequest
                ?: return "保存界面未就绪，请重试"
            launch()
            "已打开保存界面，选好位置即可"
        }.getOrElse { "无法打开保存界面：${it.message ?: "未知错误"}" }
    }

    /**
     * 导入配置：唤起文件选择器，读到后回调。
     *
     * @return 给界面显示的一句话结果
     */
    fun importConfig(onLoaded: (ConfigBundle) -> Unit): String {
        return runCatching {
            pendingImport = onLoaded
            val launch = ConfigTransferBridge.importRequest
                ?: return "文件选择器未就绪，请重试"
            launch()
            "已打开文件选择器"
        }.getOrElse { "无法打开文件选择器：${it.message ?: "未知错误"}" }
    }

    companion object {
        const val EXPORT_VERSION = 1

        private val JSON = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        /** 待写入的 JSON，文件选择器返回后由 Activity 取出。 */
        @Volatile
        var pendingJson: String? = null

        /** 待执行的回调。 */
        @Volatile
        var pendingImport: ((ConfigBundle) -> Unit)? = null

        /** 解析导入的内容。 */
        fun parse(raw: String): ConfigBundle? = runCatching {
            JSON.decodeFromString<ConfigBundle>(raw)
        }.getOrNull()
    }
}

/**
 * Activity 与 [ConfigTransfer] 之间的桥。
 *
 * 系统文件选择器只能由 Activity 用 registerForActivityResult 启动，
 * 所以在这里挂两个 lambda，由 MainActivity 在 onCreate 时填入。
 */
object ConfigTransferBridge {
    var exportRequest: (() -> Unit)? = null
    var importRequest: (() -> Unit)? = null
}

/** 导出的整包内容。 */
@Serializable
data class ConfigBundle(
    val version: Int = ConfigTransfer.EXPORT_VERSION,
    val settings: GatewaySettings? = null,
    val accounts: List<Account>? = null,
)

/** 把 Uri 读成字符串。 */
fun readTextFromUri(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
}.getOrNull()

/** 把待导出内容写到 Uri。 */
fun writeTextToUri(context: Context, uri: Uri, text: String): Boolean = runCatching {
    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
    true
}.getOrElse { false }
