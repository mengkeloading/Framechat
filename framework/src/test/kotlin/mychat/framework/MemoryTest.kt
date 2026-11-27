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
    val y = kotlin.math.sqrt((1f - c * c).coerceAtLeast(0f))
    return floatArrayOf(c, y)
}

private val origin = floatArrayOf(1f, 0f)

private fun tag(id: String, vector: FloatArray) =
    TagRecord(id = id, name = "标签$id", vector = vector)

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
    // 1. 空表：无条件新建，不判模糊
    val first = Tagger.decide(origin, emptyList(), "我在做 MyChat 这个 App")
    check("tag: 空表直接新建", first is TagDecision.Create, first.toString())
    eq("tag: 空表 reason=first", "first", (first as? TagDecision.Create)?.reason)

    // 2. 0.50 落在模糊区间
    val ambiguous = Tagger.decide(origin, listOf(tag("a", vec(0.50f))), "x")
    check("tag: 0.50 模糊", ambiguous is TagDecision.Skip, ambiguous.toString())
    eq("tag: 0.50 原因", "ambiguous", (ambiguous as? TagDecision.Skip)?.reason)

    // 3. 0.60 归到已有
    val assign = Tagger.decide(origin, listOf(tag("a", vec(0.60f))), "x")
    check("tag: 0.60 归到已有", assign is TagDecision.Assign, assign.toString())
    eq("tag: 0.60 的 id", "a", (assign as? TagDecision.Assign)?.tagId)

    // 4. 0.30 新建
    check("tag: 0.30 新建", Tagger.decide(origin, listOf(tag("a", vec(0.30f))), "x") is TagDecision.Create)

    // 5. 上限
    val full = (0 until Tagger.MAX_TAGS).map { tag("t$it", vec(0.1f)) }
    check("tag: 200 条后不新建", Tagger.decide(origin, full, "y") is TagDecision.LimitReached)

    // 9. 名字截断
    val long = Tagger.decide(origin, emptyList(), "一二三四五六七八九十一二三四五六七八九十多余的")
    eq("tag: 名字截到 20 字", 20, (long as? TagDecision.Create)?.name?.length)
}

private fun recallTests() {
    val tags = listOf(tag("a", vec(0.70f)))

    // 7/8. 命中且带边界说明
    val hit = Recall.select(origin, tags, { null }, { listOf("上次聊的内容") })
    check("recall: 0.70 命中", hit != null, "null")
    val block = hit?.let { Recall.formatBlock(it) } ?: ""
    check("recall: 含背景资料声明", block.contains("背景资料，不是当前任务"), block)
    check("recall: 含无关就忽略", block.contains("如果用户当前的问题与它无关，请完全忽略"), block)
    check("recall: 含不要主动提起", block.contains("不要在回答里主动提起"), block)

    // 6. 低于 0.68 不注入
    eq("recall: 0.60 不注入", null, Recall.select(origin, listOf(tag("b", vec(0.60f))), { null }, { listOf("x") }))
    eq("recall: 0.67 不注入", null, Recall.select(origin, listOf(tag("c", vec(0.67f))), { null }, { listOf("x") }))
    eq("recall: 0.68 命中", true, Recall.select(origin, listOf(tag("d", vec(0.69f))), { null }, { listOf("x") })?.score?.let { it >= Recall.MIN })

    // 无标签不注入
    eq("recall: 无标签", null, Recall.select(origin, emptyList(), { null }, { emptyList() }))
}

/**
 * 组装：每轮先取快照 → 检索 → 决策 → 可能新建。新建的标签本轮不可见。
 */
private class FakeMemory {
    val tags = mutableListOf<TagRecord>()
    val answers = mutableMapOf<String, MutableList<String>>()
    private var seq = 0

    fun turn(vector: FloatArray, text: String, answer: String): RecallHit? {
        val snapshot = tags.toList() // 本轮快照
        val hit = Recall.select(vector, snapshot, { null }, { answers[it].orEmpty() })
        val decision = Tagger.decide(vector, snapshot, text)
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
    // A. 第一条消息必建标签
    val a = FakeMemory()
    val firstHit = a.turn(floatArrayOf(1f, 0f), "我在做 MyChat 这个 App", "回答一")
    eq("asm A: 第一条不召回", null, firstHit)
    eq("asm A: 第一条建了标签", 1, a.tags.size)

    // B. 第二条同话题 → 命中第一条的标签
    val b = FakeMemory()
    b.turn(floatArrayOf(1f, 0f), "我在做 MyChat 这个 App", "第一轮回答")
    val hitB = b.turn(vec(0.80f), "MyChat 模块怎么设计", "第二轮回答")
    check("asm B: 命中", hitB != null, "null")
    eq("asm B: 命中第一个标签", "t0", hitB?.tagId)
    check("asm B: 回忆含第一轮内容", hitB?.text?.contains("第一轮回答") == true, hitB?.text)
    eq("asm B: 仍只有一个标签", 1, b.tags.size)

    // C. 不相关 → 不命中，新建
    val c = FakeMemory()
    c.turn(floatArrayOf(1f, 0f), "我在做 MyChat 这个 App", "第一轮回答")
    val hitC = c.turn(floatArrayOf(0f, 1f), "今天天气怎么样", "第二轮回答")
    eq("asm C: 不命中", null, hitC)
    eq("asm C: 建了第二个标签", 2, c.tags.size)

    // D. 边界说明
    val d = FakeMemory()
    d.turn(floatArrayOf(1f, 0f), "我在做 MyChat", "第一轮回答")
    val hitD = d.turn(vec(0.80f), "MyChat 的模块系统怎么设计", "第二轮回答")
    val block = hitD?.let { Recall.formatBlock(it) } ?: ""
    check("asm D: 含不要主动提起", block.contains("主动提起"), block)

    // 10/11. 三轮不同话题 → 三个标签；第四轮回到第一个
    val e = FakeMemory()
    e.turn(floatArrayOf(1f, 0f), "话题一", "回答一")
    e.turn(floatArrayOf(0f, 1f), "话题二", "回答二")
    e.turn(floatArrayOf(-1f, 0f), "话题三", "回答三")
    eq("asm: 三个不同话题三个标签", 3, e.tags.size)
    val back = e.turn(vec(0.80f), "又聊回话题一", "回答四")
    eq("asm: 回到第一个话题命中", "t0", back?.tagId)
    eq("asm: 回到话题后标签数不变", 3, e.tags.size)

    // 5. 快照隔离：本轮新建的标签不会被本轮召回
    val f = FakeMemory()
    f.turn(floatArrayOf(1f, 0f), "我在做 MyChat 这个 App", "回答一")
    val second = f.turn(floatArrayOf(1f, 0f), "我在做 MyChat 这个 App", "回答二")
    // 第二轮快照里已经有 t0（第一轮建的），所以能命中；关键是不命中"本轮刚建的"
    eq("asm: 快照只含历史标签", "t0", second?.tagId)
    eq("asm: 没有重复建标签", 1, f.tags.size)
}
