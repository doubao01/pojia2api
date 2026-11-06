package io.modelbridge.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.modelbridge.app.ViewModels

/**
 * 我的：设置与关于。
 *
 * 原版这一页放的是「策略库」（按模型注入正文后转发）与一些偏好项。
 * ModelBridge 的策略功能尚未实现，这里先把已有能力收拢成设置项，
 * 并明确标注未实现的部分，避免给出点了没反应的入口。
 */
@Composable
fun SettingsScreen(vms: ViewModels) {
    val running by vms.runner.running.collectAsState()
    val port by vms.runner.port.collectAsState()
    val models by vms.models.collectAsState()
    val groups by vms.providerGroups.collectAsState()

    val enabledProviders = groups.count { it.anyEnabled }

    Column(modifier = Modifier.fillMaxWidth()) {

        SectionTitle("当前配置")
        SettingsCard {
            SettingRow("网关端口", if (running) port.toString() else "—")
            Divider()
            SettingRow("启用的供应商", "$enabledProviders / ${groups.size}")
            Divider()
            SettingRow("可用模型", models.size.toString())
        }

        SectionTitle("数据")
        SettingsCard {
            SettingRow("账号存储", "内存（重启后清空）", warn = true)
            Divider()
            SettingRow("策略库", "暂未实现", warn = true)
        }

        SectionTitle("关于")
        SettingsCard {
            SettingRow("应用", "ModelBridge")
            Divider()
            SettingRow("版本", "1.0.0")
            Divider()
            SettingRow("许可证", "Apache-2.0")
        }

        Text(
            text = "ModelBridge 是网关管家的重写实现，接口与原版兼容。",
            fontSize = 12.sp,
            color = AppStyle.SubtitleText,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 18.dp),
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
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppStyle.CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun SettingRow(label: String, value: String, warn: Boolean = false) {
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
            color = if (warn) AppStyle.Accent else AppStyle.SubtitleText,
            fontFamily = if (warn) FontFamily.Default else FontFamily.Monospace,
        )
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 14.dp),
        thickness = 1.dp,
        color = AppStyle.Divider,
    )
}

/** 预留：可点击的设置项，等有实际行为时启用。 */
@Composable
private fun ClickableSettingRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontSize = 14.sp, color = AppStyle.BodyText)
        Text(text = value, fontSize = 13.sp, color = AppStyle.SubtitleText)
    }
}
