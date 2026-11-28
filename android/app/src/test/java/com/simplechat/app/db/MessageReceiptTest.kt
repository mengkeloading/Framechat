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
                id = "u1", conversationId = "c1", parentId = "root", variantIndex = 0,
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

        val path = ChatRepository(db, ApplicationProvider.getApplicationContext()).loadActivePath("c1")
        val receipt = path.first { it.id == "a1" }
        val normal = path.first { it.id == "a2" }
        assertTrue(receipt.receipt)
        assertFalse(normal.receipt)

        // 「上一轮产物」取最后一条非回执 assistant
        assertEquals("正常回答", path.last { it.role.name == "ASSISTANT" && !it.receipt }.content)
        assertEquals(1, path.count { it.receipt })
    }
}
