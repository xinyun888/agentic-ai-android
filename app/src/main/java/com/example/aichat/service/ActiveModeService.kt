package com.example.aichat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.aichat.MainActivity
import com.example.aichat.R
import com.example.aichat.data.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class ActiveModeService : Service() {

    companion object {
        const val CHANNEL_FG = "active_mode_fg"
        const val CHANNEL_PUSH = "active_mode_push"
        const val ACTION_START = "com.example.aichat.ACTION_START_ACTIVE"
        const val ACTION_STOP = "com.example.aichat.ACTION_STOP_ACTIVE"
        const val EXTRA_PERSONA_ID = "persona_id"
        const val EXTRA_INTERVAL_MIN = "interval_min"
        const val EXTRA_IMMERSIVE = "immersive"
        const val EXTRA_SHOW_THINKING = "show_thinking"
        const val EXTRA_START_HOUR = "start_hour"
        const val EXTRA_END_HOUR = "end_hour"

        var isRunning = false
            private set
    }

    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient()
    private val gson = Gson()
    private var personaId = "worker"
    private var intervalMin = 15
    private var immersive = false
    private var showThinking = false
    private var startHour = 0
    private var endHour = 24

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_STICKY
        val action = intent.action ?: ""
        when {
            action == ACTION_START || (action.isEmpty() && !isRunning) -> {
                personaId = intent.getStringExtra(EXTRA_PERSONA_ID) ?: "worker"
                intervalMin = intent.getIntExtra(EXTRA_INTERVAL_MIN, 15)
                immersive = intent.getBooleanExtra(EXTRA_IMMERSIVE, false)
                showThinking = intent.getBooleanExtra(EXTRA_SHOW_THINKING, false)
                startHour = intent.getIntExtra(EXTRA_START_HOUR, 0)
                endHour = intent.getIntExtra(EXTRA_END_HOUR, 24)
                startHeartbeat()
            }
            action == ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        heartbeatJob?.cancel()
        scope.cancel()
        isRunning = false
        super.onDestroy()
    }

    private fun startHeartbeat() {
        if (isRunning) return
        isRunning = true
        val persona = Personas.getById(personaId)
        val fullName = "${persona.emoji} ${persona.name}"
        startForeground(1001, buildNotification(fullName + " 正在陪伴", "主动模式 · ${intervalMin}分钟 · ${if (immersive) "沉浸" else "轻盈"}"))

        heartbeatJob = scope.launch {
            // First heartbeat immediately
            try {
                val result = doHeartbeat(persona)
                if (result.isNotBlank() && result.uppercase() != "PASS") {
                    pushNotification(persona.name, result)
                }
                updateNotification("${fullName} 正在陪伴 · ${if (result.isBlank()) "本轮PASS" else "已推送: ${result.take(15)}…"}")
            } catch (e: Exception) {
                updateNotification("${fullName} 心跳异常 · ${e.message?.take(20) ?: "未知"}")
            }
            // Then loop with delay
            while (isActive) {
                delay(intervalMin * 60_000L)
                if (!isInTimeRange()) continue
                try {
                    val result = doHeartbeat(persona)
                    if (result.isNotBlank() && result != "PASS") {
                        pushNotification(persona.name, result)
                        updateNotification("${fullName} 正在陪伴 · 已推送: ${result.take(15)}…")
                    } else {
                        updateNotification("${fullName} 正在陪伴 · ${if (result.isBlank()) "本轮PASS" else "本轮PASS"}")
                    }
                } catch (e: Exception) {
                    updateNotification("${fullName} 心跳异常 · ${e.message?.take(20) ?: "未知"}")
                }
            }
        }
    }

    private suspend fun doHeartbeat(persona: Persona): String {
        val storage = StorageManager(this)
        val profile = storage.getActiveProfile()
            ?: storage.getProfiles().firstOrNull()
            ?: return ""
        if (profile.apiKey.isBlank()) return ""

        val now = java.time.LocalDateTime.now()
        val timeStr = "${now.monthValue}月${now.dayOfMonth}日 ${now.hour}:${String.format("%02d", now.minute)}"
        val hour = now.hour

        // Detect screen state via PowerManager
        val pm = getSystemService(POWER_SERVICE) as? PowerManager
        val screenOn = pm?.isInteractive ?: false

        val contextNote = buildString {
            if (screenOn) append("用户正在使用手机") else append("手机屏幕处于关闭状态")
            append("。")
        }

        val messages = mutableListOf<ChatMessageDto>()

        // Build judgment prompt based on time of day
        val shouldSkip = hour in 0..6 || (!screenOn && hour in 22..23)
        val pasHint = if (shouldSkip) """
⚠️ 当前是休息时段或用户不在使用手机。你必须严格回复 PASS。
""".trimIndent() else ""

        if (immersive) {
            messages.add(ChatMessageDto(role = "system", content = persona.prompt))
            val memFile = File(filesDir, "memory/memory.md")
            if (memFile.exists()) memFile.readText().take(1500).let {
                if (it.isNotBlank()) messages.add(ChatMessageDto(role = "system", content = "长期记忆：\n$it"))
            }
        } else {
            messages.add(ChatMessageDto(role = "system", content = persona.heartbeatPrompt()))
            val memFile = File(filesDir, "memory/memory.md")
            if (memFile.exists()) memFile.readText().take(400).let {
                if (it.isNotBlank()) messages.add(ChatMessageDto(role = "system", content = "记忆：$it"))
            }
            val convs = storage.getConversations()
            val lastMsgs = convs.filter { it.profileId == personaId || it.profileId.isBlank() }
                .flatMap { it.messages }.filter { it.role != "system" }.takeLast(4)
            lastMsgs.forEach {
                messages.add(ChatMessageDto(role = it.role, content = it.content))
            }
        }

        val userPrompt = buildString {
            append(pasHint)
            append("现在是 $timeStr。$contextNote\n")
            if (shouldSkip) {
                append("现在不是合适的聊天时间。你必须回复 PASS。")
            } else {
                append("随便说点什么吧，一句简短的话就够了。可以问候、吐槽、或者随便聊聊。")
                append("不要回复 PASS，除非你真的完全不想说话。")
            }
        }

        messages.add(ChatMessageDto(role = "user", content = userPrompt))
        val bodyMap = mutableMapOf<String, Any>(
            "model" to profile.model,
            "messages" to messages.map { mapOf("role" to it.role, "content" to (it.content ?: "")) },
            "temperature" to 0.7,
            "max_tokens" to if (immersive) 512 else 350,
            "stream" to false
        )
        if (showThinking) bodyMap["thinking"] = mapOf("type" to "enabled")
        val bodyJson = gson.toJson(bodyMap)
        val response = try {
            client.newCall(Request.Builder()
                .url("${profile.baseUrl}/chat/completions")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer ${profile.apiKey}")
                .build()).execute()
        } catch (_: Exception) { return "" }

        val bodyStr = response.body?.string() ?: return ""
        response.close()
        val json = try { gson.fromJson(bodyStr, Map::class.java) as? Map<*, *> } catch (_: Exception) { return "" } ?: return ""
        val choices = json["choices"] as? List<*> ?: return ""
        val first = choices.firstOrNull() as? Map<*, *> ?: return ""
        val msg = first["message"] as? Map<*, *> ?: return ""
        val content = msg["content"]?.toString()?.trim() ?: return ""
        // Match "PASS" or "PASS（...）" etc
        val isPass = content.uppercase().startsWith("PASS") || 
                      content.equals("PASS", ignoreCase = true)
        return if (isPass) "" else content
    }

    private fun pushNotification(title: String, content: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_conversation", personaId)
        }
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val display = if (content.length > 200) content.take(200) + "…" else content
        val notif = NotificationCompat.Builder(this, CHANNEL_PUSH)
            .setContentTitle("${Personas.getById(personaId).emoji} $title")
            .setContentText(display)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .build()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify((System.currentTimeMillis() % 100000).toInt(), notif)
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1001, buildNotification("", text))
    }

    private fun buildNotification(title: String, text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_FG)
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi).setOngoing(true).build()
    }

    private fun isInTimeRange(): Boolean {
        val now = java.time.LocalTime.now()
        return now.hour in startHour until endHour
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val fg = NotificationChannel(CHANNEL_FG, "主动模式", NotificationManager.IMPORTANCE_LOW).apply { description = "常驻通知" }
            val push = NotificationChannel(CHANNEL_PUSH, "角色消息", NotificationManager.IMPORTANCE_HIGH).apply { description = "角色主动推送" }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(fg)
            nm.createNotificationChannel(push)
        }
    }
}
