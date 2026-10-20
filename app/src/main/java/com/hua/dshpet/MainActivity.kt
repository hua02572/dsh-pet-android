package com.hua.dshpet

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private val overlayRequestCode = 1001

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
            text = "桌宠（dsh-pet 安卓版）\n\n1. 点「开启桌宠」\n2. 系统跳转授权页时，允许「显示在其他应用上层」\n3. 回来再点一次「开启桌宠」"
            textSize = 15f
        }
        val btnStart = Button(this).apply { text = "开启桌宠" }
        val btnStop = Button(this).apply { text = "停止桌宠" }

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
        setContentView(root)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == overlayRequestCode && Settings.canDrawOverlays(this)) {
            startForegroundService(Intent(this, PetService::class.java))
        }
    }
}
