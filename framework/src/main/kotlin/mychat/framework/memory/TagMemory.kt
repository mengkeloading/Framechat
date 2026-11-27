package mychat.framework.memory

/**
 * 标签记忆：用向量给对话贴标签，下一轮遇到相似话题就把上次的内容捞回来。
 *
 * 零模型成本——只用已有的 embedding，不打标、不生成摘要、不额外请求。
 * 所有判断都是纯函数，App 侧只负责存取向量。
 */
data class TagRecord(
    val id: String,
    val name: String,
    val description: String? = null,
    /** 标签的代表向量（创建时就是那轮输入的查询向量）。 */
    val vector: FloatArray,
) {
    // FloatArray 的默认 equals 是引用比较，data class 生成的实现会误判
    override fun equals(other: Any?): Boolean =
        this === other || (other is TagRecord && id == other.id && name == other.name)

    override fun hashCode(): Int = id.hashCode() * 31 + name.hashCode()
}

/** 打标决策。 */
sealed interface TagDecision {

    /** 归到已有标签。 */
    data class Assign(val tagId: String, val score: Float) : TagDecision

    /** 新建标签。 */
    data class Create(val name: String, val score: Float) : TagDecision

    /** 分数落在模糊区间，不贴也不建。 */
    data class Skip(val score: Float, val reason: String) : TagDecision

    /** 标签数到顶，不新建。 */
    data class LimitReached(val score: Float) : TagDecision
}

object Tagger {

    /** 高于它归到已有标签。 */
    const val ASSIGN_MIN = 0.55f

    /** 低于它才新建；中间是"说不准"。 */
    const val NEW_MAX = 0.40f

    /** 上限：标签表膨胀会让每轮多算 N 次余弦。 */
    const val MAX_TAGS = 200

    /** 新标签名取输入前多少字。 */
    const val NAME_LIMIT = 20

    fun decide(
        query: FloatArray,
        tags: List<TagRecord>,
        text: String,
        assignMin: Float = ASSIGN_MIN,
        newMax: Float = NEW_MAX,
        maxTags: Int = MAX_TAGS,
    ): TagDecision {
        if (tags.isNotEmpty()) {
            val best = tags.maxByOrNull { cosine(query, it.vector) }
            if (best != null) {
                val score = cosine(query, best.vector)
                if (score >= assignMin) return TagDecision.Assign(best.id, score)
                if (score >= newMax) return TagDecision.Skip(score, "ambiguous")
                if (tags.size >= maxTags) return TagDecision.LimitReached(score)
                return TagDecision.Create(nameOf(text), score)
            }
        }
        // 一个标签都没有：第一轮直接建
        return TagDecision.Create(nameOf(text), 0f)
    }

    fun nameOf(text: String): String {
        val trimmed = text.trim().replace('\n', ' ')
        return if (trimmed.length <= NAME_LIMIT) trimmed else trimmed.take(NAME_LIMIT)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0f || nb == 0f) return 0f
        return dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))
    }
}

/** 命中的回忆。 */
data class RecallHit(
    val tagId: String,
    val tagName: String,
    val score: Float,
    val text: String,
)

object Recall {

    /** 低于它不注入：多半是"沾边但不是同一件事"。 */
    const val MIN = 0.55f

    /**
     * @param summaryOf 标签摘要；现在没有生成器，恒为 null
     * @param recentOf  该标签下最近的原文（已按时间倒序）
     */
    fun select(
        query: FloatArray,
        tags: List<TagRecord>,
        summaryOf: (String) -> String?,
        recentOf: (String) -> List<String>,
        min: Float = MIN,
        maxMessages: Int = 2,
        excerpt: Int = 200,
    ): RecallHit? {
        if (tags.isEmpty()) return null
        val best = tags.maxByOrNull { cosine(query, it.vector) } ?: return null
        val score = cosine(query, best.vector)
        if (score < min) return null
        val summary = summaryOf(best.id)
        val text = if (!summary.isNullOrBlank()) {
            summary
        } else {
            recentOf(best.id).take(maxMessages)
                .map { it.take(excerpt) }
                .joinToString("\n---\n")
        }
        if (text.isBlank()) return null
        return RecallHit(best.id, best.name, score, text)
    }

    /** 拼进注入块的整段文本。 */
    fun formatBlock(hit: RecallHit): String =
        "【回忆】\n关于「" + hit.tagName + "」：\n" + hit.text

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0f || nb == 0f) return 0f
        return dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))
    }
}
