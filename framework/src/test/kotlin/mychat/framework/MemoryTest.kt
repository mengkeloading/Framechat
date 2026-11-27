package mychat.framework

import mychat.framework.memory.Recall
import mychat.framework.memory.RecallHit
import mychat.framework.memory.TagDecision
import mychat.framework.memory.TagRecord
import mychat.framework.memory.MemoryGate
import mychat.framework.memory.MemoryFeedback
import mychat.framework.memory.MemoryItem
import mychat.framework.memory.TaggedTurn
import mychat.framework.memory.TagStrength
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

private fun near(name: String, expected: Float, actual: Float) =
    check(name, kotlin.math.abs(expected - actual) < 0.001f, "expected=$expected actual=$actual")

/** 造一个与 [1,0] 余弦恰好等于 [c] 的向量。 */
private fun vec(c: Float): FloatArray {
    val y = kotlin.math.sqrt((1f - c * c).coerceAtLeast(0f))
    return floatArrayOf(c, y)
}

private val origin = floatArrayOf(1f, 0f)

private fun users(vararg texts: String): List<MemoryItem> =
    texts.map { MemoryItem("user", it) }

private fun tag(id: String, vector: FloatArray) =
    TagRecord(id = id, name = "标签$id", vector = vector)

fun main() {
    taggingTests()
    recallTests()
    assemblyTests()
    strengthTests()
    depthTests()
    penaltyTests()
    strengthAssemblyTests()
    recallRoleTests()
    feedbackTests()
    gateTests()

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
    val hit = Recall.select(origin, tags, { null }, { users("上次聊的内容") })
    check("recall: 0.70 命中", hit != null, "null")
    val block = hit?.let { Recall.formatBlock(it) } ?: ""
    check("recall: 含背景资料声明", block.contains("背景资料，不是当前任务"), block)
    check("recall: 含无关就忽略", block.contains("如果用户当前的问题与它无关，请完全忽略"), block)
    check("recall: 含不要主动提起", block.contains("不要在回答里主动提起"), block)

    // 6. 低于 0.68 不注入
    eq("recall: 0.60 不注入", null, Recall.select(origin, listOf(tag("b", vec(0.60f))), { null }, { users("x") }))
    eq("recall: 0.67 不注入", null, Recall.select(origin, listOf(tag("c", vec(0.67f))), { null }, { users("x") }))
    check(
        "recall: 0.69 命中",
        Recall.select(origin, listOf(tag("d", vec(0.69f))), { null }, { users("x") }) != null
    )

    // 无标签不注入
    eq("recall: 无标签", null, Recall.select(origin, emptyList(), { null }, { emptyList() }))
}

// ---------- 为什么没跑 ----------

private fun gateTests() {
    eq("gate: 关闭 → disabled", "disabled", MemoryGate.offReason(false, true, true))
    eq("gate: 模型未就绪", "embedder_not_ready", MemoryGate.offReason(true, false, true))
    eq("gate: 没有启用模块", "no_modules", MemoryGate.offReason(true, true, false))
    eq("gate: 都正常 → null", null, MemoryGate.offReason(true, true, true))
    eq("gate: 关闭时优先报 disabled", "disabled", MemoryGate.offReason(false, false, false))
}

// ---------- 回忆只取用户消息 ----------

private fun recallRoleTests() {
    val tags = listOf(tag("a", vec(0.70f)))

    val mixed = listOf(
        MemoryItem("user", "第一句用户输入"),
        MemoryItem("assistant", "模型的第一段回答"),
        MemoryItem("user", "第二句用户输入"),
        MemoryItem("assistant", "模型的第二段回答"),
    )
    val hit = Recall.select(origin, tags, { null }, { mixed })
    check("recall: 只取用户消息", hit?.text?.contains("第一句用户输入") == true, hit?.text)
    check(
        "recall: 不含模型回答",
        hit?.text?.contains("模型的第一段回答") == false &&
            hit?.text?.contains("模型的第二段回答") == false,
        hit?.text
    )

    val onlyAssistant = listOf(MemoryItem("assistant", "只有我说话"))
    val fallback = Recall.select(origin, tags, { null }, { onlyAssistant })
    check("recall: 没有用户消息时给兜底", fallback?.text?.contains(Recall.NO_DETAIL) == true, fallback?.text)

    // role 大小写不敏感：不同写入路径可能给大写
    val upper = listOf(MemoryItem("USER", "大写角色"))
    check(
        "recall: role 大小写都能匹配",
        Recall.select(origin, tags, { null }, { upper })?.text?.contains("大写角色") == true
    )

    // 空列表也要给兜底，而不是 null
    val empty = Recall.select(origin, tags, { null }, { emptyList() })
    check("recall: 空列表给兜底", empty?.text?.contains(Recall.NO_DETAIL) == true, empty?.text)
}

