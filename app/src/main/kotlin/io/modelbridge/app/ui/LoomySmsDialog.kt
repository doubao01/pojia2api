package io.modelbridge.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.modelbridge.app.ViewModels

/** Loomy 的真实两步短信登录：用户先主动发短信，再提交验证码。 */
@Composable
fun LoomySmsDialog(
    vms: ViewModels,
    onDismiss: () -> Unit,
) {
    val state by vms.loomyLogin.collectAsState()
    var phone by remember { mutableStateOf(state.phone) }
    var code by remember { mutableStateOf("") }

    LaunchedEffect(state.phone) {
        if (state.phone.isNotBlank()) phone = state.phone
    }

    val sent = state.challenge != null
    val finished = state.finished

    AlertDialog(
        onDismissRequest = {
            vms.resetLoomyLogin()
            onDismiss()
        },
        title = { Text("手机号验证码 · Loomy", fontSize = 17.sp) },
        text = {
            Column {
                Text(
                    state.message,
                    fontSize = 12.sp,
                    color = if (finished) AppStyle.BodyText else AppStyle.SubtitleText,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    enabled = !sent && !state.busy,
                    singleLine = true,
                    label = { Text("手机号（不含国家码）", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (sent) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        enabled = !state.busy && !finished,
                        singleLine = true,
                        label = { Text("短信验证码", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                if (state.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(top = 12.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.busy && !finished &&
                    if (sent) code.isNotBlank() else phone.isNotBlank(),
                onClick = {
                    if (sent) vms.completeLoomyLogin(code) else vms.sendLoomyCode(phone)
                },
            ) {
                Text(when {
                    finished -> "完成"
                    sent -> "登录"
                    else -> "发送验证码"
                })
            }
        },
        dismissButton = {
            TextButton(onClick = {
                vms.resetLoomyLogin()
                onDismiss()
            }) { Text(if (finished) "关闭" else "取消") }
        },
    )
}
