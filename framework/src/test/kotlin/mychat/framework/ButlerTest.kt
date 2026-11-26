package mychat.framework

import mychat.framework.butler.Butler
import mychat.framework.butler.ButlerManager
import mychat.framework.butler.KeywordButler
import mychat.framework.butler.ModuleMeta
import mychat.framework.butler.NoButler
import mychat.framework.butler.RouteResult
import mychat.framework.butler.SemanticButler
import mychat.framework.embedding.Candidate
import mychat.framework.embedding.ModuleRouter
import mychat.framework.embedding.ModuleSelector
import mychat.framework.embedding.TextEmbedder
import mychat.framework.hook.ModuleManifest
import mychat.framework.hook.ModulePackage
import kotlin.system.exitProcess

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, cond: Boolean, detail: String? = null) {
    if (cond) {
        passed++
    } else {
        failures += name
        println("FAIL  $name" + (detail?.let { "  ($detail)" } ?: ""))
    }
}

private fun eq(name: String, expected: Any?, actual: Any?) =
    check(name, expected == actual, "expected=$expected actual=$actual")

private class MapEmbedder(
    private val table: Map<String, FloatArray>,
    override val ready: Boolean = true,
) : TextEmbedder {
    var queryCalls = 0
        private set

    /** 先精确匹配，再退到「包含」——模块文档文本是拼接出来的，不会与 key 完全相等。 */
    override fun embed(text: String): FloatArray? {
        table[text]?.let { return it }
        return table.entries
            .filter { text.contains(it.key) }
            .maxByOrNull { it.key.length }
            ?.value
    }

    override fun embedQuery(text: String): FloatArray? {
        queryCalls++
        return embed(text)
    }

    override fun embedPassage(text: String): FloatArray? = embed(text)
}

fun main() {
    semanticTests()
    notReadyTests()
    keywordTests()
    noButlerTests()
    managerTests()

    println()
    println("PASS $passed / ${passed + failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  - $it") }
        exitProcess(1)
    }
}

// ---------- 语义管家：与 ModuleRouter 结果一致 ----------

private fun semanticTests() {
    val embedder = MapEmbedder(
        mapOf(
            "q_story" to floatArrayOf(1f, 0f),
            "doc_novel" to floatArrayOf(1f, 0f),
            "doc_calc" to floatArrayOf(0f, 1f),
            "q_none" to floatArrayOf(-1f, -1f),
        )
    )
    val butler = SemanticButler(embedder)
    val metas = listOf(
        ModuleMeta("skill_novel", "小说写作", "", null, "doc_novel"),
        ModuleMeta("local_calc", "本地计算", "", null, "doc_calc"),
    )
    val candidates = listOf(
        Candidate("skill_novel", floatArrayOf(1f, 0f)),
        Candidate("local_calc", floatArrayOf(0f, 1f)),
    )

    // 等价性：管家结果必须与直接调 ModuleRouter.decide 完全一致
    for (query in listOf("q_story", "q_none")) {
        val q = embedder.embedQuery(query)!!
        val expected = ModuleRouter.decide(q, candidates).modules
        val actual = butler.route(query, metas).modules
        eq("butler: $query 与 ModuleRouter 一致", expected, actual)
    }

    val story = butler.route("q_story", metas)
    eq("butler: 命中小说", listOf("skill_novel"), story.modules)
    eq("butler: reason=hit", "hit", story.reason)
    check("butler: scores 带分数", story.scores.containsKey("skill_novel"))

    val none = butler.route("q_none", metas)
    eq("butler: 全低返回空", emptyList<String>(), none.modules)

    eq("butler: 未知查询拿不到向量", "no_vector", butler.route("ghost", metas).reason)
    eq("butler: 空模块直接 empty", "empty", butler.route("q_story", emptyList()).reason)

    eq("butler: classify 未实现", null, butler.classify("x"))
    eq("butler: normalize 未实现", null, butler.normalize("x"))
    eq("butler: judge 未实现", null, butler.judge("x", "y"))
}

// ---------- 模型未就绪 ----------

private fun notReadyTests() {
    val embedder = MapEmbedder(
        mapOf("q" to floatArrayOf(1f, 0f), "小说写作" to floatArrayOf(1f, 0f)),
        ready = false
    )
    val butler = SemanticButler(embedder)
    val metas = listOf(ModuleMeta("skill_novel", "小说写作", "", null, "小说写作"))

    val route = butler.route("q", metas)
    eq("ready: 未就绪时返回空", emptyList<String>(), route.modules)
    eq("ready: reason=not_ready", "not_ready", route.reason)
    eq("ready: 未就绪时不调 embedQuery", 0, embedder.queryCalls)

    val modules = listOf(
        ModulePackage(
            manifest = ModuleManifest("skill_novel", "小说写作", description = "写故事"),
            body = "", scripts = emptyMap(), references = mapOf("SKILL.md" to "规范")
        )
    )
    eq(
        "ready: 未就绪时仍能兜底",
        setOf("skill_novel"),
        ModuleSelector.select(modules, route)
    )
}

