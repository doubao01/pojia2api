package io.modelbridge.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.modelbridge.app.BridgeApplication
import io.modelbridge.app.GatewayRunner
import io.modelbridge.app.MainActivity

/** 前台服务只管理生命周期；监听器、日志、端口状态由应用唯一实例持有。 */
class GatewayService : Service() {
    private val app: BridgeApplication get() = application as BridgeApplication
    private val runner: GatewayRunner get() = app.container.runner

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // 重复启动仍须先转前台，不能因已运行就省略这一步。
                showNotification("正在启动网关")
                val port = if (intent.hasExtra(INTENT_PORT)) {
                    intent.getIntExtra(INTENT_PORT, DEFAULT_PORT)
                } else {
                    app.container.settings.current().port
                }
                val actualPort = runner.start(port)
                if (actualPort > 0) {
                    showNotification("已启动，监听端口 $actualPort")
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                runner.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                // 不因空 Intent 自动启动；用户停止后也不重建监听。
                if (runner.running.value) {
                    showNotification("已启动，监听端口 ${runner.port.value}")
                } else {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // 必须同步释放监听；不能启动一个可能永远不执行的销毁协程。
        runner.stop()
        super.onDestroy()
    }

    private fun showNotification(message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "网关服务通知", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val stop = Intent(this, GatewayService::class.java).apply { action = ACTION_STOP }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ModelBridge 网关")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            .addAction(android.R.drawable.ic_media_pause, "停止", PendingIntent.getService(
                this, 1, stop, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val NOTIFICATION_ID = 10001
        const val CHANNEL_ID = "modelbridge_gateway"
    }
}

const val DEFAULT_PORT = 8080
const val ACTION_START = "io.modelbridge.action.START"
const val ACTION_STOP = "io.modelbridge.action.STOP"
const val INTENT_PORT = "io.modelbridge.port"
