package mychat.framework.embedding

data class Candidate(val name: String, val vector: FloatArray)

data class Scored(val name: String, val score: Float)

data class RouteDecision(
    /** 命中的模块 id；null = 不激活任何模块（全量注入 / 交给模型自己判断）。 */
    val module: String?,
    val top1: Scored?,
    val top2: Scored?,
    /** hit | below_min | margin | empty */
    val reason: String,
) {
    val margin: Float
        get() = if (top1 != null && top2 != null) top1.score - top2.score else 1f
}

/**
 * 路由判定：用**相对排序**而不是绝对阈值。
 *
 * 只看 top-1 的绝对分数不够——模型整体相似度偏低（描述写得短、领域词少）时，
 * 0.55 的固定阈值会把所有查询都判成"不匹配"；反过来一堆模块都 0.5 上下时，
 * 又会随手选第一个。所以同时要求：
 *   1) top-1 至少像（MIN_SCORE）
 *   2) top-1 比 top-2 明显更像（MIN_MARGIN）
 */
object ModuleRouter {

    /** 下限：防止"什么都不像也硬选一个"。 */
    const val MIN_SCORE = 0.30f

    /** 领先优势：防止"两个都差不多"时瞎选。 */
    const val MIN_MARGIN = 0.05f

    fun rank(query: FloatArray, candidates: List<Candidate>): List<Scored> =
        candidates
            .map { Scored(it.name, Vectors.cosine(query, it.vector)) }
            .sortedByDescending { it.score }

    fun decide(query: FloatArray, candidates: List<Candidate>): RouteDecision {
        if (candidates.isEmpty()) return RouteDecision(null, null, null, "empty")
        val sorted = rank(query, candidates)
        val top1 = sorted[0]
        val top2 = sorted.getOrNull(1)
        if (top1.score <= MIN_SCORE) return RouteDecision(null, top1, top2, "below_min")
        if (top2 != null && top1.score - top2.score <= MIN_MARGIN) {
            return RouteDecision(null, top1, top2, "margin")
        }
        return RouteDecision(top1.name, top1, top2, "hit")
    }
}
