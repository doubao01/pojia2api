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

/**
 * 首页：网关概览。
 *
 * 对照原版「首页」的信息结构：运行状态、接入地址、访问密钥、
 * 累计请求 / 成功率 / 平均耗时。
 * 当前数据来自进程内统计（[io.modelbridge.app.GatewayRunner] 的日志），
 * 重启后清零；接入地址与密钥沿用原版的语义。
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
    val entries by vms.runner.log.collectAsState()
    val models by vms.models.collectAsState()
    val settings by vms.settings.collectAsState()
    val accessKey = settings.accessKey

    val total = entries.size
    val ok = entries.count { it.status in 200..299 }
    val fail = entries.count { it.status >= 400 }
    val successRate = if (total > 0) ok * 100.0 / total else 0.0
    val avgCost = entries.filter { it.costMillis > 0 }
        .let { list -> if (list.isEmpty()) 0L else list.sumOf { it.costMillis } / list.size }
    val hitRate = vms.runner.cacheHitRate()
    val (tokenIn, tokenOut) = vms.runner.tokenTotals()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth()) {

        // 状态卡 + 启停按钮
        StatusCard(
            running = running,
            port = port,
            onToggle = { if (running) vms.stopGateway() else vms.startGateway() },
        )

        // 概览统计
        SectionTitle("概览")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppStyle.CardSpacing),
        ) {
            StatCard("累计请求", total.toString(), Modifier.weight(1f))
            StatCard("成功", ok.toString(), Modifier.weight(1f))
            StatCard("失败", fail.toString(), Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = AppStyle.CardSpacing),
            horizontalArrangement = Arrangement.spacedBy(AppStyle.CardSpacing),
        ) {
            StatCard("成功率", "%.1f%%".format(successRate), Modifier.weight(1f))
            StatCard("平均耗时", if (avgCost > 0) "${avgCost} ms" else "—", Modifier.weight(1f))
            StatCard("可用模型", models.size.toString(), Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = AppStyle.CardSpacing),
            horizontalArrangement = Arrangement.spacedBy(AppStyle.CardSpacing),
        ) {
            StatCard(
                "缓存命中率",
                hitRate?.let { "%.1f%%".format(it) } ?: "—",
                Modifier.weight(1f),
            )
            StatCard("输入 token", tokenIn.toString(), Modifier.weight(1f))
            StatCard("输出 token", tokenOut.toString(), Modifier.weight(1f))
        }

        // 接入信息
        SectionTitle("Agent 接入地址")
        val lanUrl = if (running) "http://本机IP:$port/v1" else "网关已停止"
        val localUrl = if (running) "http://127.0.0.1:$port/v1" else "网关已停止"
        InfoCard {
            CopyRow("局域网", lanUrl, running)
            CopyRow("仅本机", localUrl, running)
            InfoRow(
                "访问密钥",
                accessKey.ifBlank { "未设置，本机任意客户端可访问" },
            )
        }

        SectionTitle("接入说明")
        InfoCard {
            Text(
                text = "在 Agent 里把 API 地址填成上面的地址，模型名用「模型」页里列出的 ID。",
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
                hint = if (running) "签到、任务、额度" else "先启动网关",
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
private fun StatusCard(running: Boolean, port: Int, onToggle: () -> Unit) {
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
                text = if (running) "监听端口 $port" else "点击下方按钮启动",
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

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = AppStyle.CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppStyle.BodyText,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = label,
                fontSize = 12.sp,
                color = AppStyle.SubtitleText,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
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
