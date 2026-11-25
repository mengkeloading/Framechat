package mychat.framework.core

import mychat.framework.hook.ToolCallRef
import java.util.TreeMap

/**
 * 把一条流里的增量攒成最终结果。
 * reasoning 只留给 UI 展示，永远不会随请求回传。
 */
class ReplyCollector {

    private val content = StringBuilder()
    private val reasoning = StringBuilder()
    private val calls = TreeMap<Int, Call>()

    private class Call(var id: String?, var name: String?) {
        val args = StringBuilder()
    }

    fun accept(line: String) {
        SseParser.extractDelta(line)?.let { content.append(it) }
        SseParser.extractReasoning(line)?.let { reasoning.append(it) }
        SseParser.extractToolCall(line)?.let { frag ->
            val call = calls.getOrPut(frag.index) { Call(null, null) }
            frag.id?.takeIf { it.isNotEmpty() }?.let { call.id = it }
            frag.name?.takeIf { it.isNotEmpty() }?.let { call.name = it }
            call.args.append(frag.arguments)
        }
    }

    fun content(): String = content.toString()

    fun reasoning(): String = reasoning.toString()

    fun toolCalls(): List<ToolCallRef> = calls.entries.mapNotNull { (index, call) ->
        val name = call.name ?: return@mapNotNull null
        val args = call.args.toString().trim().ifEmpty { "{}" }
        ToolCallRef(call.id ?: "call_$index", name, args)
    }
}
