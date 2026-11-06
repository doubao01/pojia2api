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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.modelbridge.app.ViewModels
import io.modelbridge.spi.AuthKind
import io.modelbridge.spi.Provider

/**
 * 账号页：按供应商列出，可启停账号、查看所需登录形态。
 */
@Composable
fun AccountsScreen(vms: ViewModels) {
    val groups = vms.providerGroups.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("供应商与账号", style = MaterialTheme.typography.titleLarge)
        Text(
            "关掉的账号不会出现在模型列表里",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Box(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groups.value, key = { it.provider.id }) { group ->
                    ProviderCard(vms, group)
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(vms: ViewModels, group: ProviderGroup) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        group.provider.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = buildString {
                            append(group.provider.id)
                            append(" · ").append(authLabel(group.provider.authKind))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                Text(
                    text = "尚未配置账号",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
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
