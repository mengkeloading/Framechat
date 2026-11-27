package mychat.framework.memory.compress

/**
 * 把记忆往下压一层。
 *
 * 框架只做「决策 + 组装 + 日志」，取数与落库都通过 [CompressionSource] 端口 ——
 * 所以这里不含任何 Android 类型，纯 JVM 可测。
 */
interface Compressor {
    /**
     * 把 [tagId] 压到 [toLevel]。
     *
     * 成功：写摘要 + 更新 tags 的 summary/currentLevel/lastCompressedAt
     * 失败：不改任何状态，返回 false，并留下一条 skip 或 fail 日志
     */
    suspend fun compress(tagId: String, toLevel: Int): Boolean
}

/** 压缩需要知道的最小标签信息。 */
data class CompressionTag(val id: String, val currentLevel: Int)

/** 原文消息：只要"谁说的"和内容。 */
data class CompressionMessage(val id: String, val role: String, val content: String)

/** 已经存在的下一层摘要（作为上一层的输入）。 */
data class CompressionSummary(val id: String, val text: String)

/** 一次压缩的落库请求。 */
data class CompressionWrite(
    val tagId: String,
    val level: Int,
    val summaryId: String,
    val text: String,
    /** `["msg:<id>", …]` 或 `["sum:<id>"]` */
    val sources: List<String>,
    val now: Long,
)

/** 取数与落库。App 侧用 Room 实现，测试用内存实现。 */
interface CompressionSource {
    suspend fun tag(tagId: String): CompressionTag?
    suspend fun messagesOf(tagId: String): List<CompressionMessage>
    suspend fun summaryOf(tagId: String, level: Int): CompressionSummary?
    /** 写摘要并把 tags 的指针前移；返回 false 表示没写成功。 */
    suspend fun write(write: CompressionWrite): Boolean
}

/** 只要一个"给输入、要摘要"的能力，不涉及流式 / 工具 / 多轮。 */
interface Summarizer {
    suspend fun summarize(prompt: String): String?
}

class DefaultCompressor(
    private val source: CompressionSource,
    private val summarizer: Summarizer,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
    private val log: (String) -> Unit = {},
) : Compressor {

    override suspend fun compress(tagId: String, toLevel: Int): Boolean {
        val tag = source.tag(tagId)
            ?: return skip(tagId, toLevel, "tag_not_found")
        if (toLevel !in 1..4) return skip(tagId, toLevel, "bad_level")
        if (toLevel <= tag.currentLevel) return skip(tagId, toLevel, "already_at_level")

        // 输入与来源：L0 用原文，L1+ 用下一层的摘要
        val input: String
        val sources: List<String>
        if (tag.currentLevel == 0) {
            val msgs = source.messagesOf(tagId)
            if (msgs.isEmpty()) return skip(tagId, toLevel, "no_source")
            input = msgs.joinToString("\n") { "[${it.role}] ${it.content}" }
            sources = msgs.map { "msg:${it.id}" }
        } else {
            val prev = source.summaryOf(tagId, tag.currentLevel)
                ?: return skip(tagId, toLevel, "prev_summary_missing")
            input = prev.text
            sources = listOf("sum:${prev.id}")
        }

        log("compress:start tag=$tagId from=${tag.currentLevel} to=$toLevel inLen=${input.length}")

        val target = LevelPolicy.targetLength(toLevel)
        val out = runCatching { summarizer.summarize(buildPrompt(input, target, toLevel)) }
            .getOrElse { error ->
                log("compress:fail tag=$tagId to=$toLevel err=${error.javaClass.simpleName}")
                return false
            }
        if (out.isNullOrBlank()) return skip(tagId, toLevel, "llm_empty")

        val text = out.trim()
        val summaryId = newId()
        val ok = runCatching {
            source.write(
                CompressionWrite(
                    tagId = tagId,
                    level = toLevel,
                    summaryId = summaryId,
                    text = text,
                    sources = sources,
                    now = clock(),
                )
            )
        }.getOrElse { error ->
            log("compress:fail tag=$tagId to=$toLevel err=${error.javaClass.simpleName}")
            return false
        }
        if (!ok) {
            log("compress:fail tag=$tagId to=$toLevel err=write_failed")
            return false
        }

        log("compress:done tag=$tagId level=$toLevel outLen=${text.length} sumId=$summaryId")
        return true
    }

    /** 每次调用都必须留下一条终态日志，不许静默返回。 */
    private fun skip(tagId: String, toLevel: Int, reason: String): Boolean {
        log("compress:skip tag=$tagId to=$toLevel reason=$reason")
        return false
    }

    companion object {

        fun buildPrompt(input: String, maxLen: Int, level: Int): String {
            val hint = when (level) {
                1 -> "保留结论、决定、未解决的问题；丢弃寒暄、过程、重复。"
                2 -> "只保留要点，一句话一个。"
                3 -> "压成一句话，不超过 40 字。"
                else -> "压成 12 字以内的短语。"
            }
            return """
                把下面的内容压缩到 $maxLen 字以内。
                $hint

                不要编造，只压缩。保留用户明确说过的原话措辞（如果能）。

                内容：
                $input
            """.trimIndent()
        }

        /** sourceRefs 的 JSON 形态：`["msg:a","msg:b"]`。 */
        fun sourcesToJson(sources: List<String>): String =
            sources.joinToString(prefix = "[", postfix = "]") { "\"" + it.replace("\"", "\\\"") + "\"" }
    }
}
