package mychat.framework

import mychat.framework.butler.ButlerManager
import mychat.framework.butler.ModuleMeta
import mychat.framework.butler.SemanticButler
import mychat.framework.embedding.Candidate
import mychat.framework.embedding.ModuleDocuments
import mychat.framework.embedding.ModuleRouter
import mychat.framework.embedding.ModuleSelector
import mychat.framework.embedding.TextEmbedder
import mychat.framework.hook.AuditLog
import mychat.framework.hook.GatedHostBridge
import mychat.framework.hook.HookAResult
import mychat.framework.hook.HookSession
import mychat.framework.hook.HostBridge
import mychat.framework.hook.JsExecutor
import mychat.framework.hook.ModuleDispatcher
import mychat.framework.hook.ModulePackage
import mychat.framework.hook.ModuleRegistry
import java.io.File
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

/** 假向量：查询与文档分开，并数调用次数——这是 F 那条的关键。 */
private class ScriptedEmbedder(
    private val queryVec: Map<String, FloatArray>,
    private val passageVec: Map<String, FloatArray> = queryVec,
) : TextEmbedder {
    var queryCalls = 0
    var passageCalls = 0

    override fun embed(text: String): FloatArray? = embedQuery(text)

    override fun embedQuery(t: String): FloatArray {
        queryCalls++
        return queryVec[t] ?: ZERO
    }

    override fun embedPassage(t: String): FloatArray {
        passageCalls++
        return passageVec[t] ?: ZERO
    }

    companion object {
        val ZERO = floatArrayOf(0f, 0f, 0f)
    }
}

fun main() {
    val repoRoot = System.getProperty("mychat.repoRoot")
        ?: run {
            println("SKIP  需要 -Dmychat.repoRoot")
            return
        }
    val registry = ModuleRegistry.scan(
        File(repoRoot, "android/app/src/main/assets/modules")
    ).registry
    check("assets: 模块可扫描", registry.all().isNotEmpty())
    // 配对审计会报"输出模块缺 Guide"（真实缺口），只要求没有硬错误
    val auditProblems = ModuleRegistry.scan(
        File(repoRoot, "android/app/src/main/assets/modules")
    ).problems
    eq(
        "assets: 没有重复前缀",
        0,
        auditProblems.count { it.startsWith("dup_prefix") }
    )
    eq(
        "assets: 没有 marks 缺结构",
        0,
        auditProblems.count { it.startsWith("no_structure") }
    )

    hitCase(registry)
    fallbackCase(registry)
    emptyCase(registry)
    metaCases(registry)
    metaConsistencyCases(registry)
    whenToUseCases(registry)
    sampleQueryCase()
    cacheKeyCase(registry)
    embedderMethodCase(registry)
    parityCase(registry)

    println()
    println("PASS $passed / ${passed + failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  - $it") }
        exitProcess(1)
    }
}

// ---------- 公共工具 ----------

private fun textOf(pkg: ModulePackage): String = ModuleDocuments.textOf(pkg)

private fun embedderFor(registry: ModuleRegistry, query: String, queryVector: FloatArray, hitId: String): ScriptedEmbedder {
    val passage = LinkedHashMap<String, FloatArray>()
    for (pkg in registry.all()) {
        val vector = if (pkg.id == hitId) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 1f, 0f)
        passage[textOf(pkg)] = vector
        // 有 sample_queries 的模块按示例建向量
        for (sample in pkg.manifest.sampleQueries) passage[sample] = vector
    }
    return ScriptedEmbedder(mapOf(query to queryVector), passage)
}

private class NullExecutor : JsExecutor {
    override fun execute(scriptRef: String, inputJson: String, host: HostBridge): String? = null
}

private fun dispatcherOf(registry: mychat.framework.hook.ModuleSource): ModuleDispatcher =
    ModuleDispatcher(registry, NullExecutor(), GatedHostBridge.factory(registry, AuditLog(), { null }))

