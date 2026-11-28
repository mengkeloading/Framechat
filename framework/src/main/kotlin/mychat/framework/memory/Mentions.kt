package mychat.framework.memory

/**
 * 摘要末尾的 `MENTIONS: 话题A, 话题B`。
 *
 * 为什么要让模型顺手写这一行：摘要是模型唯一一次"通读整段对话"的时机，
 * 这时候顺手记下它提到了哪些**别的**话题，比之后再单独跑一次提取便宜得多。
 */
data class MentionsResult(val summary: String, val mentions: List<String>)

object Mentions {

    /** 最多留几个：再多就不是"提到过"，是"什么都提到了"。 */
    const val MAX = 5

    private val LINE = Regex("(?im)^\\s*MENTIONS\\s*[:：]\\s*(.+?)\\s*$")
    private val SEPARATORS = Regex("[,，、;；]+")

    /**
     * 从模型输出里剥出 mentions 行，剩下的才是摘要正文。
     *
     * 没有这一行很正常（确实没提到别的），返回空列表即可，不要编。
     */
    fun extract(raw: String): MentionsResult {
        val matches = LINE.findAll(raw).toList()
        if (matches.isEmpty()) return MentionsResult(raw.trim(), emptyList())

        val names = matches
            .flatMap { it.groupValues[1].split(SEPARATORS) }
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= 20 }
            .distinct()
            .take(MAX)

        val body = LINE.replace(raw, "").trim()
        return MentionsResult(body, names)
    }

    /** 存库用：逗号分隔（和 profile 的列表列一样）。 */
    fun join(mentions: List<String>): String = mentions.joinToString(",")

    fun split(raw: String?): List<String> =
        raw.orEmpty().split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * 标签的质心向量：新消息归入时把标签的向量往它那边挪一点。
 *
 * 为什么只挪前 10 条：标签刚建时向量就是"第一条消息"，太窄；挪着挪着会变成
 * 整个话题的平均。但如果一直挪，一个聊了半年的宽泛标签会漂到"什么都像"，
 * 召回就开始乱给 —— 10 条之后固定，保住它的语义中心。
 */
object TagCentroid {

    const val MAX_MESSAGES = 10

    /** 返回更新后的单位向量；已经够了就返回 null（调用方不该写库）。 */
    fun updated(vector: FloatArray, msgCount: Int, next: FloatArray): FloatArray? {
        if (msgCount >= MAX_MESSAGES) return null
        val n = minOf(vector.size, next.size)
        if (n == 0) return null
        val out = FloatArray(n)
        val weight = msgCount.coerceAtLeast(0).toFloat()
        for (i in 0 until n) {
            out[i] = (vector[i] * weight + next[i]) / (weight + 1f)
        }
        return normalize(out)
    }

    fun normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = kotlin.math.sqrt(norm)
        if (norm == 0f) return v
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = v[i] / norm
        return out
    }
}