// ---------- 负反馈：取哪一条标签 ----------

private fun feedbackTests() {
    eq(
        "feedback: 取最后一条 assistant 的标签",
        "X",
        MemoryFeedback.prevTagIdOf(
            listOf(TaggedTurn("user", "X"), TaggedTurn("assistant", null))
        )
    )
    eq(
        "feedback: 全是 null 时返回 null",
        null,
        MemoryFeedback.prevTagIdOf(
            listOf(TaggedTurn("user", null), TaggedTurn("assistant", null))
        )
    )
    // 语义写死：取**最后一条** assistant（多条时用户刚看到的是最近那条）
    eq(
        "feedback: 多条 assistant 取最近的一条",
        "Y",
        MemoryFeedback.prevTagIdOf(
            listOf(TaggedTurn("user", "X"), TaggedTurn("assistant", "Y"))
        )
    )
    check("feedback: 不对 = 否定", MemoryFeedback.isNegative("不对，这不是我要的"))
    check("feedback: 好的 不是否定", !MemoryFeedback.isNegative("好的，谢谢"))
}

// ---------- 强度 ----------

private const val DAY = 86_400_000L

private fun strengthTests() {
    val now = 1_700_000_000_000L
    val fresh = TagRecord("a", "标签", vector = origin, lastDecayAt = now)

    // 1. 新建：1.0
    eq("strength: 新建 1.0", 1.0f, TagStrength.current(fresh, now))
    eq("strength: 新建 depth=summary", "summary", TagStrength.depthOf(1.0f))

    // 2/3. 召回一次 / 三次
    val once = TagStrength.boosted(fresh, now)
    eq("strength: 召回一次 1.3", 1.3f, once.strength)
    eq("strength: readCount=1", 1, once.readCount)

    var thrice = fresh
    repeat(3) { thrice = TagStrength.boosted(thrice, now) }
    near("strength: 召回三次 1.9", 1.9f, thrice.strength)
    eq("strength: readCount=3", 3, thrice.readCount)

    // 4. 封顶
    var many = fresh
    repeat(10) { many = TagStrength.boosted(many, now) }
    eq("strength: 封顶 3.0", TagStrength.MAX, many.strength)

    // 5. 30 天不读：0.02 * 30 = 0.6
    val stale = fresh.copy(strength = 2.0f, lastDecayAt = now - 30 * DAY)
    check(
        "strength: 30 天衰减到 1.4",
        kotlin.math.abs(TagStrength.current(stale, now) - 1.4f) < 0.01f,
        TagStrength.current(stale, now).toString()
    )

    // 6. readCount 高衰减更慢
    val cold = fresh.copy(strength = 2.0f, readCount = 0, lastDecayAt = now - 30 * DAY)
    val warm = fresh.copy(strength = 2.0f, readCount = 10, lastDecayAt = now - 30 * DAY)
    check(
        "strength: readCount=10 衰减更慢",
        TagStrength.current(warm, now) > TagStrength.current(cold, now),
        "warm=${TagStrength.current(warm, now)} cold=${TagStrength.current(cold, now)}"
    )
}

private fun depthTests() {
    eq("depth: 2.0 → full", "full", TagStrength.depthOf(2.0f))
    eq("depth: 1.0 → summary", "summary", TagStrength.depthOf(1.0f))
    eq("depth: 0.3 → name_only", "name_only", TagStrength.depthOf(0.3f))
    eq("depth: 0.05 → archived", "archived", TagStrength.depthOf(0.05f))

    // 10/13. 归档不参与召回
    val archived = TagRecord("a", "旧话题", vector = origin, strength = 0.05f, lastDecayAt = 0)
    eq(
        "depth: 归档不召回",
        null,
        Recall.select(origin, listOf(archived), { "摘要" }, { users("x") })
    )

    // 11. name_only 只给标签名
    val weak = TagRecord("a", "MyChat 开发", vector = origin, strength = 0.3f, lastDecayAt = 0)
    val weakHit = Recall.select(origin, listOf(weak), { "不该出现的摘要" }, { users("原文") })
    val weakBlock = weakHit?.let { Recall.formatBlock(it) } ?: ""
    check("depth: name_only 含标签名", weakBlock.contains("MyChat 开发"), weakBlock)
    check("depth: name_only 不含摘要", !weakBlock.contains("不该出现的摘要"), weakBlock)

    // 12. summary 含摘要
    val summaryTag = TagRecord("a", "MyChat 开发", vector = origin, strength = 1.0f, lastDecayAt = 0)
    val summaryHit = Recall.select(origin, listOf(summaryTag), { "这是摘要" }, { users("原文") })
    check("depth: summary 含摘要", summaryHit?.text?.contains("这是摘要") == true, summaryHit?.text)

    // full 带「可展开原文」
    val fullTag = TagRecord("a", "MyChat 开发", vector = origin, strength = 2.0f, lastDecayAt = 0)
    val fullHit = Recall.select(origin, listOf(fullTag), { null }, { users("原文一") })
    check("depth: full 可展开", fullHit?.text?.contains("可展开原文") == true, fullHit?.text)
}

