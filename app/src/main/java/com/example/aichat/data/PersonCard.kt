
package com.example.aichat.data

import android.content.Context
import com.google.gson.Gson

/** 人物卡条目：每条都带出处，便于"快定位原句" */
data class CardEntry(
    val id: String = "",
    val cat: String = "",
    val text: String = "",
    val type: String = "自述",       // 自述 | 命盘 | 联想
    val strength: String = "倾向",    // 明确 | 倾向 | 待确认
    val quote: String = "",          // 逐字原句（自述类）
    val turn: Int = 0,
    val refs: List<String> = emptyList(),   // 联想依据的卡片 id
    val chain: String = "",          // 联想链说明（A+BC）
    val source: String = "",         // 来源（面相用：图1/正面、图2/侧面45；自述用空）
    val counter: List<String> = emptyList(),// 反例原句
    val state: String = "active",    // active | rejected | conflict
    val updatedAt: Long = 0L
)

/** 一张人物卡（一个八字 / 一个咨询对象） */
data class PersonCard(
    val version: Int = 1,
    val key: String = "",
    val title: String = "",
    val algoVersion: String = "",
    val entries: List<CardEntry> = emptyList(),
    val updatedAt: Long = 0L
)

/** 本轮卡片更新结果 */
data class CardCommitResult(
    val text: String,            // 去掉 <<CARD_UPDATE>> 块后的可见文本
    val card: PersonCard?,       // 合并后的卡（null = 本轮无更新）
    val parsed: String = "none", // strict | repair | regex | none
    val added: Int = 0,
    val confirmed: Int = 0,
    val rejected: Int = 0,
    val counters: Int = 0,
    val conflicted: Int = 0
)

/** 解析出来的增量（纯数据，便于自检） */
data class CardUpdateDto(
    val add: List<CardEntry> = emptyList(),
    val confirm: List<String> = emptyList(),
    val reject: List<String> = emptyList(),
    val counter: List<Pair<String, String>> = emptyList(), // (条目 id/关键词, 原句)
    val conflict: List<Triple<String, String, String>> = emptyList() // (cat, 旧, 新)
)

/**
 * 人物卡：一张有出处的、可增量维护的用户画像。
 *
 * 设计要点（对应产品需求）：
 * 1) 快照与证据分离：条目正文是结论，原句放 quote，注入时按需展开；
 * 2) 每条带类型（自述/命盘/联想）与强度（明确/倾向/待确认），联想默认"待确认"；
 * 3) 反例：用户否定过的原句进 counter，命中反例的联想自动降级；
 * 4) 冲突不覆盖：新旧并列，交由用户裁决；
 * 5) 有界：每类目最多 MAX_PER_CAT 条，超出合并为"历史摘要"。
 */
object CardStore {
    private const val PREFS = "person_cards"
    private const val MAX_PER_CAT = 8
    private val gson = Gson()

    private val BLOCK = Regex("<<CARD_UPDATE>>([\\s\\S]*?)<<END>>", RegexOption.IGNORE_CASE)

    /** 注入时用的类别关联表：问某类问题时，顺带带上这些类别的既有条目 */
    private val RELATED: Map<String, List<String>> = mapOf(
        "性格" to listOf("处事", "职业", "人际", "婚恋", "财官", "健康", "学业", "其他"),
        "处事" to listOf("性格", "职业", "人际", "财官", "健康"),
        "职业" to listOf("性格", "处事", "学业", "财官", "人际"),
        "学业" to listOf("性格", "处事", "职业"),
        "人际" to listOf("性格", "处事", "婚恋", "职业"),
        "婚恋" to listOf("性格", "处事", "人际", "财官"),
        "财官" to listOf("性格", "处事", "职业", "婚恋"),
        "健康" to listOf("性格", "处事", "时间轴"),
        "时间轴" to listOf("职业", "财官", "婚恋"),
        "其他" to listOf("性格", "处事")
    )
    private val ALL_CATS = listOf("性格", "处事", "职业", "学业", "人际", "婚恋", "财官", "健康", "时间轴", "其他")

    // ---------------- 指纹 ----------------

    /** 从排盘确认卡 JSON 里取指纹：八字 + 时辰（同一个人换会话也能接上） */
    fun fingerprintOf(paipanJson: String?): String? {
        if (paipanJson.isNullOrBlank()) return null
        return try {
            val m = gson.fromJson(paipanJson, Map::class.java) as? Map<*, *> ?: return null
            val bazi = (m["bazi"] as? String)?.trim().orEmpty()
            val hour = (m["hour"] as? String)?.trim().orEmpty()
            if (bazi.isBlank()) null else ("fp:" + bazi + "/" + hour)
        } catch (_: Exception) { null }
    }

    // ---------------- 解析增量 ----------------

