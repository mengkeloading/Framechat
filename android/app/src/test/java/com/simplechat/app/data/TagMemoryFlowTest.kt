package com.simplechat.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.simplechat.app.db.AppDatabase
import com.simplechat.app.db.ConversationEntity
import com.simplechat.app.hook.TagMemoryService
import com.simplechat.app.ui.chat.MessageRole
import kotlinx.coroutines.test.runTest
import mychat.framework.embedding.TextEmbedder
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 「内存 ↔ 数据库」这一层：tagId 必须在两边同时存在，且读回来还认得。
 *
 * 用 Robolectric + in-memory Room，不需要模拟器。为什么值得：这个 bug 已经
 * 出现过两次（打标只写库 / 打标只写内存），纯 JVM 测试抓不到。
 */
@RunWith(RobolectricTestRunner::class)
class TagMemoryFlowTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: ChatRepository

    private val conversationId = "conv1"
    private val userId = "m-user"
    private val replyId = "m-reply"

    /** 固定向量：不加载 22MB 模型，但走真实的 Tagger 决策路径。 */
    private class FixedEmbedder(private val vector: FloatArray) : TextEmbedder {
        override fun embed(text: String): FloatArray = vector
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ChatRepository(db, context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun seed() = runTest {
        db.conversationDao().upsert(
            ConversationEntity(
                id = conversationId,
                title = "t",
                mode = "CREATIVE",
                provider = "DEEPSEEK",
                model = "deepseek-chat",
                createdAt = 1L,
                updatedAt = 1L,
            )
        )
        db.messageDao().upsertAll(
            listOf(
                newMessageEntity(
                    id = userId, conversationId = conversationId, parentId = "",
                    variantIndex = 0, role = MessageRole.USER, content = "我在做 MyChat"
                ),
                newMessageEntity(
                    id = replyId, conversationId = conversationId, parentId = userId,
                    variantIndex = 0, role = MessageRole.ASSISTANT, content = "好的"
                ),
            )
        )
    }

    @Test
    fun taggingWritesBothMemoryAndDb() = runTest {
        seed()
        val service = TagMemoryService(
            embedder = FixedEmbedder(floatArrayOf(1f, 0f)),
            tags = db.tagDao(),
            enabled = { true },
        )
        val turn = service.begin("我在做 MyChat")!!
        val tagId = assertNotNull(turn.tag("m-reply", "m-user"))

        // 库：两条都写上了
        assertEquals(tagId, db.messageDao().getByIds(listOf(replyId)).firstOrNull()?.tagId)

        // 内存：ViewModel 的 patch 语义就是"换一个新对象"，这里照做
        val memory = repository.loadActivePath(conversationId).toMutableList()
        val idx = memory.indexOfFirst { it.id == replyId }
        memory[idx] = memory[idx].copy(tagId = tagId)
        assertEquals(tagId, memory[idx].tagId)

        // 两边一致
        assertEquals(tagId, memory[idx].tagId)
        assertEquals(memory[idx].tagId, db.messageDao().getByIds(listOf(replyId)).firstOrNull()?.tagId)
    }

    @Test
    fun mappersRoundTripPreservesTagId() = runTest {
        seed()
        // 库里写 tagId，经 toUi 读回来不能丢（reloadPath 走的就是这条路）
        db.messageDao().upsert(
            newMessageEntity(
                id = "m-x", conversationId = conversationId, parentId = "",
                variantIndex = 0, role = MessageRole.ASSISTANT, content = "hi"
            ).copy(tagId = "abc", originalContent = "old")
        )
        val ui = db.messageDao().getByIds(listOf("m-x")).single().toUi()
        assertEquals("abc", ui.tagId)
        assertEquals("old", ui.originalContent)
    }

    @Test
    fun reloadKeepsTagId() = runTest {
        seed()
        val service = TagMemoryService(
            embedder = FixedEmbedder(floatArrayOf(1f, 0f)),
            tags = db.tagDao(),
            enabled = { true },
        )
        val turn = service.begin("我在做 MyChat")!!
        val tagId = assertNotNull(turn.tag("m-reply", "m-user"))

        // reloadPath 的核心就是"从库里重新读一遍"
        val reloaded = repository.loadActivePath(conversationId)
        assertEquals(tagId, reloaded.firstOrNull { it.id == replyId }?.tagId)
        assertEquals(tagId, reloaded.firstOrNull { it.id == userId }?.tagId)
    }
}
