package io.modelbridge.app.service

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 启动 / 停止 [GatewayService]。
 *
 * 走前台服务而不是直接调 [io.modelbridge.app.GatewayRunner]：
 * Android 会回收后台进程，网关被回收后手机上的客户端就连不上了。
 */
class ServiceManager(private val context: Context) {

    /** 启动。Android 8 起必须用 startForegroundService。 */
    fun start(port: Int = DEFAULT_PORT) {
        val intent = Intent(context, GatewayService::class.java).apply {
            action = ACTION_START
            putExtra(INTENT_PORT, port)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /** 停止服务会同步释放应用唯一监听器；不额外创建一个新服务。 */
    fun stop() {
        context.stopService(Intent(context, GatewayService::class.java))
    }
}
