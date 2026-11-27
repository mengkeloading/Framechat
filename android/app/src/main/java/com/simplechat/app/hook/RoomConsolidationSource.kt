package com.simplechat.app.hook

import com.simplechat.app.data.SettingsStore
import com.simplechat.app.db.TagDao
import mychat.framework.memory.consolidate.CompressibleTag
import mychat.framework.memory.consolidate.ConsolidateSettings
import mychat.framework.memory.consolidate.ConsolidationSource

/** 可压缩的标签：currentLevel < 4 的那些。 */
class RoomConsolidationSource(private val tags: TagDao) : ConsolidationSource {

    override suspend fun compressible(): List<CompressibleTag> {
        val counts = tags.messageCounts().associate { it.tagId to it.count }
        return tags.allCompressible().map { tag ->
            CompressibleTag(
                id = tag.id,
                strength = tag.strength,
                readCount = tag.readCount,
                lastDecayAt = tag.lastDecayAt,
                currentLevel = tag.currentLevel,
                createdAt = tag.createdAt,
                lastCompressedAt = tag.lastCompressedAt,
                messageCount = counts[tag.id] ?: 0,
            )
        }
    }
}

/** 上次整理时间存在 DataStore 里，跟其它设置一样。 */
class DataStoreConsolidateSettings(
    private val store: SettingsStore,
) : ConsolidateSettings {
    override suspend fun lastConsolidateAt(): Long = store.lastConsolidateAt()
    override suspend fun setLastConsolidateAt(t: Long) = store.setLastConsolidateAt(t)
}
