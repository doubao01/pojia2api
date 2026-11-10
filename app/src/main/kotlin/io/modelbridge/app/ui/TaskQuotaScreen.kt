package io.modelbridge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.modelbridge.app.ViewModels
import io.modelbridge.core.Account
import io.modelbridge.providers.account.QuotaKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 原版首页把「任务管理」和「账号管理」拆成两个独立页面。 */
enum class AccountCenterMode { Tasks, Accounts }

/**
 * 任务管理 / 账号管理。
 *
 * 已接入的供应商按原版协议查询真实任务、签到结果与额度；未接入的供应商
 * 明确显示不支持。网关本地请求数与 token 用量不会冒充供应商额度。
 */
@Composable
fun TaskQuotaScreen(
    vms: ViewModels,
    onBack: () -> Unit,
    mode: AccountCenterMode = AccountCenterMode.Tasks,
) {
    val accounts by vms.accounts.collectAsState()
    val state by vms.taskQuota.collectAsState()
    val credits by vms.accountCredits.collectAsState()
    val enabledAccounts = accounts.filter { it.enabled }
    val accountIds = accounts.map { it.id }
    LaunchedEffect(mode, accountIds) {
        when (mode) {
            AccountCenterMode.Tasks -> vms.refreshTaskQuota()
            AccountCenterMode.Accounts -> accountIds.forEach { vms.refreshAccountCredit(it) }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onBack) { Text("返回") }
        when (mode) {
            AccountCenterMode.Tasks -> TaskCenter(
                vms = vms,
                state = state,
                enabledCount = enabledAccounts.size,
            )
            AccountCenterMode.Accounts -> AccountCenter(
                vms = vms,
                accounts = accounts,
                credits = credits,
                state = state,
            )
        }
    }
}

@Composable
private fun TaskCenter(
    vms: ViewModels,
    state: ViewModels.TaskQuotaUiState,
    enabledCount: Int,
) {
    Text("任务管理", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AppStyle.TitleText)
    Text(
        "按供应商一键签到与领取任务",
        fontSize = 13.sp,
        color = AppStyle.SubtitleText,
        modifier = Modifier.padding(top = 5.dp, bottom = 14.dp),
    )

    GroupTitle("任务")
    SettingsGroup {
        ActionRow(
            label = "一键签到",
            value = if (state.busy) "正在签到…" else "执行",
            hint = "按供应商逐账号签到",
            onClick = { if (!state.busy) vms.checkInAllAccounts() },
        )
        RowDivider()
        ActionRow(
            label = "一键任务",
            value = if (state.busy) "正在任务…" else "执行",
            hint = "逐账号领取可领任务",
            onClick = { if (!state.busy) vms.claimAllAccountTasks() },
        )
        RowDivider()
        ActionRow(
            label = "刷新任务与额度",
            value = if (state.busy) "查询中…" else "刷新",
            hint = "$enabledCount 个启用账号",
            onClick = { if (!state.busy) vms.refreshTaskQuota() },
        )
        RowDivider()
        if (state.tasks.isEmpty()) {
            TaskRow("任务列表", "没有可显示的任务", state.tasksError.ifBlank { "上游未返回任务" })
        } else {
            state.tasks.forEachIndexed { index, task ->
                if (index > 0) RowDivider()
                TaskRow(
                    label = task.displayTitle,
                    value = task.stateLabel,
                    hint = listOfNotNull(
                        task.rewardCredit.takeIf { it > 0 }?.let { "+$it 积分" },
                        task.description.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").ifBlank { task.code },
                )
            }
        }
    }

    GroupTitle("额度一览")
    SettingsGroup {
        QuotaSummary(state)
        TaskRow(
            label = "刷新时间",
            value = formatTime(state.lastRefreshedAtMillis),
            hint = state.lastResult?.message?.ifBlank { null } ?: "仅显示上游真实返回的数据",
        )
    }

    GroupTitle("说明")
    SettingsGroup {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "已接入 WorkBuddy 签到/成长任务/额度、Qoder 额度、Loomy 新人任务/积分、" +
                    "小浣熊/CatPaw/Meoo/Tabbit/Trae/AStudio 签到或额度；其它供应商暂不支持签到或任务。\n\n" +
                    "网关统计中的请求数、token 用量和缓存命中率不等于供应商额度。本页不会把本地统计伪装成额度。",
                fontSize = 13.sp,
                color = AppStyle.SubtitleText,
                lineHeight = 19.sp,
            )
        }
    }
}

