package com.example.aichat.data

import android.content.Context
import android.content.SharedPreferences

/** 用量计量：累计 DeepSeek 请求的 token 消耗与缓存命中率（存 SharedPreferences） */
object UsageMeter {
    private const val PREFS = "usage_meter"
    private const val K_PROMPT = "prompt_tokens"
    private const val K_COMPLETION = "completion_tokens"
    private const val K_HIT = "cache_hit_tokens"
    private const val K_MISS = "cache_miss_tokens"
    private const val K_REQUESTS = "request_count"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun record(prompt: Long, completion: Long, cacheHit: Long, cacheMiss: Long) {
        val p = prefs ?: return
        if (prompt == 0L && completion == 0L) return
        p.edit()
            .putLong(K_PROMPT, p.getLong(K_PROMPT, 0) + prompt)
            .putLong(K_COMPLETION, p.getLong(K_COMPLETION, 0) + completion)
            .putLong(K_HIT, p.getLong(K_HIT, 0) + cacheHit)
            .putLong(K_MISS, p.getLong(K_MISS, 0) + cacheMiss)
            .putLong(K_REQUESTS, p.getLong(K_REQUESTS, 0) + 1)
            .apply()
    }

    /** 返回摘要文本：累计输入/输出/缓存命中率 */
    fun stats(): String {
        val p = prefs ?: return "计量未初始化"
        val prompt = p.getLong(K_PROMPT, 0)
        val completion = p.getLong(K_COMPLETION, 0)
        val hit = p.getLong(K_HIT, 0)
        val miss = p.getLong(K_MISS, 0)
        val requests = p.getLong(K_REQUESTS, 0)
        val cached = hit + miss
        val hitRate = if (cached > 0) hit * 100 / cached else 0
        return buildString {
            appendLine("累计请求: $requests 次")
            appendLine("输入: ${fmt(prompt)} tokens（缓存命中 ${fmt(hit)} / 未命中 ${fmt(miss)}，命中率 $hitRate%）")
            appendLine("输出: ${fmt(completion)} tokens")
        }
    }

    private fun fmt(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.0fK".format(n / 1_000.0)
        else -> n.toString()
    }
}
