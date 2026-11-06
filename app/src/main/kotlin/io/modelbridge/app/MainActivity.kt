package io.modelbridge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.modelbridge.app.ui.AccountsScreen
import io.modelbridge.app.ui.BridgeTheme
import io.modelbridge.app.ui.LogScreen
import io.modelbridge.app.service.ServiceManager
import io.modelbridge.app.ui.ModelsScreen

class MainActivity : ComponentActivity() {

    private val app: BridgeApplication
        get() = application as BridgeApplication

    private val vms: ViewModels by viewModels {
        viewModelFactory { initializer { ViewModels(app.container, GatewayRunner(app.container.gateway)) } }
    }

    private val serviceManager = ServiceManager(this)

    override fun onStart() {
        super.onStart()
        // 每回主界面查看状态
        if (!vms.runner.running.value) {
            val port = vms.runner.start(0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BridgeTheme {
                MainScreen(vms)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(vms: ViewModels) {
    val running by vms.runner.running.collectAsState()
    val port by vms.runner.port.collectAsState()

    // 底部导航状态
    var tab by remember { mutableStateOf(Tab.Models) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ModelBridge" + if (running) "  :$port" else "") },
                actions = {
                    androidx.compose.material3.TextButton(onClick = {
                        if (running) vms.runner.stop() else vms.runner.start(8080)
                    }) {
                        Text(
                            if (running) "停止" else "启动",
                            color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { inner ->
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.padding(inner),
        ) {
            when (tab) {
                Tab.Models -> ModelsScreen(vms)
                Tab.Accounts -> AccountsScreen(vms)
                Tab.Log -> LogScreen(vms)
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Models("模型", Icons.Filled.Memory),
    Accounts("账号", Icons.Filled.Settings),
    Log("日志", Icons.Filled.List),
}
