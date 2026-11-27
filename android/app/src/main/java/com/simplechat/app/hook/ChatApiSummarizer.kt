package com.simplechat.app.hook

import com.simplechat.app.net.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull
import mychat.framework.memory.compress.Summarizer

/**
 * 用聊天通道做摘要：复用流式接口，收完整段再返回。
 *
 * 不新增非流式 API —— 那会多一条要维护的请求路径，而这里只是把同一份流读完。
 * 入参故意是一个"给 prompt、吐流"的函数，而不是 ChatApi：后者是带
 * OkHttp / SseClient / AnthropicApi 的具体类，测试里没法替换。
 */
class ChatApiSummarizer(
    private val stream: suspend (String) -> Flow<StreamEvent>,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : Summarizer {

    override suspend fun summarize(prompt: String): String? = withTimeoutOrNull(timeoutMs) {
        val sb = StringBuilder()
        var failed = false
        stream(prompt).collect { event ->
            when (event) {
                is StreamEvent.Content -> sb.append(event.text)
                is StreamEvent.Error -> failed = true
                else -> Unit
            }
        }
        if (failed || sb.isBlank()) null else sb.toString()
    }

    companion object {
        /** 摘要的输入通常几千字，30 秒够；超时按失败处理。 */
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}
