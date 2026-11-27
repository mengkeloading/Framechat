package com.simplechat.app.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 数据层：分层摘要表与 tags 的压缩列。 */
@RunWith(RobolectricTestRunner::class)
class TagSummaryTest {

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

    private suspend fun seedTag(id: String = "t1") {
        db.tagDao().insert(
            TagEntity(
                id = id,
                name = "MyChat 开发",
                embedding = ByteArray(8),
                createdAt = 1000L,
                updatedAt = 1000L,
            )
        )
    }

    @Test
    fun summaryRoundTrip() = runTest {
        seedTag()
        val row = TagSummaryEntity(
            id = "s1", tagId = "t1", level = 1,
            text = "L1 摘要：结论是模块化",
            sourceRefs = """["msg:a","msg:b"]""",
            createdAt = 2000L,
        )
        db.tagSummaryDao().upsert(row)

        val back = db.tagSummaryDao().at("t1", 1)
        assertNotNull(back)
        assertEquals(row, back)
        assertEquals("""["msg:a","msg:b"]""", back!!.sourceRefs)
    }

    @Test
    fun uniquePerTagAndLevel() = runTest {
        seedTag()
        db.tagSummaryDao().upsert(
            TagSummaryEntity("s1", "t1", 2, "旧摘要", "[]", 1L)
        )
        // 同 tagId + level，换一个主键 → UNIQUE 生效，只留一条，内容是后写的
        db.tagSummaryDao().upsert(
            TagSummaryEntity("s2", "t1", 2, "新摘要", """["sum:s1"]""", 2L)
        )

        val all = db.tagSummaryDao().allOf("t1")
        assertEquals(1, all.size)
        assertEquals("新摘要", all.single().text)
        assertEquals("s2", all.single().id)
    }

    @Test
    fun orderedByLevel() = runTest {
        seedTag()
        db.tagSummaryDao().upsert(TagSummaryEntity("s3", "t1", 3, "L3", "[]", 1L))
        db.tagSummaryDao().upsert(TagSummaryEntity("s1", "t1", 1, "L1", "[]", 1L))
        db.tagSummaryDao().upsert(TagSummaryEntity("s2", "t1", 2, "L2", "[]", 1L))

        assertEquals(listOf(1, 2, 3), db.tagSummaryDao().allOf("t1").map { it.level })
    }

    @Test
    fun migrationAddsColumnsAndKeepsRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(20) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // v20 的 tags 形状（能跑通迁移所需的最小集合）
                    db.execSQL(
                        """
                        CREATE TABLE tags (
                            id TEXT NOT NULL PRIMARY KEY,
                            name TEXT NOT NULL,
                            description TEXT,
                            embedding BLOB NOT NULL,
                            createdAt INTEGER NOT NULL,
                            updatedAt INTEGER NOT NULL,
                            strength REAL NOT NULL DEFAULT 1.0,
                            readCount INTEGER NOT NULL DEFAULT 0,
                            lastReadAt INTEGER NOT NULL DEFAULT 0,
                            lastDecayAt INTEGER NOT NULL DEFAULT 0,
                            depth TEXT NOT NULL DEFAULT 'summary'
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        "INSERT INTO tags (id, name, embedding, createdAt, updatedAt) " +
                            "VALUES ('t1', '老标签', x'00', 12345, 12345)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val raw = helper.writableDatabase
        AppDatabase.MIGRATION_20_21.migrate(raw)

        // 新列有默认值，老行还在
        raw.query("SELECT createdAt, currentLevel, summary, lastCompressedAt FROM tags").use { c ->
            c.moveToFirst()
            assertEquals(12345L, c.getLong(0))
            assertEquals(0, c.getInt(1))
            assertNull(c.getString(2))
            assertEquals(0L, c.getLong(3))
        }
        // 新表存在
        raw.query("SELECT COUNT(*) FROM tag_summaries").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        helper.close()
    }

    @Test
    fun setCompressedUpdatesThreeColumns() = runTest {
        seedTag()
        db.tagDao().setCompressed("t1", "压到 L1 的摘要", 1, 7777L)

        val tag = db.tagDao().byId("t1")!!
        assertEquals("压到 L1 的摘要", tag.summary)
        assertEquals(1, tag.currentLevel)
        assertEquals(7777L, tag.lastCompressedAt)
        // 可压缩集合随之少一条（currentLevel = 4 才排除）
        assertEquals(listOf("t1"), db.tagDao().allCompressible().map { it.id })
    }
}
