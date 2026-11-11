package io.modelbridge.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.modelbridge.app.ConfigTransfer
import io.modelbridge.app.ViewModels
import io.modelbridge.app.prefs.GatewaySettings

/**
 * 我的：设置与关于。
 *
 * 分组与条目对照原版「网关管家」的「我的」页，四组：
 * 监听 / 调度 / 启动 / 身份与指纹（进阶）+ 关于。
 */
@Composable
fun SettingsScreen(vms: ViewModels) {
    val settings by vms.settings.collectAsState()
    val context = LocalContext.current
    val transfer = remember { ConfigTransfer(context) }

    var pendingMessage by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {

        // ---------------- 监听 ----------------
        GroupTitle("监听")
        SettingsGroup {
            NumberRow(
                label = "监听端口",
                value = settings.port,
                hint = "网关监听端口，范围 1024–65535",
                range = 1024..65535,
                onValueChange = { vms.updateSettings { s -> s.copy(port = it) } },
            )
            RowDivider()
            SwitchRow(
                label = "允许局域网访问",
                checked = settings.allowLan,
                hint = "开启后监听 0.0.0.0",
                onCheckedChange = { vms.updateSettings { s -> s.copy(allowLan = it) } },
            )
            RowDivider()
            TextRow(
                label = "访问密钥",
                value = settings.accessKey,
                hint = "未设置（不校验）",
                placeholder = "留空表示不校验",
                masked = true,
                maskedLabel = "已启用校验",
                onValueChange = { vms.updateSettings { s -> s.copy(accessKey = it) } },
            )
            RowDivider()
            TextRow(
                label = "+ 添加密钥",
                value = settings.extraAccessKeys.joinToString("\n"),
                hint = "每行一个附加密钥",
                placeholder = "每行一个密钥",
                masked = true,
                multiline = true,
                onValueChange = { value ->
                    vms.updateSettings { s ->
                        s.copy(extraAccessKeys = value.lines().map { it.trim() }.filter { it.isNotEmpty() })
                    }
                },
            )
        }

        // ---------------- 调度 ----------------
        GroupTitle("调度")
        SettingsGroup {
            NumberRow(
                label = "最大重试次数",
                value = settings.maxRetries,
                hint = "最大重试次数，0–10",
                range = 0..10,
                onValueChange = { vms.updateSettings { s -> s.copy(maxRetries = it) } },
            )
            RowDivider()
            NumberRow(
                label = "失败阈值",
                value = settings.failureThreshold,
                hint = "连续失败多少次进入冷却（1–100）",
                range = 1..100,
                onValueChange = { vms.updateSettings { s -> s.copy(failureThreshold = it) } },
            )
            RowDivider()
            NumberRow(
                label = "冷却时长（秒）",
                value = settings.cooldownSeconds,
                suffix = " 秒",
                hint = "冷却时长（秒），1–3600",
                range = 1..3600,
                onValueChange = { vms.updateSettings { s -> s.copy(cooldownSeconds = it) } },
            )
            RowDivider()
            NumberRow(
                label = "请求超时（秒）",
                value = settings.requestTimeoutSeconds,
                suffix = " 秒",
                hint = "请求超时（秒），10–1800",
                range = 10..1800,
                onValueChange = { vms.updateSettings { s -> s.copy(requestTimeoutSeconds = it) } },
            )
            RowDivider()
            NumberRow(
                label = "日志上限",
                value = settings.logLimit,
                suffix = " 条",
                hint = "日志最多保留条数（50–5000）",
                range = 50..5000,
                onValueChange = { vms.updateSettings { s -> s.copy(logLimit = it) } },
            )
        }

        // ---------------- 启动 ----------------
        GroupTitle("启动")
        SettingsGroup {
            SwitchRow(
                label = "开机自启",
                checked = settings.autoStart,
                onCheckedChange = { vms.updateSettings { s -> s.copy(autoStart = it) } },
            )
            RowDivider()
            SwitchRow(
                label = "显示悬浮球",
                checked = settings.showFloatingBall,
                onCheckedChange = { vms.updateSettings { s -> s.copy(showFloatingBall = it) } },
            )
            if (settings.showFloatingBall) {
                RowDivider()
                NumberRow(
                    label = "悬浮球大小",
                    value = settings.floatingBallSizeDp,
                    suffix = " dp",
                    hint = "范围 1–96 dp",
                    range = 1..96,
                    onValueChange = { vms.updateSettings { s -> s.copy(floatingBallSizeDp = it) } },
                )
            }
        }

        // ---------------- 身份与指纹（进阶） ----------------
        GroupTitle("身份与指纹（进阶）")
        SettingsGroup {
            Text(
                "影响上游怎么识别这批请求。默认关闭，打开后才开始清理；被风控盯上时再开。",
                fontSize = 12.5.sp,
                color = AppStyle.SubtitleText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            SwitchRow(
                label = "去掉客户端指纹",
                checked = settings.stripClientFingerprint,
                hint = "去掉客户端指纹",
                onCheckedChange = { enabled ->
                    vms.updateSettings { s -> s.copy(stripClientFingerprint = enabled) }
                    vms.toast(
                        if (enabled) "已开启：请求将去掉客户端身份痕迹"
                        else "已关闭：请求原样透传",
                    )
                },
            )
            Text(
                "只清理对话内容里的客户端痕迹：计费头整行、Claude Code / Codex 身份句、模板句、空模板参数。模型和工具参数不动。",
                fontSize = 12.5.sp,
                color = AppStyle.SubtitleText,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        // ---------------- 配置 ----------------
        GroupTitle("配置")
        SettingsGroup {
            ActionRow(
                label = "导出配置",
                hint = "保存为 JSON 文件",
                onClick = {
                    pendingMessage = transfer.exportConfig(
                        settings = vms.settings.value,
                        accounts = vms.accounts.value,
                        policies = vms.policies.value,
                    )
                },
            )
            RowDivider()
            ActionRow(
                label = "导入配置",
                hint = "从 JSON 文件读取",
                onClick = {
                    pendingMessage = transfer.importConfig { cfg ->
                        cfg.settings?.let { s -> vms.updateSettings { s } }
                        cfg.accounts?.let { vms.importAccounts(it, replace = true) }
                        cfg.policies?.let(vms::importPolicies)
                        val imported = (cfg.accounts?.size ?: 0) +
                            (cfg.policies?.size ?: 0) + (cfg.keys?.size ?: 0)
                        pendingMessage = "已导入 $imported 项"
                    }
                },
            )
            RowDivider()
            ActionRow(
                label = "重置统计",
                hint = "清空访问日志与计数",
                onClick = {
                    vms.runner.resetStats()
                    pendingMessage = "统计已重置"
                },
            )
        }

        // ---------------- 关于 ----------------
        GroupTitle("关于")
        SettingsGroup {
            InfoRow("应用", "网关管家（ModelBridge 重写实现）", mono = false)
            RowDivider()
            InfoRow("版本", ABOUT_VERSION)
            RowDivider()
            InfoRow("作者", ABOUT_AUTHOR, mono = false)
            RowDivider()
            InfoRow("许可证", "Apache-2.0")
            RowDivider()
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "免责声明",
                    fontSize = 13.sp,
                    color = AppStyle.BodyText,
                )
                Text(
                    text = "本应用仅供个人学习与技术研究使用，不提供任何上游服务，也不内置任何模型或策略内容。\n\n" +
                        "上游地址、密钥、模型与策略正文均由使用者自行配置，其来源与合规性由使用者自行负责。使用本应用时须遵守所在地法律法规，以及所用上游服务的条款。\n\n" +
                        "请勿将本应用用于任何违法活动。使用本应用产生的一切后果由使用者自行承担，与作者无关。\n\n" +
                        "本应用只在本机转发，不连接任何第三方服务器。上游是你自己配置的本地网关，策略正文也只存在本机。",
                    fontSize = 12.sp,
                    color = AppStyle.SubtitleText,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        Text(
            text = "本项目是网关管家的自研重写实现，接口与原版兼容。",
            fontSize = 12.sp,
            color = AppStyle.SubtitleText,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 18.dp),
        )

        pendingMessage?.let { msg ->
            MessageToast(msg) { pendingMessage = null }
        }
    }
}

/** 作者署名，与原版一致。 */
private const val ABOUT_AUTHOR = "MT一束挽风"

/** 原版关于页显示的版本号。 */
private const val ABOUT_VERSION = "3.10.0"
