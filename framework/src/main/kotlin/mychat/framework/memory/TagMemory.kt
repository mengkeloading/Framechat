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
    /** 可选摘要；现在没有生成器，恒为 null。 */
    val summary: String? = null,
    val strength: Float = TagStrength.INITIAL,
    val readCount: Int = 0,
    val lastReadAt: Long = 0,
    /** 衰减起点；0 表示"就是现在"，避免新标签一创建就欠几十天。 */
    val lastDecayAt: Long = 0,
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

    /** 新建标签。[reason] 为 "first" 表示标签表本来是空的。 */
    data class Create(val name: String, val score: Float, val reason: String? = null) : TagDecision

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
        // 一个标签都没有：第一轮直接建。不比较、不判模糊 —— 没有可比的东西。
        if (tags.isEmpty()) {
            return TagDecision.Create(nameOf(text), 0f, reason = "first")
        }
        val best = tags.maxByOrNull { cosine(query, it.vector) }
            ?: return TagDecision.Create(nameOf(text), 0f, reason = "first")
        val score = cosine(query, best.vector)
        if (score >= assignMin) return TagDecision.Assign(best.id, score)
        if (score >= newMax) return TagDecision.Skip(score, "ambiguous")
        if (tags.size >= maxTags) return TagDecision.LimitReached(score)
        return TagDecision.Create(nameOf(text), score)
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

/**
 * 回忆素材：只取用户说过的话。
 *
 * 为什么不取模型回答：回答是"当时的解释"，换个说法就过时；用户的提问才是
 * 这个话题的稳定事实。而且回答里带结论，注入回去容易让模型照抄旧结论。
 */
data class MemoryItem(val role: String, val text: String)

/** 命中的回忆。 */
data class RecallHit(
    val tagId: String,
    val tagName: String,
    val score: Float,
    val text: String,
    /** name_only / summary / full */
    val depth: String = "summary",
)

object Recall {

    /**
     * 低于它不注入。
     *
     * 0.55 太松——BGE 短中文里"沾边"轻松就能过 0.55，结果是模型被无关旧内容带偏。
     * 真实相关的同话题句子一般在 0.65~0.8，所以门槛定在 0.68：
     * **宁可少注入，不可错注入。**
     */
    const val MIN = 0.68f

    /**
     * 强度闸门：低于它不主动召回（但标签仍可见）。
     *
     * 与归档阈值分开：归档是"再也不提"，闸门是"这次先不提" —— 强度还能靠召回涨回来。
     */
    const val MIN_STRENGTH = 0.22f

    /**
     * @param summaryOf 标签摘要；现在没有生成器，恒为 null
     * @param recentOf  该标签下最近的原文（已按时间倒序）
     */
    fun select(
        query: FloatArray,
        tags: List<TagRecord>,
        summaryOf: (String) -> String?,
        recentOf: (String) -> List<MemoryItem>,
        min: Float = MIN,
        maxMessages: Int = 2,
        excerpt: Int = 200,
        now: Long = System.currentTimeMillis(),
    ): RecallHit? {
        if (tags.isEmpty()) return null

        // 归档的不参与召回；其余按「余弦 × 强度加权」排序
        val scored = tags.mapNotNull { tag ->
            val strength = TagStrength.current(tag, now)
            val depth = TagStrength.depthOf(strength)
            if (depth == DEPTH_ARCHIVED) return@mapNotNull null
            if (strength < MIN_STRENGTH) return@mapNotNull null
            val cosine = cosine(query, tag.vector)
            Scored(tag, cosine * TagStrength.weight(strength), cosine, depth)
        }
        val best = scored.maxByOrNull { it.finalScore } ?: return null
        if (best.cosine < min) return null

        val userLines = recentOf(best.tag.id).filter { it.role.equals("user", ignoreCase = true) }
        val text = when (best.depth) {
            DEPTH_NAME_ONLY -> ""
            DEPTH_FULL -> {
                val body = summaryOf(best.tag.id) ?: userLines
                    .take(FULL_MESSAGES).map { it.text.take(excerpt) }.joinToString("\n---\n")
                if (body.isBlank()) NO_DETAIL else body + "\n（可展开原文）"
            }
            else -> {
                val summary = summaryOf(best.tag.id)
                if (!summary.isNullOrBlank()) {
                    summary
                } else {
                    val body = userLines.take(maxMessages)
                        .map { it.text.take(excerpt) }
                        .joinToString("\n---\n")
                    if (body.isBlank()) NO_DETAIL else body
                }
            }
        }
        if (best.depth != DEPTH_NAME_ONLY && text.isBlank()) return null
        return RecallHit(best.tag.id, best.tag.name, best.finalScore, text, best.depth)
    }

    private class Scored(
        val tag: TagRecord,
        val finalScore: Float,
        val cosine: Float,
        val depth: String,
    )

    /** 拼进注入块的整段文本。边界说明是内容的一部分，不是可选装饰。 */
    fun formatBlock(hit: RecallHit): String = buildString {
        append("【回忆】（背景资料，不是当前任务）\n")
        if (hit.depth == DEPTH_NAME_ONLY) {
            append("关于「").append(hit.tagName).append("」的记忆（只记得话题，细节已淡）。\n")
        } else {
            append("关于「").append(hit.tagName).append("」的过往记录：\n")
            append(hit.text).append('\n')
        }
        append("注意：以上内容仅供背景参考。\n")
        append("如果用户当前的问题与它无关，请完全忽略，直接回答当前问题。\n")
        append("不要在回答里主动提起上面的内容。")
    }

    private const val DEPTH_ARCHIVED = "archived"
    private const val DEPTH_NAME_ONLY = "name_only"
    private const val DEPTH_FULL = "full"
    private const val FULL_MESSAGES = 4

    /** 标签下没有用户原话时的兜底：总比空着强。 */
    const val NO_DETAIL = "（暂无更多细节）"

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
