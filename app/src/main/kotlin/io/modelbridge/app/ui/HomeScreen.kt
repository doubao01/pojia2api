package io.modelbridge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import io.modelbridge.app.ViewModels
import io.modelbridge.core.LanAddresses
import io.modelbridge.core.StatDisplay

/**
 * 首页：网关概览。
 *
 * 对照原版「首页」的信息结构：运行状态、接入地址、访问密钥、
 * 累计请求 / 成功率 / 平均耗时。
 * 当前数据来自 GatewayRunner 的独立累计统计，按 SharedPreferences 持久化，
 * 日志达到上限不影响统计；接入地址与密钥沿用原版的语义。
 */
@Composable
fun HomeScreen(
    vms: ViewModels,
    onOpenAccounts: () -> Unit = {},
    onOpenPolicies: () -> Unit = {},
    onOpenTasks: () -> Unit = {},
) {
    val running by vms.runner.running.collectAsState()
    val port by vms.runner.port.collectAsState()
    val stats = vms.runner.stats.collectAsState()
    val models by vms.models.collectAsState()
    val settings by vms.settings.collectAsState()
    val accessKeys = buildList {
        if (settings.accessKey.isNotBlank()) add(settings.accessKey)
        addAll(settings.extraAccessKeys.filter { it.isNotBlank() })
    }.distinct()
    val accessKeySummary = when {
        accessKeys.isEmpty() -> "未设置，本机任意客户端可访问"
        accessKeys.size == 1 -> "已设置"
        else -> "已设置 ${accessKeys.size} 个密钥"
    }

    val total = stats.value.total
    val ok = stats.value.success
    val fail = stats.value.failed
    val successRate = stats.value.successRate
    val avgCost = stats.value.averageCostMillis
    val hitRate = stats.value.cacheHitRate
    val (tokenIn, tokenOut) = vms.runner.tokenTotals()

    Column(modifier = Modifier.fillMaxWidth()) {

        // 状态卡 + 启停按钮
        StatusCard(
            running = running,
            port = port,
            models = models.size,
            success = ok,
            failed = fail,
            onToggle = { if (running) vms.stopGateway() else vms.startGateway() },
        )

        // 概览统计：原版 3.9.3 `MainActivity.k()` 是每行两个 key/value。
        SectionTitle("概览")
        InfoCard {
            StatPairRow(
                leftLabel = "累计请求",
                leftValue = StatDisplay.compact(total),
                rightLabel = "成功率",
                rightValue = StatDisplay.percent(successRate),
            )
            StatPairRow(
                leftLabel = "输入 token",
                leftValue = StatDisplay.compact(tokenIn),
                rightLabel = "输出 token",
                rightValue = StatDisplay.compact(tokenOut),
            )
            StatPairRow(
                leftLabel = "缓存命中率",
                leftValue = StatDisplay.percent(hitRate),
                rightLabel = "平均耗时",
                rightValue = if (avgCost > 0) "${avgCost}ms" else "—",
            )
        }

        // 接入信息
        SectionTitle("Agent 接入地址")
        val lanAddress = remember(running, settings.allowLan) {
            if (running && settings.allowLan) LanAddresses.pick(LanAddresses.current()) else null
        }
        val lanUrl = when {
            !running -> "网关已停止"
            !settings.allowLan -> "仅本机可访问"
            else -> LanAddresses.baseUrl(lanAddress, port) ?: "未检测到局域网地址"
        }
        val localUrl = if (running) "http://127.0.0.1:$port/v1" else "网关已停止"
        InfoCard {
            CopyRow("局域网", lanUrl, running && settings.allowLan && lanAddress != null)
            CopyRow("仅本机", localUrl, running)
            InfoRow(
                "访问密钥",
                accessKeySummary,
            )
        }

        SectionTitle("接入说明")
        InfoCard {
            Text(
                text = "在 Agent 里把 API 地址填 $localUrl，API 路径填 /chat/completions（或 /responses、/messages），模型填「上游」页里任一模型 ID，密钥填状态页的访问密钥（未设置则随意）。\n\n" +
                    "模型名带 `@上游昵称` 后缀（如 deepseek-v4.1-flash@猫爪），指定了后缀就只走那一家；不带后缀时由所有拥有该模型的上游按优先级轮流分担。\n\n" +
                    "请求先到达本 App，按模型选中对应本地网关，把命中的策略正文前置到请求里，再转发过去。日志页会记录每次实际命中的上游。",
                fontSize = 13.sp,
                color = AppStyle.SubtitleText,
                lineHeight = 19.sp,
            )
        }

        // 快捷入口，对应原版首页底部的「账号管理 / 任务管理 / 策略管理」
        SectionTitle("管理")
        SettingsGroup {
            ActionRow(
                label = "账号管理",
                hint = "查看所有供应商已登录的账号",
                onClick = onOpenAccounts,
            )
            RowDivider()
            ActionRow(
                label = "任务管理",
                hint = "签到、任务、额度一目了然",
                onClick = onOpenTasks,
            )
            RowDivider()
            ActionRow(
                label = "策略管理",
                hint = "按模型注入正文，转发时前置到请求里",
                onClick = onOpenPolicies,
            )
        }
    }
}

