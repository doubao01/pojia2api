package io.modelbridge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.modelbridge.app.service.ServiceManager
import io.modelbridge.app.ui.AccountsScreen
import io.modelbridge.app.ui.AppStyle
import io.modelbridge.app.ui.BridgeTheme
import io.modelbridge.app.ui.HomeScreen
import io.modelbridge.app.ui.LogScreen
import io.modelbridge.app.ui.ModelsScreen
import io.modelbridge.app.ui.PillTabBar
import io.modelbridge.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private val app: BridgeApplication
        get() = application as BridgeApplication

    private val vms: ViewModels by viewModels {
        viewModelFactory { initializer { ViewModels(app.container, GatewayRunner(app.container.gateway)) } }
    }

    private val serviceManager = ServiceManager(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BridgeTheme {
                MainScreen(vms)
            }
        }
    }
}

/**
 * 主界面骨架，对照原应用「网关管家」的布局：
 *
 * ```
 * ┌─────────────────────────┐
 * │ 网关管家          26sp  │  ← 固定标题栏
 * │ 副标题            13sp  │
 * ├─────────────────────────┤
 * │                         │
 * │   内容区（可滚动）       │
 * │                         │
 * │   ╭─────────────────╮   │  ← 悬浮胶囊标签栏
 * │   │首页 供应商 模型 …│   │     62dp，左右 22dp
 * │   ╰─────────────────╯   │
 * └─────────────────────────┘
 * ```
 */
@Composable
private fun MainScreen(vms: ViewModels) {
    var tab by remember { mutableIntStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppStyle.PageBackground),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ---------- 固定标题栏 ----------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = AppStyle.TitleBarPaddingHorizontal,
                        end = AppStyle.TitleBarPaddingHorizontal,
                        top = AppStyle.TitleBarPaddingTop,
                        bottom = AppStyle.TitleBarPaddingBottom,
                    ),
            ) {
                Text(
                    text = "网关管家",
                    fontSize = AppStyle.TitleFontSize,
                    fontWeight = FontWeight.Bold,
                    color = AppStyle.TitleText,
                )
                Text(
                    text = "多供应商模型聚合 · OpenAI 兼容接口",
                    fontSize = AppStyle.SubtitleFontSize,
                    color = AppStyle.SubtitleText,
                    modifier = Modifier.padding(top = AppStyle.SubtitlePaddingTop),
                )
            }

            // ---------- 内容区 ----------
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = AppStyle.ContentPadding,
                            end = AppStyle.ContentPadding,
                            top = AppStyle.ContentPadding,
                            bottom = AppStyle.ContentBottomInset,
                        ),
                ) {
                    when (tab) {
                        0 -> HomeScreen(vms)
                        1 -> AccountsScreen(vms)
                        2 -> ModelsScreen(vms)
                        3 -> LogScreen(vms)
                        else -> SettingsScreen(vms)
                    }
                }
            }
        }

        // ---------- 悬浮标签栏 ----------
        PillTabBar(
            tabs = TABS,
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = AppStyle.TabBarMarginBottom),
        )
    }
}

/** 标签顺序与原版一致：首页 / 供应商 / 模型 / 日志 / 我的。 */
private val TABS = listOf("首页", "供应商", "模型", "日志", "我的")
