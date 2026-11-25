package mychat.framework.embedding

data class Candidate(val name: String, val vector: FloatArray)

data class Scored(val name: String, val score: Float)

data class RouteDecision(
    /** 命中的模块，按**执行顺序**排列；空表示走全量注入兜底。 */
    val modules: List<String>,
    val top1: Scored?,
    val top2: Scored?,
    /** hit（1~2 个） | below_min | empty */
    val reason: String,
) {
    val margin: Float
        get() = if (top1 != null && top2 != null) top1.score - top2.score else 1f

    val primary: String? get() = modules.firstOrNull()
}

/**
 * 路由判定：用**相对排序**而不是绝对阈值。
 *
 * 只看 top-1 的绝对分数不够——模型整体相似度偏低（描述写得短、领域词少）时，
 * 固定阈值会把所有查询都判成"不匹配"；反过来一堆模块都 0.5 上下时，又会随手选第一个。
 *
 * 为什么返回两个：模块之间可以协作。`算一下 12*37` 既需要 local_calc（算），
 * 也需要 math_guard（核对），只留一个等于把另一侧能力丢掉。
 * 返回顺序即执行顺序：分数高者靠前，短路型模块自然排在检查型前面。
 */
object ModuleRouter {

    /** 下限：防止"什么都不像也硬选一个"。 */
    const val MIN_SCORE = 0.30f

    /** 领先优势：两个分数接近时不再区分谁"更像"，直接都收。 */
    const val MIN_MARGIN = 0.05f

    /** 第二名够得着的窗口：与 top-1 相差不超过这个值才算协作候选。 */
    const val SECOND_WINDOW = 0.15f

    /** 最多激活几个模块。 */
    const val MAX_MODULES = 2

    /**
     * 不参与路由的模块：它自己就是兜底路径（全量注入）。
     * 描述是「把所有技能规范注入本轮，由模型自己判断」，天然和所有查询沾边，
     * 放进来只会制造假 margin。无条件排除，不看分数。
     */
    val EXCLUDED_IDS = setOf("skill_hub")

    fun isRoutable(id: String): Boolean = id !in EXCLUDED_IDS

    fun rank(query: FloatArray, candidates: List<Candidate>): List<Scored> =
        candidates
            .map { Scored(it.name, Vectors.cosine(query, it.vector)) }
            .sortedByDescending { it.score }

    /**
     * 返回空列表**不等于“不激活任何模块”**：调用方应当回退到全量注入
     * （注入所有启用模块的 SKILL.md）。路由是优化，不是必需。
     */
    fun decide(query: FloatArray, candidates: List<Candidate>): RouteDecision {
        val routable = candidates.filter { isRoutable(it.name) }
        if (routable.isEmpty()) return RouteDecision(emptyList(), null, null, "empty")
        val sorted = rank(query, routable)
        val top1 = sorted[0]
        val top2 = sorted.getOrNull(1)

        if (top1.score <= MIN_SCORE) {
            return RouteDecision(emptyList(), top1, top2, "below_min")
        }

        val accepted = sorted
            .filter { it.score > MIN_SCORE && it.score >= top1.score - SECOND_WINDOW }
            .take(MAX_MODULES)
            .map { it.name }

        return RouteDecision(accepted, top1, top2, "hit")
    }
}
