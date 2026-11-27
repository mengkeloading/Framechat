package mychat.framework.memory

/** 记忆反馈要用到的消息视图：只要"谁说的"和"归属哪个标签"。 */
data class TaggedTurn(val role: String, val tagId: String?)

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
     * 上一轮的标签：**最后一条 assistant** 的 tagId；它是空的就退回同轮用户消息的。
     *
     * 为什么要退：老数据只给 assistant 打过标，新数据两个都打。只看 assistant
     * 会让历史会话的负反馈全部失效。
     */
    fun prevTagIdOf(messages: List<TaggedTurn>): String? {
        val assistantIndex = messages.indexOfLast { it.role.equals("assistant", ignoreCase = true) }
        if (assistantIndex < 0) return null
        return messages[assistantIndex].tagId
            ?: messages.take(assistantIndex)
                .lastOrNull { it.role.equals("user", ignoreCase = true) }
                ?.tagId
    }
}
