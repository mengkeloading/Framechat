package mychat.framework.memory

/** 记忆反馈要用到的消息视图：只要"谁说的"、"归属哪个标签"和"是哪条"。 */
data class TaggedTurn(val role: String, val tagId: String?, val id: String? = null)

/**
 * 负反馈的判定：用户否定的永远是**上一条回答**，所以扣的是那条回答的标签。
 *
 * 抽成纯函数是为了能在 JVM 里测 —— 这段逻辑此前埋在 ViewModel 的消息列表操作里，
 * 只能靠真机观察。
 */
object MemoryFeedback {

    /** 用户说"你答错了"这一类话。 */
    val NEGATIVE_WORDS = listOf("不对", "错了", "不是这个", "不是这样", "说错了")

    fun isNegative(text: String): Boolean = NEGATIVE_WORDS.any { text.contains(it) }

    /**
     * 该扣哪个标签：输入是否定语气 → 上一轮的标签；否则 null。
     *
     * 注意入参里**没有 recall**：负反馈不依赖本轮召回是否命中。
     */
    fun penaltyTargetOf(
        text: String,
        messages: List<TaggedTurn>,
        currentUserId: String? = null,
    ): String? = if (isNegative(text)) prevTagIdOf(messages, currentUserId) else null

    /**
     * 当前这条用户消息**之前**最后一条带标签的 assistant。
     *
     * 为什么必须给 currentUserId：发送时本轮的空回答已经进了列表（tagId 还是 null），
     * 只按"最后一条 assistant"取，取到的就是这条自己 —— prevTag 于是永远是 null。
     *
     * [currentUserId] 为 null 时看整个列表（老调用方）；找不到该 id 时返回 null：
     * 上下文不清楚宁可不扣，扣错标签比不扣更糟。
     */
    fun prevTagIdOf(messages: List<TaggedTurn>, currentUserId: String? = null): String? {
        val end = if (currentUserId == null) {
            messages.size
        } else {
            val index = messages.indexOfFirst { it.id == currentUserId }
            if (index < 0) return null
            index
        }
        return messages.take(end)
            .lastOrNull { it.role.equals("assistant", ignoreCase = true) && it.tagId != null }
            ?.tagId
    }
}
