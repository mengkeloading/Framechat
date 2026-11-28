package com.simplechat.app.hook

import com.simplechat.app.db.TagDao
import mychat.framework.memory.MemoryDirectory
import mychat.framework.memory.TagEntry
import mychat.framework.memory.TagStrength

/**
 * 记忆目录：标签 + 每个标签下的消息数。
 *
 * 消息数用 LEFT JOIN 一次拿到（没有消息的标签记 0），不逐个 count ——
 * 标签一多就是 N 次查询。
 */
class RoomMemoryDirectory(
    private val tags: TagDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : MemoryDirectory {

    override suspend fun list(limit: Int): List<TagEntry> {
        val now = clock()
        val counts = tags.messageCounts().associate { it.tagId to it.count }
        return tags.all()
            .map { tag ->
                TagEntry(
                    id = tag.id,
                    name = tag.name,
                    count = counts[tag.id] ?: 0,
                    strength = TagStrength.currentOf(
                        tag.strength, tag.lastDecayAt, tag.readCount, now,
                    ),
                )
            }
            .sortedByDescending { it.strength }
            .take(limit)
    }
}
