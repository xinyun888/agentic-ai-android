package com.example.aichat.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

// 设备重启后恢复陪伴模式：拉起服务重新注册闹钟
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val i = Intent(context, ActiveModeService::class.java).apply {
            action = ActiveModeService.ACTION_BOOT_RESUME
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        } catch (_: Exception) {}
    }
}
