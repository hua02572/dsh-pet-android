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
import kotlin.math.roundToInt

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

    /** 完整动画画布宽度；裁掉透明区域时保持这个绘制比例，避免宠物忽大忽小。 */
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
        val height = (size * 9f / 16f).roundToInt()

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
            // 关键：允许 file:// 页面读取同源媒体画入 canvas 的像素，
            // 否则 getImageData 抛 SecurityError（画布被污染），边界测量失效
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = true
            webViewClient = WebViewClient()
        }
        webView = web

        // 容器拦截全部触摸（onInterceptTouchEvent=true），
        // 触摸由容器的 OnTouchListener 统一处理拖动与点按，WebView 不参与手势
        val frame = object : FrameLayout(this) {
            override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = true
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = true
        }
        // WebView 始终按完整 16:9 画布绘制；只缩小外层窗口并平移子视图，
        // 让透明边缘落在窗口外，不再拦截屏幕触摸，也不拉伸宠物。
        frame.addView(web, FrameLayout.LayoutParams(size, height).apply {
            gravity = Gravity.TOP or Gravity.LEFT
        })

        // 拖动 + 轻点判定
        var downRawX = 0f; var downRawY = 0f
        var lastRawX = 0f; var lastRawY = 0f
        var moved = false
        frame.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    lastRawX = event.rawX; lastRawY = event.rawY
                    moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (moved || dx * dx + dy * dy > 100) {
                        moved = true
                        // 增量位移保留边界测量对窗口位置的修正，拖动期间也不会跳位。
                        params.x += (event.rawX - lastRawX).roundToInt()
                        params.y -= (event.rawY - lastRawY).roundToInt()
                        lastRawX = event.rawX; lastRawY = event.rawY
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
                if (!l.isFinite() || !t.isFinite() || !w.isFinite() || !h.isFinite()) return
                if (l < 0.0 || t < 0.0 || w <= 0.0 || h <= 0.0 ||
                    l + w > 1.000001 || t + h > 1.000001) return
                runOnUiThread { applyPetBox(params, frame, l, t, w, h) }
            }

            @android.webkit.JavascriptInterface
            fun onDebug(msg: String?) {
                android.util.Log.w("PetService", "js: $msg")
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
     * 用完整画布的固定比例计算窗口宽高，同时裁掉左右和上下透明区域。
     * 平移窗口与 WebView 的偏移互相抵消，宠物的屏幕位置和大小保持稳定。
     */
    private fun applyPetBox(params: WindowManager.LayoutParams, frame: View, l: Double, t: Double, w: Double, h: Double) {
        val web = webView ?: return
        if (container !== frame) return
        val child = web.layoutParams as FrameLayout.LayoutParams
        val fullW = child.width
        val fullH = child.height
        val left = (l * fullW).roundToInt().coerceIn(0, fullW - 1)
        val top = (t * fullH).roundToInt().coerceIn(0, fullH - 1)
        val right = ((l + w) * fullW).roundToInt().coerceIn(left + 1, fullW)
        val bottom = ((t + h) * fullH).roundToInt().coerceIn(top + 1, fullH)
        val newW = right - left
        val newH = bottom - top
        val oldLeft = -child.leftMargin
        val oldBottomInset = fullH + child.topMargin - params.height
        val bottomInset = fullH - bottom
        if (params.width == newW && params.height == newH &&
            left == oldLeft && bottomInset == oldBottomInset) return

        params.x += left - oldLeft
        // gravity=BOTTOM：窗口底边向上裁掉多少，y 就增加多少。
        params.y += bottomInset - oldBottomInset
        params.width = newW
        params.height = newH
        child.leftMargin = -left
        child.topMargin = -top
        web.layoutParams = child
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
        mainHandler.removeCallbacksAndMessages(null)
        container?.let { windowManager.removeView(it) }
        container = null
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
