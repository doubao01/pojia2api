package io.modelbridge.app.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.modelbridge.app.ViewModels
import io.modelbridge.spi.Provider

/** Qoder/CodeBuddy 的设备授权登录：WebView 只负责用户确认，token 由后台轮询拿回。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DeviceLoginDialog(
    provider: Provider,
    vms: ViewModels,
    onDismiss: () -> Unit,
) {
    val state by vms.deviceLogin.collectAsState()
    val session = state?.takeIf { it.providerId == provider.id }?.session

    LaunchedEffect(provider.id) {
        vms.beginDeviceLogin(provider.id)
    }

    AlertDialog(
        onDismissRequest = {
            vms.cancelDeviceLogin()
            onDismiss()
        },
        title = { Text("设备授权 · ${provider.displayName}", fontSize = 17.sp) },
        text = {
            Column {
                Text(
                    state?.message ?: "正在获取授权链接…",
                    fontSize = 13.sp,
                    color = if (state?.finished == true) AppStyle.BodyText else AppStyle.SubtitleText,
                )
                if (session != null && state?.finished != true) {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(380.dp)
                            .padding(top = 8.dp),
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                webViewClient = WebViewClient()
                                loadUrl(session.loginUrl)
                            }
                        },
                        update = { view ->
                            if (view.url != session.loginUrl) view.loadUrl(session.loginUrl)
                        },
                    )
                } else if (state?.finished != true) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (state?.finished == true) onDismiss()
            }) { Text(if (state?.finished == true) "完成" else "等待中") }
        },
        dismissButton = {
            TextButton(onClick = {
                vms.cancelDeviceLogin()
                onDismiss()
            }) { Text("取消") }
        },
    )
}
