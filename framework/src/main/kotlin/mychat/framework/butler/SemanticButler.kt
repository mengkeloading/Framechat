package mychat.framework.butler

import mychat.framework.embedding.Candidate
import mychat.framework.embedding.ModuleRouter
import mychat.framework.embedding.TextEmbedder

/**
 * 向量路由：bge-small-zh 算查询向量，模块文档算候选向量，余弦 + 相对排序。
 *
 * 判定算法就是 [ModuleRouter.decide]（MIN_SCORE 0.30 / MIN_MARGIN 0.05），
 * 这里只是把它包成 Butler，并加上向量缓存与日志用的分数表。
 */
class SemanticButler(
    private val embedder: TextEmbedder,
) : Butler {

    override val id: String = ID
    override val name: String = "语义路由"

    private val keyword = KeywordButler()

    private class Cached(val text: String, val vector: FloatArray)

    private val cache = HashMap<String, Cached>()

    /** 同一次 route 里算过的分数，方便调用方写日志。 */
    override fun route(query: String, modules: List<ModuleMeta>): RouteResult {
        if (modules.isEmpty()) return RouteResult(emptyList(), "empty")
        // 模型未就绪时不能拿错误分数当结论：那是"还没准备好"，不是"都不像"
        if (!embedder.ready) return RouteResult(emptyList(), "not_ready")
        val queryVector = embedder.embedQuery(query)
            ?: return RouteResult(emptyList(), "no_vector")
        val candidates = modules.mapNotNull { meta ->
            vectorOf(meta)?.let { Candidate(meta.id, it) }
        }
        if (candidates.isEmpty()) return RouteResult(emptyList(), "no_vector")

        val decision = ModuleRouter.decide(queryVector, candidates)
        val scores = ModuleRouter.rank(queryVector, candidates).associate { it.name to it.score }
        return RouteResult(decision.modules, decision.reason, scores)
    }

    /**
     * 先用关键词规则（快、准、可解释），没命中再比向量。
     *
     * 关键词那条**总会**给结论（最差也是 CHAT），所以这里的向量分支只在
     * 关键词判成 CHAT 时才用得上 —— 那是"确实没看到任何特征词"的情况。
     */
    override fun classify(text: String): String? {
        val keyword = keyword.classify(text) ?: return null
        if (keyword != Intent.CHAT.id) return keyword
        if (!embedder.ready) return keyword
        val queryVector = embedder.embedQuery(text) ?: return keyword
        var best: Intent? = null
        var bestScore = INTENT_MIN_SCORE
        for (intent in Intent.entries) {
            val desc = INTENT_DESCRIPTIONS[intent] ?: continue
            val vector = intentVector(intent, desc) ?: continue
            val score = mychat.framework.embedding.Vectors.cosine(queryVector, vector)
            if (score > bestScore) {
                bestScore = score
                best = intent
            }
        }
        return best?.id ?: keyword
    }

    private class IntentCached(val desc: String, val vector: FloatArray)

    private val intentVectors = HashMap<Intent, IntentCached>()

    private fun intentVector(intent: Intent, desc: String): FloatArray? {
        intentVectors[intent]?.let { if (it.desc == desc) return it.vector }
        val vector = embedder.embedPassage(desc) ?: return null
        intentVectors[intent] = IntentCached(desc, vector)
        return vector
    }

    override fun normalize(text: String): String? = null

    override fun judge(output: String, spec: String): JudgeResult? = null

    override fun similarity(a: String, b: String): Float {
        val va = embedder.embedPassage(a) ?: return 0f
        val vb = embedder.embedPassage(b) ?: return 0f
        return mychat.framework.embedding.Vectors.cosine(va, vb)
    }

    /** 向量按 (id, 文本) 缓存：改过描述的模块自动重算。 */
    private fun vectorOf(meta: ModuleMeta): FloatArray? {
        val text = if (meta.embeddingText.isNotEmpty()) meta.embeddingText else meta.description
        cache[meta.id]?.let { if (it.text == text) return it.vector }
        val vector = embedder.embedPassage(text) ?: return null
        cache[meta.id] = Cached(text, vector)
        return vector
    }

    companion object {
        const val ID = "semantic"

        /** 向量判类型的门槛：低于它宁可保留关键词的结论。 */
        const val INTENT_MIN_SCORE = 0.45f

        private val INTENT_DESCRIPTIONS = mapOf(
            Intent.CHAT to "闲聊、寒暄、天气、日常问候",
            Intent.CODE to "代码审查、报错排查、重构、函数与类的问题",
            Intent.WRITING to "写故事、写小说、写文案、续写、场景描写",
            Intent.CALC to "数学计算、单位换算、数值求解",
            Intent.QUESTION to "知识问答、概念解释、原理与区别",
        )
    }
}
