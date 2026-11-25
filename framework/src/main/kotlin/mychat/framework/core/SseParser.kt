package mychat.framework.core

import mychat.framework.json.JsonValue
import mychat.framework.json.parseJson

/**
 * DeepSeek/OpenAI 兼容的流式行解析。纯单行解析：不合并分片，
 * 每行都是完整的 `data: {...}` 或 `data: [DONE]`。
 */
object SseParser {

    data class ToolCallFragment(
        val index: Int,
        val id: String?,
        val name: String?,
        val arguments: String
    )

    fun payload(line: String?): JsonValue? {
        if (line == null) return null
        if (!line.startsWith("data:")) return null
        val body = line.substring(5).trim()
        if (body.isEmpty() || body == "[DONE]") return null
        return parseJson(body)
    }

    fun extractDelta(line: String): String? = delta(line)?.str("content")

    fun extractReasoning(line: String): String? =
        delta(line)?.str("reasoning_content")?.takeIf { it.isNotEmpty() }

    fun extractToolCall(line: String): ToolCallFragment? {
        val tc = delta(line)?.arr("tool_calls")?.firstOrNull() as? JsonValue.Obj ?: return null
        val fn = tc.obj("function")
        return ToolCallFragment(
            index = tc.int("index") ?: 0,
            id = tc.str("id"),
            name = fn?.str("name"),
            arguments = fn?.str("arguments") ?: ""
        )
    }

    fun isDone(line: String): Boolean =
        line.startsWith("data:") && line.substring(5).trim() == "[DONE]"

    private fun delta(line: String): JsonValue? =
        payload(line)?.arr("choices")?.firstOrNull()?.obj("delta")
}
