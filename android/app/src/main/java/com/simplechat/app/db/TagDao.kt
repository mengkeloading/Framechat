package com.simplechat.app.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 话题标签。向量在创建时就是那轮输入的查询向量，之后只增不改。
 *
 * 零模型成本：不生成摘要、不改名、不合并——一个标签就是"一段话的代表向量"。
 */
@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String? = null,
    /** FloatArray 的原始字节（512 维 × 4B）。 */
    val embedding: ByteArray,
    val createdAt: Long,
    val updatedAt: Long,
    /** 记忆强度，惰性衰减；见 TagStrength。 */
    val strength: Float = 1f,
    val readCount: Int = 0,
    val lastReadAt: Long = 0,
    val lastDecayAt: Long = 0,
    /** name_only / summary / full / archived，由 strength 推出，落库只为查询。 */
    val depth: String = "summary",
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is TagEntity && id == other.id && name == other.name)

    override fun hashCode(): Int = id.hashCode() * 31 + name.hashCode()
}

@Dao
interface TagDao {

    @Query("SELECT * FROM tags ORDER BY createdAt")
    suspend fun all(): List<TagEntity>

    @Query("SELECT COUNT(*) FROM tags")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tag: TagEntity)

    @Query(
        "UPDATE tags SET strength = :strength, readCount = :readCount, " +
            "lastReadAt = :lastReadAt, lastDecayAt = :lastDecayAt, depth = :depth, " +
            "updatedAt = :now WHERE id = :id"
    )
    suspend fun updateStrength(
        id: String,
        strength: Float,
        readCount: Int,
        lastReadAt: Long,
        lastDecayAt: Long,
        depth: String,
        now: Long,
    )

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun byId(id: String): TagEntity?

    /**
     * 只写入算好的值。**规则不在 SQL 里** —— 状态转移的唯一实现是
     * TagStrength，两边各写一套迟早会分叉。
     */
    @Query("UPDATE tags SET strength = :value, lastDecayAt = :now WHERE id = :id")
    suspend fun setStrength(id: String, value: Float, now: Long)

    /** 一批消息各自属于哪些标签（去重后的非空 tagId）。 */
    @Query("SELECT DISTINCT tagId FROM messages WHERE id IN (:ids) AND tagId IS NOT NULL")
    suspend fun tagIdsOf(ids: List<String>): List<String>

    @Query("SELECT * FROM tags ORDER BY strength DESC LIMIT :limit")
    suspend fun top(limit: Int): List<TagEntity>

    /** 打标：只写 tagId，正文不动。 */
    @Query("UPDATE messages SET tagId = :tagId WHERE id = :id")
    suspend fun setMessageTag(id: String, tagId: String?)

    /**
     * 某个标签下最近的**用户原话**（按时间倒序）。
     *
     * 不取模型回答：回答是当时的解释，换个说法就过时；要的正是"用户当时怎么问的"。
     */
    @Query(
        "SELECT content FROM messages WHERE tagId = :tagId AND role = 'user' " +
            "AND isDeletedNode = 0 ORDER BY createdAt DESC LIMIT :limit"
    )
    suspend fun recentUserContents(tagId: String, limit: Int): List<String>
}
