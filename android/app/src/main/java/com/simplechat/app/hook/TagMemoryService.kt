package com.simplechat.app.hook

import com.simplechat.app.db.TagDao
import com.simplechat.app.db.TagEntity
import mychat.framework.embedding.TextEmbedder
import mychat.framework.memory.Recall
import mychat.framework.memory.TagDecision
import mychat.framework.memory.TagRecord
import mychat.framework.memory.Tagger
import java.nio.ByteBuffer
import java.util.UUID

/**
 * 标签记忆的门面：向量算好、决策交给框架、存取交给 DAO。
 *
 * 全流程 fail-open —— 记忆是为了让对话更连贯，不是为了在它坏掉时把对话拦住。
 */
class TagMemoryService(
    private val embedder: TextEmbedder,
    private val tags: TagDao,
    private val enabled: () -> Boolean,
) {

    suspend fun recall(text: String): String? {
        if (!enabled() || !embedder.ready) return null
        return runCatching {
            val query = embedder.embedQuery(text) ?: return null
            val records = load()
            if (records.isEmpty()) {
                log("recall:miss best=0")
                return null
            }
            val hit = Recall.select(
                query = query,
                tags = records,
                summaryOf = { null },
                recentOf = { tagId -> tags.recentContents(tagId, MAX_RECALL_MESSAGES) },
            )
            if (hit == null) {
                val best = records.maxOfOrNull { cosineOf(query, it.vector) } ?: 0f
                log("recall:miss best=" + fmt(best))
                return null
            }
            log("recall:hit tag=" + hit.tagId + " len=" + hit.text.length)
            Recall.formatBlock(hit)
        }.getOrElse { error ->
            log("recall:error " + error.javaClass.simpleName)
            null
        }
    }

    suspend fun tag(userText: String, messageId: String) {
        if (!enabled() || !embedder.ready) return
        runCatching {
            val query = embedder.embedQuery(userText) ?: return
            val records = load()
            when (val decision = Tagger.decide(query, records, userText)) {
                is TagDecision.Assign -> {
                    tags.setMessageTag(messageId, decision.tagId)
                    log("tag:assign id=" + decision.tagId + " score=" + fmt(decision.score))
                }
                is TagDecision.Create -> {
                    val id = UUID.randomUUID().toString()
                    val now = System.currentTimeMillis()
                    tags.insert(TagEntity(id, decision.name, null, encode(query), now, now))
                    tags.setMessageTag(messageId, id)
                    log(
                        "tag:new name=\"" + decision.name + "\" score=" + fmt(decision.score) +
                            " total=" + (records.size + 1)
                    )
                }
                is TagDecision.Skip -> {
                    tags.setMessageTag(messageId, null)
                    log("tag:skip score=" + fmt(decision.score) + " reason=" + decision.reason)
                }
                is TagDecision.LimitReached -> {
                    tags.setMessageTag(messageId, null)
                    log("tag:limit_reached total=" + records.size)
                }
            }
        }.onFailure { error ->
            log("tag:error " + error.javaClass.simpleName)
        }
    }

    private suspend fun load(): List<TagRecord> = tags.all().mapNotNull { entity ->
        decode(entity.embedding)?.let { vector ->
            TagRecord(entity.id, entity.name, entity.description, vector)
        }
    }

    private fun log(line: String) {
        if (com.simplechat.app.BuildConfig.DEBUG) UsageLog.record(line)
    }

    private fun fmt(value: Float): String = String.format("%.2f", value)

    private fun cosineOf(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in 0 until minOf(a.size, b.size)) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0f || nb == 0f) return 0f
        return dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))
    }

    private fun encode(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * 4)
        vector.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    private fun decode(bytes: ByteArray): FloatArray? {
        if (bytes.isEmpty() || bytes.size % 4 != 0) return null
        val buffer = ByteBuffer.wrap(bytes)
        return FloatArray(bytes.size / 4) { buffer.getFloat() }
    }

    private companion object {
        const val MAX_RECALL_MESSAGES = 2
    }
}
