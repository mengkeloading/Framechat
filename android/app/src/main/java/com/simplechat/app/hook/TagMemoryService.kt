package com.simplechat.app.hook

import com.simplechat.app.db.TagDao
import com.simplechat.app.db.TagEntity
import mychat.framework.embedding.TextEmbedder
import mychat.framework.memory.LayeredRecall
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
    private val links: com.simplechat.app.db.TagLinkDao? = null,
) {

    /** 一轮的记忆句柄；开关关闭、模型未就绪或算不出向量时为 null。 */
    suspend fun begin(text: String): Turn? {
        val off = mychat.framework.memory.MemoryGate.offReason(
            enabled = enabled(),
            embedderReady = embedder.ready,
            hasModules = true,
        )
        if (off != null) {
            log("mem:off reason=" + off)
            return null
        }
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
            val now = System.currentTimeMillis()
            // 三层：精确 → 模糊 → 提及。精确不看强度，所以归档的标签也可能在这里命中
            val result = LayeredRecall.recall(
                text = text,
                query = query,
                tags = snapshot,
                summaryOf = { id -> snapshot.firstOrNull { it.id == id }?.summary },
                log = { line -> log(line) },
                now = now,
            )
            when (result.reason) {
                LayeredRecall.MISS -> return null
                LayeredRecall.MENTION -> {
                    // 只记得名字：列出来让用户点名，不假装还记得细节
                    val names = result.hits.joinToString(" / ") { "「" + it.tagName + "」" }
                    return "【回忆】（背景资料，不是当前任务）\n" +
                        "还提到过：" + names + "\n（要看哪个，说它的名字）"
                }
            }

            var hit = result.first ?: return null
            /*
             * 原话要查库（suspend），而检索本身是纯函数 —— 摘要缺失时在这里补一次，
             * 只补命中的那一个标签，不扫全表。
             */
            if (hit.summary == null && hit.text == Recall.NO_DETAIL) {
                val lines = tags.recentUserContents(hit.tagId, MAX_RECALL_MESSAGES)
                    .take(MAX_RECALL_MESSAGES)
                    .map { it.take(EXCERPT) }
                if (lines.isNotEmpty()) {
                    val joined = lines.joinToString("\n---\n")
                    hit = hit.copy(text = joined, fallbackText = joined)
                }
            }
            if (result.reason == LayeredRecall.FUZZY && hit.depth != "name_only") {
                // 只有模糊命中才加强度：用户自己说出名字不该被算作「又想起来一次」
                val tag = snapshot.firstOrNull { it.id == hit.tagId }
                if (tag != null) {
                    val before = TagStrength.current(tag, now)
                    val boosted = TagStrength.boosted(tag, now)
                    persist(boosted, boosted.strength, TagStrength.depthOf(boosted.strength), now)
                    log(
                        "mem:boost tag=" + tag.id.take(8) + " from=" + fmt(before) +
                            " to=" + fmt(boosted.strength) + " reason=recall"
                    )
                }
            }
            log(
                "recall:hit tag=" + hit.tagId.take(8) + " reason=" + result.reason +
                    " score=" + fmt(hit.score) + " depth=" + hit.depth +
                    " len=" + hit.text.length
            )
            Recall.formatBlock(hit, relatedNames(hit.tagId))
        }.getOrElse { error ->
            log("recall:error " + error.javaClass.simpleName)
            null
        }

        /** 上一条打上的标签：用来记「这轮话题是从哪个转过来的」。 */
        private var lastTagId: String? = null

        /** 本轮标签与上一轮不同 → 记一次共现。 */
        private suspend fun linkFromPrevious(tagId: String) {
            val dao = links ?: return
            val prev = lastTagId
            lastTagId = tagId
            if (prev == null || prev == tagId) return
            runCatching { dao.link(prev, tagId, System.currentTimeMillis()) }
                .onSuccess { log("link:add from=" + prev.take(8) + " to=" + tagId.take(8)) }
                .onFailure { log("link:fail " + it.javaClass.simpleName) }
        }

        /** 与某个标签共现最多的标签名（最多 5 个）。 */
        private suspend fun relatedNames(tagId: String): List<String> {
            val dao = links ?: return emptyList()
            return runCatching { dao.relatedNames(tagId, Recall.RELATED_MAX) }
                .getOrDefault(emptyList())
        }

        /** 打标：与检索用同一份快照，新建的标签本轮不参与召回。 */
        /** @return 打上的标签 id；没打上（模糊/超限/出错）返回 null。 */
        suspend fun tag(messageId: String, userMessageId: String? = null): String? {
            return runCatching {
                when (val decision = Tagger.decide(query, snapshot, text)) {
                    is TagDecision.Assign -> {
                        tags.setMessageTag(messageId, decision.tagId)
                        userMessageId?.let { tags.setMessageTag(it, decision.tagId) }
                        log("tag:assign id=" + decision.tagId + " score=" + fmt(decision.score))
                        decision.tagId
                    }
                    is TagDecision.Create -> {
                        val id = UUID.randomUUID().toString()
                        val now = System.currentTimeMillis()
                        tags.insert(TagEntity(id, decision.name, null, encode(query), now, now))
                        tags.setMessageTag(messageId, id)
                        userMessageId?.let { tags.setMessageTag(it, id) }
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
                        id
                    }
                    is TagDecision.Skip -> {
                        tags.setMessageTag(messageId, null)
                        log("tag:skip score=" + fmt(decision.score) + " reason=" + decision.reason)
                        null
                    }
                    is TagDecision.LimitReached -> {
                        tags.setMessageTag(messageId, null)
                        log("tag:limit_reached total=" + snapshot.size)
                        null
                    }
                }
            }.onFailure { error ->
                log("tag:error " + error.javaClass.simpleName)
            }.getOrNull()?.also { id ->
                runCatching { linkFromPrevious(id) }
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

    /** 老标签的衰减锚点补一次；返回修了几条（0 = 不用修）。 */
    suspend fun fixMissingAnchors(now: Long = System.currentTimeMillis()): Int {
        val count = runCatching { tags.fixMissingDecayAnchor(now) }.getOrElse {
            log("mem:error " + it.javaClass.simpleName)
            return 0
        }
        if (count > 0) log("decay:anchor_fix count=" + count)
        return count
    }

    /**
     * 按 id 直接扣分：删除消息这类场景没有 Turn（不在某一次发送里）。
     * 失败只记日志 —— 记忆坏了不该让删除失败。
     */
    suspend fun penalize(tagId: String?, amount: Float, reason: String) {
        if (tagId == null) return
        runCatching {
            val entity = tags.byId(tagId) ?: return
            val now = System.currentTimeMillis()
            val record = decode(entity.embedding)?.let { vector ->
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
            } ?: return
            // 规则只在 Kotlin 里算一次，SQL 只负责写入
            val next = TagStrength.penalized(record, now, amount)
            tags.setStrength(entity.id, next.strength, next.lastDecayAt)
            log(
                "mem:penalty tag=" + tagId.take(8) + " from=" + fmt(record.strength) +
                    " to=" + fmt(next.strength) + " amount=" + fmt(amount) + " reason=" + reason
            )
        }.onFailure { error -> log("mem:error " + error.javaClass.simpleName) }
    }

    /** 批量删除：按 id 查出涉及的标签（去重），每个扣一次。 */
    suspend fun penalizeMessages(messageIds: Collection<String>, amount: Float, reason: String) {
        if (messageIds.isEmpty()) return
        runCatching {
            val tagIds = tags.tagIdsOf(messageIds.toList())
            for (tagId in tagIds) penalize(tagId, amount, reason)
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
