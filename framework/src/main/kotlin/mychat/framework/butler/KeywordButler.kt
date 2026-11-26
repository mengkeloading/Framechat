package mychat.framework.butler

/**
 * 关键词路由：没有模型也能工作。
 *
 * 关键词从两处取：
 * - `prefix`（如「小说」）——用户显式打的前缀词
 * - description 里的**引号片段**——模块作者写 "用户说\"帮我写个故事\"" 时，
 *   那些片段本身就是查询样本
 *
 * 只做整串包含匹配，不做分词：中文分词要么引库要么不准，而这里的样本本来就短。
 */
class KeywordButler : Butler {

    override val id: String = ID
    override val name: String = "关键词路由"

    override fun route(query: String, modules: List<ModuleMeta>): RouteResult {
        if (modules.isEmpty()) return RouteResult(emptyList(), "empty")
        val text = query.trim()
        if (text.isEmpty()) return RouteResult(emptyList(), "empty")

        val scores = LinkedHashMap<String, Float>()
        for (meta in modules) {
            val hits = keywordsOf(meta).count { text.contains(it) }
            if (hits > 0) scores[meta.id] = hits.toFloat()
        }
        if (scores.isEmpty()) return RouteResult(emptyList(), "below_min", scores)

        val best = scores.entries.maxByOrNull { it.value }!!
        return RouteResult(listOf(best.key), "hit", scores)
    }

    override fun classify(text: String): String? = null

    override fun normalize(text: String): String? = null

    override fun judge(output: String, spec: String): JudgeResult? = null

    override fun similarity(a: String, b: String): Float = 0f

    /** 前缀 + 描述里被引号包住的短语（2~12 字）。 */
    fun keywordsOf(meta: ModuleMeta): List<String> {
        val out = LinkedHashSet<String>()
        meta.prefix?.takeIf { it.isNotBlank() }?.let { out += it }
        QUOTED.findAll(meta.description).forEach { m ->
            val word = m.groupValues[1].trim()
            if (word.length in 2..12) out += word
        }
        return out.toList()
    }

    companion object {
        const val ID = "keyword"
        private val QUOTED = Regex("[\"“]([^\"”]{2,12})[\"”]")
    }
}
