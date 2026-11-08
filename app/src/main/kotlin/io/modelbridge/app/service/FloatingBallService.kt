package io.modelbridge.app.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import io.modelbridge.app.BridgeApplication
import io.modelbridge.app.MainActivity

/**
 * 悬浮球：屏幕上一个小圆点，显示网关是否在跑，点一下回到主界面。
 *
 * 「我的」页里的「显示悬浮球」和「悬浮球大小」此前只有开关没有实现。
 * 这里做成独立的前台服务，避免主界面退出后球跟着消失。
 *
 * 不做成可拖动吸附：原版只是点击回到应用，拖动反而容易误触，
 * 也省掉一套边缘吸附逻辑。
 */
class FloatingBallService : Service() {

    private var windowManager: WindowManager? = null
    private var ballView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                hide()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // 先挂前台通知，再建悬浮球：startForegroundService 之后
                // 必须尽快 startForeground，否则系统会判定 ANR。
                startForegroundSafely()
                show()
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        if (ballView != null) return

        val app = application as? BridgeApplication ?: return
        val sizeDp = app.container.settings.current().floatingBallSizeDp.coerceIn(24, 96)
        val sizePx = (sizeDp * resources.displayMetrics.density).toInt().coerceAtLeast(1)

        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = manager

        val view = TextView(this).apply {
            text = ""
            setBackgroundColor(0xCC2F6FED.toInt())
            // 圆形：用背景色 + 大圆角近似，避免额外 drawable
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xCC2F6FED.toInt())
                setStroke((1.5f * resources.displayMetrics.density).toInt(), 0x66FFFFFF)
            }
            // 点击回到主界面
            setOnClickListener {
                val intent = Intent(this@FloatingBallService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
            }
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - sizePx - (24 * resources.displayMetrics.density).toInt()
            y = (resources.displayMetrics.heightPixels * 0.62f).toInt()
        }

        // 允许上下拖动，松手就停在当前位置
        view.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var dragged = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                return when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = params.x
                        startY = params.y
                        dragged = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) dragged = true
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { manager.updateViewLayout(v, params) }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        // 拖过就不算点击，避免移动后误跳转
                        if (!dragged) v.performClick()
                        true
                    }
                    else -> false
                }
            }
        })

        runCatching { manager.addView(view, params) }
            .onSuccess { ballView = view }
            .onFailure {
                // 没有悬浮窗权限时安静退出，不弹窗打断用户
                stopSelf()
            }
    }

    /** 悬浮球本身不需要常驻通知，但服务要能活下来，挂一个极低优先级的即可。 */
    private fun startForegroundSafely() {
        val channelId = "modelbridge_floating_ball"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "悬浮球", NotificationManager.IMPORTANCE_MIN),
            )
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = androidx.core.app.NotificationCompat
            .Builder(this, channelId)
            .setContentTitle("ModelBridge")
            .setContentText("悬浮球已显示")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pending)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun hide() {
        ballView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        ballView = null
        stopForeground(true)
    }

    override fun onDestroy() {
        hide()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 10002
        const val ACTION_SHOW = "io.modelbridge.action.FLOATING_SHOW"
        const val ACTION_HIDE = "io.modelbridge.action.FLOATING_HIDE"

        /** 按开关显示或隐藏悬浮球。 */
        fun apply(context: Context, show: Boolean) {
            val intent = Intent(context, FloatingBallService::class.java).apply {
                action = if (show) ACTION_SHOW else ACTION_HIDE
            }
            runCatching {
                if (show) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
