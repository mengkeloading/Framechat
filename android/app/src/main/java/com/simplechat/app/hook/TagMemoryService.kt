package com.simplechat.app.hook

import com.simplechat.app.db.TagDao
import com.simplechat.app.db.TagEntity
import mychat.framework.embedding.TextEmbedder
import mychat.framework.memory.Recall
import mychat.framework.memory.RecallHit
import mychat.framework.memory.TagDecision
import mychat.framework.memory.TagRecord
import mychat.framework.memory.Tagger
import java.nio.ByteBuffer
import java.util.UUID

/**
 * 标签记忆的门面：向量算好、决策交给框架、存取交给 DAO。
 *
 * 全流程 fail-open —— 记忆是为了让对话更连贯，不是为了在它坏掉时把对话拦住。
 *
 * **一轮一份快照**：检索与打标必须看同一份标签，否则本轮刚建的标签会立刻
 * 参与本轮的召回（那次命中的分数接近 1.0，会把回答带偏到自己的输入上）。
 */
class TagMemoryService(
    private val embedder: TextEmbedder,
    private val tags: TagDao,
    private val enabled: () -> Boolean,
) {

    /** 一轮的记忆句柄；开关关闭、模型未就绪或算不出向量时为 null。 */
    suspend fun begin(text: String): Turn? {
        if (!enabled() || !embedder.ready) return null
        return runCatching {
            val query = embedder.embedQuery(text) ?: return null
            Turn(query, load(), text)
        }.getOrNull()
    }

    inner class Turn(
        private val query: FloatArray,
        /** 本轮开始时加载的标签快照，**不含**本轮新建的。 */
        private val snapshot: List<TagRecord>,
        private val text: String,
    ) {

        /** 检索：只在快照里找，且必须够像才注入。 */
        suspend fun recallBlock(): String? = runCatching {
            if (snapshot.isEmpty()) {
                log("recall:miss best=0 threshold=" + fmt(Recall.MIN))
                return null
            }
            val best = snapshot.maxByOrNull { cosineOf(query, it.vector) } ?: return null
            val score = cosineOf(query, best.vector)
            if (score < Recall.MIN) {
                log("recall:miss best=" + fmt(score) + " threshold=" + fmt(Recall.MIN))
                return null
            }
            val body = tags.recentContents(best.id, MAX_RECALL_MESSAGES)
                .take(MAX_RECALL_MESSAGES)
                .map { it.take(EXCERPT) }
                .joinToString("\n---\n")
            if (body.isBlank()) {
                log("recall:miss best=" + fmt(score) + " threshold=" + fmt(Recall.MIN))
                return null
            }
            log("recall:hit tag=" + best.id + " score=" + fmt(score) + " len=" + body.length)
            Recall.formatBlock(RecallHit(best.id, best.name, score, body))
        }.getOrElse { error ->
            log("recall:error " + error.javaClass.simpleName)
            null
        }

        /** 打标：与检索用同一份快照，新建的标签本轮不参与召回。 */
        suspend fun tag(messageId: String) {
            runCatching {
                when (val decision = Tagger.decide(query, snapshot, text)) {
                    is TagDecision.Assign -> {
                        tags.setMessageTag(messageId, decision.tagId)
                        log("tag:assign id=" + decision.tagId + " score=" + fmt(decision.score))
                    }
                    is TagDecision.Create -> {
                        val id = UUID.randomUUID().toString()
                        val now = System.currentTimeMillis()
                        tags.insert(TagEntity(id, decision.name, null, encode(query), now, now))
                        tags.setMessageTag(messageId, id)
                        val score = if (decision.reason == "first") {
                            "none"
                        } else {
                            fmt(decision.score)
                        }
                        log(
                            "tag:new name=\"" + decision.name + "\" score=" + score +
                                " total=" + (snapshot.size + 1) +
                                (decision.reason?.let { " reason=" + it } ?: "")
                        )
                    }
                    is TagDecision.Skip -> {
                        tags.setMessageTag(messageId, null)
                        log("tag:skip score=" + fmt(decision.score) + " reason=" + decision.reason)
                    }
                    is TagDecision.LimitReached -> {
                        tags.setMessageTag(messageId, null)
                        log("tag:limit_reached total=" + snapshot.size)
                    }
                }
            }.onFailure { error ->
                log("tag:error " + error.javaClass.simpleName)
            }
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
        const val EXCERPT = 200
    }
}
