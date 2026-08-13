package com.example.aichat.ui

import java.util.zip.ZipInputStream

// 文件类型辅助函数 —— 从 Office XML 格式提取文本
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
