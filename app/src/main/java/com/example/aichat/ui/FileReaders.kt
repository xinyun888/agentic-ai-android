package com.example.aichat.ui

import java.io.File
import java.util.zip.ZipInputStream

// 文件类型辅助函数 —— 从 Office XML 格式提取文本

/** 读取文本文件的前 maxChars 个字符，避免大文件一次性读入内存 */
fun readTextHead(file: File, maxChars: Int): String {
    val sb = StringBuilder(minOf(maxChars, 8192))
    file.inputStream().reader(Charsets.UTF_8).use { r ->
        val buf = CharArray(minOf(maxChars, 8192))
        var total = 0
        var read = r.read(buf)
        while (read > 0 && total < maxChars) {
            val take = minOf(read, maxChars - total)
            sb.append(buf, 0, take)
            total += take
            if (total >= maxChars) break
            read = r.read(buf)
        }
    }
    return sb.toString()
}

fun readZipXmlText(bytes: ByteArray, targetEntry: String): String {
    val sb = StringBuilder()
    ZipInputStream(bytes.inputStream()).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            if (entry.name == targetEntry) {
                val xml = zis.readBytes().toString(Charsets.UTF_8)
                sb.append(xml.replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ").trim())
                break
            }
            entry = zis.nextEntry
        }
    }
    return sb.toString().ifEmpty { "(空文档)" }
}

fun readDocxText(bytes: ByteArray): String = readZipXmlText(bytes, "word/document.xml")

fun readPptxText(bytes: ByteArray): String {
    val sb = StringBuilder()
    ZipInputStream(bytes.inputStream()).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            if (entry.name.startsWith("ppt/slides/slide") && entry.name.endsWith(".xml")) {
                val xml = zis.readBytes().toString(Charsets.UTF_8)
                val text = xml.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
                if (text.isNotBlank()) sb.appendLine("--- Slide ---\n$text")
            }
            entry = zis.nextEntry
        }
    }
    return sb.toString().ifEmpty { "(空演示文稿)" }
}

fun readXlsxText(bytes: ByteArray): String {
    val sb = StringBuilder()
    ZipInputStream(bytes.inputStream()).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            when {
                entry.name == "xl/sharedStrings.xml" -> {
                    val xml = zis.readBytes().toString(Charsets.UTF_8)
                    val texts = Regex("""<t[^>]*>(.*?)</t>""").findAll(xml).map { it.groupValues[1] }.joinToString(" ")
                    if (texts.isNotBlank()) sb.append(texts).append(' ')
                }
                entry.name.startsWith("xl/worksheets/") && entry.name.endsWith(".xml") -> {
                    // inlineStr 单元格：<c t="inlineStr"><is><t>文本</t></is></c>（不走 sharedStrings）
                    val xml = zis.readBytes().toString(Charsets.UTF_8)
                    val inline = Regex("""<c[^>]*t="inlineStr"[^>]*>.*?<t[^>]*>(.*?)</t>""").findAll(xml)
                        .map { it.groupValues[1] }.joinToString(" ")
                    if (inline.isNotBlank()) sb.append(inline).append(' ')
                }
            }
            entry = zis.nextEntry
        }
    }
    return sb.toString().trim().ifEmpty { "(空表格)" }
}
