package io.modelbridge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.modelbridge.app.ViewModels
import io.modelbridge.spi.AuthKind
import io.modelbridge.spi.Provider

/**
 * 供应商页：按供应商列出，可启停账号、查看所需登录形态。
 *
 * 外层已是可滚动容器，这里用 Column 铺开。
 */
@Composable
fun AccountsScreen(vms: ViewModels) {
    val groups = vms.providerGroups.collectAsState()

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "供应商与账号",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppStyle.BodyText,
        )
        Text(
            "关掉的账号不会出现在模型列表里",
            fontSize = 12.sp,
            color = AppStyle.SubtitleText,
            modifier = Modifier.padding(top = 2.dp),
        )

        Column(
            modifier = Modifier.padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(AppStyle.CardSpacing),
        ) {
            groups.value.forEach { group -> ProviderCard(vms, group) }
        }
    }
}

@Composable
private fun ProviderCard(vms: ViewModels, group: ProviderGroup) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppStyle.CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        group.provider.displayName,
                        fontSize = 15.sp,
                        color = AppStyle.BodyText,
                    )
                    Text(
                        text = buildString {
                            append(group.provider.id)
                            append(" · ").append(authLabel(group.provider.authKind))
                        },
                        fontSize = 12.sp,
                        color = AppStyle.SubtitleText,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Switch(
                    checked = group.anyEnabled,
                    onCheckedChange = { vms.toggleProvider(group.provider.id, !group.anyEnabled) },
                )
            }

            if (group.accounts.isNotEmpty()) {
                Text(
                    text = "${group.enabledCount} / ${group.accounts.size} 个账号启用",
                    fontSize = 12.sp,
                    color = AppStyle.SubtitleText,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                Text(
                    text = "尚未配置账号",
                    fontSize = 12.sp,
                    color = AppStyle.Accent,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

private fun authLabel(kind: AuthKind): String = when (kind) {
    AuthKind.None -> "免登录"
    AuthKind.ApiKey -> "API Key"
    AuthKind.Cookie -> "Cookie"
    AuthKind.OAuth -> "OAuth 登录"
    AuthKind.PhoneCode -> "手机验证码"
}

/** 界面用的供应商分组：一个供应商下挂它的账号。 */
data class ProviderGroup(
    val provider: Provider,
    val accounts: List<io.modelbridge.core.Account>,
) {
    val anyEnabled: Boolean get() = accounts.any { it.enabled }
    val enabledCount: Int get() = accounts.count { it.enabled }
}
