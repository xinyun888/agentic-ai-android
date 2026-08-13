package com.example.aichat.data

import retrofit2.http.GET
import retrofit2.http.Query

// --- DuckDuckGo 即时回答 API ---

data class DdgResponse(
    val AbstractText: String = "",
    val AbstractURL: String = "",
    val AbstractSource: String = "",
    val Heading: String = "",
    val RelatedTopics: List<DdgTopic> = emptyList()
)

data class DdgTopic(
    val Text: String = "",
    val FirstURL: String = ""
)

interface SearchService {
    @GET("/")
    suspend fun search(
        @Query("q") query: String,
        @Query("format") format: String = "json",
        @Query("no_html") noHtml: Int = 1,
        @Query("skip_disambig") skipDisambig: Int = 1
    ): DdgResponse
}

fun buildSearchContext(response: DdgResponse): String {
    val parts = mutableListOf<String>()

    if (response.AbstractText.isNotBlank()) {
        parts.add("【摘要】${response.AbstractText}")
        if (response.AbstractURL.isNotBlank()) {
            parts.add("来源: ${response.AbstractURL}")
        }
    }

    response.RelatedTopics.take(5).forEachIndexed { i, topic ->
        if (topic.Text.isNotBlank()) {
            // 去除主题文本中的 HTML
            val cleanText = topic.Text.replace(Regex("<[^>]*>"), "")
            parts.add("【结果${i + 1}】$cleanText")
        }
    }

    return if (parts.isEmpty()) {
        "未找到相关搜索结果。"
    } else {
        parts.joinToString("\n\n")
    }
}
