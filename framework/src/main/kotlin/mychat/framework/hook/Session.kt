package mychat.framework.hook

import mychat.framework.json.JsonValue

/** 会话元数据。钩子调度只看这个，不碰 UI 也不碰 Context。 */
data class HookSession(
    val id: String,
    val title: String = "",
    val model: String = "deepseek-chat",
    val systemPrompt: String? = null,
    val contextBudget: Int? = null,
    val activeHardModules: List<String> = emptyList(),
    val activeSoftModules: List<String> = emptyList(),
    val maxToolRounds: Int = 3
) {
    fun toJson(): JsonValue = JsonValue.of(
        "id" to JsonValue.Str(id),
        "title" to JsonValue.Str(title),
        "model" to JsonValue.Str(model),
        "systemPrompt" to systemPrompt?.let { JsonValue.Str(it) },
        "contextBudget" to contextBudget?.let { JsonValue.Num(it.toDouble()) },
        "activeHardModules" to JsonValue.array(activeHardModules.map { JsonValue.Str(it) }),
        "activeSoftModules" to JsonValue.array(activeSoftModules.map { JsonValue.Str(it) }),
        "maxToolRounds" to JsonValue.Num(maxToolRounds.toDouble())
    )
}

data class ToolCallRef(
    val id: String,
    val name: String,
    val argsJson: String
) {
    fun toJson(): JsonValue = JsonValue.of(
        "id" to JsonValue.Str(id),
        "type" to JsonValue.Str("function"),
        "function" to JsonValue.of(
            "name" to JsonValue.Str(name),
            "arguments" to JsonValue.Str(argsJson)
        )
    )
}

/**
 * 一条会话消息。toolCalls 只为回放 assistant 的工具调用轮次而存在；
 * reasoning_content 不在这份模型里 —— 它永远不回传给上游。
 */
data class Message(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCallRef> = emptyList()
) {
    fun toJson(): JsonValue = JsonValue.of(
        "role" to JsonValue.Str(role),
        "content" to JsonValue.Str(content),
        "tool_call_id" to toolCallId?.let { JsonValue.Str(it) },
        "tool_calls" to toolCalls.takeIf { it.isNotEmpty() }
            ?.let { JsonValue.array(it.map { c -> c.toJson() }) }
    )
}

/** 附件。text 只用于组包；模块侧只能看到元数据。 */
data class Attachment(
    val name: String,
    val mime: String = "",
    val size: Long = 0L,
    val text: String? = null
) {
    fun toMetaJson(): JsonValue = JsonValue.of(
        "name" to JsonValue.Str(name),
        "mime" to JsonValue.Str(mime),
        "size" to JsonValue.Num(size.toDouble())
    )
}

/** 宿主能力清单。模块只能调用这三个，且必须在 MODULE.md 里声明配额。 */
object Caps {
    const val RESOURCE_READ = "resource.read"
    const val CLOCK_NOW = "clock.now"
    const val DATA_SESSION = "data.session"
}

/** 一次调度能看到的会话快照。 */
data class SessionData(
    val session: HookSession,
    val messages: List<Message> = emptyList()
)
