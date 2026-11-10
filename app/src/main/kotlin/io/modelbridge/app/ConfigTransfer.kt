package io.modelbridge.app

import android.content.Context
import android.net.Uri
import io.modelbridge.app.prefs.GatewaySettings
import io.modelbridge.core.Account
import io.modelbridge.core.AccountArchive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.decodeFromJsonElement

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
    fun exportConfig(
        settings: GatewaySettings,
        accounts: List<Account>,
        policies: List<io.modelbridge.core.policy.Policy> = emptyList(),
    ): String {
        val payload = ConfigBundle(
            version = EXPORT_VERSION,
            settings = settings,
            accounts = accounts,
            policies = policies,
            keys = exportKeys(settings),
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

    /**
     * 只导出账号，对应供应商页的「导出账号」。
     */
    fun exportAccounts(accounts: List<Account>): String {
        return runCatching {
            pendingJson = AccountArchive.export(accounts)
            val launch = ConfigTransferBridge.exportRequest
                ?: return "保存界面未就绪，请重试"
            launch()
            "已打开保存界面，选好位置即可"
        }.getOrElse { "无法打开保存界面：${it.message ?: "未知错误"}" }
    }

    /**
     * 只导入账号，对应供应商页的「导入账号」。
     *
     * @param onLoaded 解析成功后的回调，只带 accounts 部分
     */
    fun importAccounts(onLoaded: (List<Account>) -> Unit): String {
        return runCatching {
            pendingImport = { bundle -> onLoaded(bundle.accounts.orEmpty()) }
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

        /** 解析导入的内容：兼容整包配置、原版 upstreams/keys 配置、账号数组和单账号对象。 */
        fun parse(raw: String): ConfigBundle? = runCatching {
            val root = JSON.parseToJsonElement(raw)
            if (root is JsonObject && root.containsKey("upstreams") && !root.containsKey("settings")) {
                return@runCatching parseLegacyConfig(root)
            }
            val containsConfigFields = root is JsonObject &&
                (root.containsKey("settings") || root.containsKey("policies") ||
                    root.containsKey("keys") || root["version"] != null)
            val bundle = if (containsConfigFields) {
                JSON.decodeFromJsonElement<ConfigBundle>(root)
            } else {
                ConfigBundle(accounts = AccountArchive.parseAccounts(raw))
            }
            val keys = (root as? JsonObject)?.get("keys") as? JsonArray
            if (keys != null) mergeKeys(bundle, keys) else bundle
        }.getOrNull()

        /** 原版整包配置把它翻译成 ModelBridge 的设置/账号/策略/密钥结构。 */
        private fun parseLegacyConfig(root: JsonObject): ConfigBundle {
            val keyItems = root["keys"] as? JsonArray ?: JsonArray(emptyList())
            val enabledSecrets = enabledKeySecrets(keyItems)
            val settings = GatewaySettings(
                accessKey = enabledSecrets.firstOrNull().orEmpty(),
                extraAccessKeys = enabledSecrets.drop(1),
            )
            val accounts = (root["upstreams"] as? JsonArray)
                ?.mapNotNull { AccountArchive.parseLegacyUpstream(it) }
                .orEmpty()
            val policies = (root["profiles"] as? JsonArray)
                ?.mapNotNull { AccountArchive.parseLegacyProfile(it) }
                .orEmpty()
            return ConfigBundle(
                settings = settings,
                accounts = accounts.takeIf { it.isNotEmpty() },
                policies = policies.takeIf { it.isNotEmpty() },
                keys = keyItems,
                upstreams = root["upstreams"] as? JsonArray,
                profiles = root["profiles"] as? JsonArray,
            )
        }

        /** 把原版 keys 数组里的启用 secret 合并进简化版 GatewaySettings。 */
        private fun mergeKeys(bundle: ConfigBundle, keys: JsonArray): ConfigBundle {
            val enabled = enabledKeySecrets(keys)
            if (enabled.isEmpty()) return bundle
            val settings = (bundle.settings ?: GatewaySettings()).copy(
                accessKey = enabled.first(),
                extraAccessKeys = (enabled.drop(1) + bundle.settings?.extraAccessKeys.orEmpty())
                    .distinct(),
            )
            return bundle.copy(settings = settings, keys = keys)
        }

        private fun enabledKeySecrets(keys: JsonArray): List<String> = keys.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            if (obj["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == false) {
                return@mapNotNull null
            }
            obj["secret"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
        }.distinct()
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

/** 导出的整包内容；keys 保留原版 id/name/secret/enabled 结构。 */
@Serializable
data class ConfigBundle(
    val version: Int = ConfigTransfer.EXPORT_VERSION,
    val settings: GatewaySettings? = null,
    val accounts: List<Account>? = null,
    val policies: List<io.modelbridge.core.policy.Policy>? = null,
    val keys: JsonArray? = null,
    /** 原版整包配置中的自定义上游与策略正文，保留原样以便后续迁移/审计。 */
    val upstreams: JsonArray? = null,
    val profiles: JsonArray? = null,
)

/** 把简化设置里的主密钥和附加密钥导出为原版 keys 数组。 */
fun exportKeys(settings: GatewaySettings): JsonArray = JsonArray(
    buildList<JsonObject> {
        settings.accessKey.takeIf { it.isNotBlank() }?.let { add(keyObject("key-main", "密钥 1", it)) }
        settings.extraAccessKeys.forEachIndexed { index, secret ->
            add(keyObject("key-extra-${index + 1}", "密钥 ${index + 2}", secret))
        }
    },
)

private fun keyObject(id: String, name: String, secret: String): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name)
    put("secret", secret)
    put("enabled", true)
}

/** 把 Uri 读成字符串。 */
fun readTextFromUri(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
}.getOrNull()

/** 把待导出内容写到 Uri。 */
fun writeTextToUri(context: Context, uri: Uri, text: String): Boolean = runCatching {
    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
    true
}.getOrElse { false }