// ---------- A. 命中 → 有注入块 ----------

private fun hitCase(registry: ModuleRegistry) {
    val embedder = embedderFor(registry, "帮我写个故事", floatArrayOf(0.98f, 0.2f, 0f), "skill_novel")
    val butler = SemanticButler(embedder)
    val modules = registry.all()
    val metas = modules.map { ButlerManager(mapOf()).toMeta(it) }

    val route = butler.route("帮我写个故事", metas)
    val selected = ModuleSelector.select(modules, route)
    check("A: 命中含 skill_novel", selected.contains("skill_novel"), "selected=$selected route=${route.modules} ${route.reason}")

    val dispatcher = dispatcherOf(registry)
    val result = dispatcher.dispatchInput(
        HookSession(id = "s", activeHardModules = selected.toList()),
        "帮我写个故事",
        selected = selected
    )
    check("A: 有注入块", result is HookAResult.Context, "实际 ${result::class.simpleName}")
    val extra = (result as? HookAResult.Context)?.extra.orEmpty()
    check("A: 块 id 是 skill_novel", extra.contains("id: skill_novel"), extra.take(160))
    check("A: 注入的是 SKILL.md 全文", extra.length > 100, "len=${extra.length}")
    check("A: 带技能结束标记", extra.contains("【技能结束】"))
    check("A: 带使用声明段", extra.contains("【使用声明】"))
}

// ---------- B. 未命中 → 兜底 ----------

private fun fallbackCase(registry: ModuleRegistry) {
    // 查询向量与所有模块向量正交
    val embedder = embedderFor(registry, "无关的问题", floatArrayOf(0f, 0f, 1f), "___none___")
    val butler = SemanticButler(embedder)
    val modules = registry.all()
    val metas = modules.map { ButlerManager(mapOf()).toMeta(it) }

    val route = butler.route("无关的问题", metas)
    eq("B: 路由为空", emptyList<String>(), route.modules)
    eq("B: reason=below_min", "below_min", route.reason)

    val selected = ModuleSelector.select(modules, route)
    val expected = modules.filter { ModuleSelector.hasSkill(it) }.map { it.id }.toSet()
    eq("B: 兜底到所有 hasSkill", expected, selected)
    check("B: 兜底非空", selected.isNotEmpty(), "expected=$expected")

    val result = dispatcherOf(registry).dispatchInput(
        HookSession(id = "s", activeHardModules = selected.toList()),
        "无关的问题",
        selected = selected
    )
    check("B: 兜底也能注入", result is HookAResult.Context, "实际 ${result::class.simpleName}")
    check("B: 注入多个块", (result as? HookAResult.Context)?.extra.orEmpty()
        .let { Regex("【已加载技能】").findAll(it).count() } >= 1)
}

// ---------- C. 无启用 → 全空 ----------

private fun emptyCase(registry: ModuleRegistry) {
    val modules = emptyList<ModulePackage>()
    val selected = ModuleSelector.select(modules, mychat.framework.butler.RouteResult(emptyList(), "empty"))
    eq("C: 无模块时 selected 为空", emptySet<String>(), selected)

    val manager = ButlerManager(mapOf("semantic" to { SemanticButler(ScriptedEmbedder(emptyMap())) }))
    eq("C: 空模块 reason=empty", "empty", manager.route("semantic", "q", emptyList()).reason)

    val result = dispatcherOf(registry).dispatchInput(
        HookSession(id = "s", activeHardModules = emptyList()), "q", selected = emptySet()
    )
    check("C: 没有块", result is HookAResult.Pass, "实际 ${result::class.simpleName}")
    eq("C: 正文原样", "q", result.text)
}

// ---------- D/E. 元数据 ----------

