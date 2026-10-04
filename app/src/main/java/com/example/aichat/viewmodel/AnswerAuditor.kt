package com.example.aichat.viewmodel

import com.example.aichat.data.AgentStep
import com.example.aichat.data.CardStore
import com.example.aichat.data.PersonCard

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

    // ===== 人物卡相关（第 5-8 条规则）=====
    /** 推断标记：【推断】/【猜测】/（推断）/（猜测）/ 推断： */
    private val INFER_PATTERN = Regex("""【(推断|猜测)】|（(推断|猜测)）|(^|\n)\s*(推断|猜测)\s*[:：]""")
    /** 依据标记：依据/出处/来源/引自/根据上文 */
    private val EVIDENCE_PATTERN = Regex("""(依据|出处|来源|引自|根据上文|卡\[)""")
    /** 卡片引用 卡[x1] / 卡【x1】 */
    private val CARD_REF_PATTERN = Regex("""卡\s*[\[【]\s*([A-Za-z0-9\u4e00-\u9fa5_\-]{1,24})\s*[\]】]""")
    /** 正文引号引用 「」 */
    private val QUOTE_PATTERN = Regex("""[「『]([^」』]{4,120})[」』]""")
    /** 命盘断言口吻（把联想当命盘结论的典型句式） */
    private val CHART_ASSERT_PATTERN = Regex("""(八字|命盘|格局|四柱|日主|十神)[^。\n]{0,12}(就是|注定|说明你|证明你)""")
    // ===== 面相（第 9-12 条规则）=====
    private val FACE_PART_WORDS = listOf("脸型", "三停", "额头", "发际", "眉", "眼", "鼻", "颧", "法令", "唇", "嘴角", "下巴", "下颌", "痣", "气色")
    /** 面相结论口吻（"面相说明""可以看出五官"） */
    private val FACE_CONCLUSION_PATTERN = Regex("""(面相|五官|三停|骨相|脸型)[^。\n]{0,20}(说明|可以看出|代表|主|偏|显示|预示)|(说明|可以看出|代表|预示)[^。\n]{0,12}(面相|五官|三停|骨相)""")
    /** 健康/疾病诊断（面相最容易越界的地方，直接拦） */
    private val HEALTH_DIAG_PATTERN = Regex("""(肝|肾|脾|胃|心脏|肺|血糖|血压|内分泌|气血|经络)[^。\n]{0,10}(不好|有问题|虚弱|亏虚|有病|疾病|炎症|失调|受阻)""")
    /** 面相几何测量（比例/数值）没照片时出现就是编的 */
    private val FACE_MEASURE_PATTERN = Regex("""(上停|中停|下停|脸宽|鼻宽|眉眼距|下颌|三停)[^。\n]{0,14}[0-9]""")
    /** 年龄/性别/整容等敏感断言 */
    private val SENSITIVE_FACE_PATTERN = Regex("""(看起来|大概|估计)[^。\n]{0,6}(岁)|是男是女|整容|整形|医美|动过刀|你的身份""")

    /** 事实断言（联想与硬事实冲突防护）：卦名/干支都在前面几条规则里覆盖，这里只看"断言口吻" */

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
    fun check(
        text: String,
        steps: List<AgentStep>,
        personaId: String,
        card: PersonCard? = null,
        contextText: String = "",
        hasImage: Boolean = false
    ): List<String> {
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

        // ===== 规则 5-8：人物卡引用校验（只对命理师生效，纯本地比对，零 token）=====
        val cardIds = CardStore.quoteIndex(card)
        val inferred = INFER_PATTERN.containsMatchIn(text)

        if (inferred && !EVIDENCE_PATTERN.containsMatchIn(text)) {
            warnings.add("\u2139\uFE0F 提示：本轮含【推断】但没看到「依据」，按规则应写清 卡[id] 或原句，便于你核对。")
        }

        val refs = CARD_REF_PATTERN.findAll(text).map { it.groupValues[1].trim() }.toList()
        val unknown = refs.filter { cardIds.isNotEmpty() && !cardIds.containsKey(it) && cardIds.keys.none { k -> k.equals(it, true) } }
        if (unknown.isNotEmpty()) {
            warnings.add("\u26A0\uFE0F 系统检测：引用的卡片条目（${unknown.joinToString("、")}）在人物卡里不存在，可能为凭空引用。")
        }

        if (card != null) {
            for (q in QUOTE_PATTERN.findAll(text).map { it.groupValues[1] }.toList()) {
                val inCard = CardStore.quoteExists(card, q)
                val inContext = contextText.isNotBlank() && contextText.filter { !it.isWhitespace() }.contains(q.filter { !it.isWhitespace() })
                if (!inCard && !inContext) {
                    warnings.add("\u2139\uFE0F 提示：文中引用的「${q.take(20)}」未在人物卡原句或最近上下文里找到，建议核对出处。")
                    break
                }
            }
        }

        if (inferred && CHART_ASSERT_PATTERN.containsMatchIn(text) && refs.isNotEmpty()) {
            val allInference = refs.all { id -> cardIds[id]?.type == "联想" }
            if (allInference) {
                warnings.add("\u2139\uFE0F 提示：上述判断来自联想（卡[${refs.joinToString("、")}]），不是命盘直接结论，建议在文字里标明来源。")
            }
        }

        // ===== 规则 9-12：面相（多模态）=====
        val faceConclusion = FACE_CONCLUSION_PATTERN.containsMatchIn(text)

        // 9) 硬：本轮没有照片，却给出面相结论
        if (faceConclusion && !hasImage) {
            warnings.add("\u26A0\uFE0F 系统检测：以上面相结论出现在本轮没有照片的情况下，无法核对，请勿采信。")
        }

        // 10/11) 软：面相部位断言缺少「观测」条目支撑
        if (card != null && (hasImage || faceConclusion)) {
            val observed = card.entries.filter { it.cat.startsWith("面相") && it.type == "观测" }.map { it.cat }
            val mentioned = FACE_PART_WORDS.filter { text.contains(it) }
            if (mentioned.isNotEmpty() && observed.isEmpty()) {
                warnings.add("\u2139\uFE0F 提示：文中提到面相部位但没有对应的「观测」条目，建议先给客观特征表（部位｜观察值｜置信度）再解读。")
            } else if (mentioned.isNotEmpty() && mentioned.all { p -> observed.none { it.contains(p) } }) {
                warnings.add("\u2139\uFE0F 提示：文中涉及的面相部位（" + mentioned.take(4).joinToString("、") + "）在本轮观测里没有记录，可能缺乏支撑。")
            }
        }

        // 12.5) 硬：没有照片却给出面相测量数值
        if (FACE_MEASURE_PATTERN.containsMatchIn(text) && !hasImage) {
            warnings.add("""\u26A0\uFE0F 系统检测：本轮没有照片，却出现面相测量数值（比例/宽度等），无法核对。""")
        }

        // 12) 硬：健康诊断 / 年龄性别整容断言
        if (HEALTH_DIAG_PATTERN.containsMatchIn(text)) {
            warnings.add("\u26A0\uFE0F 系统检测：面相只能谈倾向与作息建议，不能做健康/疾病诊断（这条已越界）。")
        }
        if (SENSITIVE_FACE_PATTERN.containsMatchIn(text)) {
            warnings.add("\u26A0\uFE0F 系统检测：不做年龄/性别/身份判断，也不给整容/医美建议。")
        }

        return warnings
    }
}
