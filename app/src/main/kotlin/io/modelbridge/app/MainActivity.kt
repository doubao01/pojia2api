package io.modelbridge.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.core.content.ContextCompat
import io.modelbridge.app.service.ServiceManager
import io.modelbridge.app.ui.AccountsScreen
import io.modelbridge.app.ui.AppStyle
import io.modelbridge.app.ui.BridgeTheme
import io.modelbridge.app.ui.HomeScreen
import io.modelbridge.app.ui.LogScreen
import io.modelbridge.app.ui.ModelsScreen
import io.modelbridge.app.ui.PillTabBar
import io.modelbridge.app.ui.PolicyScreen
import io.modelbridge.app.ui.SettingsScreen
import io.modelbridge.app.ui.AccountCenterMode
import io.modelbridge.app.ui.TaskQuotaScreen
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val app: BridgeApplication
        get() = application as BridgeApplication

    private val vms: ViewModels by viewModels {
        viewModelFactory {
            initializer {
                ViewModels(
                    container = app.container,
                    runner = app.container.runner,
                    serviceManager = ServiceManager(applicationContext),
                )
            }
        }
    }

    /** 导出：用户选好位置后把 JSON 写进去。 */
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val payload = ConfigTransfer.pendingJson
        ConfigTransfer.pendingJson = null
        if (uri != null && payload != null) {
            writeTextToUri(this, uri, payload)
        }
    }

    /** Android 13+ 前台服务通知权限；拒绝时给出明确提示，不静默失败。 */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            vms.toast("通知权限被拒绝，前台服务通知可能不显示")
        }
    }

    /** 导入：读到文件后解析并交给回调。 */
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val callback = ConfigTransfer.pendingImport
        ConfigTransfer.pendingImport = null
        if (uri != null && callback != null) {
            val raw = readTextFromUri(this, uri)
            val bundle = raw?.let { ConfigTransfer.parse(it) }
            if (bundle != null) callback(bundle)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        // 把系统文件选择器的启动方式接到 Activity 的 result API 上。
        // ConfigTransfer 里 startActivity 只是为了唤起选择器，真正的
        // 落盘/读取在这里完成。
        ConfigTransferBridge.exportRequest = { exportLauncher.launch("modelbridge-config.json") }
        ConfigTransferBridge.importRequest = { importLauncher.launch(arrayOf("application/json", "*/*")) }

        setContent {
            BridgeTheme {
                MainScreen(vms)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        val settings = app.container.settings.current()
        // 若开启了开机自启且网关没跑，进入界面时拉起
        if (settings.autoStart && !vms.runner.running.value) {
            vms.startGateway(settings.port)
        }
        // 只恢复已授权的悬浮球，不在生命周期回调里主动弹权限页。
        vms.syncFloatingBall(settings.showFloatingBall)
    }

    /** 悬浮窗权限被拒后回到界面时补一次同步。 */
    override fun onResume() {
        super.onResume()
        val settings = app.container.settings.current()
        if (settings.showFloatingBall) vms.syncFloatingBall(true)
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
    var showPolicies by remember { androidx.compose.runtime.mutableStateOf(false) }
    var centerMode by remember { androidx.compose.runtime.mutableStateOf<AccountCenterMode?>(null) }
    BackHandler(enabled = showPolicies || centerMode != null) {
        showPolicies = false
        centerMode = null
    }

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
                    if (centerMode != null) {
                        TaskQuotaScreen(
                            vms,
                            onBack = { centerMode = null },
                            mode = centerMode!!,
                        )
                    } else if (showPolicies) {
                        PolicyScreen(vms, onBack = { showPolicies = false })
                    } else {
                        when (tab) {
                            0 -> HomeScreen(
                                vms,
                                onOpenAccounts = {
                                    showPolicies = false
                                    centerMode = AccountCenterMode.Accounts
                                },
                                onOpenPolicies = {
                                    centerMode = null
                                    showPolicies = true
                                },
                                onOpenTasks = {
                                    showPolicies = false
                                    centerMode = AccountCenterMode.Tasks
                                },
                            )
                            1 -> AccountsScreen(vms)
                            2 -> ModelsScreen(vms)
                            3 -> LogScreen(vms)
                            else -> SettingsScreen(vms)
                        }
                    }
                }
            }
        }

        // ---------- 操作提示 ----------
        // ViewModels 会在保存 / 删除 / 导入账号后写入 message，
        // 展示两秒后自动清除，避免用户以为操作没生效。
        val message by vms.message.collectAsState()
        message?.let { text ->
            LaunchedEffect(text) {
                delay(2000)
                vms.clearMessage()
            }
            Surface(
                color = AppStyle.CardBackground,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = AppStyle.TabBarMarginBottom + 64.dp),
            ) {
                Text(
                    text = text,
                    fontSize = AppStyle.SubtitleFontSize,
                    color = AppStyle.Accent,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        // ---------- 悬浮标签栏 ----------
        PillTabBar(
            tabs = TABS,
            selectedIndex = tab,
            onSelect = {
                showPolicies = false
                centerMode = null
                tab = it
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = AppStyle.TabBarMarginBottom),
        )
    }
}

/** 标签顺序与原版一致：首页 / 供应商 / 模型 / 日志 / 我的。 */
private val TABS = listOf("首页", "供应商", "模型", "日志", "我的")
