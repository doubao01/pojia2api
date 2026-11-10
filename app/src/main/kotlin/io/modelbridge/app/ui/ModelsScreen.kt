package io.modelbridge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.modelbridge.app.ViewModels
import io.modelbridge.spi.ModelInfo

/**
 * 模型页：展示当前可见的模型清单。
 *
 * 列表只包含「启用了账号」的供应商贡献的模型，
 * 与网关 `/v1/models` 返回的内容一致。
 *
 * 注意：外层已是可滚动容器，这里用 Column 直接铺开，
 * 不能再嵌 LazyColumn（高度无界会崩）。
 */
@Composable
fun ModelsScreen(vms: ViewModels) {
    val models = vms.models.collectAsState()
    val loading = vms.loadingModels.collectAsState()
    val lastError = vms.modelsError.collectAsState()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "模型目录",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppStyle.BodyText,
                )
                Text(
                    "${models.value.size} 个模型",
                    fontSize = 12.sp,
                    color = AppStyle.SubtitleText,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            OutlinedButton(
                onClick = { vms.refreshModels() },
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("拉取模型", fontSize = 13.sp)
            }
        }

        when {
            models.value.isEmpty() && loading.value -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                        Text(
                            "正在从各供应商拉取模型目录",
                            fontSize = 12.sp,
                            color = AppStyle.SubtitleText,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            models.value.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val accounts = vms.accounts.collectAsState().value
                        Text(
                            text = if (accounts.none { it.enabled }) "还没有登录账号" else "还没有可用模型",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppStyle.BodyText,
                        )
                        Text(
                            text = if (accounts.none { it.enabled }) {
                                "到「供应商」页点「登录账号」添加"
                            } else {
                                "已登录但没有拉到模型，打开「日志」看具体原因"
                            },
                            fontSize = 12.sp,
                            color = AppStyle.SubtitleText,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            else -> {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(AppStyle.CardSpacing),
                ) {
                    models.value.forEach { model -> ModelCard(model) }
                }
                if (loading.value) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    }
                }
            }
        }

        lastError.value?.let { err ->
            Text(
                text = err,
                fontSize = 12.sp,
                color = AppStyle.Accent,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun ModelCard(model: ModelInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppStyle.CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = model.id,
                fontSize = 15.sp,
                color = AppStyle.BodyText,
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
                fontSize = 12.sp,
                color = AppStyle.SubtitleText,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}
