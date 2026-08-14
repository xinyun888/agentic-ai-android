package com.example.aichat.viewmodel

/**
 * 答案审计器（第二层兜底）：在最终答案落盘前做纯规则检查，不花 token。
 *
 * 背景：模型偶尔会绕过工具直接编造卦象/日期（"我算了一卦，本卦X变卦Y"），
 * 提示词只能降低频率不能根除。审计器用确定性规则把"没调工具却输出结果"的
 * 答案抓出来打标，用户一眼可见。
 *
 * 规则（仅命理师角色生效，避免其他角色误报）：
 * 1. 文本命中卦象关键词，但本轮 agentSteps 无 gua_yao 调用 → 卦象真实性存疑
 * 2. 文本命中农历/干支模式，但本轮无 date_convert/bazi_paipan 调用 → 日期可能不准
 *
 * 返回空列表 = 通过；非空 = 需要追加到消息末尾的警告标注。
 */
object AnswerAuditor {

    // 卦象相关关键词：命中了这些词，答案里就有卦象内容
    private val GUA_KEYWORDS = listOf(
        "本卦", "变卦", "动爻", "梅花", "六爻", "小六壬", "大安", "留连",
        "速喜", "赤口", "小吉", "空亡", "世爻", "应爻", "纳甲", "六亲",
        "互卦", "体用", "卦象", "摇卦", "起卦"
    )

    // 农历/干支模式：
    // 1) "农历/阴历 X月X日" 形式
    // 2) 干支对（1 天干 + 1 地支，如"丙午""甲子"，模型报"丙午年/甲子月"时命中）
    private val LUNAR_PATTERN = Regex("""(农历|阴历|腊月|正月|冬月)[^。，,\n]{0,10}(月|日)""")
    private val GANZHI_PATTERN = Regex("""[甲乙丙丁戊己庚辛壬癸][子丑寅卯辰巳午未申酉戌亥]""")

    /**
     * @param text 最终答案全文
     * @param steps 本轮（本次 sendMessage 以来）的 AgentStep
     * @param personaId 当前角色 id
     * @return 警告标注列表（空 = 通过）
     */
    fun check(text: String, steps: List<AgentStep>, personaId: String): List<String> {
        if (personaId != "fortune") return emptyList()
        if (text.isBlank()) return emptyList()

        val toolNames = steps.map { it.toolName }.filter { it.isNotBlank() }.toSet()
        val hasGua = "gua_yao" in toolNames
        val hasDate = "date_convert" in toolNames || "bazi_paipan" in toolNames

        val warnings = mutableListOf<String>()

        if (!hasGua && GUA_KEYWORDS.any { text.contains(it) }) {
            warnings.add("⚠️ 系统检测：以上卦象未经起卦工具验证，真实性存疑，请勿采信。")
        }
        if (!hasDate && (LUNAR_PATTERN.containsMatchIn(text) || GANZHI_PATTERN.containsMatchIn(text))) {
            warnings.add("⚠️ 系统检测：以上日期/干支未经换算工具验证，可能不准确。")
        }

        return warnings
    }
}
