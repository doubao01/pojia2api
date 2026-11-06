package io.modelbridge.app.service

import android.content.Context
import android.content.Intent
import io.modelbridge.app.BridgeApplication

/**
 * 启动 / 停止 GatewayService。
 *
 * 前台服务（Foreground Service）让网关不被 Android 杀死。
 */
class ServiceManager(private val context: Context) {

    private val app = context.applicationContext as BridgeApplication

    /** 启动。 */
    fun start(port: Int = DEFAULT_PORT) {
        val intent = Intent(context, GatewayService::class.java).apply {
            action = ACTION_PREFIX + "START"
            putExtra(INTENT_PORT, port)
        }
        // 先给 gateway 和 runner 链接
        val gateway = app.container.gateway
        app.startService(intent)
    }

    /** 停止。 */
    fun stop() {
        val intent = Intent(context, GatewayService::class.java).apply {
            action = ACTION_PREFIX + "STOP"
        }
        app.startService(intent)
    }

    private fun Intent.cls(): Intent = this
}

/**
 * ForegroundService 启动器，重启后恢复。
 */
private const val ACTION_PREFIX = "io.modelbridge.action."
private const val INTENT_PORT = "io.modelbridge.port"
