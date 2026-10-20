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

    private lateinit var windowManager: WindowManager
    private var container: FrameLayout? = null
    private var webView: WebView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showPet()
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

    private fun petSizePx(): Int = (300 * resources.displayMetrics.density).toInt()

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
            // 页面内所有触摸都透传给容器的拖拽/点按判定
            setOnTouchListener { _, _ -> true }
        }
        webView = web

        val frame = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
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

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                injectConfig()
            }
        }
        web.loadUrl("file:///android_asset/pet/index.html")
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
        container?.let { windowManager.removeView(it) }
        container = null
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
