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

    private class Cached(val text: String, val vector: FloatArray)

    private val cache = HashMap<String, Cached>()

    /** 同一次 route 里算过的分数，方便调用方写日志。 */
    override fun route(query: String, modules: List<ModuleMeta>): RouteResult {
        if (modules.isEmpty()) return RouteResult(emptyList(), "empty")
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

    override fun classify(text: String): String? = null

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
    }
}
