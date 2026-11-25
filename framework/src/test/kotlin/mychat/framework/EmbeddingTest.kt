package mychat.framework

import mychat.framework.embedding.TextEmbedder
import mychat.framework.embedding.Vectors
import mychat.framework.embedding.WordPieceTokenizer
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
