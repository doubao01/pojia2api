package io.modelbridge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.modelbridge.app.ViewModels

/**
 * 任务/额度页面。
 *
 * 网关内核只负责转发，不拥有供应商任务接口，因此额度必须显示真实可用性，
 * 不能用请求数或 token 数冒充供应商额度。供应商任务接口接入后可在此页逐项扩展。
 */
@Composable
fun TaskQuotaScreen(vms: ViewModels, onBack: () -> Unit) {
    val accounts by vms.accounts.collectAsState()
    val enabledAccounts = accounts.count { it.enabled }

    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onBack) { Text("返回") }
        Text("任务与额度", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AppStyle.TitleText)
        Text(
            "签到、任务、额度一目了然",
            fontSize = 13.sp,
            color = AppStyle.SubtitleText,
            modifier = Modifier.padding(top = 5.dp, bottom = 14.dp),
        )

        GroupTitle("任务")
        SettingsGroup {
            ActionRow(
                label = "一键签到",
                value = "未接入",
                hint = "按供应商一键签到与领取任务",
                onClick = { vms.toast("当前供应商均不支持签到或任务") },
            )
            RowDivider()
            ActionRow(
                label = "一键任务",
                value = "未接入",
                hint = "任务列表拉取失败时会显示真实错误，不伪造任务",
                onClick = { vms.toast("当前供应商均不支持签到或任务") },
            )
            RowDivider()
            TaskRow("任务列表", "没有可显示的任务", "$enabledAccounts 个账号待执行")
        }

        GroupTitle("额度")
        SettingsGroup {
            TaskRow("套餐额度", "未提供", "额度接口需要由具体供应商适配器实现")
            RowDivider()
            TaskRow("共享额度", "未提供", "上游未返回可解析额度")
            RowDivider()
            TaskRow("加购额度", "未提供", "额度包信息尚未查询")
            RowDivider()
            TaskRow("刷新时间", "—", "尚未获取供应商额度数据")
        }

        GroupTitle("说明")
        SettingsGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "按供应商一键签到与领取任务；当前供应商均不支持签到或任务。\n\n" +
                        "网关统计中的请求数、token 用量和缓存命中率不等于供应商额度。本页不会把本地统计伪装成额度。",
                    fontSize = 13.sp,
                    color = AppStyle.SubtitleText,
                    lineHeight = 19.sp,
                )
            }
        }
    }
}

@Composable
private fun TaskRow(label: String, value: String, hint: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, color = AppStyle.BodyText)
            Text(hint, fontSize = 11.sp, color = AppStyle.SubtitleText, modifier = Modifier.padding(top = 3.dp))
        }
        Text(value, fontSize = 13.sp, color = AppStyle.SubtitleText)
    }
}