@Composable
private fun AccountCenter(
    vms: ViewModels,
    accounts: List<Account>,
    credits: Map<String, ViewModels.AccountCreditUiState>,
    state: ViewModels.TaskQuotaUiState,
) {
    Text("账号管理", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AppStyle.TitleText)
    Text(
        "签到、任务、额度一目了然",
        fontSize = 13.sp,
        color = AppStyle.SubtitleText,
        modifier = Modifier.padding(top = 5.dp, bottom = 14.dp),
    )

    GroupTitle("账号")
    SettingsGroup {
        ActionRow(
            label = "刷新全部积分",
            value = if (state.busy) "查询中…" else "刷新",
            hint = "${accounts.size} 个账号",
            onClick = { if (!state.busy) vms.refreshTaskQuota() },
        )
        if (accounts.isEmpty()) {
            RowDivider()
            TaskRow("可用积分", "—", "还没有登录账号")
        } else {
            accounts.forEachIndexed { index, account ->
                RowDivider()
                val credit = credits[account.id]
                val quota = credit?.quota
                val canCredit = vms.supportsAccountCredit(account.providerId)
                val canCheckIn = vms.supportsAccountCheckIn(account.providerId)
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(account.label.ifBlank { account.id }, fontSize = 14.sp, color = AppStyle.BodyText)
                    Text(
                        text = buildList {
                            add(account.providerId)
                            if (canCredit) {
                                add(
                                    when {
                                        credit?.busy == true -> "可用积分：查询中…"
                                        quota?.known == true -> "可用积分：${quota.balance}"
                                        quota?.error?.isNotBlank() == true -> "可用积分：查询失败"
                                        else -> "可用积分：…"
                                    },
                                )
                            }
                            if (canCheckIn) {
                                add(if (credit?.checkedInToday == true) "今日已签到" else "今日未签到")
                            }
                            if (!canCredit && !canCheckIn) add("未接入积分或签到")
                            quota?.detail?.takeIf { it.isNotBlank() }?.let { add("额度：$it") }
                            quota?.error?.takeIf { it.isNotBlank() }?.let { add(it) }
                        }.joinToString(" · "),
                        fontSize = 11.sp,
                        color = AppStyle.SubtitleText,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        if (canCredit) {
                            TextButton(
                                enabled = credit?.busy != true,
                                onClick = { vms.refreshAccountCredit(account.id) },
                            ) { Text("刷新积分", fontSize = 12.sp) }
                        }
                        if (canCheckIn) {
                            TextButton(
                                enabled = credit?.busy != true,
                                onClick = { vms.checkInAccount(account.id) },
                            ) { Text("签到", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    }

    GroupTitle("额度汇总")
    SettingsGroup { QuotaSummary(state) }
}

@Composable
private fun QuotaSummary(state: ViewModels.TaskQuotaUiState) {
    val quotas = state.quotas.values.filter { it.known && it.entries.isNotEmpty() }
    if (quotas.isEmpty()) {
        TaskRow("可用积分", "查询失败", "上游未返回可解析额度")
        return
    }
    val entries = quotas.flatMap { it.entries }
    val kinds = listOf(QuotaKind.Plan, QuotaKind.Shared, QuotaKind.AddOn, QuotaKind.Points, QuotaKind.Other)
    kinds.forEachIndexed { index, kind ->
        val matching = entries.filter { it.kind == kind }
        if (matching.isEmpty()) return@forEachIndexed
        if (index > 0) RowDivider()
        TaskRow(
            label = kind.label,
            value = matching.sumOf { it.remaining }.toString(),
            hint = matching.joinToString("；") {
                listOfNotNull(
                    it.label.takeIf { label -> label != kind.label },
                    it.detail.takeIf { detail -> detail.isNotBlank() },
                ).joinToString(" · ")
            }.ifBlank { "来自 ${quotas.size} 个账号" },
        )
    }
}

private fun formatTime(millis: Long): String = if (millis > 0) {
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
} else {
    "—"
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
