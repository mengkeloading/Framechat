package mychat.framework.core

import mychat.framework.hook.Attachment
import mychat.framework.hook.HookSession
import mychat.framework.hook.Message
import mychat.framework.hook.ModuleManifest
import mychat.framework.hook.ModuleSource
import mychat.framework.json.JsonValue
import kotlin.math.ceil

/**
 * 请求体构造。顺序是契约的一部分：
 * system（软模块注入）→ 历史 → 本轮输入 → 附件 → 钩子 A 的 extra → 待回放的工具结果。
 *
 * tools 只来自模块 manifest 的声明，绝不用启用清单冒充（否则上游 422）。
 */
class RequestBuilder(
    private val source: ModuleSource,
    private val defaultBudget: Int = DEFAULT_BUDGET,
    private val minAllowance: Int = 48
) {

    data class Stats(
        val budget: Int,
        val allowance: Int,
        val kept: Int,
        val dropped: Int
    )

    data class Built(
        val body: JsonValue.Obj,
        val messages: List<JsonValue>,
        val stats: Stats
    )

    fun build(
        session: HookSession,
        history: List<Message>,
        userInput: String? = null,
        hookAExtra: String? = null,
        pendingToolMessages: List<Message> = emptyList(),
        attachments: List<Attachment> = emptyList()
    ): Built {
        val system = systemBlock(session)
        val budget = session.contextBudget ?: defaultBudget
        val reserved = estimate(system) + estimate(userInput) + estimate(hookAExtra) + 24
        val allowance = maxOf(minAllowance, budget - reserved)

        val kept = trim(history, allowance)
        val messages = mutableListOf<JsonValue>()

        if (system.isNotEmpty()) {
            messages += JsonValue.of(
                "role" to JsonValue.Str("system"),
                "content" to JsonValue.Str(system)
            )
        }
        kept.forEach { messages += it.toJson() }

        if (!userInput.isNullOrEmpty()) {
            val last = messages.lastOrNull() as? JsonValue.Obj
            val duplicate = last?.str("role") == "user" &&
                last.str("content") == userInput
            if (!duplicate) messages += Message("user", userInput).toJson()
        }

        attachments.forEach { a ->
            val text = a.text
            if (!text.isNullOrEmpty()) {
                messages += Message("system", "[附件 ${a.name}]\n$text").toJson()
            }
        }

        if (!hookAExtra.isNullOrEmpty()) {
            messages += Message("system", hookAExtra).toJson()
        }

        pendingToolMessages.forEach { messages += it.toJson() }

        val fields = LinkedHashMap<String, JsonValue>()
        fields["model"] = JsonValue.Str(session.model)
        fields["stream"] = JsonValue.Bool(true)
        fields["messages"] = JsonValue.array(messages)
        val tools = toolsOf(session)
        if (tools.isNotEmpty()) fields["tools"] = JsonValue.array(tools)

        return Built(
            body = JsonValue.Obj(fields),
            messages = messages,
            stats = Stats(budget, allowance, kept.size, history.size - kept.size)
        )
    }

    private fun systemBlock(session: HookSession): String {
        val blocks = mutableListOf<String>()
        for (id in session.activeSoftModules) {
            val pkg = source.getPackage(id) ?: continue
            when (pkg.manifest.inject) {
                "full" -> if (pkg.body.isNotEmpty()) blocks += pkg.body
                "summary" -> blocks += summaryLine(pkg.manifest)
            }
        }
        for (id in session.activeHardModules) {
            val manifest = source.getPackage(id)?.manifest ?: continue
            if (manifest.inject == "summary") blocks += summaryLine(manifest)
        }
        session.systemPrompt?.let { return it }
        return blocks.joinToString("\n\n")
    }

    private fun summaryLine(manifest: ModuleManifest): String =
        "- ${manifest.name}：${manifest.description}"

    /** 从最新往回取，直到超预算；无论如何至少留最后一条。 */
    private fun trim(history: List<Message>, allowance: Int): List<Message> {
        if (history.isEmpty()) return emptyList()
        var start = history.size
        var used = 0
        for (i in history.indices.reversed()) {
            val cost = estimate(history[i].content) + 4
            if (used + cost > allowance && start < history.size) break
            used += cost
            start = i
        }
        // 配对优先：历史不能以孤立的助手回复开头
        if (start < history.size && start > 0 &&
            history[start].role != "user" && history[start - 1].role == "user"
        ) {
            start -= 1
        }
        return history.subList(start, history.size)
    }

    private fun toolsOf(session: HookSession): List<JsonValue> =
        session.activeHardModules.flatMap { id ->
            source.getPackage(id)?.manifest?.tools ?: emptyList()
        }

    companion object {
        const val DEFAULT_BUDGET = 4000

        /** CJK 按字符 / 1.6 粗估 token。 */
        fun estimate(text: String?): Int =
            if (text.isNullOrEmpty()) 0 else ceil(text.length / 1.6).toInt()
    }
}
