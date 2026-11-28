package com.simplechat.app.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.simplechat.app.data.ChatRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 命令回执标记要能落库、也能读回来（否则「上一轮产物」过滤不掉）。 */
@RunWith(RobolectricTestRunner::class)
class MessageReceiptTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun receiptSurvivesRoundTrip() = runTest {
        db.conversationDao().upsert(
            ConversationEntity(
                id = "c1", title = "测试", mode = "CREATIVE",
                provider = "DEEPSEEK", model = "deepseek-flash",
                createdAt = 0L, updatedAt = 0L,
            )
        )
        db.messageDao().upsert(
            MessageEntity(
                id = "u1", conversationId = "c1", parentId = MessageEntity.ROOT_PARENT, variantIndex = 0,
                active = true, role = "user", content = "你好", createdAt = 1L,
            )
        )
        db.messageDao().upsert(
            MessageEntity(
                id = "a1", conversationId = "c1", parentId = "u1", variantIndex = 0,
                active = true, role = "assistant", content = "角色卡已更新：…",
                createdAt = 2L, receipt = true,
            )
        )
        db.messageDao().upsert(
            MessageEntity(
                id = "a2", conversationId = "c1", parentId = "a1", variantIndex = 0,
                active = true, role = "assistant", content = "正常回答",
                createdAt = 3L,
            )
        )

        // 落库能读回：标记本身生效
        assertTrue(db.messageDao().findById("a1")!!.receipt)
        assertFalse(db.messageDao().findById("a2")!!.receipt)

        val path = ChatRepository(db, ApplicationProvider.getApplicationContext()).loadActivePath("c1")
        val ids = path.map { it.id }
        assertEquals("活跃路径", listOf("u1", "a1", "a2"), ids)

        // 「上一轮产物」取最后一条非回执 assistant
        assertEquals(
            "正常回答",
            path.lastOrNull { it.role.name == "ASSISTANT" && !it.receipt }?.content
        )
        assertEquals(1, path.count { it.receipt })
    }
}
