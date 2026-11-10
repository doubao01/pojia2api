package io.modelbridge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.modelbridge.app.LogEntry
import io.modelbridge.app.ViewModels
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志页：展示网关收到的请求。
 *
 * 条目数由 [io.modelbridge.app.GatewayRunner] 按设置的日志上限保留，
 * 界面按 3.10.0 `MainActivity.n()` 渲染最近 30 条，避免无限增长。
 */
@Composable
fun LogScreen(vms: ViewModels) {
    val entries = vms.log.collectAsState()

    // 原版 MainActivity.n() 只把最近 30 条拼成 HTML 显示
    val visible = entries.value.takeLast(30).asReversed()

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "访问日志",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppStyle.BodyText,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                "最近 ${entries.value.size} 条 · 新的在上",
                fontSize = 12.sp,
                color = AppStyle.SubtitleText,
                modifier = Modifier.padding(top = 2.dp),
            )
            OutlinedButton(
                onClick = { vms.runner.clearLog() },
                enabled = entries.value.isNotEmpty(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
            ) {
                Text("清空", fontSize = 13.sp)
            }
        }

        if (visible.isEmpty()) {
            Text(
                text = "暂无日志",
                fontSize = 13.sp,
                color = AppStyle.SubtitleText,
                modifier = Modifier.padding(top = 32.dp),
            )
        } else {
            Column(
                modifier = Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                visible.forEach { entry -> LogCard(entry) }
            }
        }
    }
}

/** 3.10.0 `C/M.java` 日志级别：INFO/WARN/ERROR 分别显示 I/W/E。 */
private enum class LogLevel(val label: String) {
    Info("I"),
    Warn("W"),
    Error("E");

    companion object {
        fun label(status: Int): String = when {
            status in 200..299 -> Info.label
            status in 300..399 || status == 0 -> Warn.label
            else -> Error.label
        }
    }
}

@Composable
private fun LogCard(entry: LogEntry) {
    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.time))
    val statusColor = when {
        entry.status in 200..299 -> AppStyle.BodyText
        entry.status >= 400 -> AppStyle.Accent
        else -> AppStyle.SubtitleText
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppStyle.CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = time,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = AppStyle.SubtitleText,
                )
                Text(
                    text = "  ${entry.method} ${entry.path}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = AppStyle.BodyText,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (entry.status > 0) entry.status.toString() else "-",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = statusColor,
                )
            }
            val details = buildList {
                add(LogLevel.label(entry.status))
                if (entry.costMillis > 0) add("${entry.costMillis} ms")
                entry.upstreamId?.takeIf { it.isNotBlank() }?.let { add("上游：$it") }
                entry.message?.takeIf { it.isNotBlank() }?.let { add(it) }
                if (entry.promptTokens > 0 || entry.completionTokens > 0 || entry.cachedTokens > 0) {
                    add("输入 ${entry.promptTokens} · 输出 ${entry.completionTokens} · 缓存 ${entry.cachedTokens}")
                }
            }
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString(" · "),
                    fontSize = 11.sp,
                    color = AppStyle.SubtitleText,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
