package io.modelbridge.app.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.modelbridge.spi.Provider

/**
 * 网页登录：内嵌 WebView，让用户在真实登录页里登录，
 * 登录完成后自动抓取该站点的 Cookie 作为凭据。
 *
 * 做法与浏览器一致：登录成功后 Cookie 由 [CookieManager] 持有，
 * 这里把 domain 下的所有 Cookie 拼成 `name=value; ...` 交给内核。
 *
 * 注意：不同供应商的登录页地址不同，[loginUrl] 由供应商给出；
 * 若供应商没提供可用地址，界面会提示改用手动填 Cookie。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginDialog(
    provider: Provider,
    onDismiss: () -> Unit,
    onCookie: (String) -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var currentUrl by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val loginUrl = provider.loginUrl

    // CookieManager 必须先允许 Cookie，并对当前 WebView 放开第三方 Cookie，
    // 否则部分站点的会话 Cookie 拿不到。
    remember {
        CookieManager.getInstance().setAcceptCookie(true)
        true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("登录 ${provider.displayName}", fontSize = 17.sp) },
        text = {
            Column {
                Text(
                    text = when {
                        loginUrl == null -> "该供应商未提供网页登录地址，请使用「手动填 Cookie」。"
                        else -> "在下方页面用浏览器登录，登录完成后点「我已登录完成」自动获取凭据。"
                    },
                    fontSize = 12.sp,
                    color = AppStyle.SubtitleText,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                if (loginUrl != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(380.dp),
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxWidth().height(380.dp),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    CookieManager.getInstance()
                                        .setAcceptThirdPartyCookies(this, true)
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.userAgentString = settings.userAgentString
                                        ?.replace("; wv", "")
                                        ?: ""
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(
                                            view: WebView?,
                                            url: String?,
                                        ) {
                                            loading = false
                                            currentUrl = url ?: ""
                                        }
                                    }
                                    loadUrl(loginUrl)
                                }
                            },
                            update = { /* 保持用户操作，不随重组重载 */ },
                        )
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center),
                                strokeWidth = 2.dp,
                            )
                        }
                    }

                    if (currentUrl.isNotBlank()) {
                        Text(
                            text = currentUrl.take(60),
                            fontSize = 10.sp,
                            color = AppStyle.SubtitleText,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                error?.let {
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        color = AppStyle.Accent,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = loginUrl != null,
                onClick = {
                    // WebView 的 Cookie 可能仍在内存队列里；按 AndroidWebView 约定先落盘。
                    CookieManager.getInstance().flush()
                    val raw = readCookiesFor(provider, currentUrl)
                    if (raw.isNullOrBlank()) {
                        error = "还没读到 Cookie，请确认已登录成功再试"
                    } else {
                        onCookie(raw)
                    }
                },
            ) { Text("我已登录完成") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 从 CookieManager 读取该供应商域名下的 Cookie。
 *
 * @param currentUrl 当前 WebView 的地址，用来推断域名；
 *        拿不到时退回供应商的默认域名。
 */
private fun readCookiesFor(provider: Provider, currentUrl: String): String? {
    val manager = CookieManager.getInstance()
    val candidates = buildList {
        if (currentUrl.isNotBlank()) {
            runCatching { java.net.URI(currentUrl).host }.getOrNull()?.let { add(it) }
        }
        provider.cookieDomains.forEach { add(it) }
    }.distinct()

    val cookieHeader = candidates.asSequence()
        .flatMap { host ->
            sequenceOf(
                runCatching { manager.getCookie(host) }.getOrNull(),
                runCatching { manager.getCookie("https://$host") }.getOrNull(),
            )
        }
        .filterNotNull()
        .firstOrNull { it.isNotBlank() }

    return cookieHeader
}
