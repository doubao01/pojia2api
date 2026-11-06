package io.modelbridge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.modelbridge.app.ViewModels
import io.modelbridge.spi.ModelInfo

/**
 * 模型页：展示当前可见的模型清单。
 *
 * 列表只包含「启用了账号」的供应商贡献的模型，
 * 与网关 `/v1/models` 返回的内容一致。
 */
@Composable
fun ModelsScreen(vms: ViewModels) {
    val models = vms.models.collectAsState()
    val loading = vms.loadingModels.collectAsState()
    val lastError = vms.modelsError.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("模型目录", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${models.value.size} 个可调用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { vms.refreshModels() }) {
                Text("刷新")
            }
        }

        Box(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
            when {
                // 还没有模型，且加载中
                models.value.isEmpty() && loading.value -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Text("正在拉取模型...", style = MaterialTheme.typography.bodySmall)
                    }
                }
                // 没有模型，且不在加载
                models.value.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("还没有可用模型", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "先到「账号」页启用一个供应商",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // 正常模型列表
                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(models.value, key = { it.providerId + "/" + it.id }) { model ->
                            ModelCard(model)
                        }
                    }
                    // 正在刷新的时候，只显示视图右下角的转圈
                    if (loading.value) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }

            // 背景错误
            lastError.value?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun ModelCard(model: ModelInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = model.id,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = buildString {
                    append(model.providerId)
                    if (model.displayName != model.id) append(" · ${model.displayName}")
                    if (model.contextLength > 0) append(" · 上下文 ${model.contextLength}")
                    if (model.supportsTools) append(" · 工具")
                    if (model.supportsReasoning) append(" · 推理")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
