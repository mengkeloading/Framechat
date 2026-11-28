package mychat.framework.memory

/** 分层召回的结果：命中的标签 + 是哪一层命中的。 */
data class RecallResult(val hits: List<RecallHit>, val reason: String) {
    val first: RecallHit? get() = hits.firstOrNull()
    val isEmpty: Boolean get() = hits.isEmpty()
}

/**
 * 三层检索：精确 → 模糊 → 提及。
 *
 * 为什么要有「精确」这一层：用户说「MyChat 的注入是不是有问题」时，标签名里就写着
 * MyChat，向量相似度却可能因为句子长短掉到 0.6 以下 —— 字面已经对上了还要算余弦，
 * 等于白扔掉最可靠的那条线索。
 *
 * 顺序不能换：精确命中一律优先于模糊，否则一个总分更高的无关话题会把对的挤掉。
 */
object LayeredRecall {

    const val EXACT = "exact"
    const val FUZZY = "fuzzy"
    const val MENTION = "mention"
    const val MISS = "miss"

    /** mentions 命中最多列几个。 */
    const val MENTION_MAX = 3

    /** 标签名里的分隔符：按这些切开后再逐词比对。 */
    private val NAME_SEPARATORS = Regex("[\\s，。、·]+")

    /** 太短的词（「的」「我」）到处都是，把它当命中等于全部命中。 */
    private const val MIN_WORD = 2

    /**
     * 字面命中：标签名切成词，任一词出现在输入里即命中；多个命中取名字最长的。
     *
     * **不看强度**：归档的标签也参与 —— 用户自己提起名字，说明这事还在他心里，
     * 这时候装失忆比召回错的更糟。
     */
    fun exactMatch(text: String, tags: List<TagRecord>): TagRecord? {
        if (text.isBlank()) return null
        return tags
            .filter { tag ->
                tag.name.split(NAME_SEPARATORS)
                    .filter { it.length >= MIN_WORD }
                    .any { word -> text.contains(word, ignoreCase = true) }
            }
            .maxByOrNull { it.name.length }
    }

    fun recall(
        text: String,
        query: FloatArray,
        tags: List<TagRecord>,
        summaryOf: (String) -> String? = { null },
        recentOf: (String) -> List<MemoryItem> = { emptyList() },
        log: (String) -> Unit = {},
        now: Long = System.currentTimeMillis(),
    ): RecallResult {
        exactMatch(text, tags)?.let { tag ->
            log("recall:exact tag=" + tag.id.take(8) + " name=" + tag.name)
            return RecallResult(listOf(detailOf(tag, summaryOf, recentOf)), EXACT)
        }

        // 模糊只在够像、且强度没塌的标签里找（归档的不参与）
        val fuzzy = Recall.select(
            query, tags,
            { id -> runCatching { summaryOf(id) }.getOrNull() },
            { id -> runCatching { recentOf(id) }.getOrDefault(emptyList()) },
            now = now,
        )
        if (fuzzy != null) {
            log("recall:fuzzy tag=" + fuzzy.tagId.take(8) + " score=" + fmt(fuzzy.score))
            return RecallResult(listOf(fuzzy), FUZZY)
        }

        val mentioned = tags
            .filter { tag -> tag.mentions.any { it.isNotBlank() && text.contains(it) } }
            .take(MENTION_MAX)
            .map { tag ->
                RecallHit(
                    tagId = tag.id,
                    tagName = tag.name,
                    score = 0f,
                    text = "",
                    depth = DEPTH_NAME_ONLY,
                )
            }
        if (mentioned.isNotEmpty()) {
            log("recall:mention names=[" + mentioned.joinToString(", ") { it.tagName } + "]")
            return RecallResult(mentioned, MENTION)
        }

        val best = tags.maxOfOrNull { cosine(query, it.vector) } ?: 0f
        log("recall:miss best=" + fmt(best))
        return RecallResult(emptyList(), MISS)
    }

    /** 精确命中的内容：摘要优先，其次原话，都没有就给一句「暂无细节」。 */
    private fun detailOf(
        tag: TagRecord,
        summaryOf: (String) -> String?,
        recentOf: (String) -> List<MemoryItem>,
    ): RecallHit {
        val summary = tag.summary?.takeIf { it.isNotBlank() }
            ?: runCatching { summaryOf(tag.id) }.getOrNull()?.takeIf { it.isNotBlank() }
        val summaryText = summary?.let {
            if (it.length > Recall.SUMMARY_MAX) it.take(Recall.SUMMARY_MAX) else it
        }
        val fallback = runCatching { recentOf(tag.id) }.getOrDefault(emptyList())
            .filter { it.role.equals("user", ignoreCase = true) }
            .take(2)
            .map { it.text.take(EXCERPT) }
            .joinToString("\n---\n")
        val body = summaryText ?: fallback.ifBlank { Recall.NO_DETAIL }
        return RecallHit(
            tagId = tag.id,
            tagName = tag.name,
            score = 1f,
            text = body,
            depth = DEPTH_EXACT,
            summary = summaryText,
            fallbackText = fallback,
        )
    }

    private const val DEPTH_EXACT = "exact"
    private const val DEPTH_NAME_ONLY = "name_only"
    private const val EXCERPT = 200

    private fun fmt(v: Float): String = "%.3f".format(v)

    /** 只用来在 miss 日志里报「最近的那个有多近」。 */
    private fun cosine(a: FloatArray, b: FloatArray): Float {
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
}