private fun metaCases(registry: ModuleRegistry) {
    val manager = ButlerManager(mapOf())
    val withSkill = registry.all().filter { ModuleSelector.hasSkill(it) }
    check("D: 有 hasSkill 的模块", withSkill.isNotEmpty(), "count=${withSkill.size}")

    val emptyText = withSkill.filter { manager.toMeta(it).embeddingText.isEmpty() }
    eq("D: 每个 hasSkill 模块的 embeddingText 非空", emptyList<String>(), emptyText.map { it.id })

    val novel = registry.getPackage("skill_novel")
    check("E: skill_novel 存在", novel != null)
    if (novel != null) {
        val text = manager.toMeta(novel).embeddingText
        check(
            "E: embeddingText 含用户说法",
            text.contains("小说") || text.contains("故事") || text.contains("写"),
            text.take(120)
        )
    }
}

// ---------- D'. toMeta 与 ModuleDocuments 完全一致 ----------

private fun metaConsistencyCases(registry: ModuleRegistry) {
    val manager = ButlerManager(mapOf())
    val mismatched = registry.all()
        .filter { ModuleSelector.hasSkill(it) }
        .filter { ModuleDocuments.textOf(it) != manager.toMeta(it).embeddingText }
    eq("D': toMeta 与 textOf 完全一致", emptyList<String>(), mismatched.map { it.id })
}

// ---------- H'. embeddingText 真的含「何时使用」内容 ----------

private fun whenToUseCases(registry: ModuleRegistry) {
    val manager = ButlerManager(mapOf())
    val expected = mapOf(
        "skill_novel" to "写小说片段",
        "skill_code" to "贴出代码",
        "skill_product" to "我想做一个"
    )
    for ((id, phrase) in expected) {
        val pkg = registry.getPackage(id)
        check("H: $id 存在", pkg != null)
        if (pkg == null) continue
        val text = manager.toMeta(pkg).embeddingText
        // 现行设计：name + description + 「何时使用」节，量级 ~100 字。
        // 若将来改成整篇 SKILL.md，这个下限应上调到 200+。
        check("H: $id 文本长度 >= 60", text.length >= 60, "len=${text.length}")
        check("H: $id 含何时使用特征词「$phrase」", text.contains(phrase), text.take(120))
    }
}

// ---------- J. 示例查询参与路由 ----------

private class SampleEmbedder(
    private val passages: Map<String, FloatArray>,
    private val queryVectors: Map<String, FloatArray> = emptyMap(),
) : mychat.framework.embedding.TextEmbedder {
    var passageCalls = 0
    override fun embed(text: String): FloatArray? = embedPassage(text)
    override fun embedQuery(text: String): FloatArray =
        queryVectors[text] ?: passages[text] ?: floatArrayOf(0f, 0f, 1f)

    override fun embedPassage(text: String): FloatArray? {
        passageCalls++
        return passages[text]
    }
}

private fun sampleQueryCase() {
    val q = "帮我看看这段代码"
    val embedder = SampleEmbedder(
        passages = mapOf(
            "示例A" to floatArrayOf(1f, 0f, 0f),
            "描述文本" to floatArrayOf(0f, 1f, 0f),
        ),
        queryVectors = mapOf(q to floatArrayOf(1f, 0f, 0f)),
    )
    val butler = SemanticButler(embedder)

    // 1. 有 sample_queries → 用示例算分（接近 1.0，远高于描述那条）
    val withSamples = ModuleMeta(
        id = "m1", name = "代码审查", description = "描述文本",
        embeddingText = "描述文本", sampleQueries = listOf("示例A"),
    )
    val scored = butler.route(q, listOf(withSamples))
    check("J: 示例参与打分", (scored.scores["m1"] ?: 0f) > 0.9f, scored.scores.toString())

    // 2. 没有 → 回退 description 的向量
    val without = withSamples.copy(sampleQueries = emptyList())
    val fallback = butler.route(q, listOf(without))
    check("J: 无示例回退描述", (fallback.scores["m1"] ?: 0f) < 0.3f, fallback.scores.toString())

    // 3. 路由阈值逻辑不变：分数相同时两个都收（SECOND_WINDOW 内）
    val twin = withSamples.copy(id = "m2", sampleQueries = listOf("示例A"))
    val tie = butler.route(q, listOf(withSamples, twin))
    check(
        "J: 分数接近都收",
        tie.reason == "hit" && tie.modules.size == 2,
        tie.reason + " " + tie.modules
    )

    // 4. 示例分数低时仍然 below_min：示例不绕过阈值
    val far = ModuleMeta(
        id = "m3", name = "无关模块", description = "x",
        embeddingText = "x", sampleQueries = listOf("示例B"),
    )
    val lowEmbedder = SampleEmbedder(
        passages = mapOf("示例B" to floatArrayOf(0f, 1f, 0f)),
        queryVectors = mapOf(q to floatArrayOf(1f, 0f, 0f)),
    )
    val low = SemanticButler(lowEmbedder).route(q, listOf(far))
    eq("J: 分数低仍 below_min", "below_min", low.reason)
}

