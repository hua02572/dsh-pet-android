package com.hua.dshpet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * 悬浮窗桌宠服务：
 *  - WindowManager TYPE_APPLICATION_OVERLAY 挂一个 WebView
 *  - WebView 内 Canvas 逐帧绘制透明 webm 动画（alpha 通道保留）
 *  - 动画链逻辑在 index.html 的 JS 里（权重选片，与桌面版一致）
 *  - 单指拖动；轻点触发"点击回应"动画
 */
class PetService : Service() {

    companion object {
        /** 供 App 界面判断服务是否在跑（换大小后决定要不要重启） */
        @Volatile
        var isRunning = false
    }

    private lateinit var windowManager: WindowManager
    private var container: FrameLayout? = null
    private var webView: WebView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundWithNotification()
        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showPet()
    }

    /** 宠物宽度：SharedPreferences 里的 sizeDp（默认 280dp），高度按 9:16 自动算 */
    private fun petSizePx(): Int {
        val dp = getSharedPreferences("pet", MODE_PRIVATE).getInt("sizeDp", 280)
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("pet", "桌宠", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "桌宠悬浮窗运行中"
                }
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification =
            if (Build.VERSION.SDK_INT >= 34) {
                Notification.Builder(this, "pet")
                    .setContentTitle("桌宠运行中")
                    .setContentText("点按打开控制页")
                    .setSmallIcon(android.R.drawable.sym_def_app_icon)
                    .setContentIntent(pi)
                    .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                    .build()
            } else {
                Notification.Builder(this, "pet")
                    .setContentTitle("桌宠运行中")
                    .setContentText("点按打开控制页")
                    .setSmallIcon(android.R.drawable.sym_def_app_icon)
                    .setContentIntent(pi)
                    .build()
            }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun showPet() {
        val size = petSizePx()
        val height = (size * 9f / 16f).toInt()

        val params = WindowManager.LayoutParams(
            size, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = 24
            y = 160
        }
        layoutParams = params

        val web = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            webViewClient = WebViewClient()
        }
        webView = web

        // 容器拦截全部触摸（onInterceptTouchEvent=true），
        // 触摸由容器的 OnTouchListener 统一处理拖动与点按，WebView 不参与手势
        val frame = object : FrameLayout(this) {
            override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = true
        }.apply { setBackgroundColor(Color.TRANSPARENT) }
        frame.addView(web, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // 拖动 + 轻点判定
        var downRawX = 0f; var downRawY = 0f
        var startX = 0; var startY = 0
        var moved = false
        frame.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    startX = params.x; startY = params.y
                    moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (moved || dx * dx + dy * dy > 100) {
                        moved = true
                        params.x = startX + dx
                        params.y = startY - dy
                        windowManager.updateViewLayout(frame, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) web.evaluateJavascript("window.petTap && petTap()", null)
                    true
                }
                else -> false
            }
        }

        container = frame
        windowManager.addView(frame, params)

        // JS 端逐帧测量宠物不透明边界 → 动态把悬浮窗缩到贴合宠物本体（两侧空白不遮挡）
        web.addJavascriptInterface(object : Any() {
            @android.webkit.JavascriptInterface
            fun onBox(l: Double, t: Double, w: Double, h: Double) {
                if (w <= 0.0 || h <= 0.0) return
                runOnUiThread { applyPetBox(params, frame, l, t, w, h) }
            }
        }, "PetBox")

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                injectConfig()
            }
        }
        web.loadUrl("file:///android_asset/pet/index.html")
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun runOnUiThread(block: () -> Unit) { mainHandler.post(block) }

    /**
     * JS 报告宠物边界（源视频 640x360 中的归一化 l/t/w/h）。
     * 悬浮窗宽度保持「设定宠物宽度」，高度按边界纵横比收缩；
     * 中心点保持不动。变化幅度 <5% 时忽略，避免窗口抖动。
     */
    private fun applyPetBox(params: WindowManager.LayoutParams, frame: View, l: Double, t: Double, w: Double, h: Double) {
        val oldW = params.width
        val oldH = params.height
        if (oldW <= 0 || oldH <= 0) return
        // 边界纵横比（h/w 的像素比）：(h/360) / (w/640) = h*640 / (w*360)
        val newH = (oldW.toDouble() * (h * 640.0) / (w * 360.0)).toInt().coerceAtLeast(oldW / 3)
        if (Math.abs(newH - oldH) < oldH * 0.05) return
        params.height = newH
        // gravity=BOTTOM：y 是窗口底边到屏幕底边的距离，保持中心 → y += (oldH - newH)/2
        params.y = Math.max(0, params.y + (oldH - newH) / 2)
        windowManager.updateViewLayout(frame, params)
    }

    /** 从 assets 读 pet-config.json，注入页面后启动动画链 */
    private fun injectConfig() {
        try {
            val json = assets.open("pet/pet-config.json").bufferedReader().use { it.readText() }
            JSONObject(json) // 校验 JSON 合法，坏了就别启动动画链
            webView?.evaluateJavascript(
                "window.__PET_CONFIG__ = $json; window.startPet && window.startPet();", null
            )
        } catch (e: Exception) {
            android.util.Log.e("PetService", "config inject failed", e)
        }
    }

    override fun onDestroy() {
        isRunning = false
        container?.let { windowManager.removeView(it) }
        container = null
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
