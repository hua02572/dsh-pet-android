package com.hua.dshpet

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private val overlayRequestCode = 1001
    private var statusText: TextView? = null
    private val statusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshStatus = object : Runnable {
        override fun run() {
            statusText?.text = PetService.debugStatus
            statusHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        val title = TextView(this).apply {
            text = "桌宠 1.0.2（预采样裁剪版）\n\n1. 点「开启桌宠」\n2. 允许「显示在其他应用上层」\n3. 回来再点一次「开启桌宠」\n\n粉色边框表示真正拦截触摸的窗口范围。"
            textSize = 15f
        }
        val btnStart = Button(this).apply { text = "开启桌宠" }
        val btnStop = Button(this).apply { text = "停止桌宠" }

        // 宠物大小三档（宽度 dp；高度自动 9:16）
        val sizeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val sizeTitle = TextView(this).apply { text = "宠物大小：" }
        fun sizeButton(label: String, dp: Int): Button {
            val b = Button(this).apply { text = label }
            b.setOnClickListener {
                getSharedPreferences("pet", MODE_PRIVATE).edit().putInt("sizeDp", dp).apply()
                if (PetService.isRunning) {
                    stopService(Intent(this, PetService::class.java))
                    startForegroundService(Intent(this, PetService::class.java))
                }
            }
            return b
        }
        sizeRow.addView(sizeTitle)
        sizeRow.addView(sizeButton("小", 200))
        sizeRow.addView(sizeButton("中", 280))
        sizeRow.addView(sizeButton("大", 360))

        val btnApply = Button(this).apply { text = "应用大小并重启" }
        btnApply.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                stopService(Intent(this, PetService::class.java))
                startForegroundService(Intent(this, PetService::class.java))
            }
        }

        btnStart.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                startForegroundService(Intent(this, PetService::class.java))
            } else {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, overlayRequestCode)
            }
        }
        btnStop.setOnClickListener {
            stopService(Intent(this, PetService::class.java))
        }

        root.addView(title)
        root.addView(btnStart)
        root.addView(btnStop)
        root.addView(sizeRow)
        root.addView(btnApply)
        val debugBox = CheckBox(this).apply {
            text = "显示触摸窗口边框"
            isChecked = getSharedPreferences("pet", MODE_PRIVATE).getBoolean("debugBounds", false)
            setOnCheckedChangeListener { _, checked ->
                getSharedPreferences("pet", MODE_PRIVATE).edit().putBoolean("debugBounds", checked).apply()
                if (PetService.isRunning) {
                    stopService(Intent(this@MainActivity, PetService::class.java))
                    startForegroundService(Intent(this@MainActivity, PetService::class.java))
                }
            }
        }
        root.addView(debugBox)
        statusText = TextView(this).apply { text = PetService.debugStatus }
        root.addView(statusText)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        statusHandler.post(refreshStatus)
    }

    override fun onPause() {
        statusHandler.removeCallbacks(refreshStatus)
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == overlayRequestCode && Settings.canDrawOverlays(this)) {
            startForegroundService(Intent(this, PetService::class.java))
        }
    }
}

