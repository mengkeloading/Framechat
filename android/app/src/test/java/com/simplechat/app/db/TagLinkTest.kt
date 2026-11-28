package com.simplechat.app.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 数据层：标签共现。 */
@RunWith(RobolectricTestRunner::class)
class TagLinkTest {

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

    private suspend fun seedTag(id: String, name: String) {
        db.tagDao().insert(
            TagEntity(
                id = id,
                name = name,
                embedding = ByteArray(8),
                createdAt = 1000L,
                updatedAt = 1000L,
            )
        )
    }

    @Test
    fun linkCountsUp() = runTest {
        seedTag("a", "标签A")
        seedTag("b", "标签B")
        db.tagLinkDao().link("a", "b", 1L)
        assertEquals(1, db.tagLinkDao().coCountOf("a", "b"))
    }

    @Test
    fun repeatLinkAccumulates() = runTest {
        seedTag("a", "标签A")
        seedTag("b", "标签B")
        repeat(3) { db.tagLinkDao().link("a", "b", it.toLong()) }
        assertEquals(3, db.tagLinkDao().coCountOf("a", "b"))
    }

    @Test
    fun relatedIsOrderedAndCapped() = runTest {
        seedTag("a", "A")
        // 7 个邻居，共现次数递减，验证排序与上限
        for (i in 1..7) {
            seedTag("n$i", "邻居$i")
            repeat(i) { db.tagLinkDao().link("a", "n$i", it.toLong()) }
        }

        val related = db.tagLinkDao().relatedNames("a", 5)

        assertEquals(5, related.size)
        // 共现最多的是 n7，最少的是 n1
        assertEquals("邻居7", related.first())
        assertEquals("邻居3", related.last())

        // 无链接的标签 → 空
        seedTag("solo", "孤立")
        assertEquals(emptyList<String>(), db.tagLinkDao().relatedNames("solo", 5))
    }
}
