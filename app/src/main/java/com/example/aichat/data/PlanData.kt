package com.example.aichat.data

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Agent 任务计划，从模型的结构化输出解析而来。
 */
data class TaskPlan(
    val complexity: Int = 1,          // 1-5 自评复杂度
    val reasoning: String = "",        // 为什么是这个复杂度级别
    val tasks: List<PlanTask> = emptyList(),
    val optimizations: List<Optimization> = emptyList()
)

data class PlanTask(
    val id: Int,
    val description: String,
    val tool: String = "",             // 建议使用的工具名
    val question: String = "",         // 有歧义时的澄清问题
    var confirmed: Boolean = false,
    var skipped: Boolean = false
)

data class Optimization(
    val original: String,              // 哪一步
    val better: String,                // 更好的方案是什么
    val reason: String = ""
)

/**
 * 计划解析器：从模型输出中提取 JSON 计划。
 * 若没有计划（简单任务）则返回 null。
 */
object PlanParser {
    private val gson = Gson()

    fun tryParse(text: String): TaskPlan? {
        // 查找 ```json ... ``` 代码块
        val jsonBlock = Regex("```json\\s*\\n([\\s\\S]*?)\\n```").find(text)
        val json = jsonBlock?.groupValues?.get(1)?.trim() ?: return null

        return try {
            val raw = gson.fromJson(json, TaskPlan::class.java)
            // Gson 在 JSON 缺失时可能将列表设为 null → 归一化
            val plan = raw.copy(
                tasks = raw.tasks ?: emptyList(),
                optimizations = raw.optimizations ?: emptyList()
            )
            if (plan.tasks.isNotEmpty()) plan else null
        } catch (_: Exception) {
            null
        }
    }

    /** 生成一段教会模型如何输出计划的 prompt 片段 */
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