// ---------- I. 缓存 key 带文本 hash ----------

private fun cacheKeyCase(registry: ModuleRegistry) {
    val embedder = embedderFor(registry, "帮我写个故事", floatArrayOf(0.98f, 0.2f, 0f), "skill_novel")
    val butler = SemanticButler(embedder)
    val metas = registry.all().map { ButlerManager(mapOf()).toMeta(it) }

    butler.route("帮我写个故事", metas)
    val first = embedder.passageCalls
    check("I: 首次会算所有模块", first >= metas.size, "passageCalls=$first modules=${metas.size}")

    butler.route("帮我写个故事", metas)
    eq("I: 同文本重用缓存", first, embedder.passageCalls)

    // skill_novel 有 sample_queries，向量按示例建；加一条示例应当只重算这一条
    val changed = metas.map { meta ->
        if (meta.id == "skill_novel") {
            meta.copy(sampleQueries = meta.sampleQueries + "再来一段追加")
        } else {
            meta
        }
    }
    butler.route("帮我写个故事", changed)
    eq("I: 示例变了只重算那一条", first + 1, embedder.passageCalls)
}

// ---------- F. 查询必须走 embedQuery ----------

private fun embedderMethodCase(registry: ModuleRegistry) {
    val embedder = embedderFor(registry, "帮我写个故事", floatArrayOf(0.98f, 0.2f, 0f), "skill_novel")
    val butler = SemanticButler(embedder)
    val metas = registry.all().map { ButlerManager(mapOf()).toMeta(it) }
    butler.route("帮我写个故事", metas)

    check("F: 查询走 embedQuery", embedder.queryCalls > 0, "queryCalls=${embedder.queryCalls}")
    check(
        "F: 模块走 embedPassage",
        embedder.passageCalls >= metas.size,
        "passageCalls=${embedder.passageCalls} modules=${metas.size}"
    )
}

// ---------- G. 与 ModuleRouter 分数一致 ----------

private fun parityCase(registry: ModuleRegistry) {
    val embedder = embedderFor(registry, "帮我写个故事", floatArrayOf(0.98f, 0.2f, 0f), "skill_novel")
    val metas = registry.all().map { ButlerManager(mapOf()).toMeta(it) }
    val butler = SemanticButler(embedder)
    val route = butler.route("帮我写个故事", metas)

    // 直接用同样的向量算一遍
    val q = embedder.embedQuery("帮我写个故事")
    val candidates = metas.mapNotNull { m ->
        embedder.embedPassage(m.embeddingText)?.let { Candidate(m.id, it) }
    }
    val direct = ModuleRouter.decide(q, candidates)
    eq("G: 与 ModuleRouter 命中一致", direct.modules, route.modules)

    val ranked = ModuleRouter.rank(q, candidates).associate { it.name to it.score }
    val same = ranked.all { (id, score) -> route.scores[id]?.let { kotlin.math.abs(it - score) < 1e-6f } == true }
    check("G: 分数表一致", same, "butler=${route.scores} direct=$ranked")
}