// ---------- 关键词管家 ----------

private fun keywordTests() {
    val butler = KeywordButler()
    val metas = listOf(
        ModuleMeta(
            "skill_novel", "小说写作",
            "小说写作。用户说\"帮我写个故事\"\"写一段描写\"时使用。",
            prefix = "小说"
        ),
        ModuleMeta("local_calc", "本地计算", "本地计算。用户说\"算一下\"\"等于多少\"时使用。")
    )

    eq("keyword: 描述里的说法命中", listOf("skill_novel"), butler.route("帮我写个故事", metas).modules)
    eq("keyword: 前缀也能命中", listOf("skill_novel"), butler.route("小说：写一段雨夜", metas).modules)
    eq("keyword: 未命中返回空", emptyList<String>(), butler.route("今天天气不错", metas).modules)
    eq("keyword: 空查询返回空", emptyList<String>(), butler.route("  ", metas).modules)
    check(
        "keyword: 关键词包含前缀与引号片段",
        butler.keywordsOf(metas[0]).contains("小说") &&
            butler.keywordsOf(metas[0]).contains("帮我写个故事")
    )
    eq("keyword: similarity 没有模型时为 0", 0f, butler.similarity("a", "b"))
}

// ---------- 无管家 ----------

private fun noButlerTests() {
    val butler = NoButler()
    val metas = listOf(ModuleMeta("a", "a", "描述"))
    val result = butler.route("随便", metas)
    eq("none: 永远返回空", emptyList<String>(), result.modules)
    eq("none: reason=no_butler", "no_butler", result.reason)
    eq("none: similarity 为 0", 0f, butler.similarity("a", "b"))
}

// ---------- 管理器：切换与 fail-open ----------

private fun managerTests() {
    val embedder = MapEmbedder(
        mapOf(
            "q" to floatArrayOf(1f, 0f),
            "帮我写个故事" to floatArrayOf(1f, 0f),
            "小说写作" to floatArrayOf(1f, 0f),
        )
    )
    val manager = ButlerManager(
        mapOf(
            SemanticButler.ID to { SemanticButler(embedder) },
            KeywordButler.ID to { KeywordButler() },
            NoButler.ID to { NoButler() },
            "boom" to { BoomButler() },
        )
    )

    val novel = ModulePackage(
        manifest = ModuleManifest(
            id = "skill_novel", name = "小说写作",
            description = "小说写作。用户说\"帮我写个故事\"时使用。",
            prefix = "小说"
        ),
        body = "", scripts = emptyMap(), references = mapOf("SKILL.md" to "规范")
    )
    val modules = listOf(novel)

    eq("manager: semantic 命中", listOf("skill_novel"), manager.route("semantic", "帮我写个故事", modules).modules)
    eq("manager: keyword 也命中", listOf("skill_novel"), manager.route("keyword", "帮我写个故事", modules).modules)
    eq("manager: none 返回空", emptyList<String>(), manager.route("none", "帮我写个故事", modules).modules)
    eq("manager: 未知 id 回落到默认", listOf("skill_novel"), manager.route("ghost", "帮我写个故事", modules).modules)

    // fail-open：管家抛异常 → 空结果 + reason=error → ModuleSelector 走兜底
    val broken = manager.route("boom", "帮我写个故事", modules)
    eq("manager: 异常时返回空", emptyList<String>(), broken.modules)
    eq("manager: 异常时 reason=error", "error", broken.reason)
    eq(
        "manager: 异常后兜底注入 hasSkill",
        setOf("skill_novel"),
        ModuleSelector.select(modules, broken)
    )

    // 切换后行为随之变化
    val asSemantic = manager.route("semantic", "帮我写个故事", modules)
    val asNone = manager.route("none", "帮我写个故事", modules)
    check("manager: 切换后结果不同", asSemantic.modules != asNone.modules)
}

private class BoomButler : Butler {
    override val id: String = "boom"
    override val name: String = "会抛异常"
    override fun route(query: String, modules: List<ModuleMeta>): RouteResult =
        throw IllegalStateException("boom")
    override fun classify(text: String): String? = null
    override fun normalize(text: String): String? = null
    override fun judge(output: String, spec: String) = null
    override fun similarity(a: String, b: String): Float = 0f
}
