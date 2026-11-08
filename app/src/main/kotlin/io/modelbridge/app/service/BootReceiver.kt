package io.modelbridge.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.modelbridge.app.BridgeApplication

/**
 * 开机后按「我的」页里的「开机自启」拉起网关。
 *
 * 之前 autoStart 只在进入主界面时判断，等于「打开 App 就自启」，
 * 和设置项名字对不上。这里补上真正的开机广播。
 *
 * 只在开关打开时启动；开机广播不需要额外权限，
 * 但必须把接收器声明在 manifest 里并标记 exported=false。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val app = context.applicationContext as? BridgeApplication ?: return
        if (!app.container.settings.current().autoStart) return

        // 开机阶段不允许直接起前台服务以外的方式；
        // 用 startForegroundService 交给系统排队。
        runCatching {
            val serviceIntent = Intent(context, GatewayService::class.java).apply {
                this.action = ACTION_START
                putExtra(INTENT_PORT, app.container.settings.current().port)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
