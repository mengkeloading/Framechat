package mychat.framework.memory

/** 记忆目录的一项：标签 + 消息数 + 当前强度（已衰减）。 */
data class TagEntry(
    val id: String,
    val name: String,
    val count: Int,
    val strength: Float,
)

/**
 * 记忆目录：告诉模型"我手上有哪些话题"。
 *
 * 与召回是两件事 —— 召回是"这次该想起哪个"，目录是"还有别的，你看着办"。
 * 所以目录不参与相似度判断，也不需要向量。
 */
interface MemoryDirectory {
    /** 按强度降序，最多 [limit] 项。 */
    suspend fun list(limit: Int = DirectoryFormatter.MAX_ITEMS): List<TagEntry>
}

object DirectoryFormatter {

    const val MAX_ITEMS = 10

    /** 低于它标"正在淡化"：提醒模型这是快要忘掉的东西，可信度打折。 */
    const val FADING_BELOW = 0.5f

    /**
     * 少于 2 个标签返回 null —— 一个标签列出来没有信息量，
     * 只是每轮多占一行上下文。
     */
    fun format(entries: List<TagEntry>): String? {
        if (entries.size < 2) return null
        val sorted = entries.sortedByDescending { it.strength }.take(MAX_ITEMS)
        return buildString {
            appendLine("【记忆目录】")
            for (e in sorted) {
                val fading = if (e.strength < FADING_BELOW) "，正在淡化" else ""
                appendLine("- " + e.name + "（" + e.count + " 条" + fading + "）")
            }
        }.trimEnd()
    }
}

/**
 * 把目录与召回拼成一段注入文本。目录在前：先给"有哪些"，再给"这次用哪个"。
 */
object MemoryInjection {

    fun compose(directory: String?, recall: String?): String? {
        val parts = listOfNotNull(
            directory?.takeIf { it.isNotBlank() },
            recall?.takeIf { it.isNotBlank() },
        )
        if (parts.isEmpty()) return null
        return parts.joinToString("\n\n")
    }
}
