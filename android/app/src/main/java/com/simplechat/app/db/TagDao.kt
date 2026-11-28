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
    /** 当前层级最浓缩的那份摘要。 */
    val summary: String? = null,
    /** 被用户否定的次数；只增不减。 */
    val negativeCount: Int = 0,
    /** 0 = 还没压过（内容是原文），1..4 = 已压到第几层，见 TagSummaryEntity。 */
    val currentLevel: Int = 0,
    val lastCompressedAt: Long = 0,
    /** 摘要里提到过的其他话题名，逗号分隔；见 Mentions。 */
    val mentions: String = "",
    /** 归入过多少条消息；质心只在它 < 10 时更新。 */
    val msgCount: Int = 0,
    /** 最近两条原文的 JSON（见 RecentMemory）；摘要滞后时补上这一段。 */
    val recentMessages: String = "",
    /** 最后一次归入的时间。 */
    val lastSeenAt: Long = 0,
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

    @Query("UPDATE tags SET summary = :summary, currentLevel = :level, lastCompressedAt = :now WHERE id = :id")
    suspend fun setCompressed(id: String, summary: String, level: Int, now: Long)

    @Query("UPDATE tags SET mentions = :mentions WHERE id = :id")
    suspend fun setMentions(id: String, mentions: String)

    @Query("UPDATE tags SET recentMessages = :recent, lastSeenAt = :now WHERE id = :id")
    suspend fun setRecent(id: String, recent: String, now: Long)

    /** 只看名字与强度：相关内容要在 Kotlin 里按衰减后的强度过筛。 */
    @Query("SELECT id, name, strength, readCount, lastDecayAt FROM tags WHERE id IN (:ids)")
    suspend fun nameStrengths(ids: List<String>): List<TagNameStrength>

    /** 质心更新：向量与计数必须一起写，否则下次会按错误的权重加权。 */
    @Query("UPDATE tags SET embedding = :embedding, msgCount = :count WHERE id = :id")
    suspend fun setEmbedding(id: String, embedding: ByteArray, count: Int)

    /**
     * 把"从没衰减过"的老标签的锚点刷成 now。
     *
     * lastDecayAt = 0 在指数公式里等于"永不衰减"，老数据会永远停在初始强度。
     * 幂等：刷完就不再有 0。
     */
    @Query("UPDATE tags SET lastDecayAt = :now WHERE lastDecayAt = 0")
    suspend fun fixMissingDecayAnchor(now: Long): Int

    /**
     * 每个标签下有几条原文消息。LEFT JOIN 保证没有消息的标签也出现（count=0），
     * 否则"没原文的标签"在结果里根本不存在，就分不清它和"还没该压的"。
     */
    @Query(
        "SELECT t.id AS tagId, COUNT(m.id) AS count FROM tags t " +
            "LEFT JOIN messages m ON m.tagId = t.id AND m.isDeletedNode = 0 " +
            "GROUP BY t.id"
    )
    suspend fun messageCounts(): List<TagMessageCount>

    @Query("SELECT * FROM tags WHERE currentLevel < 4")
    suspend fun allCompressible(): List<TagEntity>

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

/** 过筛「相关内容」用的投影：强度带着衰减参数一起取回来。 */
data class TagNameStrength(
    val id: String,
    val name: String,
    val strength: Float,
    val readCount: Int,
    val lastDecayAt: Long,
)

/** 聚合查询的投影：不是表。 */
data class TagMessageCount(
    val tagId: String,
    val count: Int,
)

/** 标签共现的读写。 */
@Dao
interface TagLinkDao {

    /** 共现一次：已有就 +1，没有就建。一条 SQL 完成，避免读-改-写竞态。 */
    @Query(
        "INSERT INTO tag_links (fromTag, toTag, coCount, updatedAt) VALUES (:from, :to, 1, :now) " +
            "ON CONFLICT(fromTag, toTag) DO UPDATE SET " +
            "coCount = coCount + 1, updatedAt = :now"
    )
    suspend fun link(from: String, to: String, now: Long)

    /** 与 [tagId] 最常一起出现的标签名，按共现次数降序。 */
    @Query(
        "SELECT t.name FROM tag_links l JOIN tags t ON t.id = l.toTag " +
            "WHERE l.fromTag = :tagId AND l.toTag != :tagId " +
            "ORDER BY l.coCount DESC, l.updatedAt DESC LIMIT :limit"
    )
    suspend fun relatedNames(tagId: String, limit: Int): List<String>

    @Query("SELECT coCount FROM tag_links WHERE fromTag = :from AND toTag = :to")
    suspend fun coCountOf(from: String, to: String): Int?

    @Query("SELECT * FROM tag_links WHERE fromTag = :from ORDER BY coCount DESC")
    suspend fun linksOf(from: String): List<TagLinkEntity>
}

/**
 * 标签共现：A 之后紧接着聊了 B，就记一次。
 *
 * 只记**有向**的一条（A→B），不做对称双写：从 A 能想到 B，不代表从 B 也想到 A。
 */
@Entity(
    tableName = "tag_links",
    primaryKeys = ["fromTag", "toTag"],
)
data class TagLinkEntity(
    val fromTag: String,
    val toTag: String,
    val coCount: Int = 1,
    val updatedAt: Long,
)
