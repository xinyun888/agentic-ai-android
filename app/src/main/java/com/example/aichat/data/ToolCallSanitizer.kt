
package com.example.aichat.data

/**
 * 工具协议清洗器：保证发给 API 的消息满足
 *   assistant(tool_calls) 之后必须紧跟**每个** tool_call_id 对应的 role="tool" 消息
 *
 * 为什么需要：上下文压缩 / 断点恢复 / 中途取消 都可能只删掉 tool 结果而留下 assistant(tool_calls)，
 * 或者反过来留下孤立的 tool 消息。DeepSeek 会直接报
 *   400 "An assistant message with 'tool_calls' must be followed by tool messages responding to each 'tool_call_id'"
 * 这里做一次确定性修复（纯函数，可离线自测），让任何历史裁剪都不会再把 400 抛给用户。
 */
object ToolCallSanitizer {

    fun sanitize(input: List<ChatMessageDto>): List<ChatMessageDto> {
        if (input.isEmpty()) return input
        val out = ArrayList<ChatMessageDto>(input.size)
        var i = 0
        while (i < input.size) {
            val m = input[i]
            // 1) assistant + tool_calls：检查紧随其后的 tool 结果是否齐全
            if (m.role == "assistant" && !m.toolCalls.isNullOrEmpty()) {
                val ids = m.toolCalls.mapNotNull { it.id }.toSet()
                var j = i + 1
                val tools = LinkedHashMap<String, ChatMessageDto>()
                while (j < input.size && input[j].role == "tool") {
                    val t = input[j]
                    val id = t.toolCallId
                    if (id != null && ids.contains(id) && !tools.containsKey(id)) tools[id] = t
                    j++
                }
                if (ids.isNotEmpty() && tools.size == ids.size) {
                    out.add(m)
                    ids.forEach { id -> tools[id]?.let { out.add(it) } }   // 按 id 顺序补齐
                } else {
                    // 结果不全：丢掉 tool_calls，只保留文本内容（避免整条消息丢失语义）
                    if (!m.content?.toString().isNullOrBlank()) out.add(m.copy(toolCalls = null, reasoningContent = null))
                }
                i = j
                continue
            }
            // 2) 孤立的 tool 消息（没有匹配的 assistant(tool_calls)）直接丢弃
            if (m.role == "tool") { i++; continue }
            out.add(m)
            i++
        }
        return out
    }

    /** 自检：可离线在 JVM 上跑（不依赖 Android） */
    fun selfTest(): List<String> {
        val fails = mutableListOf<String>()
        fun check(name: String, ok: Boolean) { if (!ok) fails.add(name) }

        fun toolCall(id: String, name: String = "gua_yao") = ToolCallDto(
            id = id, function = ToolCallFunctionDto(name = name, arguments = "{}")
        )

        // 1) 正常配对：原样保留（顺序按 tool_call 顺序）
        val okList = listOf(
            ChatMessageDto(role = "user", content = "起一卦"),
            ChatMessageDto(role = "assistant", content = null, toolCalls = listOf(toolCall("c1"))),
            ChatMessageDto(role = "tool", content = "卦象结果", toolCallId = "c1")
        )
        val s1 = sanitize(okList)
        check("balanced keeps", s1.size == 3 && s1[2].role == "tool" && s1[2].toolCallId == "c1")

        // 2) 只有 tool_calls、没有 tool 结果（压缩只删了 tool） 丢 tool_calls，保留文本
        val orphan = listOf(
            ChatMessageDto(role = "assistant", content = "我先看看", toolCalls = listOf(toolCall("c1"))),
            ChatMessageDto(role = "user", content = "然后呢")
        )
        val s2 = sanitize(orphan)
        check("orphan tool_calls dropped", s2.size == 2 && s2[0].toolCalls == null && s2[0].content == "我先看看")

        // 3) 两个 tool_call 只回了一个  整组降级（不允许半配对）
        val half = listOf(
            ChatMessageDto(role = "assistant", content = null, toolCalls = listOf(toolCall("c1"), toolCall("c2"))),
            ChatMessageDto(role = "tool", content = "r1", toolCallId = "c1"),
            ChatMessageDto(role = "user", content = "hi")
        )
        val s3 = sanitize(half)
        check("half pair dropped", s3.size == 1 && s3[0].role == "user")

        // 4) 孤立的 tool 消息  丢弃
        val lone = listOf(
            ChatMessageDto(role = "tool", content = "r", toolCallId = "c9"),
            ChatMessageDto(role = "user", content = "hi")
        )
        val s4 = sanitize(lone)
        check("lone tool dropped", s4.size == 1 && s4[0].role == "user")

        // 5) tool 顺序错乱也按 id 顺序修复
        val shuffled = listOf(
            ChatMessageDto(role = "assistant", content = null, toolCalls = listOf(toolCall("a"), toolCall("b"))),
            ChatMessageDto(role = "tool", content = "rb", toolCallId = "b"),
            ChatMessageDto(role = "tool", content = "ra", toolCallId = "a")
        )
        val s5 = sanitize(shuffled)
        check("reorder by id", s5.size == 3 && s5[1].toolCallId == "a" && s5[2].toolCallId == "b")

        // 6) 无工具消息的普通对话不受影响
        val plain = listOf(ChatMessageDto(role = "system", content = "s"), ChatMessageDto(role = "user", content = "u"))
        check("plain untouched", sanitize(plain) == plain)

        return fails
    }
}
