package io.modelbridge.app.service

import android.app.NotificationChannel
import androidx.core.app.NotificationCompat
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import io.modelbridge.app.GatewayRunner
import io.modelbridge.app.MainActivity
import io.modelbridge.core.Gateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 前台服务：让网关在手机切到后台后仍然继续运行。
 *
 * 通知常驻于状态栏，点击可回到主界面。
 */
class GatewayService : Service() {

    private var runner: GatewayRunner? = null

    private val _port = MutableStateFlow<Int?>(null)
    val port: StateFlow<Int?> = _port.asStateFlow()

    /** 网关运行状态。 */
    val running: StateFlow<Boolean> get() = _running

    private val _running = MutableStateFlow<Boolean>(false)

    /** 先绑定的网关，在 initialize() 里指定。 */
    private var gateway: Gateway? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 通知 ID。 */
    private val notificationId = 10001

    /** 通知通道 ID。 */
    private val channelId = "modelbridge_gateway"

    override fun onBind(intent: Intent?): IBinder = serviceBinder

    private val serviceBinder = GatewayServiceBinder()

    inner class GatewayServiceBinder : Binder() {
        fun getService(): GatewayService = this@GatewayService
    }

    /**
     * 把网关与 Service 关联，并创建前台通知。
     *
     * 后续所有调用都通过 [start] / [stopGateway]。
     */
    fun initialize(gateway: Gateway) {
        this.gateway = gateway
        runner = GatewayRunner(gateway)
        sendNotification()
    }

    /**
     * 开始监听端口。
     */
    fun start(port: Int = DEFAULT_PORT) {
        val gateway = gateway ?: return
        val runner = runner ?: return

        scope.launch {
            runCatching {
                val actualPort = runner.start(port)
                if (actualPort > 0) {
                    _port.value = actualPort
                    _running.value = true
                    updateNotification(true, actualPort)
                } else {
                    _port.value = null
                    _running.value = false
                    updateNotification(false, 0)
                }
            }
        }
    }

    /**
     * 停止并提供的更新。
     */
    fun stopGateway() {
        runner?.stop()
        runner = null
        _port.value = null
        _running.value = false
        updateNotification(false, 0)
        stopForeground(true)
    }

    private fun sendNotification() {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("ModelBridge 网关")
            .setContentText("未运行")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent())
            .build()
        startForeground(notificationId, notification)
    }

    private fun updateNotification(running: Boolean, port: Int) {
        val builder = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("ModelBridge 网关")
            .setContentText(if (running) "已启动，监听端口 $port" else "未运行")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent())
        val notification = builder.build()
        startForeground(notificationId, notification)
    }

    /**
     * 创建跳转到主界面的 Intent，PendingIntent 不算数据，只负责点击。
     */
    private fun pendingIntent(): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 没有该通道就新建一个。
        if (manager.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(channelId, "网关服务通知", NotificationManager.IMPORTANCE_LOW).apply {
                this.importance = NotificationManager.IMPORTANCE_LOW
            }
            manager.createNotificationChannel(channel)
        }
    }
}

/** 默认监听端口。 */
const val DEFAULT_PORT = 8080
