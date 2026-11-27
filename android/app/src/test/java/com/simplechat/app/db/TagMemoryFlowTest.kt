package com.simplechat.app.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.simplechat.app.data.toUi
import com.simplechat.app.hook.TagMemoryService
import kotlinx.coroutines.test.runTest
import mychat.framework.embedding.TextEmbedder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 「内存 ↔ 数据库」这一层：打标写库之后，读回来必须还带着 tagId。
 *
 * 之前这条链断在"只写库、不写内存"，而两者都靠 tagId 串起来（召回按 tagId 查用户消息，
 * 负反馈按 tagId 找上一轮）。断一次，两个功能同时静默失效。
 */
@RunWith(RobolectricTestRunner::class)
class TagMemoryFlowTest {

    private lateinit var db: AppDatabase
    private lateinit var service: TagMemoryService

    /** 稳定但可区分的向量：同样文本 → 同样向量，不同文本 → 不同向量。 */
    private val embedder = object : TextEmbedder {
        override fun embed(text: String): FloatArray {
            val v = FloatArray(8)
            text.forEachIndexed { index, c -> v[index % 8] += c.code.toFloat() }
            val norm = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
            return if (norm == 0f) v else FloatArray(8) { v[it] / norm }
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        service = TagMemoryService(embedder, db.tagDao()) { true }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedMessages(userId: String, replyId: String) {
        // messages.conversationId 有外键（ON DELETE CASCADE），会话得先存在
        db.conversationDao().upsert(
            ConversationEntity(
                id = "c1", title = "测试会话", mode = "CREATIVE",
                provider = "DEEPSEEK", model = "deepseek-flash",
                createdAt = 0L, updatedAt = 0L,
            )
        )
        db.messageDao().upsert(
            MessageEntity(
                id = userId, conversationId = "c1", parentId = "root", variantIndex = 0,
                active = true, role = "user", content = "我在做 MyChat 这个 App",
                createdAt = 1L,
            )
        )
        db.messageDao().upsert(
            MessageEntity(
                id = replyId, conversationId = "c1", parentId = userId, variantIndex = 0,
                active = true, role = "assistant", content = "好的，说说模块系统",
                createdAt = 2L,
            )
        )
    }

    @Test
    fun taggingWritesBothMessages() = runTest {
        seedMessages("u1", "a1")

        val turn = service.begin("我在做 MyChat 这个 App")
        assertNotNull("begin 应返回 Turn（模型就绪且开关打开）", turn)
        val tagId = turn!!.tag("a1", "u1")

        assertNotNull("tag() 应返回标签 id", tagId)
        assertEquals(tagId, db.messageDao().findById("a1")?.tagId)
        assertEquals(tagId, db.messageDao().findById("u1")?.tagId)
        assertNotNull(db.tagDao().byId(tagId!!))
    }

    @Test
    fun mappersPreserveTagId() {
        val entity = MessageEntity(
            id = "a1", conversationId = "c1", parentId = "u1", variantIndex = 0,
            active = true, role = "assistant", content = "一段回答", tagId = "abc",
            createdAt = 1L,
        )
        assertEquals("abc", entity.toUi().tagId)

        val untagged = entity.copy(tagId = null)
        assertNull(untagged.toUi().tagId)
    }

    @Test
    fun reloadKeepsTagId() = runTest {
        seedMessages("u1", "a1")
        val tagId = service.begin("我在做 MyChat 这个 App")!!.tag("a1", "u1")

        // 模拟 reloadPath：整份从库里读回来（messages.clear() + addAll 的来源）
        val reloaded = db.messageDao().getAllFor("c1")

        assertEquals(tagId, reloaded.first { it.id == "a1" }.tagId)
        assertEquals(tagId, reloaded.first { it.id == "u1" }.tagId)
    }
}
