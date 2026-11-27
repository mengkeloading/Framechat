package com.simplechat.app.hook

import androidx.room.withTransaction
import com.simplechat.app.db.AppDatabase
import mychat.framework.memory.compress.CompressionMessage
import mychat.framework.memory.compress.CompressionSource
import mychat.framework.memory.compress.CompressionSummary
import mychat.framework.memory.compress.CompressionTag
import mychat.framework.memory.compress.CompressionWrite
import mychat.framework.memory.compress.DefaultCompressor
import mychat.framework.memory.compress.Summarizer

/**
 * 把框架的 [CompressionSource] 端口接到 Room 上。
 *
 * 框架层不碰 Android 类型，所以这层薄适配器是必须的；它只做取数、落库、
 * 不含量任何"压到第几层"的判断。
 */
class RoomCompressionSource(private val db: AppDatabase) : CompressionSource {

    override suspend fun tag(tagId: String): CompressionTag? =
        db.tagDao().byId(tagId)?.let { CompressionTag(it.id, it.currentLevel) }

    override suspend fun messagesOf(tagId: String): List<CompressionMessage> =
        db.messageDao().byTagId(tagId).map {
            CompressionMessage(it.id, it.role, it.content)
        }

    override suspend fun summaryOf(tagId: String, level: Int): CompressionSummary? =
        db.tagSummaryDao().at(tagId, level)?.let { CompressionSummary(it.id, it.text) }

    override suspend fun write(write: CompressionWrite): Boolean = runCatching {
        db.withTransaction {
            // UNIQUE(tagId, level) 只约束"同层唯一"，换主键时会由 SQLite 的
            // REPLACE 语义处理；这里先显式删旧行，避免依赖 REPLACE 的隐式行为。
            db.tagSummaryDao().deleteAt(write.tagId, write.level)
            db.tagSummaryDao().upsert(
                com.simplechat.app.db.TagSummaryEntity(
                    id = write.summaryId,
                    tagId = write.tagId,
                    level = write.level,
                    text = write.text,
                    sourceRefs = DefaultCompressor.sourcesToJson(write.sources),
                    createdAt = write.now,
                )
            )
            db.tagDao().setCompressed(write.tagId, write.text, write.level, write.now)
        }
        true
    }.getOrDefault(false)
}

/** 永远不可用的摘要器：没有配置模型时用它，compress 会以 llm_empty 结束。 */
object UnavailableSummarizer : Summarizer {
    override suspend fun summarize(prompt: String): String? = null
}
