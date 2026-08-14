package com.example.aichat.viewmodel

import com.example.aichat.data.AgentStep

/**
 * 答案审计器（第二层兜底）：在最终答案落盘前做纯规则检查，不花 token。
 *
 * 背景：模型偶尔会绕过工具直接编造卦象/日期（"我算了一卦，本卦X变卦Y"），
 * 提示词只能降低频率不能根除。审计器用确定性规则把"没调工具却输出结果"、
 * "正文卦象与工具结果不一致"、"谎报工具失败"的答案抓出来打标。
 *
 * 规则（仅命理师角色生效，避免其他角色误报）：
 * 1. 文本命中卦象关键词，但本轮无 gua_yao 步骤 → 卦象真实性存疑
 * 2. 文本命中农历/干支模式，但本轮无 date_convert/bazi_paipan → 日期可能不准
 * 3. 正文卦名与 gua_yao 工具输出不一致 → 以工具结果为准
 * 4. 声称"返回空/无结果/工具失败"，但本轮有成功的 gua_yao tool_result → 描述与事实不符
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

    // 全部卦名（一致性校验用）：64 卦 + 小六壬六宫。只收 2+ 字名称，避免单字（乾/坎）在普通文本中误报
    val GUA_NAME_SET: Set<String> = setOf(
        "乾为天", "天泽履", "天火同人", "天雷无妄", "天风姤", "天水讼", "天山遁", "天地否",
        "泽天夬", "兑为泽", "泽火革", "泽雷随", "泽风大过", "泽水困", "泽山咸", "泽地萃",
        "火天大有", "火泽睽", "离为火", "火雷噬嗑", "火风鼎", "火水未济", "火山旅", "火地晋",
        "雷天大壮", "雷泽归妹", "雷火丰", "震为雷", "雷风恒", "雷水解", "雷山小过", "雷地豫",
        "风天小畜", "风泽中孚", "风火家人", "风雷益", "巽为风", "风水涣", "风山渐", "风地观",
        "水天需", "水泽节", "水火既济", "水雷屯", "水风井", "坎为水", "水山蹇", "水地比",
        "山天大畜", "山泽损", "山火贲", "山雷颐", "山风蛊", "山水蒙", "艮为山", "山地剥",
        "地天泰", "地泽临", "地火明夷", "地雷复", "地风升", "地水师", "地山谦", "坤为地",
        "大安", "留连", "速喜", "赤口", "小吉", "空亡"
    )

    // 农历/干支模式：
    // 1) "农历/阴历 X月X日" 形式
    // 2) 干支对（1 天干 + 1 地支，如"丙午""甲子"，模型报"丙午年/甲子月"时命中）
    private val LUNAR_PATTERN = Regex("""(农历|阴历|腊月|正月|冬月)[^。，,\n]{0,10}(月|日)""")
    private val GANZHI_PATTERN = Regex("""[甲乙丙丁戊己庚辛壬癸][子丑寅卯辰巳午未申酉戌亥]""")

    // 模型声称工具没结果/失败的说法——上下文缺 tool_result 时模型的典型幻觉借口
    private val EMPTY_CLAIM_PATTERN = Regex("""(返回空|无结果|空结果|没有返回|没有结果|没结果|返回.*为空|工具失败|执行失败)""")

    /** 扫描文本中出现的卦名（属于 GUA_NAME_SET 的） */
    private fun guaNamesIn(text: String): Set<String> {
        val found = mutableSetOf<String>()
        for (name in GUA_NAME_SET) {
            if (text.contains(name)) found.add(name)
        }
        return found
    }

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

        // 卦象一致性：本轮起过卦（系统注入或模型调用），则正文引用的卦名必须与工具输出一致
        if (hasGua) {
            val toolGuaText = steps.filter { it.type == "tool_result" && it.toolName == "gua_yao" }
                .joinToString("\n") { it.content }
            if (toolGuaText.isNotBlank()) {
                val allowed = guaNamesIn(toolGuaText)
                val inText = guaNamesIn(text)
                val mismatched = inText - allowed
                if (mismatched.isNotEmpty()) {
                    warnings.add("⚠️ 系统检测：正文引用的卦象（${mismatched.joinToString("、")}）与工具结果不一致，以工具结果为准。")
                }
            }
            // 谎报失败：本轮有成功的起卦结果，正文却声称"返回空/无结果/工具失败"
            val hasSuccessResult = steps.any {
                it.type == "tool_result" && it.toolName == "gua_yao" && !it.content.startsWith("❌")
            }
            if (hasSuccessResult && EMPTY_CLAIM_PATTERN.containsMatchIn(text)) {
                warnings.add("⚠️ 系统检测：本轮起卦工具实际已返回完整卦象，上述\"返回空/无结果\"的说法与工具实际输出不符。")
            }
        }

        return warnings
    }
}
