package com.example.aichat.data

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Agent task plan, parsed from model's structured output.
 */
data class TaskPlan(
    val complexity: Int = 1,          // 1-5 self-assessed complexity
    val reasoning: String = "",        // why this complexity level
    val tasks: List<PlanTask> = emptyList(),
    val optimizations: List<Optimization> = emptyList()
)

data class PlanTask(
    val id: Int,
    val description: String,
    val tool: String = "",             // suggested tool name
    val question: String = "",         // clarification question if ambiguous
    var confirmed: Boolean = false,
    var skipped: Boolean = false
)

data class Optimization(
    val original: String,              // which step
    val better: String,                // what's the better approach
    val reason: String = ""
)

/**
 * Plan parser: extracts JSON plan from model output.
 * Returns null if no plan found (simple task).
 */
object PlanParser {
    private val gson = Gson()

    fun tryParse(text: String): TaskPlan? {
        // Look for ```json ... ``` block
        val jsonBlock = Regex("```json\\s*\\n([\\s\\S]*?)\\n```").find(text)
        val json = jsonBlock?.groupValues?.get(1)?.trim() ?: return null

        return try {
            val raw = gson.fromJson(json, TaskPlan::class.java)
            // Gson may set lists to null if missing in JSON → normalize
            val plan = raw.copy(
                tasks = raw.tasks ?: emptyList(),
                optimizations = raw.optimizations ?: emptyList()
            )
            if (plan.tasks.isNotEmpty()) plan else null
        } catch (_: Exception) {
            null
        }
    }

    /** Build a prompt snippet that teaches the model how to output plans */
    fun planInstruction(): String = """
## 任务规划

对于需要多步骤的复杂任务（复杂度 ≥ 3），在调用任何工具之前，先输出一份 JSON 计划：

```json
{
  "complexity": 3,
  "reasoning": "需要搜索数据→分析→画图三步",
  "tasks": [
    {"id": 1, "description": "搜索2024年GDP数据", "tool": "web_fetch"},
    {"id": 2, "description": "用pandas计算增长率", "tool": "python_exec", "question": "需要同比还是环比？"},
    {"id": 3, "description": "生成柱状图", "tool": "python_exec"}
  ],
  "optimizations": [
    {"original": "步骤1搜索", "better": "可查本地已有的data.csv", "reason": "避免重复请求"}
  ]
}
```

规则：
- complexity 1-2 的简单任务（问候、常识问答）直接回答，不输出计划
- 有模糊环节时在 question 中写明需要确认什么
- 完成计划 JSON 后等待用户确认，不要立即执行
- 用户确认后逐步执行，每步完成后汇报进度
    """.trimIndent()
}
