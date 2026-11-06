package io.modelbridge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「我的」页与「供应商」页共用的区块组件，样式对照原版的
 * 分组卡片：圆角、白底、1dp 阴影、行间细分隔线。
 */

/** 分组标题（小号灰字），对应原版每组上方的说明文字。 */
@Composable
fun GroupTitle(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = AppStyle.SubtitleText,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    )
}

/** 分组卡片容器。 */
@Composable
fun SettingsGroup(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppStyle.CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 2.dp)) { content() }
    }
}

/** 行间分隔线，左侧留出缩进与原版一致。 */
@Composable
fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 14.dp),
        thickness = 1.dp,
        color = AppStyle.Divider,
    )
}

/**
 * 只读信息行。
 *
 * @param mono 值是否用等宽字体（地址、数字类更整齐）
 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    mono: Boolean = true,
    valueColor: androidx.compose.ui.graphics.Color = AppStyle.SubtitleText,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontSize = 14.sp, color = AppStyle.BodyText)
        Text(
            text = value,
            fontSize = 13.sp,
            color = valueColor,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

/** 开关行。 */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    hint: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = label, fontSize = 14.sp, color = AppStyle.BodyText)
            if (hint != null) {
                Text(
                    text = hint,
                    fontSize = 12.sp,
                    color = AppStyle.SubtitleText,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 可点击行（进入二级页或触发动作）。 */
@Composable
fun ActionRow(
    label: String,
    value: String? = null,
    hint: String? = null,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 14.sp,
                color = if (danger) AppStyle.Accent else AppStyle.BodyText,
            )
            if (hint != null) {
                Text(
                    text = hint,
                    fontSize = 12.sp,
                    color = AppStyle.SubtitleText,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (value != null) {
            Text(
                text = value,
                fontSize = 13.sp,
                color = AppStyle.SubtitleText,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * 数值编辑行：点一下弹出输入框。
 *
 * 原版是在行内直接放 EditText；手机上弹窗输入更不容易误触，
 * 且能统一做范围校验。
 */
@Composable
fun NumberRow(
    label: String,
    value: Int,
    hint: String? = null,
    suffix: String = "",
    range: IntRange,
    onValueChange: (Int) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }

    ActionRow(
        label = label,
        value = "$value$suffix",
        hint = hint,
        onClick = { editing = true },
    )

    if (editing) {
        var text by remember { mutableStateOf(value.toString()) }
        val parsed = text.toIntOrNull()
        val valid = parsed != null && parsed in range
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(label, fontSize = 17.sp) },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter { c -> c.isDigit() } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = text.isNotEmpty() && !valid,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = hint ?: "范围 ${range.first}–${range.last}",
                        fontSize = 12.sp,
                        color = if (text.isNotEmpty() && !valid) AppStyle.Accent else AppStyle.SubtitleText,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        parsed?.let(onValueChange)
                        editing = false
                    },
                    enabled = valid,
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text("取消") }
            },
        )
    }
}

/** 文本编辑行。 */
@Composable
fun TextRow(
    label: String,
    value: String,
    hint: String? = null,
    placeholder: String = "",
    masked: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }

    ActionRow(
        label = label,
        value = when {
            value.isBlank() -> hint ?: "未设置"
            masked -> "已设置"
            else -> value
        },
        onClick = { editing = true },
    )

    if (editing) {
        var text by remember { mutableStateOf(value) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(label, fontSize = 17.sp) },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        placeholder = { Text(placeholder, fontSize = 14.sp) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (hint != null) {
                        Text(
                            text = hint,
                            fontSize = 12.sp,
                            color = AppStyle.SubtitleText,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onValueChange(text.trim())
                    editing = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text("取消") }
            },
        )
    }
}

/** 简单的状态徽标。 */
@Composable
fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = text, fontSize = 11.sp, color = color)
    }
}

/** 固定宽度的间隔，避免重复写 Spacer。 */
@Composable
fun HGap(width: Int) {
    Box(modifier = Modifier.width(width.dp))
}

/**
 * 轻量提示条。
 *
 * 原版用 Android 原生 Toast；Compose 里没有直接的等价物，
 * 这里用一个短暂显示的卡片代替，避免引入 SnackbarHost 的脚手架。
 */
@Composable
fun MessageToast(text: String, onDismiss: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(text) {
        kotlinx.coroutines.delay(2600)
        onDismiss()
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .background(androidx.compose.ui.graphics.Color(0xE6202020), RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(text = text, fontSize = 13.sp, color = androidx.compose.ui.graphics.Color.White)
        }
    }
}
