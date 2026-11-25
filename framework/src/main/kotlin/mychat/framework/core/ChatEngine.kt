package mychat.framework.core

import mychat.framework.hook.Attachment
import mychat.framework.hook.HookAResult
import mychat.framework.hook.HookBResult
import mychat.framework.hook.HookSession
import mychat.framework.hook.Message
import mychat.framework.hook.ModuleDispatcher

/** 只负责把一轮流式行喂回来；网络细节（provider、鉴权、重试）留在 App 侧。 */
fun interface ChatTransport {
    fun stream(bodyJson: String, onLine: (String) -> Unit): StreamOutcome
}

data class StreamOutcome(val ok: Boolean, val error: String? = null) {
    companion object {
        fun success(): StreamOutcome = StreamOutcome(true)

        fun failure(error: String): StreamOutcome = StreamOutcome(false, error)
    }
}

/** 执行模型请求的工具调用。失败也要返回 JSON，交给模型自愈。 */
fun interface ToolRunner {
    fun execute(name: String, argsJson: String): String
}

sealed interface TurnResult {
    /** 钩子 A 本地短路：没有发请求。 */
    data class Local(val text: String) : TurnResult

    data class Reply(
        val text: String,
        val rewritten: Boolean,
        val rounds: Int
    ) : TurnResult

    /** 工具轮次达到上限，对话中断。 */
    data class Truncated(val text: String, val rounds: Int) : TurnResult

    data class Failed(val message: String, val retryable: Boolean) : TurnResult
}

/**
 * 一轮对话的编排：钩子 A → 组包 → 流式 → 工具循环 → 钩子 B。
 * 不做持久化、不做 UI 状态机 —— 这两件事属于 App。
 */
class ChatEngine(
    private val dispatcher: ModuleDispatcher,
    private val builder: RequestBuilder,
    private val transport: ChatTransport,
    private val tools: ToolRunner
) {

    fun runTurn(
        session: HookSession,
        userInput: String,
        history: List<Message> = emptyList(),
        attachments: List<Attachment> = emptyList(),
        onDelta: (String) -> Unit = {}
    ): TurnResult {
        val hookA = dispatcher.dispatchInput(session, userInput, history, attachments)
        if (hookA is HookAResult.Local) return TurnResult.Local(hookA.text)

        val effectiveInput = hookA.text.ifEmpty { userInput }
        val extra = (hookA as? HookAResult.Context)?.extra

        val transcript = history.toMutableList()
        transcript += Message("user", effectiveInput)
        var lastText = ""
        var round = 0

        while (true) {
            if (round >= session.maxToolRounds) {
                return TurnResult.Truncated(lastText, round)
            }
            round++

            val historyForRound = if (round == 1) history else transcript.toList()
            val userForRound = if (round == 1) effectiveInput else null
            val built = builder.build(
                session = session,
                history = historyForRound,
                userInput = userForRound,
                hookAExtra = extra,
                pendingToolMessages = emptyList(),
                attachments = attachments
            )

            val collector = ReplyCollector()
            val outcome = try {
                transport.stream(built.body.encode()) { line ->
                    collector.accept(line)
                    SseParser.extractDelta(line)?.let(onDelta)
                }
            } catch (e: Exception) {
                return TurnResult.Failed(ErrorMapper.map(e.message), retryable = true)
            }
            if (!outcome.ok) {
                return TurnResult.Failed(
                    ErrorMapper.map(outcome.error),
                    retryable = ErrorMapper.retryable(outcome.error)
                )
            }

            val content = collector.content()
            lastText = content
            val calls = collector.toolCalls()

            if (calls.isEmpty()) {
                val hookB = dispatcher.dispatchOutput(
                    session = session,
                    rawText = content,
                    input = effectiveInput,
                    history = transcript,
                    attachments = attachments
                )
                val finalText = if (hookB is HookBResult.Rewrite) hookB.text else content
                return TurnResult.Reply(finalText, hookB is HookBResult.Rewrite, round)
            }

            // 硬约束：带 tool_calls 的轮次不跑钩子 B，改写会断掉工具回环
            transcript += Message("assistant", content, toolCalls = calls)
            for (call in calls) {
                val result = try {
                    tools.execute(call.name, call.argsJson)
                } catch (e: Exception) {
                    ErrorMapper.mapJsError(e.message)
                }
                transcript += Message("tool", result, call.id)
            }
        }
    }
}
