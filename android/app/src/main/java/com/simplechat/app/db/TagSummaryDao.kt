package com.simplechat.app.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 标签的分层摘要。
 *
 * `level` 是压缩层级，数字越大越短：
 * - 1 = L1 摘要（200 字，保留结论和决策）
 * - 2 = L2 摘要（100 字，只留要点）
 * - 3 = L3 摘要（40 字，一句话）
 * - 4 = L4（12 字，等同标签名，几乎不存）
 *
 * 与 `tags.currentLevel` 对应：0 = 还没有任何摘要（内容是原文），1..4 = 已经压到第几层。
 */
@Entity(
    tableName = "tag_summaries",
    indices = [Index("tagId"), Index(value = ["tagId", "level"], unique = true)],
)
data class TagSummaryEntity(
    @PrimaryKey val id: String,
    val tagId: String,
    /** 1..4，见 [TagSummaryEntity] 的类注释。 */
    val level: Int,
    val text: String,
    /** JSON 数组字符串，如 `["msg:abc","msg:def"]` 或 `["sum:xyz"]`。 */
    val sourceRefs: String,
    val createdAt: Long,
)

@Dao
interface TagSummaryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: TagSummaryEntity)

    @Query("SELECT * FROM tag_summaries WHERE tagId = :tagId ORDER BY level")
    suspend fun allOf(tagId: String): List<TagSummaryEntity>

    @Query("SELECT * FROM tag_summaries WHERE tagId = :tagId AND level = :level LIMIT 1")
    suspend fun at(tagId: String, level: Int): TagSummaryEntity?

    @Query("DELETE FROM tag_summaries WHERE tagId = :tagId AND level = :level")
    suspend fun deleteAt(tagId: String, level: Int)

    @Query("DELETE FROM tag_summaries WHERE tagId = :tagId")
    suspend fun deleteAllOf(tagId: String)
}
