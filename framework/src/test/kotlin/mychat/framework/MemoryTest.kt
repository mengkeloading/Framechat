package mychat.framework

import mychat.framework.memory.Recall
import mychat.framework.memory.RecallHit
import mychat.framework.memory.TagDecision
import mychat.framework.memory.TagRecord
import mychat.framework.memory.Tagger
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

/** 造一个与 [1,0] 余弦恰好等于 [c] 的向量。 */
private fun vec(c: Float): FloatArray {
    // [c, sqrt(1-c^2)] 与 [1,0] 的余弦就是 c
    val y = kotlin.math.sqrt((1f - c * c).coerceAtLeast(0f))
    return floatArrayOf(c, y)
}

private val origin = floatArrayOf(1f, 0f)

private fun tag(id: String, vector: FloatArray) =
    TagRecord(id = id, name = "标签" + id, vector = vector)

fun main() {
    taggingTests()
    recallTests()

    assemblyTests()

    println()
    println("PASS $passed / ${passed + failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  - $it") }
        exitProcess(1)
    }
}

private fun taggingTests() {
    // 1. 相似 0.6 → 归到已有标签
    val assign = Tagger.decide(origin, listOf(tag("a", vec(0.6f))), "随便说一句")
    check("tag: 0.6 归到已有", assign is TagDecision.Assign, assign.toString())
    eq("tag: 归到的 id", "a", (assign as? TagDecision.Assign)?.tagId)

    // 2. 相似 0.3 → 新建，名字取前 20 字
    val create = Tagger.decide(
        origin, listOf(tag("a", vec(0.3f))),
        "我在做 MyChat 这个 App，主要想解决模块化的问题"
    )
    check("tag: 0.3 新建", create is TagDecision.Create, create.toString())
    eq("tag: 新名字截 20 字", 20, (create as? TagDecision.Create)?.name?.length)

    // 3. 0.47 → 模糊，不贴
    val ambiguous = Tagger.decide(origin, listOf(tag("a", vec(0.47f))), "x")
    check("tag: 0.47 模糊", ambiguous is TagDecision.Skip, ambiguous.toString())
    eq("tag: 模糊原因", "ambiguous", (ambiguous as? TagDecision.Skip)?.reason)

    // 4. 没有标签 → 新建
    check("tag: 无标签时新建", Tagger.decide(origin, emptyList(), "第一句") is TagDecision.Create)

    // 5. 到上限 → 不新建
    val full = (0 until Tagger.MAX_TAGS).map { tag("t$it", vec(0.1f)) }
    val limited = Tagger.decide(origin, full, "新的东西")
    check("tag: 200 条后不新建", limited is TagDecision.LimitReached, limited.toString())

    // 6. 阈值可调：0.7 时 0.6 不再归到已有
    val strict = Tagger.decide(origin, listOf(tag("a", vec(0.6f))), "x", assignMin = 0.7f)
    check("tag: 阈值 0.7 时 0.6 不命中", strict !is TagDecision.Assign, strict.toString())
}

/**
 * 组装：把「检索 → 回答 → 打标」串成一轮，模拟连续对话。
 *
 * 不碰 Room：这里验的是决策链，DAO 存取是另一层的事。
 */
private class FakeMemory {
    val tags = mutableListOf<TagRecord>()
    val answers = mutableMapOf<String, MutableList<String>>()
    private var seq = 0

    /** 返回这一轮检索到的回忆（没有就是 null）。 */
    fun turn(vector: FloatArray, text: String, answer: String): RecallHit? {
        val hit = Recall.select(vector, tags, { null }, { answers[it].orEmpty() })
        val decision = Tagger.decide(vector, tags, text)
        val tagId = when (decision) {
            is TagDecision.Assign -> decision.tagId
            is TagDecision.Create -> {
                val id = "t" + (seq++)
                tags += TagRecord(id, decision.name, null, vector)
                id
            }
            else -> null
        }
        if (tagId != null) answers.getOrPut(tagId) { mutableListOf() }.add(0, answer)
        return hit
    }
}

private fun assemblyTests() {
    // A. 两轮相似 → 同一个标签
    val a = FakeMemory()
    val firstVec = floatArrayOf(1f, 0f)
    a.turn(firstVec, "我在做 MyChat 这个 App", "第一轮回答")
    val secondVec = vec(0.8f) // 与第一轮相似度 0.8
    a.turn(secondVec, "MyChat 的模块系统怎么设计", "第二轮回答")
    eq("asm A: 相似话题只建一个标签", 1, a.tags.size)

    // B. 两轮不相关 → 两个标签
    val b = FakeMemory()
    b.turn(floatArrayOf(1f, 0f), "我在做 MyChat 这个 App", "回答一")
    b.turn(floatArrayOf(0f, 1f), "今天天气怎么样", "回答二")
    eq("asm B: 不相关话题建两个标签", 2, b.tags.size)

    // C. 第三轮回到第一轮话题 → 回忆里是第一轮内容
    val c = FakeMemory()
    c.turn(floatArrayOf(1f, 0f), "我在做 MyChat 这个 App", "第一轮回答")
    c.turn(floatArrayOf(0f, 1f), "今天天气怎么样", "第二轮回答")
    val recall = c.turn(vec(0.8f), "MyChat 的模块系统怎么设计", "第三轮回答")
    check("asm C: 第三轮命中原话题", recall != null, "recall=null")
    check("asm C: 回忆含第一轮内容", recall?.text?.contains("第一轮回答") == true, recall?.text)
    check("asm C: 回忆不含第二轮内容", recall?.text?.contains("第二轮回答") == false, recall?.text)
}

private fun recallTests() {
    val tags = listOf(tag("a", vec(0.6f)))

    // 7. 有摘要 → 用摘要
    val withSummary = Recall.select(origin, tags, { "这是摘要" }, { emptyList() })
    check("recall: 有摘要用摘要", withSummary?.text == "这是摘要", withSummary.toString())

    // 8. 无摘要 → 最近 2 条原文，各截 200
    val raw = listOf("第一段".repeat(100), "第二段", "第三段")
    val noSummary = Recall.select(origin, tags, { null }, { raw })
    check("recall: 取最近两条", noSummary?.text?.contains("第二段") == true, noSummary?.text)
    check("recall: 不取第三条", noSummary?.text?.contains("第三段") == false, noSummary?.text)

    // 9. 0.4 不注入
    val miss = Recall.select(origin, listOf(tag("b", vec(0.4f))), { null }, { listOf("x") })
    eq("recall: 0.4 不注入", null, miss)

    // 10. 无标签不注入
    eq("recall: 无标签", null, Recall.select(origin, emptyList(), { null }, { emptyList() }))

    // 11. 注入块格式
    val block = Recall.formatBlock(RecallHit("a", "MyChat", 0.6f, "上次聊的内容"))
    check("recall: 含【回忆】", block.startsWith("【回忆】"), block)
    check("recall: 含标签名", block.contains("关于「MyChat」"), block)
    check("recall: 含正文", block.contains("上次聊的内容"), block)
    check("recall: 摘要优先于原文", withSummary?.text == "这是摘要")
}
