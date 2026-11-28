package mychat.framework

import mychat.framework.butler.RouteResult
import mychat.framework.embedding.Candidate
import mychat.framework.embedding.ModuleDocuments
import mychat.framework.embedding.ModuleRouter
import mychat.framework.embedding.ModuleSelector
import mychat.framework.embedding.TextEmbedder
import mychat.framework.embedding.Vectors
import mychat.framework.embedding.WordPieceTokenizer
import mychat.framework.hook.ModuleManifest
import mychat.framework.hook.ModulePackage
import mychat.framework.hook.ModuleRegistry
import java.io.File
import kotlin.math.abs
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

fun main() {
    vectorTests()
    tokenizerTests()
    routingTests()
    routingDecisionTests()
    selectorTests()
    moduleDocumentTests()

    println()
    println("PASS $passed / ${passed + failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  - $it") }
        exitProcess(1)
    }
}

// ---------- 向量工具 ----------

private fun vectorTests() {
    val a = floatArrayOf(1f, 0f, 0f)
    eq("vector: 相同向量相似度为 1", 1f, Vectors.cosine(a, a))
    eq("vector: 垂直向量为 0", 0f, Vectors.cosine(a, floatArrayOf(0f, 1f, 0f)))
    check("vector: 长度不一致返回 0", Vectors.cosine(a, floatArrayOf(1f)) == 0f)
    check("vector: 零向量返回 0", Vectors.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f)) == 0f)

    val normalized = Vectors.normalize(floatArrayOf(3f, 4f))
    val len = Math.sqrt((normalized[0] * normalized[0] + normalized[1] * normalized[1]).toDouble())
    check("vector: L2 归一化后长度为 1", abs(len - 1.0) < 1e-6, "len=$len")
}

// ---------- 分词（用 assets 里的真 vocab） ----------

private fun loadVocab(): Map<String, Int> {
    val repoRoot = System.getProperty("mychat.repoRoot")
        ?: throw IllegalStateException("需要 -Dmychat.repoRoot")
    val file = File(repoRoot, "android/app/src/main/assets/models/vocab.txt")
    check("vocab: 文件存在", file.isFile, file.path)
    val vocab = HashMap<String, Int>(40000)
    file.bufferedReader().useLines { lines ->
        lines.forEachIndexed { index, word -> vocab[word.trim()] = index }
    }
    return vocab
}

private fun tokenizerTests() {
    val vocab = loadVocab()
    eq("vocab: 词表大小", 21128, vocab.size)

    val tokenizer = WordPieceTokenizer(vocab)
    val ids = tokenizer.encode("帮我写个故事")
    eq("token: 6 个汉字 + CLS/SEP", 8, ids.size)
    eq("token: 首尾是 CLS/SEP", listOf(101L, 102L), listOf(ids.first(), ids.last()))
    check("token: 汉字没有退化成 UNK", ids.none { it == 100L }, ids.joinToString(","))

    val mixed = tokenizer.encode("算一下 12*37")
    check("token: 数字标点也能切", mixed.size > 3, mixed.joinToString(","))
    check("token: 混合输入没有 UNK", mixed.none { it == 100L })

    val long = "协程".repeat(400)
    eq("token: 超长截断到 512", 512, tokenizer.encode(long).size)

    val unknown = tokenizer.encode("😀")
    val body = unknown.drop(1).dropLast(1)
    check("token: 生僻字符退化为 UNK", body.contains(100L), body.joinToString(","))

    eq(
        "token: 基本切分（汉字与标点各自成词）",
        listOf("你", "好", "，", "world"),
        tokenizer.basicTokens("你好，world")
    )
    check("token: 拉丁词按词表贪心切分", tokenizer.wordPieces("coroutine").isNotEmpty())
}

// ---------- selected 集合（路由 → 兜底） ----------

private fun selectorTests() {
    fun pkgOf2(id: String, hasSkill: Boolean) = ModulePackage(
        manifest = ModuleManifest(id = id, name = id, description = "描述"),
        body = "",
        scripts = emptyMap(),
        references = if (hasSkill) mapOf("SKILL.md" to "规范") else emptyMap()
    )

    val withSkill = pkgOf2("sk", true)
    val noSkill = pkgOf2("plain", false)
    val modules = listOf(withSkill, noSkill)

    eq(
        "selector: 无模块返回空",
        emptySet<String>(),
        ModuleSelector.select(emptyList(), RouteResult(emptyList(), "empty"))
    )
    eq(
        "selector: 路由命中只选命中项",
        setOf("sk"),
        ModuleSelector.select(modules, RouteResult(listOf("sk"), "hit"))
    )
    // 路由没命中 → 不注入任何模块规范（兜底会误伤：0.404 的输入不该套「审查」规范）
    eq(
        "selector: 路由为空时不注入",
        emptySet<String>(),
        ModuleSelector.select(modules, RouteResult(emptyList(), "below_min"))
    )
    eq(
        "selector: 路由失败也不注入",
        emptySet<String>(),
        ModuleSelector.select(modules, RouteResult(emptyList(), "error"))
    )
}