    /** 拆出可见文本与 JSON 块；块永远从可见文本里去掉（坏块也不能漏给用户） */
    fun splitBlock(raw: String): Pair<String, String?> {
        val m = BLOCK.find(raw) ?: return raw to null
        val text = (raw.removeRange(m.range)).trim()
        return text to m.groupValues[1].trim()
    }

    /**
     * 三级降级解析：strict JSON  修复(去 code fence/尾逗号/补括号)  正则抽取三元组。
     * 全失败返回 (null, "none")，调用方只做"去块不更新"，绝不污染卡片。
     */
    fun parseUpdate(body: String?): Pair<CardUpdateDto?, String> {
        if (body.isNullOrBlank()) return null to "none"
        val strict = tryParse(body)
        if (strict != null) return strict to "strict"
        val repaired = tryParse(repair(body))
        if (repaired != null) return repaired to "repair"
        val byRegex = parseByRegex(body)
        if (byRegex != null && byRegex.add.isNotEmpty()) return byRegex to "regex"
        return null to "none"
    }

    private fun tryParse(json: String): CardUpdateDto? {
        return try {
        val map = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return null
        CardUpdateDto(
            add = asList(map["add"] ?: map["新增"] ?: map["新增条目"]).mapNotNull { toEntry(it) },
            confirm = asList(map["confirm"] ?: map["确认"]).mapNotNull { it.toString().trim().takeIf { s -> s.isNotBlank() } },
            reject = asList(map["reject"] ?: map["否定"]).mapNotNull { it.toString().trim().takeIf { s -> s.isNotBlank() } },
            counter = asList(map["counter"] ?: map["反例"]).mapNotNull { row ->
                val m = row as? Map<*, *> ?: return@mapNotNull null
                val id = (m["id"] ?: m["条目"] ?: m["ref"])?.toString()?.trim().orEmpty()
                val q = (m["原句"] ?: m["quote"] ?: m["text"])?.toString()?.trim().orEmpty()
                if (id.isBlank() || q.isBlank()) null else id to q
            },
            conflict = asList(map["conflict"] ?: map["冲突"]).mapNotNull { row ->
                val m = row as? Map<*, *> ?: return@mapNotNull null
                val cat = (m["cat"] ?: m["类别"])?.toString()?.trim().orEmpty()
                val old = (m["旧"] ?: m["old"] ?: m["旧条目"])?.toString()?.trim().orEmpty()
                val new = (m["新"] ?: m["new"] ?: m["新条目"])?.toString()?.trim().orEmpty()
                if (cat.isBlank() || (old.isBlank() && new.isBlank())) null else Triple(cat, old, new)
            }
        )
        } catch (_: Exception) { null }
    }

    private fun toEntry(raw: Any?): CardEntry? {
        val m = raw as? Map<*, *> ?: return null
        fun s(vararg keys: String): String {
            for (k in keys) {
                val v = m[k]?.toString()?.trim()
                if (!v.isNullOrBlank()) return v
            }
            return ""
        }
        val cat = s("cat", "类别").ifBlank { "其他" }
        val text = s("text", "结论", "内容", "结论摘要")
        if (text.isBlank()) return null
        val turn = s("turn", "轮次").toIntOrNull() ?: 0
        val refs = asList(m["refs"] ?: m["依据"] ?: m["依据条目"]).map { it.toString().trim() }
            .filter { it.isNotBlank() }
        // 模型只写 cat="面相"、部位在正文里 -> 从正文推导具体部位，避免全挤进"面相-其他"
        var catNorm = normalizeCat(cat)
        if (catNorm == "面相\u00B7其他") {
            val part = FACE_PARTS.firstOrNull { text.contains(it) }
            if (part != null) catNorm = "面相\u00B7" + part
        }
        return CardEntry(
            id = s("id", "编号", "条目"),
            cat = catNorm,
            text = text.take(120),
            type = normalizeType(s("type", "类型")),
            strength = normalizeStrength(s("strength", "强度")),
            quote = s("quote", "原句").take(200),
            turn = turn,
            refs = refs,
            chain = s("chain", "链", "推理", "推理链").take(120),
            source = s("source", "来源", "图", "视角").take(40)
        )
    }

