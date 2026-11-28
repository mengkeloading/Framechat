package com.simplechat.app.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.simplechat.app.hook.ExpandResolver
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** `[[!EXPAND]]` 的落地：按名字找标签、取细一层。 */
@RunWith(RobolectricTestRunner::class)
class ExpandResolverTest {

    private lateinit var db: AppDatabase
    private val logs = mutableListOf<String>()

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

    private fun resolver() = ExpandResolver(db.tagDao(), db.tagSummaryDao()) { logs += it }

    private suspend fun seedTag(id: String, name: String, level: Int) {
        db.tagDao().insert(
            TagEntity(
                id = id,
                name = name,
                embedding = ByteArray(8),
                createdAt = 1000L,
                updatedAt = 1000L,
            )
        )
        if (level > 0) db.tagDao().setCompressed(id, "L$level 摘要", level, 2000L)
    }

    @Test
    fun missingTagIsSkipped() = runTest {
        assertNull(resolver().resolve("不存在的标签"))
        assertTrue(logs.any { it == "expand:miss name=不存在的标签" })
    }

    @Test
    fun levelTwoGoesToLevelOne() = runTest {
        seedTag("t1", "标签A", level = 2)
        db.tagSummaryDao().upsert(
            TagSummaryEntity(
                id = "s1", tagId = "t1", level = 1,
                text = "L1 的详细摘要", sourceRefs = "[]", createdAt = 3000L,
            )
        )

        val hit = resolver().resolve("标签A")

        assertNotNull(hit)
        assertEquals(2, hit!!.fromLevel)
        assertEquals(1, hit.toLevel)
        assertEquals("L1 的详细摘要", hit.text)
        assertTrue(logs.any { it.startsWith("expand:request") })
        assertTrue(logs.any { it.startsWith("expand:inject") })
    }

    @Test
    fun levelZeroReturnsRawText() = runTest {
        seedTag("t0", "标签B", level = 0)
        db.conversationDao().upsert(
            ConversationEntity(
                id = "c1", title = "测试会话", mode = "CREATIVE",
                provider = "DEEPSEEK", model = "deepseek-flash",
                createdAt = 0L, updatedAt = 0L,
            )
        )
        db.messageDao().upsert(
            MessageEntity(
                id = "m1",
                conversationId = "c1",
                parentId = "root",
                variantIndex = 0,
                active = true,
                role = "user",
                content = "这是原文内容",
                createdAt = 1L,
            )
        )
        db.tagDao().setMessageTag("m1", "t0")

        val hit = resolver().resolve("标签B")

        assertNotNull(hit)
        assertEquals(0, hit!!.toLevel)
        assertTrue(hit.text.contains("这是原文内容"))
    }
}