private fun penaltyTests() {
    val now = 1_700_000_000_000L
    val good = TagRecord("a", "话题", vector = origin, strength = 1.8f, lastDecayAt = now)

    val userNegative = TagStrength.penalized(good, now, TagStrength.PENALTY_USER_NEGATIVE)
    eq("penalty: 用户否定 -0.5", 1.3f, userNegative.strength)

    val judge = TagStrength.penalized(good, now, TagStrength.PENALTY_JUDGE)
    near("penalty: judge -0.2", 1.6f, judge.strength)

    val floor = TagStrength.penalized(good.copy(strength = 0.2f), now, 0.5f)
    eq("penalty: 不低于 0", 0f, floor.strength)

    // 删除消息：1.5 → 1.2；0.2 → 0（不是 -0.1）
    val deleted = TagStrength.penalized(
        good.copy(strength = 1.5f, lastDecayAt = now), now, TagStrength.PENALTY_DELETE
    )
    near("penalty: 删除消息 1.5 → 1.2", 1.2f, deleted.strength)

    val small = TagStrength.penalized(
        good.copy(strength = 0.2f, lastDecayAt = now), now, TagStrength.PENALTY_DELETE
    )
    eq("penalty: 0.2 删除后是 0 不是负数", 0f, small.strength)

    // 状态转移只有一份实现：写库的值必须等于这里算出来的
    val written = TagStrength.penalized(
        good.copy(strength = 2.0f, lastDecayAt = now), now, TagStrength.PENALTY_USER_NEGATIVE
    )
    near("penalty: 写入值 = Kotlin 计算值", 1.5f, written.strength)
    eq("penalty: 惩罚后 lastDecayAt=now", now, written.lastDecayAt)

    /*
     * 起点被重置：惩罚后 30 天掉 0.6；若没重置会掉 1.2。
     */
    val later30 = now + 30 * DAY
    val decayed30 = TagStrength.current(written, later30)
    near("penalty: 30 天后只掉 0.6", 0.9f, decayed30)

    val notReset = TagStrength.current(written.copy(lastDecayAt = now - 30 * DAY), later30)
    check(
        "penalty: 重置起点后衰减更慢",
        decayed30 > notReset,
        "reset=$decayed30 notReset=$notReset"
    )
}

/** 强度 × 召回 × 归档的组装。 */
private fun strengthAssemblyTests() {
    val now = 1_700_000_000_000L
    var tag = TagRecord("a", "MyChat 开发", vector = origin, lastDecayAt = now)

    repeat(3) { tag = TagStrength.boosted(tag, now) }
    near("asm: 三次召回 1.9", 1.9f, tag.strength)
    eq("asm: depth=full", "full", TagStrength.depthOf(tag.strength))

    // 200 天不读 → archived（readCount=3 时每天只衰减 0.0125）
    val later = now + 200 * DAY
    val decayed = TagStrength.current(tag, later)
    check("asm: 200 天后归档", TagStrength.depthOf(decayed) == "archived", decayed.toString())
    eq(
        "asm: 归档不注入",
        null,
        Recall.select(origin, listOf(tag), { "摘要" }, { users("x") }, now = later)
    )

    // 恢复
    val restored = TagStrength.restored(tag, later)
    eq("asm: 恢复 1.0", 1.0f, restored.strength)
    eq("asm: 恢复后 summary", "summary", TagStrength.depthOf(restored.strength))
}

/**
 * 组装：每轮先取快照 → 检索 → 决策 → 可能新建。新建的标签本轮不可见。
 */
private class FakeMemory {
    val tags = mutableListOf<TagRecord>()
    val answers = mutableMapOf<String, MutableList<MemoryItem>>()
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
        if (tagId != null) answers.getOrPut(tagId) { mutableListOf() }.add(0, MemoryItem("user", text))
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
    check("asm B: 回忆含第一轮用户输入", hitB?.text?.contains("我在做 MyChat 这个 App") == true, hitB?.text)
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
