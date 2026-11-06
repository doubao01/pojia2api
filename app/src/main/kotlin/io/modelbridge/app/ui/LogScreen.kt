package io.modelbridge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.modelbridge.app.ViewModels
import io.modelbridge.app.LogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志页：展示网关收到的请求。
 *
 * 列表自动滚动到最新一条；条目数由 [GatewayRunner] 限制，避免无限增长。
 */
@Composable
fun LogScreen(vms: ViewModels) {
    val entries = vms.log.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(entries.value.size) {
        if (entries.value.isNotEmpty()) {
            listState.animateScrollToItem(entries.value.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("访问日志", style = MaterialTheme.typography.titleLarge)
        Text(
            "${entries.value.size} 条（最近 500 条）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(entries.value, key = { it.time.toString() + it.path + it.hashCode() }) { entry ->
                LogCard(entry)
            }
        }
    }
}

@Composable
private fun LogCard(entry: LogEntry) {
    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.time))
    val statusColor = when {
        entry.status in 200..299 -> MaterialTheme.colorScheme.primary
        entry.status in 400..499 -> MaterialTheme.colorScheme.error
        entry.status >= 500 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = time,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "  ${entry.method} ${entry.path}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (entry.status > 0) entry.status.toString() else "-",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = statusColor,
                )
            }
            if (entry.costMillis > 0 || entry.message != null) {
                Text(
                    text = entry.message ?: "${entry.costMillis} ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.message != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