@Composable
private fun StatusCard(
    running: Boolean,
    port: Int,
    models: Int,
    success: Long,
    failed: Long,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppStyle.CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(AppStyle.CardPaddingHorizontal)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(
                            color = if (running) Color(0xFF22C55E) else Color(0xFF9CA3AF),
                            shape = CircleShape,
                        ),
                )
                Text(
                    text = if (running) "网关运行中" else "网关已停止",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppStyle.BodyText,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Text(
                text = buildString {
                    append(if (running) "监听端口 $port" else "点击下方按钮启动")
                    append(" · 可用模型 $models")
                    append(" · 成功 ${StatDisplay.compact(success)}")
                    append(" · 失败 ${StatDisplay.compact(failed)}")
                },
                fontSize = 13.sp,
                color = AppStyle.SubtitleText,
                modifier = Modifier.padding(top = 4.dp),
            )

            Button(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) Color(0xFF6B7280) else AppStyle.Accent,
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(if (running) "停止网关" else "启动网关", fontSize = 15.sp)
            }

            Box(modifier = Modifier.height(14.dp))
        }
    }
}

/**
 * 原版 3.9.3 `MainActivity.k()` 的两列统计行：上方小号灰字，
 * 下方 19sp 粗体数值，左右各占一半。
 */
@Composable
private fun StatPairRow(
    leftLabel: String,
    leftValue: String,
    rightLabel: String,
    rightValue: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
    ) {
        StatValue(label = leftLabel, value = leftValue, modifier = Modifier.weight(1f))
        StatValue(label = rightLabel, value = rightValue, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, fontSize = 12.5.sp, color = AppStyle.SubtitleText)
        Text(
            value,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = AppStyle.BodyText,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = AppStyle.SubtitleText,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppStyle.CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, fontSize = 13.sp, color = AppStyle.SubtitleText)
        Text(
            text = value,
            fontSize = 13.sp,
            color = AppStyle.BodyText,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * 可复制的信息行。
 *
 * 原版首页的接入地址旁有一个「复制」按钮，复制后可直接粘进 Agent 配置。
 */
@Composable
private fun CopyRow(label: String, value: String, copyable: Boolean) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontSize = 13.sp, color = AppStyle.SubtitleText)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                fontSize = 13.sp,
                color = AppStyle.BodyText,
                fontFamily = FontFamily.Monospace,
            )
            if (copyable) {
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(value))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 6.dp,
                    ),
                ) {
                    Text("复制", fontSize = 12.sp)
                }
            }
        }
    }
}