// ---------- 模块向量化文本 ----------

private fun pkgOf(name: String, description: String, body: String) = ModulePackage(
    manifest = ModuleManifest(id = name, name = name, description = description),
    body = body,
    scripts = emptyMap(),
    references = emptyMap()
)

private fun moduleDocumentTests() {
    // MODULE.md 正文里的「何时使用」
    val withSection = pkgOf(
        "周报排版", "把清单排成周报",
        "## 何时使用\n用户说写周报时\n\n## 流程\n读 style 文件"
    )
    eq(
        "doc: name/description/何时使用",
        "周报排版\n把清单排成周报\n用户说写周报时",
        ModuleDocuments.textOf(withSection)
    )
    check("doc: 不含后续小节", !ModuleDocuments.textOf(withSection).contains("读 style"))
    eq(
        "doc: 节提取截止到下一个标题",
        "用户说写周报时",
        ModuleDocuments.sectionOf(withSection.body, ModuleDocuments.SECTION_HEADING)
    )

    // SKILL.md 优先于正文
    val withSkill = ModulePackage(
        manifest = ModuleManifest(id = "s", name = "技能", description = "描述"),
        body = "## 何时使用\n正文里的",
        scripts = emptyMap(),
        references = mapOf("SKILL.md" to "## 何时使用\n技能里的\n\n## 流程\nx")
    )
    eq("doc: SKILL.md 优先", "技能里的", ModuleDocuments.whenSection(withSkill))

    // 都没有 → 只有两行
    val bare = pkgOf("无节", "描述", "## 流程\n只有流程")
    eq("doc: 无节时只剩两行", "无节\n描述", ModuleDocuments.textOf(bare))
    eq("doc: 无节时不多空行", 1, ModuleDocuments.textOf(bare).count { it == '\n' })

    // 真模块：skill_novel 的「何时使用」写在 reference/SKILL.md 里
    val repoRoot = System.getProperty("mychat.repoRoot")
    if (repoRoot != null) {
        val registry = ModuleRegistry.scan(
            File(repoRoot, "android/app/src/main/assets/modules")
        ).registry
        val novel = registry.getPackage("skill_novel")
        check("doc: 真模块 skill_novel 能扫到", novel != null)
        if (novel != null) {
            val text = ModuleDocuments.textOf(novel)
            check("doc: 真模块含 name", text.contains(novel.manifest.name))
            check("doc: 真模块含 description", text.contains(novel.manifest.description))
            check("doc: 真模块取到 SKILL.md 的何时使用", text.contains("写小说片段"))
        }
    }
}

// ---------- 路由逻辑（不依赖真模型） ----------

private class FakeEmbedder(private val table: Map<String, FloatArray>) : TextEmbedder {
    override fun embed(text: String): FloatArray? = table[text]
}

private fun routingTests() {
    // 手写的三维「语义空间」：第一维=写作，第二维=算数，第三维=代码
    val embedder = FakeEmbedder(
        mapOf(
            "帮我写个故事" to floatArrayOf(1f, 0f, 0f),
            "小说写作" to floatArrayOf(0.9f, 0.1f, 0f),
            "算一下 12*37" to floatArrayOf(0f, 1f, 0f),
            "代码审查" to floatArrayOf(0f, 0.1f, 0.9f),
            "这段代码有问题吗" to floatArrayOf(0f, 0f, 1f),
        )
    )
    val candidates = listOf("小说写作", "算一下 12*37", "代码审查").mapNotNull { name ->
        embedder.embed(name)?.let { name to it }
    }

    val story = embedder.embed("帮我写个故事")!!
    eq("routing: 故事 → 小说写作", "小说写作", Vectors.top1(story, candidates))
    val code = embedder.embed("这段代码有问题吗")!!
    eq("routing: 代码问题 → 代码审查", "代码审查", Vectors.top1(code, candidates))

    // 阈值：都不够像就该返回 null，而不是硬选一个
    val unrelated = floatArrayOf(0.4f, 0.4f, 0.4f)
    eq("routing: 低于阈值不选", null, Vectors.top1(unrelated, candidates, threshold = 0.9f))
    check("routing: 阈值内仍可选", Vectors.top1(unrelated, candidates, threshold = 0.5f) != null)

    eq("routing: 空候选返回 null", null, Vectors.top1(story, emptyList()))
}

