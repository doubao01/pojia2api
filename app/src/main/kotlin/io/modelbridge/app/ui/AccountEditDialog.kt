package io.modelbridge.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.modelbridge.spi.AuthKind
import io.modelbridge.spi.Credential

/**
 * 手动添加/编辑账号的弹窗。
 *
 * 按供应商的认证方式决定要填什么：
 * - [AuthKind.ApiKey] → 一个密钥输入框
 * - [AuthKind.Cookie] → 一个 Cookie 输入框（也可从网页登录页抓）
 * - [AuthKind.PhoneCode] → 手机号 + 验证码
 * - [AuthKind.OAuth] / [AuthKind.None] → 只填地址
 */
@Composable
fun AccountEditDialog(
    edit: AccountEdit,
    onDismiss: () -> Unit,
    onSave: (label: String, credential: Credential, baseUrl: String) -> Unit,
) {
    val provider = edit.provider
    val existing = edit.account

    var label by remember { mutableStateOf(existing?.label ?: "") }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: "") }

    // 凭据字段
    var apiKey by remember {
        mutableStateOf((existing?.credential as? Credential.ApiKey)?.key ?: "")
    }
    var cookie by remember {
        mutableStateOf((existing?.credential as? Credential.Cookies)?.raw ?: "")
    }
    var phone by remember {
        mutableStateOf((existing?.credential as? Credential.Session)?.phone ?: "")
    }
    var code by remember {
        mutableStateOf((existing?.credential as? Credential.Session)?.token ?: "")
    }

    val credential: Credential = when (provider.authKind) {
        AuthKind.ApiKey -> Credential.ApiKey(apiKey.trim())
        AuthKind.Cookie -> Credential.Cookies(cookie.trim())
        AuthKind.PhoneCode -> Credential.Session(phone.trim(), code.trim())
        AuthKind.OAuth -> existing?.credential ?: Credential.None
        AuthKind.None -> Credential.None
    }

    val valid = when (provider.authKind) {
        AuthKind.ApiKey -> apiKey.isNotBlank()
        AuthKind.Cookie -> cookie.isNotBlank()
        AuthKind.PhoneCode -> phone.isNotBlank() && code.isNotBlank()
        // OAuth 必须先在网页登录里拿到凭据
        AuthKind.OAuth -> existing?.credential != null && existing.credential != Credential.None
        // 原版自定义上游要求填地址；地址必须以 http:// 开头。
        AuthKind.None -> baseUrl.trim().let { it.isNotEmpty() && it.startsWith("http") }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (existing == null) "添加账号 · ${provider.displayName}"
                else "编辑账号 · ${provider.displayName}",
                fontSize = 17.sp,
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    label = { Text("昵称", fontSize = 12.sp) },
                    placeholder = { Text(provider.displayName, fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "昵称（模型名后缀，如 猫爪 → deepseek@猫爪）",
                    fontSize = 11.sp,
                    color = AppStyle.SubtitleText,
                    modifier = Modifier.padding(top = 4.dp),
                )

                when (provider.authKind) {
                    AuthKind.ApiKey -> {
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            singleLine = true,
                            label = { Text("API Key", fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                    AuthKind.Cookie -> {
                        OutlinedTextField(
                            value = cookie,
                            onValueChange = { cookie = it },
                            label = { Text("Cookie", fontSize = 12.sp) },
                            placeholder = { Text("name=value; name2=value2", fontSize = 12.sp) },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        Text(
                            text = "也可以在供应商卡片上点「网页登录」自动获取",
                            fontSize = 11.sp,
                            color = AppStyle.SubtitleText,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    AuthKind.PhoneCode -> {
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            singleLine = true,
                            label = { Text("手机号（不含国家码）", fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it },
                            singleLine = true,
                            label = { Text("验证码 / 令牌", fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                    AuthKind.OAuth -> {
                        Text(
                            text = if (existing?.credential == null || existing.credential == Credential.None) {
                                "该供应商需要先在上面点「OAuth 登录」取得凭据。"
                            } else {
                                "已取得凭据，可直接保存。"
                            },
                            fontSize = 12.sp,
                            color = AppStyle.SubtitleText,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    AuthKind.None -> {
                        Text(
                            text = "该供应商无需登录。",
                            fontSize = 12.sp,
                            color = AppStyle.SubtitleText,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    singleLine = true,
                    label = {
                        Text(
                            if (provider.authKind == AuthKind.None) "地址" else "自定义地址（可选）",
                            fontSize = 12.sp,
                        )
                    },
                    placeholder = { Text("留空用默认地址", fontSize = 12.sp) },
                    isError = provider.authKind == AuthKind.None && baseUrl.isNotBlank() &&
                        !baseUrl.trim().startsWith("http"),
                    supportingText = if (provider.authKind == AuthKind.None) {
                        {
                            Text(
                                if (baseUrl.isBlank()) "先填地址" else "地址需以 http:// 开头",
                                fontSize = 11.sp,
                                color = if (baseUrl.trim().startsWith("http")) {
                                    AppStyle.SubtitleText
                                } else {
                                    AppStyle.Accent
                                },
                            )
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(label.trim(), credential, baseUrl.trim()) },
                enabled = valid,
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
