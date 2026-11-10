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
 * 条目数由 [io.modelbridge.app.GatewayRunner] 限制在 500 条，避免无限增长。
 * 外层已是可滚动容器，这里直接把最新 200 条铺成 Column，
 * 不再嵌 LazyColumn。
 */
@Composable
fun LogScreen(vms: ViewModels) {
    val entries = vms.log.collectAsState()

    // 只渲染最近 200 条，既够用又不会让外层滚动卡顿
    val visible = entries.value.takeLast(200).asReversed()

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
            if (entry.costMillis > 0 || entry.message != null) {
                Text(
                    text = entry.message ?: "${entry.costMillis} ms",
                    fontSize = 11.sp,
                    color = if (entry.message != null) AppStyle.Accent else AppStyle.SubtitleText,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