// ---------- 相对排序判定 ----------

private fun routingDecisionTests() {
    val a = Candidate("A", floatArrayOf(1f, 0f))
    val b = Candidate("B", floatArrayOf(0.60f, 0.80f))   // cos 0.60
    val c = Candidate("C", floatArrayOf(0f, 1f))          // cos 0
    val all = listOf(a, b, c)

    // 1. 明颞胜出且第二名差距 > 0.15 → 只收一个
    val clear = ModuleRouter.decide(floatArrayOf(1f, 0f), all)
    eq("router: 明颞胜出只选一个", listOf("A"), clear.modules)
    eq("router: 原因是 hit", "hit", clear.reason)

    // 2. 第二名落在 0.15 窗口内 → 两个都要（协作）
    val near = listOf(
        Candidate("calc", floatArrayOf(1f, 0f)),
        Candidate("guard", floatArrayOf(0.92f, 0.3919f)),   // cos ≈ 0.92
    )
    val pair = ModuleRouter.decide(floatArrayOf(1f, 0f), near)
    eq("router: 窗口内收两个", listOf("calc", "guard"), pair.modules)
    check("router: 顺序按分数降序", pair.top1!!.score >= pair.top2!!.score)

    // 3. 全低 → 空，交给全量注入兜底
    val far = listOf(a, Candidate("E", floatArrayOf(0.2f, -0.9798f)))
    val none = ModuleRouter.decide(floatArrayOf(0f, 1f), far)
    eq("router: 全低返回空", emptyList<String>(), none.modules)
    eq("router: 原因是 below_min", "below_min", none.reason)

    // 4. 只有一个候选且够像 → 一个
    val single = ModuleRouter.decide(floatArrayOf(1f, 0f), listOf(a))
    eq("router: 单候选返回一个", listOf("A"), single.modules)
    eq("router: 单候选 margin 视为无限", 1f, single.margin)

    // 5. 空候选
    val empty = ModuleRouter.decide(floatArrayOf(1f, 0f), emptyList())
    eq("router: 空候选返回空", emptyList<String>(), empty.modules)
    eq("router: 空候选原因是 empty", "empty", empty.reason)

    // 6. 三个都在窗口内 → 最多两个
    val triple = listOf(
        Candidate("t1", floatArrayOf(1f, 0f)),
        Candidate("t2", floatArrayOf(0.95f, 0.3122f)),
        Candidate("t3", floatArrayOf(0.90f, 0.4359f)),
    )
    eq("router: 最多两个", 2, ModuleRouter.decide(floatArrayOf(1f, 0f), triple).modules.size)

    // 7. 窗口外的不收：0.80 与 top1 差 0.20
    val outside = listOf(
        Candidate("near", floatArrayOf(1f, 0f)),
        Candidate("far", floatArrayOf(0.80f, 0.60f)),
    )
    eq("router: 窗口外不收", listOf("near"), ModuleRouter.decide(floatArrayOf(1f, 0f), outside).modules)

    // 8. 排序降序
    val ranked = ModuleRouter.rank(floatArrayOf(1f, 0f), all)
    eq("router: 排序降序", listOf("A", "B", "C"), ranked.map { it.name })

    // 9. skill_hub 不参与路由（它自己就是兜底路径）
    eq("router: skill_hub 不可路由", false, ModuleRouter.isRoutable("skill_hub"))
    eq("router: debug_ctx 不可路由", false, ModuleRouter.isRoutable("debug_ctx"))
    eq("router: 普通模块可路由", true, ModuleRouter.isRoutable("local_calc"))
    val withHub = listOf(
        Candidate("skill_hub", floatArrayOf(1f, 0f)),
        Candidate("real", floatArrayOf(0.9f, 0.4359f)),
    )
    val hubDecision = ModuleRouter.decide(floatArrayOf(1f, 0f), withHub)
    eq("router: 候选里带 skill_hub 也会被过滤", listOf("real"), hubDecision.modules)

    // 10. 常量是对外契约
    eq("router: MIN_SCORE", 0.45f, ModuleRouter.MIN_SCORE)
    eq("router: MIN_MARGIN", 0.05f, ModuleRouter.MIN_MARGIN)
    eq("router: SECOND_WINDOW", 0.15f, ModuleRouter.SECOND_WINDOW)
    eq("router: MAX_MODULES", 2, ModuleRouter.MAX_MODULES)
}
