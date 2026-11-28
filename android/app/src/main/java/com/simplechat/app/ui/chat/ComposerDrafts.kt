package com.simplechat.app.ui.chat

/**
 * 切换会话时，输入框该显示什么。
 *
 * 抽成纯函数是为了能测：真正的赋值发生在 Composable 的 effect 里，
 * Robolectric 够不到；而"该显示谁的内容"这条规则本身是可以单独验的。
 */
object ComposerDrafts {

    /**
     * @param conversationId 目标会话；null / 空串 = 还没落库的新会话
     * @param editing 该会话里正在修改的文本（编辑用户消息时用的是它）
     * @param stored 该会话已存的草稿
     */
    fun draftFor(conversationId: String?, editing: String?, stored: String?): String {
        if (conversationId.isNullOrEmpty()) return ""
        return editing ?: stored.orEmpty()
    }

    /** 新会话（还没有 id）的草稿不落库：存了下次新建会被读回来。 */
    fun shouldPersist(conversationId: String?): Boolean = !conversationId.isNullOrEmpty()
}
