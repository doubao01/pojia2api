package io.modelbridge.app.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import io.modelbridge.app.BridgeApplication
import io.modelbridge.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** 悬浮球展示应用唯一网关状态；权限只由用户主动操作申请。 */
class FloatingBallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app: BridgeApplication get() = application as BridgeApplication
    private var ball: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private val manager: WindowManager by lazy { getSystemService(WindowManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            app.container.runner.running.collect { running ->
                ball?.background = background(running)
                ball?.contentDescription = if (running) "网关运行中，点击返回应用" else "网关未运行，点击返回应用"
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 重复 SHOW 也先转前台，随后更新现有窗口尺寸而不是直接早退。
        showNotification()
        if (!Settings.canDrawOverlays(this) || !app.container.settings.current().showFloatingBall) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            showOrResize()
        } catch (error: RuntimeException) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOrResize() {
        val size = (app.container.settings.current().floatingBallSizeDp.coerceIn(1, 96) *
            resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val oldBall = ball
        val oldParams = params
        if (oldBall != null && oldParams != null) {
            oldParams.width = size
            oldParams.height = size
            clamp(oldParams)
            manager.updateViewLayout(oldBall, oldParams)
            return
        }
        val window = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - (24 * resources.displayMetrics.density).toInt()
            y = (resources.displayMetrics.heightPixels * 0.62f).toInt()
        }
        clamp(window)
        val view = TextView(this).apply {
            background = background(app.container.runner.running.value)
            contentDescription = "网关状态，点击返回应用"
            setOnClickListener {
                startActivity(Intent(this@FloatingBallService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
            }
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = window.x; startY = window.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) dragged = true
                    if (dragged) {
                        window.x = startX + dx.toInt(); window.y = startY + dy.toInt()
                        clamp(window)
                        try {
                            manager.updateViewLayout(view, window)
                        } catch (error: RuntimeException) {
                            stopSelf()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!dragged) view.performClick(); true }
                MotionEvent.ACTION_CANCEL -> { dragged = false; true }
                else -> false
            }
        }
        manager.addView(view, window)
        ball = view
        params = window
    }

    private fun clamp(window: WindowManager.LayoutParams) {
        window.x = window.x.coerceIn(0, (resources.displayMetrics.widthPixels - window.width).coerceAtLeast(0))
        window.y = window.y.coerceIn(0, (resources.displayMetrics.heightPixels - window.height).coerceAtLeast(0))
    }

    private fun background(running: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (running) Color.rgb(47, 111, 237) else Color.rgb(126, 132, 142))
        alpha = 204
        setStroke((1.5f * resources.displayMetrics.density).toInt(), Color.WHITE)
    }

    private fun showNotification() {
        val notifications = getSystemService(NotificationManager::class.java)
        if (notifications.getNotificationChannel(CHANNEL_ID) == null) {
            notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID, "悬浮球", NotificationManager.IMPORTANCE_MIN))
        }
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("网关管家")
            .setContentText("悬浮球已显示")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build())
    }

    override fun onDestroy() {
        scope.cancel()
        ball?.let { view ->
            try { manager.removeView(view) } catch (_: IllegalArgumentException) { /* 已由系统移除。 */ }
        }
        ball = null
        params = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 10002
        private const val CHANNEL_ID = "modelbridge_floating_ball"
        const val ACTION_SHOW = "io.modelbridge.action.FLOATING_SHOW"
        const val ACTION_HIDE = "io.modelbridge.action.FLOATING_HIDE"

        fun apply(context: Context, show: Boolean) {
            val intent = Intent(context, FloatingBallService::class.java)
            if (!show) {
                context.stopService(intent)
                return
            }
            try {
                context.startForegroundService(intent.apply { action = ACTION_SHOW })
            } catch (_: RuntimeException) {
                android.widget.Toast.makeText(context, "悬浮球无法启动，请检查权限", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}
