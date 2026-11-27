package com.simplechat.app.hook

import com.simplechat.app.db.TagDao
import com.simplechat.app.db.TagEntity
import mychat.framework.embedding.TextEmbedder
import mychat.framework.memory.Recall
import mychat.framework.memory.RecallHit
import mychat.framework.memory.TagDecision
import mychat.framework.memory.TagRecord
import mychat.framework.memory.TagStrength
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

        /** 本轮命中的标签；负反馈只惩罚它。 */
        private var lastHitTagId: String? = null

        /** 检索：只在快照里找，且必须够像才注入。 */
        suspend fun recallBlock(): String? = runCatching {
            if (snapshot.isEmpty()) {
                log("recall:miss best=0 threshold=" + fmt(Recall.MIN))
                return null
            }
            val now = System.currentTimeMillis()
            // 先按「余弦 × 强度加权」挑最值得想起来的，再用裸余弦判断够不够像
            val best = snapshot.maxByOrNull { tag ->
                cosineOf(query, tag.vector) * TagStrength.weight(TagStrength.current(tag, now))
            } ?: return null
            val cosine = cosineOf(query, best.vector)
            if (cosine < Recall.MIN) {
                log("recall:miss best=" + fmt(cosine) + " threshold=" + fmt(Recall.MIN))
                return null
            }

            val strength = TagStrength.current(best, now)
            val depth = TagStrength.depthOf(strength)
            if (depth == "archived") {
                log("recall:miss reason=archived tag=" + best.id.take(8))
                return null
            }

            val body = if (depth == "name_only") {
                ""
            } else {
                tags.recentContents(best.id, MAX_RECALL_MESSAGES)
                    .take(MAX_RECALL_MESSAGES)
                    .map { it.take(EXCERPT) }
                    .joinToString("\n---\n")
            }
            if (depth != "name_only" && body.isBlank()) {
                log("recall:miss best=" + fmt(cosine) + " reason=empty")
                return null
            }

            val hit = RecallHit(best.id, best.name, cosine, body, depth)
            log(
                "recall:hit tag=" + best.id.take(8) + " score=" + fmt(cosine) +
                    " depth=" + depth + " len=" + body.length
            )

            val boosted = TagStrength.boosted(best, now)
            persist(boosted, boosted.strength, TagStrength.depthOf(boosted.strength), now)
            log(
                "mem:boost tag=" + best.id.take(8) + " from=" + fmt(strength) +
                    " to=" + fmt(boosted.strength) + " reason=recall"
            )
            lastHitTagId = hit.tagId
            Recall.formatBlock(hit)
        }.getOrElse { error ->
            log("recall:error " + error.javaClass.simpleName)
            null
        }

        /** 负反馈：用户说"不对"，或 judge 报了空话/复述。没命中过就什么都不做。 */
        suspend fun penalize(amount: Float, reason: String) {
            val last = lastHitTagId ?: return
            runCatching {
                val now = System.currentTimeMillis()
                val record = snapshot.firstOrNull { it.id == last } ?: return
                val penalty = TagStrength.penalized(record, now, amount)
                persist(penalty, penalty.strength, TagStrength.depthOf(penalty.strength), now)
                log(
                    "mem:penalty tag=" + last.take(8) + " from=" + fmt(record.strength) +
                        " to=" + fmt(penalty.strength) + " reason=" + reason
                )
            }.onFailure { error -> log("mem:error " + error.javaClass.simpleName) }
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
            TagRecord(
                id = entity.id,
                name = entity.name,
                description = entity.description,
                vector = vector,
                strength = entity.strength,
                readCount = entity.readCount,
                lastReadAt = entity.lastReadAt,
                lastDecayAt = entity.lastDecayAt,
            )
        }
    }

    /** 启动时打前 5 个标签的强度/深度，用来判断衰减是否在跑。 */
    suspend fun logTopTags() {
        if (!enabled()) return
        runCatching {
            val now = System.currentTimeMillis()
            tags.top(5).forEach { entity ->
                val record = TagRecord(
                    entity.id, entity.name, entity.description,
                    decode(entity.embedding) ?: return@forEach,
                    strength = entity.strength,
                    readCount = entity.readCount,
                    lastReadAt = entity.lastReadAt,
                    lastDecayAt = entity.lastDecayAt,
                )
                val current = TagStrength.current(record, now)
                log(
                    "mem:strength tag=" + entity.id.take(8) + " strength=" + fmt(current) +
                        " depth=" + TagStrength.depthOf(current) + " read=" + entity.readCount
                )
                if (entity.strength - current > 0.01f) {
                    log("mem:decay tag=" + entity.id.take(8) + " from=" + fmt(entity.strength) + " to=" + fmt(current))
                }
            }
        }.onFailure { error -> log("mem:error " + error.javaClass.simpleName) }
    }

    /**
     * 按 id 直接扣分：删除消息这类场景没有 Turn（不在某一次发送里）。
     * 失败只记日志 —— 记忆坏了不该让删除失败。
     */
    suspend fun penalize(tagId: String?, amount: Float, reason: String) {
        if (tagId == null) return
        runCatching {
            tags.penalizeTag(tagId, amount)
            log("mem:penalty tag=" + tagId.take(8) + " amount=" + fmt(amount) + " reason=" + reason)
        }.onFailure { error -> log("mem:error " + error.javaClass.simpleName) }
    }

    /** 用当前强度与深度把一句标签落库。 */
    private suspend fun persist(tag: TagRecord, strength: Float, depth: String, now: Long) {
        tags.updateStrength(
            id = tag.id,
            strength = strength,
            readCount = tag.readCount,
            lastReadAt = tag.lastReadAt,
            lastDecayAt = tag.lastDecayAt,
            depth = depth,
            now = now,
        )
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
