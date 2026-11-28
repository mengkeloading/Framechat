package com.simplechat.app.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.simplechat.app.hook.RoomCompressionSource
import kotlinx.coroutines.test.runTest
import mychat.framework.memory.compress.CompressResult
import mychat.framework.memory.compress.CompressionWrite
import mychat.framework.memory.compress.DefaultCompressor
import mychat.framework.memory.compress.Summarizer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 压缩链：L0 → L1 → L2，来源可追溯，失败不写库。 */
@RunWith(RobolectricTestRunner::class)
class TagCompressorTest {

    private lateinit var db: AppDatabase
    private lateinit var source: RoomCompressionSource
    private val logs = mutableListOf<String>()

    /** 可编排的假模型：给它一句话，它照说。 */
    private class FakeLlm : Summarizer {
        var answer: String? = null
        var lastPrompt: String? = null
        override suspend fun summarize(prompt: String): String? {
            lastPrompt = prompt
            return answer
        }
    }

    private val llm = FakeLlm()

    private fun compressor() = DefaultCompressor(
        source = source,
        summarizer = llm,
        clock = { 5_000L },
        newId = { "sum-" + (++idSeed) },
        log = { logs += it },
    )

    private var idSeed = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        source = RoomCompressionSource(db)
        logs.clear()
        idSeed = 0
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seed(level: Int = 0) {
        db.conversationDao().upsert(
            ConversationEntity(
                id = "c1", title = "t", mode = "CREATIVE",
                provider = "DEEPSEEK", model = "deepseek-flash",
                createdAt = 0L, updatedAt = 0L,
            )
        )
        db.tagDao().insert(
            TagEntity(
                id = "t1", name = "MyChat 开发", embedding = ByteArray(8),
                createdAt = 1000L, updatedAt = 1000L, currentLevel = level,
            )
        )
    }

    private suspend fun seedMessages(vararg pairs: Pair<String, String>) {
        pairs.forEachIndexed { i, (role, text) ->
            db.messageDao().upsert(
                MessageEntity(
                    id = "m$i", conversationId = "c1", parentId = if (i == 0) "root" else "m${i - 1}",
                    variantIndex = 0, active = true, role = role, content = text,
                    tagId = "t1", createdAt = 100L + i,
                )
            )
        }
    }

    @Test
    fun l0_to_l1() = runTest {
        seed()
        seedMessages(
            "user" to "我在做 MyChat，" + "这个 App 的模块系统还在设计。".repeat(12),
            "assistant" to "说说技术栈吧。".repeat(8),
            "user" to "Kotlin，" + "后端用 DeepSeek。".repeat(10),
        )
        llm.answer = "技术栈 Kotlin，团队熟"

        assertTrue(compressor().compress("t1", 1) is CompressResult.Done)

        val rows = db.tagSummaryDao().allOf("t1")
        assertEquals(1, rows.size)
        assertEquals(1, rows.single().level)
        // 只取用户消息：m1 是 assistant，不该进来源
        val refs = rows.single().sourceRefs
        assertEquals("refs 实际是: " + refs, """["msg:m0","msg:m2"]""", refs)
        val tag = db.tagDao().byId("t1")!!
        assertEquals("summary 实际是: " + tag.summary, "技术栈 Kotlin，团队熟", tag.summary)
        assertEquals(1, tag.currentLevel)
        assertEquals("lastCompressedAt 实际是: " + tag.lastCompressedAt, 5000L, tag.lastCompressedAt)
    }

    @Test
    fun l1_to_l2_uses_previous_summary_as_source() = runTest {
        seed()
        seedMessages(
            "user" to "我在做 MyChat，" + "用 Kotlin 写。".repeat(25),
            "user" to "记忆系统也要做。".repeat(10),
        )
        // L1 要比 L2 的目标长度（100 字）长，否则会命中 already_short
        llm.answer = "技术栈 Kotlin，团队熟悉。" + "背景信息".repeat(30)
        assertTrue(compressor().compress("t1", 1) is CompressResult.Done)
        val l1 = db.tagSummaryDao().at("t1", 1)!!

        llm.answer = "用 Kotlin"
        assertTrue(compressor().compress("t1", 2) is CompressResult.Done)

        val l2 = db.tagSummaryDao().at("t1", 2)!!
        assertEquals("""["sum:${l1.id}"]""", l2.sourceRefs)
        assertEquals("用 Kotlin", l2.text)
        assertEquals(2, db.tagDao().byId("t1")!!.currentLevel)
    }

    @Test
    fun sameLevelWriteIsReplaced() = runTest {
        seed()
        // 同一层写两次（不同主键）：表里应只剩一条，内容是后写的
        assertTrue(
            source.write(
                CompressionWrite("t1", 1, "sum-a", "第一次", listOf("msg:m0"), 1L)
            )
        )
        assertTrue(
            source.write(
                CompressionWrite("t1", 1, "sum-b", "第二次", listOf("msg:m0"), 2L)
            )
        )
        val rows = db.tagSummaryDao().allOf("t1")
        assertEquals(1, rows.size)
        assertEquals("第二次", rows.single().text)
        assertEquals("sum-b", rows.single().id)
    }

    @Test
    fun llmEmptyLeavesStateUntouched() = runTest {
        seed()
        seedMessages("user" to "我在做 MyChat，" + "细节很多。".repeat(40))
        llm.answer = null

        assertTrue(compressor().compress("t1", 1) is CompressResult.Failed)

        assertEquals(0, db.tagSummaryDao().allOf("t1").size)
        assertEquals(0, db.tagDao().byId("t1")!!.currentLevel)
        assertNotNull(logs.firstOrNull { it.contains("compress:start") })
        assertTrue(logs.any { it.contains("compress:fail") && it.contains("llm_empty") })
    }

    @Test
    fun shortInputIsSkipped() = runTest {
        seed()
        // 约 24 字，远小于 L1 的 200 字上限
        seedMessages("user" to "用 Kotlin。".repeat(3))
        llm.answer = "不该被调用"

        assertTrue(compressor().compress("t1", 1) is CompressResult.Skipped)

        assertEquals(0, db.tagSummaryDao().allOf("t1").size)
        assertTrue(logs.any { it.contains("already_short") })
        // 连 start 都不打：根本没打算压
        assertFalse(logs.any { it.contains("compress:start") })
    }

    @Test
    fun noSourceIsSkipped() = runTest {
        seed()
        llm.answer = "不该被用到"

        assertTrue(compressor().compress("t1", 1) is CompressResult.Skipped)

        assertEquals(0, db.tagSummaryDao().allOf("t1").size)
        assertTrue(logs.any { it.contains("compress:skip") && it.contains("no_source") })
        // 没有 start：输入都没取到
        assertFalse(logs.any { it.contains("compress:start") })
    }
}
