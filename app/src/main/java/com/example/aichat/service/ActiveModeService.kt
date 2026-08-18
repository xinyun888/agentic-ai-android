package com.example.aichat.service

import android.app.Notification
import android.app.AlarmManager
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
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ActiveModeService : Service() {

    companion object {
        const val CHANNEL_FG = "active_mode_fg"
        const val CHANNEL_PUSH = "active_mode_push"
        const val ACTION_START = "com.example.aichat.ACTION_START_ACTIVE"
        const val ACTION_STOP = "com.example.aichat.ACTION_STOP_ACTIVE"
        const val ACTION_HEARTBEAT = "com.example.aichat.ACTION_ACTIVE_HEARTBEAT"
        const val ACTION_BOOT_RESUME = "com.example.aichat.ACTION_BOOT_RESUME"
        const val EXTRA_PERSONA_ID = "persona_id"
        const val EXTRA_CONV_ID = "conv_id"
        const val EXTRA_INTERVAL_MIN = "interval_min"
        const val EXTRA_IMMERSIVE = "immersive"
        const val EXTRA_SHOW_THINKING = "show_thinking"
        const val EXTRA_START_HOUR = "start_hour"
        const val EXTRA_END_HOUR = "end_hour"

        /** 正在运行的角色集合，支持多开 */
        val runningPersonas: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val runningConversations: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun isRunning(personaId: String): Boolean = personaId in runningPersonas
    }

    private data class ActiveConfig(
        val convId: String, val intervalMin: Int, val immersive: Boolean,
        val showThinking: Boolean, val startHour: Int, val endHour: Int
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = HttpClient.instance
    private val gson = Gson()

    /** 每个角色一个心跳 Job */
    private val jobs = ConcurrentHashMap<String, Job>()
    /** personaId → convId */
    private val personaConvs = ConcurrentHashMap<String, String>()
    /** personaId → 心跳配置，进程被杀后由闹钟恢复 */
    private val configs = ConcurrentHashMap<String, ActiveConfig>()

    private fun prefs() = getSharedPreferences("active_mode", MODE_PRIVATE)

    private fun loadConfigsFromPrefs() {
        try {
            val json = prefs().getString("configs", null) ?: return
            // getParameterized 显式构造泛型，绕开 R8 剥离签名导致的 Missing type parameter
            val type = com.example.aichat.data.GsonTypes.map(String::class.java, ActiveConfig::class.java)
            val map: Map<String, ActiveConfig> = gson.fromJson(json, type) ?: return
            configs.putAll(map)
            runningPersonas.addAll(map.keys)
            map.values.forEach { cfg -> if (cfg.convId.isNotBlank()) runningConversations.add(cfg.convId) }
        } catch (_: Exception) {}
    }

    private fun saveConfigsToPrefs() {
        try { prefs().edit().putString("configs", gson.toJson(configs)).apply() } catch (_: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        com.example.aichat.data.UsageMeter.init(this)
        // 恢复持久化配置，进程被杀后由闹钟拉起时能继续心跳
        loadConfigsFromPrefs()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_STICKY
        val action = intent.action ?: ""
        val personaId = intent.getStringExtra(EXTRA_PERSONA_ID) ?: "worker"
        when {
            action == ACTION_START -> {
                val convId = intent.getStringExtra(EXTRA_CONV_ID) ?: ""
                val intervalMin = intent.getIntExtra(EXTRA_INTERVAL_MIN, 15)
                val immersive = intent.getBooleanExtra(EXTRA_IMMERSIVE, false)
                val showThinking = intent.getBooleanExtra(EXTRA_SHOW_THINKING, false)
                val startHour = intent.getIntExtra(EXTRA_START_HOUR, 0)
                val endHour = intent.getIntExtra(EXTRA_END_HOUR, 24)
                startHeartbeat(personaId, convId, intervalMin, immersive, showThinking, startHour, endHour)
            }
            action == ACTION_STOP -> {
                stopHeartbeat(personaId)
            }
            action == ACTION_HEARTBEAT -> {
                // 闹钟唤醒，后台/被杀也能执行心跳
                handleHeartbeat(personaId)
            }
            action == ACTION_BOOT_RESUME -> {
                // 设备重启后恢复：重新注册所有陪伴角色的闹钟
                configs.keys.forEach { pid ->
                    if (pid in runningPersonas) scheduleAlarm(pid)
                }
                if (configs.isEmpty()) stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        scope.cancel()
        super.onDestroy()
        // 不在这里清 configs / 取消闹钟：进程被杀时，持久化配置和已注册的闹钟必须存活，下次才能恢复心跳
    }

    private fun buildHeartbeatPI(personaId: String): PendingIntent {
        val intent = Intent(this, ActiveModeService::class.java).apply {
            action = ACTION_HEARTBEAT
            putExtra(EXTRA_PERSONA_ID, personaId)
        }
        return PendingIntent.getService(this, 1000 + Math.floorMod(personaId.hashCode(), 1000),
            intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun scheduleAlarm(personaId: String) {
        val cfg = configs[personaId] ?: return
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        val at = System.currentTimeMillis() + cfg.intervalMin * 60_000L
        // Android 12+ 精确闹钟可能被用户拒绝，检测后降级为普通闹钟
        val canExact = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (canExact) {
                // 精确闹钟，Doze 下也能触发
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, buildHeartbeatPI(personaId))
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, buildHeartbeatPI(personaId))
            }
        } catch (_: Exception) {
            try { am.set(AlarmManager.RTC_WAKEUP, at, buildHeartbeatPI(personaId)) }
            catch (_: Exception) {}
        }
    }

    private fun cancelAlarm(personaId: String) {
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        am.cancel(buildHeartbeatPI(personaId))
    }

    private fun startHeartbeat(
        personaId: String, convId: String, intervalMin: Int, immersive: Boolean,
        showThinking: Boolean, startHour: Int, endHour: Int
    ) {
        // 已运行则忽略重复开启
        if (personaId in runningPersonas) return
        runningPersonas.add(personaId)
        if (convId.isNotBlank()) {
            runningConversations.add(convId)
            personaConvs[personaId] = convId
        }

        val persona = Personas.getByIdWithCustom(personaId, this)
        val fullName = "${persona.emoji} ${persona.name}"

        // 每个角色独立的前台通知 id
        val fgId = 2000 + Math.floorMod(personaId.hashCode(), 1000)
        try {
            startForeground(fgId, buildNotification(fullName + " 正在陪伴", "主动模式 · ${intervalMin}分钟 · 每轮心跳写回对话", fgId))
        } catch (_: Exception) {
            // 前台服务启动失败时不阻断配置保存，后续闹钟仍可尝试拉起
        }

        // 持久化配置，进程被杀后闹钟拉起时能恢复
        configs[personaId] = ActiveConfig(convId, intervalMin, immersive, showThinking, startHour, endHour)
        saveConfigsToPrefs()
        // 间隔后首次心跳，不立即触发
        updateNotification(fgId, fullName, "${intervalMin}分钟后首次心跳")
        scheduleAlarm(personaId)
    }

    /** 闹钟唤醒时执行心跳 */
    private fun handleHeartbeat(personaId: String) {
        val cfg = configs[personaId] ?: return
        if (personaId !in runningPersonas) return
        val persona = Personas.getByIdWithCustom(personaId, this)
        val fgId = 2000 + Math.floorMod(personaId.hashCode(), 1000)
        val fullName = "${persona.emoji} ${persona.name}"

        // 闹钟拉起服务时确保前台状态
        try {
            startForeground(fgId, buildNotification(fullName + " 正在陪伴", "心跳中...", fgId))
        } catch (_: Exception) {}

        jobs[personaId]?.cancel()
        // 先注册下一次闹钟再发请求：即使进程在请求中途被杀，心跳链也不断
        scheduleAlarm(personaId)
        jobs[personaId] = scope.launch {
            try {
                if (!isInTimeRange(cfg.startHour, cfg.endHour)) {
                    updateNotification(fgId, fullName, "休息时段跳过")
                    return@launch
                }
                val result = doHeartbeat(persona, cfg.convId, cfg.immersive, cfg.showThinking)
                if (result.isNotBlank() && !result.uppercase().startsWith("PASS")) {
                    pushNotification(persona, result)
                    saveToConversation(cfg.convId, persona, result)
                    updateNotification(fgId, fullName, "已推送")
                } else {
                    updateNotification(fgId, fullName, "本轮PASS")
                }
            } catch (e: Exception) {
                updateNotification(fgId, fullName, "心跳异常: ${e.message?.take(20) ?: "未知"}")
            } finally {
                jobs.remove(personaId)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun stopHeartbeat(personaId: String) {
        jobs.remove(personaId)?.cancel()
        cancelAlarm(personaId)
        configs.remove(personaId)
        saveConfigsToPrefs()
        runningPersonas.remove(personaId)
        personaConvs.remove(personaId)?.let { runningConversations.remove(it) }
        // 最后一个角色停止后，前台服务没有继续存在的必要
        if (runningPersonas.isEmpty()) {
            stopForeground(true)
            stopSelf()
        }
    }

    private suspend fun doHeartbeat(persona: Persona, convId: String, immersive: Boolean, showThinking: Boolean): String {
        val storage = StorageManager(this)
        val profile = storage.getActiveProfile()
            ?: storage.getProfiles().firstOrNull()
            ?: return ""
        if (profile.apiKey.isBlank()) return ""

        val now = java.time.LocalDateTime.now()
        val timeStr = "${now.monthValue}月${now.dayOfMonth}日 ${now.hour}:${String.format("%02d", now.minute)}"
        val hour = now.hour

        // 检测屏幕亮灭
        val pm = getSystemService(POWER_SERVICE) as? PowerManager
        val screenOn = pm?.isInteractive ?: false

        val contextNote = buildString {
            if (screenOn) append("用户正在使用手机") else append("手机屏幕处于关闭状态")
            append("。")
        }

        val messages = mutableListOf<ChatMessageDto>()

        // 按时段构建判断提示
        val shouldSkip = hour in 0..6 || (!screenOn && hour in 22..23)
        val pasHint = if (shouldSkip) """
⚠️ 当前是休息时段或用户不在使用手机。你必须严格回复 PASS。
""".trimIndent() else ""

        // 记忆按对话隔离
        val memDir = if (convId.isBlank()) File(filesDir, "memory")
                     else File(File(filesDir, "memory"), convId.replace(Regex("[^a-zA-Z0-9_-]"), "_")).also { it.mkdirs() }

        // 极简/沉浸模式给不同的思维链协议：让思考方式本质不同，而不是只靠 max_tokens 决定长短
        // 统一要求“第一人称强判断”，避免普通模式出现“让我先/我来”这类偏弱的开头
        val thinkingStyle = if (immersive) {
            when (profile.reasoningLevel) {
                "fast" -> "\n\n直接以第一人称进入推理，禁止“让我先/我来”开头。按“问题本质 → 关键依据 → 结论”输出简洁强判断。"
                "deep" -> "\n\n直接以第一人称进入推理，禁止“让我先/我来”开头。按“问题本质 → 假设 → 验证 → 多角度分析 → 结论与风险”输出完整强判断。"
                else -> "\n\n直接以第一人称进入推理，禁止“让我先/我来”开头。按“问题 → 关键依据 → 推理 → 结论”输出，先判断再依据。"
            }
        } else {
            "\n\n直接以第一人称极简推理：问题本质 → 关键依据 → 结论。禁止“让我先/我来”开头，保持强判断。"
        }

        if (immersive) {
            messages.add(ChatMessageDto(role = "system", content = persona.prompt + thinkingStyle))
            val memFile = File(memDir, "memory.md")
            if (memFile.exists()) memFile.readText().take(1500).let {
                if (it.isNotBlank()) messages.add(ChatMessageDto(role = "system", content = "长期记忆：\n$it"))
            }
            // 沉浸模式也必须带最近对话上下文，否则只有人设没有聊天记忆，反而比极简模式弱
            val conv = convId.ifBlank { null }?.let { StorageManager(this).getConversation(it) }
            val lastMsgs = conv?.messages?.filter { it.role != "system" }?.takeLast(8)
            lastMsgs?.forEach {
                messages.add(ChatMessageDto(role = it.role, content = it.content))
            }
        } else {
            messages.add(ChatMessageDto(role = "system", content = persona.heartbeatPrompt() + thinkingStyle))
            val memFile = File(memDir, "memory.md")
            if (memFile.exists()) memFile.readText().take(400).let {
                if (it.isNotBlank()) messages.add(ChatMessageDto(role = "system", content = "记忆：$it"))
            }
            // 取本对话最近消息作上下文
            val conv = convId.ifBlank { null }?.let { StorageManager(this).getConversation(it) }
            val lastMsgs = conv?.messages?.filter { it.role != "system" }?.takeLast(4)
            lastMsgs?.forEach {
                messages.add(ChatMessageDto(role = it.role, content = it.content))
            }
        }

        // 从 appointments.md 提取约定
        val apptFile = File(memDir, "appointments.md")
        val apptBlocks = if (apptFile.exists()) {
            apptFile.readText().split("\n## ").mapIndexed { i, p ->
                if (i == 0) p.removePrefix("## ").trim() else p.trim()
            }.filter { it.startsWith("约定-") }
        } else emptyList()

        val userPrompt = buildString {
            append(pasHint)
            append("现在是 $timeStr。$contextNote\n")
            if (shouldSkip) {
                append("现在不是合适的聊天时间。你必须回复 PASS。")
            } else {
                append("根据你们最近的聊天内容自然地继续聊下去。")
                append("如果之前有约定或没聊完的话题（比如约好了一会儿一起玩、晚点再聊），主动提起并延续它。")
                append("不要聊与最近对话完全无关的新话题。")
                append("说一句简短的话就够了。不要回复 PASS，除非你真的完全不想说话。")
            }
            if (apptBlocks.isNotEmpty()) {
                val summaries = apptBlocks.map { b ->
                    val title = b.substringBefore("\n")
                    val body = b.substringAfter("\n", "").replace(Regex("\\[[^]]*]"), "").trim()
                    "$title：$body"
                }.take(5)
                append("\n\n【你们之间的约定】\n${summaries.joinToString("\n")}")
                append("\n根据当前时间判断：如果某个约定现在可以提起了就主动提；还没到或已过期就忽略。")
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
        bodyMap["reasoning_effort"] = when (profile.reasoningLevel) {
            "fast" -> "low"
            "deep" -> "max"
            else -> "medium"
        }
        if (showThinking) bodyMap["thinking"] = mapOf("type" to "enabled")
        val bodyJson = gson.toJson(bodyMap)
        val bodyStr = try {
            client.newCall(Request.Builder()
                .url("${profile.baseUrl.trim().trimEnd('/')}/chat/completions")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer ${profile.apiKey}")
                .build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use ""
                    resp.body?.string() ?: return@use ""
                }
        } catch (_: Exception) { return "" }
        if (bodyStr.isBlank()) return ""
        val json = try { gson.fromJson(bodyStr, Map::class.java) as? Map<*, *> } catch (_: Exception) { return "" } ?: return ""
        // 心跳请求也计入用量
        try {
            (json["usage"] as? Map<*, *>)?.let { u ->
                val toL = { v: Any? -> ((v as? Double) ?: 0.0).toLong() }
                com.example.aichat.data.UsageMeter.record(
                    toL(u["prompt_tokens"]), toL(u["completion_tokens"]),
                    toL(u["prompt_cache_hit_tokens"]), toL(u["prompt_cache_miss_tokens"])
                )
            }
        } catch (_: Exception) {}
        val choices = json["choices"] as? List<*> ?: return ""
        val first = choices.firstOrNull() as? Map<*, *> ?: return ""
        val msg = first["message"] as? Map<*, *> ?: return ""
        val content = msg["content"]?.toString()?.trim() ?: return ""
        // 匹配 "PASS" 或 "PASS（...）"
        val isPass = content.uppercase().startsWith("PASS") ||
                      content.equals("PASS", ignoreCase = true)
        // 约定生命周期：执行过→删，过期 3 次→删；PASS（休息时段）不算过期
        if (!isPass && apptFile.exists() && apptBlocks.isNotEmpty()) {
            updateAppointments(apptFile, apptBlocks, content)
        }
        return if (isPass) "" else content
    }

    // 约定生命周期：心跳回复提到约定关键词→删除；否则过期计数，满 3 次→删除
    private fun updateAppointments(apptFile: File, blocks: List<String>, result: String) {
        try {
            val keepBlocks = mutableListOf<String>()
            var changed = false
            for (block in blocks) {
                val body = block.substringAfter("\n", "").trim()
                val keyword = body.replace(Regex("\\[[^]]*]"), "").trim().take(10)
                if (keyword.isNotBlank() && result.contains(keyword)) {
                    changed = true  // 已执行/回应，删除
                    continue
                }
                val cntRegex = Regex("""\[过期(\d+)次]""")
                val cnt = cntRegex.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val newCnt = cnt + 1
                if (newCnt >= 3) {
                    changed = true  // 过期 3 次，删除
                } else {
                    val newBody = if (cnt == 0) "$body [过期1次]" else body.replace(cntRegex, "[过期${newCnt}次]")
                    keepBlocks.add("## ${block.substringBefore("\n")}\n$newBody")
                    changed = true
                }
            }
            if (changed) {
                apptFile.writeText(keepBlocks.joinToString("\n").trim() + if (keepBlocks.isEmpty()) "" else "\n")
                if (keepBlocks.isEmpty()) apptFile.delete()
            }
        } catch (_: Exception) {}
    }

    private fun pushNotification(persona: Persona, content: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_persona", persona.id)
        }
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val display = if (content.length > 200) content.take(200) + "…" else content
        val notif = NotificationCompat.Builder(this, CHANNEL_PUSH)
            .setContentTitle("${persona.emoji} ${persona.name}")
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

    private fun saveToConversation(convId: String, persona: Persona, content: String) {
        try {
            if (convId.isBlank()) return
            StorageManager(this).appendMessage(convId, ChatMessage(
                role = "assistant",
                content = "[${persona.emoji}${persona.name}] $content",
                timestamp = System.currentTimeMillis()
            ))
        } catch (_: Exception) {}
    }

    private fun updateNotification(fgId: Int, fullName: String, text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(fgId, buildNotification(fullName + " 正在陪伴", text, fgId))
    }

    private fun buildNotification(title: String, text: String, fgId: Int): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, fgId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_FG)
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi).setOngoing(true).build()
    }

    private fun isInTimeRange(startHour: Int, endHour: Int): Boolean {
        val h = java.time.LocalTime.now().hour
        return if (startHour < endHour) {
            h in startHour until endHour
        } else {
            // 跨午夜时段（如 22→6）：22-23 或 0-5 都算范围内
            h >= startHour || h < endHour
        }
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
