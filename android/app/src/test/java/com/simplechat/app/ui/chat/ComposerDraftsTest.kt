package com.simplechat.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 切换会话时输入框该显示什么。 */
class ComposerDraftsTest {

    @Test
    fun newConversationStartsEmpty() {
        // 还没落库的新会话：上一次留下的草稿不能带过来
        assertEquals("", ComposerDrafts.draftFor(null, "上次打的字", "更早的草稿"))
        assertEquals("", ComposerDrafts.draftFor("", "上次打的字", "更早的草稿"))
    }

    @Test
    fun switchingBackRestoresOnlyThatConversation() {
        assertEquals("A 的草稿", ComposerDrafts.draftFor("a", null, "A 的草稿"))
        // B 没有草稿 → 空白，不会串成 A 的
        assertEquals("", ComposerDrafts.draftFor("b", null, null))
    }

    @Test
    fun editingTextWins() {
        assertEquals("正在改的这条", ComposerDrafts.draftFor("a", "正在改的这条", "旧草稿"))
    }

    @Test
    fun emptyKeyIsNotPersisted() {
        assertFalse(ComposerDrafts.shouldPersist(null))
        assertFalse(ComposerDrafts.shouldPersist(""))
        assertTrue(ComposerDrafts.shouldPersist("a"))
    }
}