    private fun repair(raw: String): String {
        var s = raw.trim()
        s = s.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start >= 0 && end > start) s = s.substring(start, end + 1)
        s = Regex(",\\s*([}\\]])").replace(s, "$1")
        return s
    }

    /** 正则兜底：抽出 类别/结论/原句 三元组 */
    private fun parseByRegex(body: String): CardUpdateDto? {
        val add = mutableListOf<CardEntry>()
        val catRe = Regex("(?:类别|cat)[\"']?\\s*[:：]\\s*[\"']?([^\"',}\\n]{1,12})")
        val textRe = Regex("(?:结论|text|内容)[\"']?\\s*[:：]\\s*[\"']?([^\"',}\\n]{1,120})")
        val quoteRe = Regex("(?:原句|quote)[\"']?\\s*[:：]\\s*[\"']?([^\"',}\\n]{1,200})")
        for (chunk in body.split(Regex("[\\n;；]"))) {
            val c = catRe.find(chunk)?.groupValues?.get(1)?.trim() ?: continue
            val t = textRe.find(chunk)?.groupValues?.get(1)?.trim() ?: continue
            add.add(
                CardEntry(
                    cat = normalizeCat(c),
                    text = cutAtKeys(t).take(120),
                    quote = cutAtKeys(quoteRe.find(chunk)?.groupValues?.get(1)?.trim().orEmpty()).take(200),
                    type = if (chunk.contains("联想") || chunk.contains("推断")) "联想" else "自述",
                    strength = when {
                        chunk.contains("待确认") -> "待确认"
                        chunk.contains("明确") -> "明确"
                        else -> "倾向"
                    }
                )
            )
        }
        return if (add.isEmpty()) null else CardUpdateDto(add = add)
    }

    /** 条目归一化：类别/类型/强度统一，并把 "面相" 细化为 "面相-部位"（两条路径都需要） */
    private fun normalizeEntry(e: CardEntry): CardEntry {
        var cat = normalizeCat(e.cat)
        if (cat == "面相\u00B7其他") {
            val part = FACE_PARTS.firstOrNull { e.text.contains(it) }
            if (part != null) cat = "面相\u00B7" + part
        }
        return e.copy(cat = cat, type = normalizeType(e.type), strength = normalizeStrength(e.strength))
    }

    /** 截掉后续 key：正则兜底容易把 "原句: xxx" 一起吞进结论正文 */
    private fun cutAtKeys(raw: String): String {
        var out = raw
        for (k in listOf("原句", "类型", "强度", "依据", "轮次", "quote", "type", "strength", "refs", "turn")) {
            val i = out.indexOf(k)
            if (i > 0) out = out.substring(0, i)
        }
        return out.trim().trimEnd(':', '：', ' ', '"')
    }

    // ---------------- 合并（纯函数，可自检） ----------------

    fun merge(card: PersonCard, dto: CardUpdateDto, now: Long = System.currentTimeMillis()): PersonCard {
        var entries = card.entries.toMutableList()
        var added = 0
        var confirmed = 0
        var rejected = 0
        var counters = 0
        var conflicted = 0

        // 1) add（同类别 + 语义相近  合并进已有条目，避免卡片变流水账）
        //    单轮硬上限：最多吃 6 条新增，防止模型一次灌爆卡片
        for (raw in dto.add.take(6)) {
            val e = normalizeEntry(raw)
            val idx = entries.indexOfFirst { it.state == "active" && it.cat == e.cat && similar(it.text, e.text) }
            if (idx >= 0) {
                val old = entries[idx]
                entries[idx] = old.copy(
                    text = if (e.text.length > old.text.length) e.text else old.text,
                    quote = listOf(old.quote, e.quote).filter { it.isNotBlank() }.distinct().joinToString("；").take(200),
                    refs = (old.refs + e.refs).distinct(),
                    chain = e.chain.ifBlank { old.chain },
                    strength = stronger(old.strength, e.strength),
                    type = if (old.type == e.type) old.type else if (old.type == "命盘" || e.type == "命盘") "命盘" else old.type,
                    turn = if (e.turn > 0) e.turn else old.turn,
                    updatedAt = now
                )
            } else {
                val id = uniqueId(entries, e.id, e.cat)
                entries.add(e.copy(id = id, updatedAt = now))
                added++
            }
        }

        // 2) confirm：升为明确 + 去掉待确认标记
        for (id in dto.confirm) {
            val idx = entries.indexOfFirst { it.id.equals(id, true) }
            if (idx >= 0) {
                entries[idx] = entries[idx].copy(strength = "明确", state = "active", updatedAt = now)
                confirmed++
            }
        }

        // 3) reject：标记 rejected（保留留痕，不再参与联想）
        for (id in dto.reject) {
            val idx = entries.indexOfFirst { it.id.equals(id, true) }
            if (idx >= 0) {
                entries[idx] = entries[idx].copy(state = "rejected", updatedAt = now)
                rejected++
            }
        }

        // 4) counter：反例原句挂到对应条目
        for ((ref, quote) in dto.counter) {
            val idx = entries.indexOfFirst { it.id.equals(ref, true) }
                .takeIf { it >= 0 } ?: entries.indexOfFirst { ref.isNotBlank() && it.text.contains(ref) }
            if (idx >= 0) {
                val old = entries[idx]
                entries[idx] = old.copy(
                    counter = (old.counter + quote).distinct().take(5),
                    strength = "待确认",   // 有反例  降级
                    updatedAt = now
                )
                counters++
            }
        }

        // 4.5) 反例联动：refs 指向被反例条目的联想条目，一并降级为待确认
        for (id in dto.counter.map { it.first }) {
            entries = entries.map {
                if (it.refs.any { r -> r.equals(id, true) }) it.copy(strength = "待确认", updatedAt = now) else it
            }.toMutableList()
        }

        // 5) conflict：新旧并列，标 conflict 交用户裁决
        for ((cat, oldTxt, newTxt) in dto.conflict) {
            val iOld = entries.indexOfFirst { it.cat == cat && oldTxt.isNotBlank() && it.text.contains(oldTxt.take(20)) }
            val iNew = entries.indexOfFirst { it.cat == cat && newTxt.isNotBlank() && it.text.contains(newTxt.take(20)) }
            if (iOld >= 0) entries[iOld] = entries[iOld].copy(state = "conflict", updatedAt = now)
            if (iNew >= 0) entries[iNew] = entries[iNew].copy(state = "conflict", updatedAt = now)
            conflicted++
        }

        entries = trimOverflow(entries, now)
        return card.copy(entries = entries, updatedAt = now)
    }

    /** 每类目超过 MAX_PER_CAT 条的，把最老的合并成一条"历史摘要" */
    private fun trimOverflow(entries: MutableList<CardEntry>, now: Long): MutableList<CardEntry> {
        val out = mutableListOf<CardEntry>()
        for (cat in entries.map { it.cat }.distinct()) {
            val group = entries.filter { it.cat == cat }.sortedByDescending { it.updatedAt }
            if (group.size <= MAX_PER_CAT) { out.addAll(group); continue }
            val keep = group.take(MAX_PER_CAT - 1)
            val old = group.drop(MAX_PER_CAT - 1)
            val quotes = old.mapNotNull { it.quote.takeIf { q -> q.isNotBlank() } }
            out.addAll(keep)
            out.add(
                CardEntry(
                    id = uniqueId(out, cat + "-sum", cat),
                    cat = cat,
                    text = "（历史摘要）" + old.joinToString("；") { it.text }.take(110),
                    type = "自述",
                    strength = "倾向",
                    quote = quotes.joinToString("；").take(200),
                    turn = old.minOfOrNull { it.turn } ?: 0,
                    counter = old.flatMap { it.counter }.distinct().take(5),
                    updatedAt = now
                )
            )
        }
        return out
    }

    private fun uniqueId(entries: List<CardEntry>, proposed: String, cat: String): String {
        val base = proposed.trim().ifBlank { cat + (entries.count { it.cat == cat } + 1) }
        var id = base
        var n = 1
        while (entries.any { it.id.equals(id, true) }) { n++; id = base + "-" + n }
        return id
    }

    /** 命理/性格场景高频特征词：命中同一个词视为同一条（"性子急" 约等于 "做事急躁"） */
    private val TRAIT_WORDS = listOf(
        "急", "拖", "等", "耐心", "耐性", "冲动", "犹豫", "纠结", "细致", "马虎", "认真",
        "外向", "内向", "乐观", "悲观", "敏感", "固执", "随和", "要强", "佛系", "焦虑",
        "稳", "快", "慢", "专注", "分心", "情绪", "理性", "感性", "强势", "讨好"
    )

    private fun similar(a: String, b: String): Boolean {
        val x = a.filter { it.isLetterOrDigit() }
        val y = b.filter { it.isLetterOrDigit() }
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val shorter = if (x.length <= y.length) x else y
        val longer = if (x.length <= y.length) y else x
        var hit = 0
        for (c in shorter) if (longer.contains(c)) hit++
        if (hit.toDouble() / shorter.length >= 0.6) return true
        val words = TRAIT_WORDS.filter { a.contains(it) }
        return words.isNotEmpty() && words.any { b.contains(it) }
    }

    private fun stronger(a: String, b: String): String {
        val order = listOf("待确认", "倾向", "明确")
        return if (order.indexOf(b) > order.indexOf(a)) b else a
    }

    /** 面相部位（用于 面相额 / 面相眉  这类类别） */
    private val FACE_PARTS = listOf("脸型", "三停", "额", "眉", "眼", "鼻", "颧", "法令", "口", "唇", "下巴", "下颌", "痣", "纹", "气色")

    private fun normalizeCat(raw: String): String {
        val s = raw.trim()
        // 面相类别直接透传：显式写了"面相"前缀，或整个类别名就是一个部位
        if (s.startsWith("面相")) {
            val part = s.removePrefix("面相").trimStart('\u00B7', '-', ':', '：', ' ')
            val hit = FACE_PARTS.firstOrNull { part.contains(it) }
            return "面相\u00B7" + (hit ?: part.ifBlank { "其他" })
        }
        if (s.length <= 4) {
            val hit = FACE_PARTS.firstOrNull { s == it }
            if (hit != null) return "面相" + hit
        }
        return ALL_CATS.firstOrNull { s.contains(it) } ?: when {
            s.contains("工作") || s.contains("事业") -> "职业"
            s.contains("学习") || s.contains("考试") -> "学业"
            s.contains("感情") || s.contains("婚姻") -> "婚恋"
            s.contains("钱") || s.contains("财") -> "财官"
            s.contains("身体") || s.contains("健康") -> "健康"
            s.contains("运") || s.contains("流年") -> "时间轴"
            else -> "其他"
        }
    }

    private fun normalizeType(raw: String): String = when {
        raw.contains("命盘") || raw.contains("工具") -> "命盘"
        raw.contains("观测") || raw.contains("照片") || raw.contains("图片") || raw.contains("面相") -> "观测"
        raw.contains("联想") || raw.contains("推断") -> "联想"
        else -> "自述"
    }

    private fun normalizeStrength(raw: String): String = when {
        raw.contains("明确") || raw.contains("确定") -> "明确"
        raw.contains("待确认") || raw.contains("猜测") -> "待确认"
        else -> "倾向"
    }

    private fun asList(v: Any?): List<Any> = when (v) {
        null -> emptyList()
        is List<*> -> v.filterNotNull()
        else -> listOf(v)
    }

    // ---------------- 渲染（注入用，按需展开原句） ----------------

    /** 只注入"与当前问题相关类别 + 待确认 + 冲突 + 反例"，避免卡片无限膨胀吃掉上下文 */
    fun render(card: PersonCard?, question: String, maxChars: Int = 1400, expandQuotes: Boolean = true): String {
        if (card == null || card.entries.isEmpty()) return ""
        val qCats = ALL_CATS.filter { question.contains(it) }.toSet()
        val head = if (qCats.isEmpty()) setOf("性格") else qCats
        val related = (head + head.flatMap { RELATED[it] ?: emptyList() }).toSet()
        // 问面相/五官时，把 面相* 条目全部带上（部位条目分散，按类别过滤会漏）
        val faceAsk = listOf("面相", "脸", "五官", "照片", "图片", "图").any { question.contains(it) } ||
            listOf("额", "眉", "眼", "鼻", "颧", "唇", "下巴", "法令", "气色", "三停", "痣").any { question.contains(it) }
        val active = card.entries.filter { it.state == "active" }
        val picked = active.filter { it.cat in related || (faceAsk && it.cat.startsWith("面相")) }
        val pending = card.entries.filter { it.state != "active" || it.strength == "待确认" }
        val moved = (picked + pending).distinctBy { it.id }

        val sb = StringBuilder()
        sb.append("## 人物卡（key=").append(card.key).append("，共 ").append(active.size).append(" 条）\n")
        sb.append("引用写法：结论引用 卡[id]（如 卡[x1]）；引用原句必须逐字；联想默认「待确认」，最多 2 跳且写出中间项。\n")
        for (e in moved.sortedBy { it.cat }) {
            sb.append("- [").append(e.cat).append("#").append(e.id).append("] ").append(e.text)
            sb.append("｜").append(e.type).append("\u00B7").append(if (e.state == "conflict") "冲突" else e.strength)
            if (expandQuotes && e.quote.isNotBlank()) sb.append("｜原句「").append(e.quote.take(80)).append("」")
            if (e.turn > 0) sb.append("｜轮").append(e.turn)
            if (e.refs.isNotEmpty()) sb.append("｜依据[").append(e.refs.joinToString(",")).append("]")
            if (e.chain.isNotBlank()) sb.append("｜链:").append(e.chain.take(60))
            if (e.source.isNotBlank()) sb.append("｜来源:").append(e.source)
            if (e.counter.isNotEmpty()) sb.append("｜反例「").append(e.counter.first().take(40)).append("」")
            sb.append("\n")
        }
        val text = sb.toString()
        return if (text.length <= maxChars) text else text.take(maxChars) + "\n（更多条目见人物卡面板）\n"
    }

    /** 供审计器校验引用：id  条目；原句集合 */
    fun quoteIndex(card: PersonCard?): Map<String, CardEntry> =
        card?.entries?.associateBy { it.id } ?: emptyMap()

    fun quoteExists(card: PersonCard?, quoted: String): Boolean {
        if (card == null || quoted.isBlank()) return false
        val q = normalize(quoted)
        if (q.length < 4) return false
        return card.entries.any { normalize(it.quote).contains(q) || normalize(it.counter.joinToString(" ")).contains(q) }
    }

    private fun normalize(s: String): String = s.filter { !it.isWhitespace() && it !in "「」\"'，,。.；;：:" }

    // ---------------- 存储 ----------------

    fun load(context: Context, key: String): PersonCard? {
        val raw = prefs(context).getString(key, null) ?: return null
        return try { gson.fromJson(raw, PersonCard::class.java) } catch (_: Exception) { null }
    }

    fun save(context: Context, card: PersonCard) {
        try { prefs(context).edit().putString(card.key, gson.toJson(card)).apply() } catch (_: Exception) {}
    }

    fun loadOrCreate(context: Context, key: String, title: String = ""): PersonCard =
        load(context, key) ?: PersonCard(key = key, title = title, algoVersion = "bazi_paipan/v1")

    /** 一轮结束：解析增量  合并  落盘，并返回去掉块的可见文本 */
    fun commitAnswer(context: Context, key: String, raw: String): CardCommitResult {
        val (text, body) = splitBlock(raw)
        if (body.isNullOrBlank()) return CardCommitResult(text = text, card = null, parsed = "none")
        val (dto, level) = parseUpdate(body)
        if (dto == null) return CardCommitResult(text = text, card = null, parsed = "none")
        val before = loadOrCreate(context, key)
        val after = merge(before, dto)
        save(context, after)
        return CardCommitResult(
            text = text, card = after, parsed = level,
            added = dto.add.size, confirmed = dto.confirm.size,
            rejected = dto.reject.size, counters = dto.counter.size, conflicted = dto.conflict.size
        )
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- 用户操作（卡片面板） ----------------

    fun setState(context: Context, key: String, id: String, state: String): PersonCard? {
        val card = load(context, key) ?: return null
        val entries = card.entries.map {
            if (it.id.equals(id, true)) it.copy(state = state, strength = if (state == "active") "明确" else it.strength, updatedAt = System.currentTimeMillis()) else it
        }
        val after = card.copy(entries = entries, updatedAt = System.currentTimeMillis())
        save(context, after)
        return after
    }

    fun remove(context: Context, key: String, id: String): PersonCard? {
        val card = load(context, key) ?: return null
        val after = card.copy(entries = card.entries.filterNot { it.id.equals(id, true) }, updatedAt = System.currentTimeMillis())
        save(context, after)
        return after
    }

    fun addManual(context: Context, key: String, cat: String, text: String): PersonCard? {
        if (text.isBlank()) return load(context, key)
        val dto = CardUpdateDto(add = listOf(CardEntry(cat = normalizeCat(cat), text = text.take(120), type = "自述", strength = "明确")))
        val after = merge(loadOrCreate(context, key), dto)
        save(context, after)
        return after
    }

    fun exportJson(context: Context, key: String): String {
        val card = load(context, key) ?: return ""
        return "AICHAT_CARD_V1\n" + gson.toJson(card)
    }

    /** 导入：同 key 合并（按 id 去重，保留更强者） */
    fun importJson(context: Context, payload: String): Int {
        val body = payload.substringAfter("AICHAT_CARD_V1", payload).trim()
        val card = try { gson.fromJson(body, PersonCard::class.java) } catch (_: Exception) { null } ?: return 0
        if (card.key.isBlank()) return 0
        val existing = load(context, card.key)
        val merged = if (existing == null) card else {
            val dto = CardUpdateDto(add = card.entries.map { it.copy(id = "") })
            merge(existing, dto)
        }
        save(context, merged)
        return merged.entries.size
    }

    // ---------------- 面相报告（markdown，便于留存/分享） ----------------

    /** 生成面相报告（纯函数，可离线自检）：观测 -> 解读(含依据) -> 八字互证 -> 待确认/反例 */
    fun buildFaceReport(card: PersonCard?): String {
        if (card == null) return ""
        val face = card.entries.filter { it.cat.startsWith("面相") && it.state != "rejected" }
        if (face.isEmpty()) return ""
        val faceIds = face.map { it.id }.toSet()
        val obs = face.filter { it.type == "观测" }
        val reads = face.filter { it.type != "观测" }
        val linkedOut = face.flatMap { it.refs }.filter { it !in faceIds }.distinct()
            .mapNotNull { id -> card.entries.firstOrNull { it.id.equals(id, true) } }
        val linkedIn = card.entries.filter { e -> e.refs.any { r -> r in faceIds } && e.cat.startsWith("面相").not() }
        val pending = face.filter { it.strength == "待确认" && it.state != "conflict" }
        val conflicts = face.filter { it.state == "conflict" }
        val counters = face.filter { it.counter.isNotEmpty() }

        val sb = StringBuilder()
        sb.append("# 面相报告").append(if (card.title.isNotBlank()) "  " + card.title else "").append("\n\n")
        sb.append("> 生成时间：")
            .append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date()))
            .append("　　角色：命理师　　卡片：").append(card.key).append("\n")
        sb.append("> 面相为传统相术参考，不构成健康/医学/法律/财务建议；观测受光线与角度影响，请以本人核对为准。\n\n")

        sb.append("## 一、客观特征（观测）\n\n")
        if (obs.isEmpty()) sb.append("- （尚无观测条目）\n")
        else obs.groupBy { it.cat }.forEach { (cat, list) ->
            sb.append("### ").append(cat.removePrefix("面相" + "\u00B7")).append("\n")
            list.forEach { e ->
                sb.append("- ").append(e.text).append("　【").append(e.strength).append("】")
                if (e.source.isNotBlank()) sb.append("　来源：").append(e.source)
                sb.append("\n")
            }
            sb.append("\n")
        }

        sb.append("## 二、解读（含依据）\n\n")
        if (reads.isEmpty()) sb.append("- （尚无解读条目）\n")
        else reads.forEach { e ->
            sb.append("- ").append(e.text).append("　【").append(e.type).append("").append(e.strength).append("】")
            if (e.refs.isNotEmpty()) sb.append("　依据：").append(e.refs.joinToString("、") { r -> "卡[" + r + "]" })
            if (e.chain.isNotBlank()) sb.append("　链：").append(e.chain)
            sb.append("\n")
        }
        sb.append("\n")

        sb.append("## 三、八字与面相互证\n\n")
        if (linkedOut.isEmpty() && linkedIn.isEmpty()) sb.append("- （尚无互证记录：需同时有八字结论与面相观测）\n")
        else {
            linkedOut.forEach { e -> sb.append("- 面相引用八字：").append(e.cat).append("｜").append(e.text).append("\n") }
            linkedIn.forEach { e -> sb.append("- 八字引用面相：").append(e.cat).append("｜").append(e.text).append("\n") }
        }
        sb.append("\n")

        sb.append("## 四、待确认 / 冲突 / 反例\n\n")
        if (pending.isEmpty() && conflicts.isEmpty() && counters.isEmpty()) sb.append("- （无）\n")
        else {
            pending.forEach { e -> sb.append("- 待确认：").append(e.text).append(if (e.source.isNotBlank()) "（" + e.source + "）" else "").append("\n") }
            conflicts.forEach { e -> sb.append("- 冲突：").append(e.text).append("（需裁决，新旧并列）\n") }
            counters.forEach { e -> sb.append("- 反例：").append(e.text).append("  ").append(e.counter.first()).append("\n") }
        }
        sb.append("\n---\n由《AI Chat》命理师生成；可在人物卡里逐条确认/否定。\n")
        return sb.toString()
    }

    /** 从存储里取卡片再生成报告（UI 用） */
    fun exportFaceReport(context: Context, key: String): String = buildFaceReport(load(context, key))

    // ---------------- 自检（回归用例，debug 启动时跑） ----------------

    fun selfTest(): List<String> {
        val fails = mutableListOf<String>()
        fun check(name: String, ok: Boolean) { if (!ok) fails.add(name) }

        // 1) 指纹
        val fp = fingerprintOf("{\"bazi\":\"庚午 己卯 甲子 丙寅\",\"hour\":\"寅时\"}")
        check("fingerprint", fp != null && fp.contains("庚午"))

        // 2) 块拆分（坏块也要去掉）
        val (t1, b1) = splitBlock("答案正文\n<<CARD_UPDATE>>{\"add\":[]}<<END>>\n尾巴")
        check("splitBlock", b1 != null && !t1.contains("CARD_UPDATE") && t1.contains("答案正文"))

        // 3) strict 解析
        val (d1, l1) = parseUpdate("{\"add\":[{\"cat\":\"性格\",\"id\":\"x1\",\"text\":\"性子急\",\"type\":\"自述\",\"strength\":\"明确\",\"quote\":\"做事不喜欢等\",\"turn\":3}]}")
        check("parse strict", l1 == "strict" && d1?.add?.size == 1 && d1.add[0].cat == "性格")

        // 4) 修复解析（尾逗号 + code fence）
        val (d2, l2) = parseUpdate("```json\n{\"add\":[{\"cat\":\"处事\",\"text\":\"喜欢并行推进\",}],}\n```")
        check("parse repair", l2 == "repair" && d2?.add?.size == 1)

        // 5) 正则兜底
        val (d3, l3) = parseUpdate("类别: 职业 结论: 更适合推动型 原句: 坐着难受")
        check("parse regex", l3 == "regex" && d3?.add?.firstOrNull()?.cat == "职业")

        // 6) 合并 + 去重 + 确认 + 反例 + 冲突
        var card = PersonCard(key = "k")
        card = merge(card, CardUpdateDto(add = listOf(
            CardEntry(cat = "性格", id = "x1", text = "性子急", type = "自述", quote = "做事不喜欢等"),
            CardEntry(cat = "性格", id = "x2", text = "性子急", type = "自述", quote = "我怕等")
        )), 1000L)
        check("merge dedupe", card.entries.size == 1 && card.entries[0].quote.contains("我怕等"))
        card = merge(card, CardUpdateDto(confirm = listOf("x1"), counter = listOf("x1" to "我其实很能坐得住")), 2000L)
        check("confirm+counter", card.entries[0].counter.contains("我其实很能坐得住") && card.entries[0].strength == "待确认")
        card = merge(card, CardUpdateDto(add = listOf(
            CardEntry(cat = "处事", id = "s1", text = "行动优先", type = "自述"),
            CardEntry(cat = "职业", id = "z1", text = "适合推动型", type = "联想", refs = listOf("x1", "s1"), chain = "急+并行推进型", strength = "待确认")
        )), 3000L)
        check("merge add+联想", card.entries.any { it.cat == "职业" && it.refs.size == 2 })

        // 7) 溢出合并（每类目 8 条）
        var big = PersonCard(key = "k2")
        big = merge(big, CardUpdateDto(add = (1..12).map {
            CardEntry(cat = "性格", id = "e" + it, text = "性格条目" + it + "号特征描述", type = "自述")
        }), 4000L)
        check("overflow trim", big.entries.count { it.cat == "性格" } <= 8)

        // 8) 渲染只带相关类别 + 提示引用写法
        val r = render(card, "我想问处事方式", 4000)
        check("render related", r.contains("处事") && r.contains("性格") && r.contains("卡["))
        val r2 = render(card, "随便聊聊", 4000)
        check("render default", r2.contains("人物卡"))

        // 9) 引用校验辅助
        check("quoteExists", quoteExists(card, "做事不喜欢等") && !quoteExists(card, "完全没有出现过的一句话"))

        // 10) 未知 id 不崩
        val after = merge(card, CardUpdateDto(confirm = listOf("nope"), reject = listOf("nope")), 5000L)
        check("unknown id safe", after.entries.isNotEmpty())

        // 11) 回归：同域特征词要能合并（"性子急" vs "做事急躁"）
        val merged2 = merge(
            PersonCard(key = "k3"),
            CardUpdateDto(add = listOf(
                CardEntry(cat = "性格", id = "a1", text = "性子急、耐性易耗"),
                CardEntry(cat = "性格", id = "a2", text = "做事急躁")
            )), 6000L
        )
        check("regression trait dedupe", merged2.entries.size == 1)

        // 12) 回归：正则兜底不能把 "原句:" 吞进结论
        val (d12, _) = parseUpdate("类别: 性格 结论: 做事急躁 原句: 我做事就是不喜欢拖")
        val t12 = d12?.add?.firstOrNull()?.text.orEmpty()
        check("regression regex cut", t12 == "做事急躁" && d12?.add?.firstOrNull()?.quote == "我做事就是不喜欢拖")

        // 13) 回归：反例要联动降级依赖它的联想条目
        var casc = PersonCard(key = "k4")
        casc = merge(casc, CardUpdateDto(add = listOf(
            CardEntry(cat = "性格", id = "c1", text = "性子急", strength = "明确"),
            CardEntry(cat = "职业", id = "c2", text = "适合推进型", type = "联想", strength = "明确", refs = listOf("c1"))
        )), 7000L)
        casc = merge(casc, CardUpdateDto(counter = listOf("c1" to "我其实挺能坐得住")), 8000L)
        check("regression counter cascade",
            casc.entries.first { it.id == "c1" }.strength == "待确认" &&
                casc.entries.first { it.id == "c2" }.strength == "待确认")

        // 14) 面相：类别透传 + source 保留 + 问面相时能渲染出来
        val faceCard = merge(
            PersonCard(key = "k6"),
            CardUpdateDto(add = listOf(
                CardEntry(cat = "面相\u00B7额", id = "f1", text = "上停偏长、发际线偏高", type = "观测", strength = "明确", source = "图1/正面"),
                CardEntry(cat = "面相", id = "f2", text = "鼻头有肉", type = "观测", strength = "倾向", source = "图1/正面")
            )), 10000L
        )
        check("face cat passthrough",
            faceCard.entries.any { it.cat == "面相\u00B7额" } && faceCard.entries.any { it.cat == "面相\u00B7鼻" })
        val rf = render(faceCard, "帮我看看面相", 2000)
        check("face render", rf.contains("面相\u00B7额") && rf.contains("来源:图1/正面"))
        check("face source kept", faceCard.entries.first { it.id == "f1" }.source == "图1/正面")

// 15) 面相报告：四个章节 + 来源 + 依据引用
        val rep = buildFaceReport(PersonCard(key = "k6", title = "测试", entries = listOf(
            CardEntry(cat = "面相\u00B7额", id = "f1", text = "上停偏长", type = "观测", strength = "明确", source = "图1/正面"),
            CardEntry(cat = "面相\u00B7眉", id = "i1", text = "性急", type = "联想", strength = "待确认", refs = listOf("f1"))
        ), updatedAt = 0L))
        check("face report", rep.contains("面相报告") && rep.contains("一、客观特征") && rep.contains("二、解读") &&
            rep.contains("三、八字与面相互证") && rep.contains("卡[f1]") && rep.contains("图1/正面"))

        // 16) 回归：单轮新增硬上限 6 条
        val many = merge(PersonCard(key = "k5"), CardUpdateDto(add = (1..12).map {
            CardEntry(cat = "其他", id = "m" + it, text = "条目" + it + "号特征描述")
        }), 9000L)
        check("regression per-turn cap", many.entries.size <= 6)

        return fails
    }
}
