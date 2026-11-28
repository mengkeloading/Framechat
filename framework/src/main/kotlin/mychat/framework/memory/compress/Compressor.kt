package mychat.framework.memory.compress

/**
 * 把记忆往下压一层。
 *
 * 框架只做「决策 + 组装 + 日志」，取数与落库都通过 [CompressionSource] 端口 ——
 * 所以这里不含任何 Android 类型，纯 JVM 可测。
 */
/**
 * 一次压缩的结果。
 *
 * 为什么不是 Boolean：`already_short` 是"本来就够短，不用压"，和"模型调用失败"
 * 混成同一个数字后，报告里满屏 already_short 和"每次模型都挂了"看起来一模一样。
 */
sealed interface CompressResult {
    /** 压成功：摘要已落库，层指针已前移。 */
    data class Done(val level: Int) : CompressResult

    /** 不该压或压不了（already_short / no_source / already_at_level…）。 */
    data class Skipped(val reason: String) : CompressResult

    /** 该压但没压成（模型空、写库失败、异常）。 */
    data class Failed(val reason: String) : CompressResult
}

interface Compressor {
    /**
     * 把 [tagId] 压到 [toLevel]。
     *
     * 成功：写摘要 + 更新 tags 的 summary/currentLevel/lastCompressedAt
     * 没压：不改任何状态，返回带原因的 [CompressResult]
     */
    suspend fun compress(tagId: String, toLevel: Int): CompressResult
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
    /** 摘要末尾那行 `MENTIONS:` 解析出的其他话题名。 */
    val mentions: List<String> = emptyList(),
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

    override suspend fun compress(tagId: String, toLevel: Int): CompressResult {
        val tag = source.tag(tagId)
            ?: return skip(tagId, toLevel, "tag_not_found")
        if (toLevel !in 1..4) return skip(tagId, toLevel, "bad_level")
        if (toLevel <= tag.currentLevel) return skip(tagId, toLevel, "already_at_level")

        // 输入与来源：L0 用原文，L1+ 用下一层的摘要
        val input: String
        val sources: List<String>
        if (tag.currentLevel == 0) {
            /*
             * user + assistant 一起压。
             *
             * 只用 user 会丢掉真正有价值的那半：结论、代码、定下来的方案都在助手的回答里，
             * 只压用户原话会让摘要变成一串没有结果的诉求（「想要一个协程版本」）。
             * 代价是要靠提示词把「推测」挡在外面 —— 比丢掉结论便宜。
             */
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

        val target = LevelPolicy.targetLength(toLevel)
        // 已经比目标还短：再压只会丢信息，还白花一次模型调用
        if (input.length <= target) {
            return skip(tagId, toLevel, "already_short")
        }

        log("compress:start tag=$tagId from=${tag.currentLevel} to=$toLevel inLen=${input.length}")

        val out = runCatching { summarizer.summarize(buildPrompt(input, target, toLevel)) }
            .getOrElse { error ->
                log("compress:fail tag=$tagId to=$toLevel err=${error.javaClass.simpleName}")
                return CompressResult.Failed(error.javaClass.simpleName)
            }
        if (out.isNullOrBlank()) return fail(tagId, toLevel, "llm_empty")

        // 摘要正文与 MENTIONS 分开：正文落摘要，话题名落 tags.mentions
        val parsed = mychat.framework.memory.Mentions.extract(out)
        val text = parsed.summary.ifBlank { out.trim() }
        if (parsed.mentions.isNotEmpty()) {
            log(
                "compress:mentions tag=" + tagId + " count=" + parsed.mentions.size +
                    " names=" + parsed.mentions.joinToString(",")
            )
        }
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
                    mentions = parsed.mentions,
                )
            )
        }.getOrElse { error ->
            log("compress:fail tag=$tagId to=$toLevel err=${error.javaClass.simpleName}")
            return CompressResult.Failed(error.javaClass.simpleName)
        }
        if (!ok) return fail(tagId, toLevel, "write_failed")

        log("compress:done tag=$tagId level=$toLevel outLen=${text.length} sumId=$summaryId")
        return CompressResult.Done(toLevel)
    }

    /** 该压但没压成：进 failed 统计。 */
    private fun fail(tagId: String, toLevel: Int, reason: String): CompressResult {
        log("compress:fail tag=$tagId to=$toLevel err=$reason")
        return CompressResult.Failed(reason)
    }

    /** 不该压：进 skipped，不算失败。每次调用都必须留下一条终态日志，不许静默返回。 */
    private fun skip(tagId: String, toLevel: Int, reason: String): CompressResult {
        log("compress:skip tag=$tagId to=$toLevel reason=$reason")
        return CompressResult.Skipped(reason)
    }

    companion object {

        /**
         * 压缩提示词：按「会议纪要四要素」组织。
         *
         * 为什么不是"压到 N 字以内"了事：字数约束只会让模型砍尾巴，
         * 而真正该留的是决定、待办、未决、事实这四类。
         */
        fun buildPrompt(input: String, maxLen: Int, level: Int): String {
            val extra = when (level) {
                1 -> ""
                2 -> "这段还要更短：只留要点，一句话一个。"
                3 -> "压成一句话，不超过 40 字。"
                else -> "压成 12 字以内的短语。"
            }
            return """
                把下面的对话压缩成一段纪要，$maxLen 字以内。

                必须包含（有就写，没有就跳过）：
                - 决定：用户明确做出的选择
                - 待办：还没做的事
                - 未决：还在讨论的问题
                - 事实：客观信息（技术栈、项目名、时间等）

                只记录用户明确说过的事、你给出的代码和方案。
                不要记录你的推测（比如"这个 App 可能是…"）、寒暄、反问。

                必须丢弃：
                - 寒暄、客套话
                - 过程描述（"我看看""让我想想"）
                - 重复的内容
                - 你自己的建议（除非用户明确采纳）

                保留用户的原话措辞（如果能）。

                另外，提取这段对话里"提到过的其他话题名"，最多 5 个。
                输出格式在摘要末尾：

                MENTIONS: 话题A, 话题B

                （如果没提到任何别的话题，就不输出这行）

                写成一到三段短句，不要用列表符号。（$extra）

                对话：
                $input
            """.trimIndent()
        }

        /** sourceRefs 的 JSON 形态：`["msg:a","msg:b"]`。 */
        fun sourcesToJson(sources: List<String>): String =
            sources.joinToString(separator = ",", prefix = "[", postfix = "]") {
                "\"" + it.replace("\"", "\\\"") + "\""
            }
    }
}
