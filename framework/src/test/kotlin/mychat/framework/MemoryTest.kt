package mychat.framework

import mychat.framework.memory.LayeredRecall
import mychat.framework.memory.Recall
import mychat.framework.memory.RecentItem
import mychat.framework.memory.RecentMemory
import mychat.framework.memory.RelatedNames
import mychat.framework.memory.RecallHit
import mychat.framework.memory.TagDecision
import mychat.framework.memory.TagRecord
import mychat.framework.memory.MemoryGate
import mychat.framework.memory.MemoryFeedback
import mychat.framework.memory.Mentions
import mychat.framework.memory.TagCentroid
import mychat.framework.embedding.Vectors
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
    layeredRecallTests()
    mentionsTests()
    centroidTests()
    recentTests()
    relatedFilterTests()
    assemblyTests()
    strengthTests()
    depthTests()
    penaltyTests()
    strengthAssemblyTests()
    recallRoleTests()
    recallAssemblyTests()
    feedbackTests()
    tagSyncTests()
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
    // 短句否定：是纠正，不建标签
    val neg = Tagger.decide(origin, listOf(tag("a", vec(0.10f))), "不对，我说的不是这个")
    check("tag: 否定句 Skip", neg is TagDecision.Skip, neg.toString())
    eq("tag: 否定句原因", "negative_statement", (neg as? TagDecision.Skip)?.reason)
    check(
        "tag: 普通短句照常建",
        Tagger.decide(origin, listOf(tag("a", vec(0.10f))), "帮我改一下") is TagDecision.Create
    )
    check(
        "tag: 长句里的否定照常建",
        Tagger.decide(
            origin, listOf(tag("a", vec(0.10f))),
            "这个不对，但我说的是另一件事，请重新看一遍"
        ) is TagDecision.Create
    )

    // 模糊（0.40~0.55）不再丢弃：建新标签，reason 标出来方便排查
    val ambiguous = Tagger.decide(origin, listOf(tag("a", vec(0.50f))), "帮我写个故事")
    check("tag: 0.50 建新标签", ambiguous is TagDecision.Create, ambiguous.toString())
    eq("tag: 0.50 原因", "ambiguous_new", (ambiguous as? TagDecision.Create)?.reason)
    near("tag: 0.50 分数", 0.50f, (ambiguous as TagDecision.Create).score)

    // 3. 0.68 归到已有（阈值 0.65）
    val assign = Tagger.decide(origin, listOf(tag("a", vec(0.68f))), "帮我写个函数")
    check("tag: 0.68 归到已有", assign is TagDecision.Assign, assign.toString())
    eq("tag: 0.68 的 id", "a", (assign as? TagDecision.Assign)?.tagId)

    // 3b. 0.60 在阈值下，不再归入
    check(
        "tag: 0.60 不归入",
        Tagger.decide(origin, listOf(tag("a", vec(0.60f))), "帮我写个函数") !is TagDecision.Assign
    )

    // 4. 0.30 新建
    check(
        "tag: 0.30 新建",
        Tagger.decide(origin, listOf(tag("a", vec(0.30f))), "帮我写个函数") is TagDecision.Create
    )

    // 5. 上限
    val full = (0 until Tagger.MAX_TAGS).map { tag("t$it", vec(0.1f)) }
    check(
        "tag: 200 条后不新建",
        Tagger.decide(origin, full, "帮我写个函数") is TagDecision.LimitReached
    )

    // 6. 寒暄与超短句不建标签
    fun noInfo(text: String) = Tagger.decide(origin, listOf(tag("a", vec(0.10f))), text)
    eq("tag: 你好 → no_info", "no_info", (noInfo("你好") as? TagDecision.Skip)?.reason)
    eq("tag: 谢谢 → no_info", "no_info", (noInfo("谢谢") as? TagDecision.Skip)?.reason)
    eq("tag: 两字输入 → no_info", "no_info", (noInfo("嗯嗯") as? TagDecision.Skip)?.reason)

    // 9. 名字截断
    val long = Tagger.decide(origin, emptyList(), "一二三四五六七八九十一二三四五六七八九十多余的")
    eq("tag: 名字截到 20 字", 20, (long as? TagDecision.Create)?.name?.length)
}

// ---------- mentions 与质心 ----------

private fun mentionsTests() {
    // 1. 有 MENTIONS 行 → 拆出来，正文不含该行
    val parsed = Mentions.extract("决定用 Kotlin 重写。\nMENTIONS: 旧后台, 部署脚本")
    eq("mentions: 解析", listOf("旧后台", "部署脚本"), parsed.mentions)
    eq("mentions: 正文去掉该行", "决定用 Kotlin 重写。", parsed.summary)

    // 2. 没有这一行 → 空
    eq("mentions: 没有则为空", emptyList<String>(), Mentions.extract("没有提到别的话题。").mentions)

    // 3. 超过 5 个只取前 5
    val many = Mentions.extract("摘要\nMENTIONS: A, B, C, D, E, F, G")
    eq("mentions: 最多五个", 5, many.mentions.size)
    eq("mentions: 取前面那些", listOf("A", "B", "C", "D", "E"), many.mentions)

    // 4. 解析结果进 TagRecord → mention 层能命中
    val tag = TagRecord(
        id = "t", name = "老项目", vector = floatArrayOf(0f, 1f),
        strength = 1f, lastDecayAt = NOW,
        mentions = Mentions.extract("摘要\nMENTIONS: 旧后台").mentions,
    )
    val result = LayeredRecall.recall(
        text = "那个旧后台还在吗",
        query = floatArrayOf(1f, 0f),
        tags = listOf(tag),
        now = NOW,
    )
    eq("mentions: 存进标签后能命中", LayeredRecall.MENTION, result.reason)

    eq("mentions: 存库用逗号", "A,B", Mentions.join(listOf("A", "B")))
    eq("mentions: 读取时拆开", listOf("A", "B"), Mentions.split("A,B"))
    eq("mentions: 空串读出空表", emptyList<String>(), Mentions.split(""))
}

private fun recentTests() {
    val at1 = 1_700_000_000_000L
    val at2 = at1 + 60_000

    // 1. 归入 1 条 → 长度 1
    eq("recent: 一条", 1, RecentMemory.append(emptyList(), RecentItem("m1", "第一条", at1)).size)

    // 2. 归入 3 条 → 只留最近 2 条
    var list = emptyList<RecentItem>()
    list = RecentMemory.append(list, RecentItem("m1", "第一条", at1))
    list = RecentMemory.append(list, RecentItem("m2", "第二条", at1 + 1000))
    list = RecentMemory.append(list, RecentItem("m3", "第三条", at2))
    eq("recent: 只留两条", 2, list.size)
    eq("recent: 留的是最新的", listOf("m2", "m3"), list.map { it.id })

    // 3. 同会话召回 → 不含「最近：」
    val sameSession = RecentMemory.format(
        summary = "旧摘要",
        recent = list,
        lastSeenAt = at2,
        sessionStartedAt = at1 - 10_000,
        now = at2,
    )
    check("recent: 同会话只给摘要", sameSession == "旧摘要", sameSession)

    // 4. 跨会话召回 → 含「最近：」+ 两条原文
    val cross = RecentMemory.format(
        summary = "旧摘要",
        recent = list,
        lastSeenAt = at2,
        sessionStartedAt = at2 + 10_000,
        now = at2,
    )!!
    check("recent: 跨会话带最近", cross.contains("最近："), cross)
    check("recent: 含两条原文", cross.contains("第二条") && cross.contains("第三条"), cross)
    check("recent: 摘要仍在前面", cross.startsWith("旧摘要"), cross)

    // 5. 只有最近、没有摘要 → 不显示「暂无」
    val noSummary = RecentMemory.format(
        summary = null,
        recent = list,
        lastSeenAt = at2,
        sessionStartedAt = at2 + 10_000,
        now = at2,
    )!!
    check("recent: 无摘要不占位", !noSummary.contains("暂无"), noSummary)
    check("recent: 无摘要也有最近", noSummary.startsWith("最近："), noSummary)

    // 6. 时间戳：同一天给时分，跨天给日期
    val day = 86_400_000L
    check(
        "recent: 同日时间戳是时分",
        Regex("^\\d{2}:\\d{2}$").matches(RecentMemory.stamp(at2, at2 + 3_600_000L)),
        RecentMemory.stamp(at2, at2 + 3_600_000L)
    )
    check(
        "recent: 跨日时间戳带日期",
        Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}$")
            .matches(RecentMemory.stamp(at2, at2 + 3 * day)),
        RecentMemory.stamp(at2, at2 + 3 * day)
    )
}

private fun relatedFilterTests() {
    // ⑤ 相关内容：淡化的不列
    eq(
        "related: 淡化标签被排除",
        listOf("常用话题"),
        RelatedNames.filter(listOf("常用话题" to 1.2f, "淡化话题" to 0.1f))
    )
    eq("related: 门槛是 0.2", 0.2f, RelatedNames.MIN_STRENGTH)

    // ⑥ mentions：归档（0.05）仍可联想，彻底淡掉（0.03）才不提
    val archivedTag = TagRecord(
        id = "t-a", name = "归档话题", vector = floatArrayOf(0f, 1f),
        strength = 0.05f, lastDecayAt = NOW, mentions = listOf("旧后台"),
    )
    eq(
        "mentions: 0.05 仍可联想",
        LayeredRecall.MENTION,
        LayeredRecall.recall(
            text = "那个旧后台还在吗",
            query = floatArrayOf(1f, 0f),
            tags = listOf(archivedTag),
            now = NOW,
        ).reason
    )
    val gone = archivedTag.copy(id = "t-g", strength = 0.03f)
    eq(
        "mentions: 0.03 不再提",
        LayeredRecall.MISS,
        LayeredRecall.recall(
            text = "那个旧后台还在吗",
            query = floatArrayOf(1f, 0f),
            tags = listOf(gone),
            now = NOW,
        ).reason
    )

    // 模糊层仍守 0.22：0.1 的标签再像也不召回
    eq(
        "fuzzy: 0.1 强度不召回",
        LayeredRecall.MISS,
        LayeredRecall.recall(
            text = "完全不同的说法",
            query = floatArrayOf(1f, 0f),
            tags = listOf(
                TagRecord(
                    id = "t-w", name = "无关", vector = floatArrayOf(1f, 0f),
                    strength = 0.1f, lastDecayAt = NOW,
                )
            ),
            now = NOW,
        ).reason
    )
}

private fun centroidTests() {
    // 1. 新建标签：第一次归入保持首条消息的方向
    val afterOne = TagCentroid.updated(floatArrayOf(1f, 0f, 0f), 0, floatArrayOf(1f, 0f, 0f))
    check("centroid: 第一次归入有结果", afterOne != null)
    near("centroid: 与首条一致", 1f, afterOne!![0])

    // 2. 归入 5 条 → 方向应接近这 5 条的朴素平均
    //    每步都归一化，所以和朴素平均不完全相等，但方向必须几乎一样
    var vec = floatArrayOf(1f, 0f)
    var count = 0
    val samples = listOf(
        floatArrayOf(1f, 0f), floatArrayOf(1f, 0f),
        floatArrayOf(0f, 1f), floatArrayOf(0f, 1f),
        floatArrayOf(1f, 1f),
    )
    for (s in samples) {
        TagCentroid.updated(vec, count, s)?.let { vec = it }
        count++
    }
    val mean = floatArrayOf(
        samples.sumOf { it[0].toDouble() }.toFloat() / samples.size,
        samples.sumOf { it[1].toDouble() }.toFloat() / samples.size,
    )
    check(
        "centroid: 方向接近朴素平均",
        Vectors.cosine(vec, mean) > 0.99f,
        "cos=" + Vectors.cosine(vec, mean)
    )

    // 3. 第 11 条不再更新
    eq(
        "centroid: 满 10 条后不动",
        null,
        TagCentroid.updated(floatArrayOf(1f, 0f), TagCentroid.MAX_MESSAGES, floatArrayOf(0f, 1f))
    )

    // 4. 质心更新后，同话题新消息的召回分数提高
    val offTopic = floatArrayOf(1f, 0f)
    val newTopic = floatArrayOf(0.2f, 1f)
    var centroid = offTopic
    var n = 0
    repeat(5) {
        TagCentroid.updated(centroid, n, newTopic)?.let { centroid = it }
        n++
    }
    val before = Vectors.cosine(offTopic, newTopic)
    val after = Vectors.cosine(centroid, newTopic)
    check("centroid: 对新话题更近", after > before, "before=$before after=$after")
}

private const val NOW = 1_700_000_000_000L

private fun layeredRecallTests() {
    val app = TagRecord(
        id = "t-my", name = "我在做 MyChat 这个 App",
        vector = floatArrayOf(1f, 0f),
        strength = 1f, lastDecayAt = NOW,
    )

    // ① 1. 含标签名里的词 → 命中
    eq("exact: 命中标签", "t-my", LayeredRecall.exactMatch("MyChat 的注入是不是有问题", listOf(app))?.id)

    // ① 2. 归档也命中（强度 0.05）
    val archived = app.copy(id = "t-old", strength = 0.05f, name = "MyChat 早期版本")
    eq(
        "exact: 归档仍命中",
        "t-old",
        LayeredRecall.exactMatch("聊聊 MyChat 早期版本", listOf(archived))?.id
    )

    // ① 3. 不含任何名字 → 不命中
    eq("exact: 不相关不命中", null, LayeredRecall.exactMatch("今天天气怎么样", listOf(app)))

    // ① 4. 两个都命中 → 取名字最长的
    val short = app.copy(id = "t-short", name = "MyChat")
    eq(
        "exact: 取名字最长的",
        "t-my",
        LayeredRecall.exactMatch("MyChat 又在做 MyChat 这个 App 了", listOf(short, app))?.id
    )

    // ② 1. 精确命中 → reason=exact（即使模糊也能命中）
    run {
        val logs = mutableListOf<String>()
        val result = LayeredRecall.recall(
            text = "MyChat 有问题",
            query = floatArrayOf(1f, 0f),
            tags = listOf(app),
            log = { logs += it },
            now = NOW,
        )
        eq("recall: 精确优先", LayeredRecall.EXACT, result.reason)
        check("recall: 精确日志", logs.any { it.startsWith("recall:exact") }, logs.toString())
        check("recall: 精确命中带内容", result.first!!.text.isNotBlank())
    }

    // ② 2. 精确 miss + 模糊命中 → fuzzy
    run {
        val other = app.copy(id = "t-x", name = "旧话题甲")
        val result = LayeredRecall.recall(
            text = "完全不相关的说法",
            query = floatArrayOf(1f, 0f),
            tags = listOf(other),
            recentOf = { listOf(MemoryItem("user", "上次聊到的内容")) },
            now = NOW,
        )
        eq("recall: 模糊命中", LayeredRecall.FUZZY, result.reason)
    }

    // ② 3. 都 miss + mentions 命中 → 只给名字
    run {
        val obscure = TagRecord(
            id = "t-m", name = "老项目",
            vector = floatArrayOf(0f, 1f), strength = 1f, lastDecayAt = NOW,
            mentions = listOf("旧后台"),
        )
        val result = LayeredRecall.recall(
            text = "那个旧后台还在吗",
            query = floatArrayOf(1f, 0f),
            tags = listOf(obscure),
            now = NOW,
        )
        eq("recall: 提及命中", LayeredRecall.MENTION, result.reason)
        eq("recall: 只给名字", "老项目", result.first?.tagName)
        eq("recall: 提及没有正文", "", result.first?.text)
    }

    // ② 4. 全 miss
    run {
        val logs = mutableListOf<String>()
        val result = LayeredRecall.recall(
            text = "毫不相干",
            query = floatArrayOf(1f, 0f),
            tags = listOf(TagRecord("t-f", "别的", vector = floatArrayOf(0f, 1f), lastDecayAt = NOW)),
            log = { logs += it },
            now = NOW,
        )
        eq("recall: 全 miss", LayeredRecall.MISS, result.reason)
        check(
            "recall: miss 日志带 best",
            logs.any { it.startsWith("recall:miss best=") },
            logs.toString()
        )
    }

    // ③ 1. 归档 + 精确 → 召回
    run {
        val result = LayeredRecall.recall(
            text = "MyChat 早期版本",
            query = floatArrayOf(1f, 0f),
            tags = listOf(archived),
            now = NOW,
        )
        eq("archive: 精确仍召回", LayeredRecall.EXACT, result.reason)
    }

    // ③ 2. 归档 + 模糊 0.9 → 不召回
    run {
        val result = LayeredRecall.recall(
            text = "完全不同的说法",
            query = floatArrayOf(1f, 0f),
            tags = listOf(archived.copy(name = "无关名字")),
            now = NOW,
        )
        eq("archive: 模糊不召回", LayeredRecall.MISS, result.reason)
    }

    // ③ 3. 归档（强度 0.05）仍能被 mentions 联想起来
    run {
        val result = LayeredRecall.recall(
            text = "那个旧话题乙还在吗",
            query = floatArrayOf(1f, 0f),
            tags = listOf(
                archived.copy(
                    id = "t-z", name = "无关名字", vector = floatArrayOf(0f, 1f),
                    mentions = listOf("旧话题乙"),
                )
            ),
            now = NOW,
        )
        eq("archive: mentions 仍可联想", LayeredRecall.MENTION, result.reason)
    }
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

    // 摘要优先：标签自带的 summary 非空就用它，不再回放原话
    val withSummary = listOf(
        TagRecord(
            "s", "有摘要", vector = origin, strength = 1.0f, lastDecayAt = 0,
            summary = "决定用 Kotlin，待办是写测试"
        )
    )
    val sumHit = Recall.select(origin, withSummary, { null }, { users("这段话不该出现") })
    check("recall: 有摘要用摘要", sumHit?.text?.contains("决定用 Kotlin") == true, sumHit?.text)
    check("recall: 有摘要就不给原话", sumHit?.text?.contains("这段话不该出现") == false, sumHit?.text)
    eq("recall: summary 字段带出来", "决定用 Kotlin，待办是写测试", sumHit?.summary)

    // 没摘要 → 原话，且 fallbackText 里能拿到
    val noSum = Recall.select(origin, tags, { null }, { users("上次说的原话") })
    check("recall: 无摘要用原话", noSum?.text?.contains("上次说的原话") == true, noSum?.text)
    check(
        "recall: fallbackText 有原话",
        noSum?.fallbackText?.contains("上次说的原话") == true,
        noSum?.fallbackText
    )
    eq("recall: 无摘要时 summary 为空", null, noSum?.summary)

    // 两者都空 → 兜底文案
    val none = Recall.select(origin, tags, { null }, { emptyList() })
    eq("recall: 都空给兜底", Recall.NO_DETAIL, none?.text)

    // 相关内容：最多 5 个；没有关联就不加
    val relatedBlock = Recall.formatBlock(sumHit!!, listOf("标签B", "标签C", "标签D"))
    check("link: 末尾含相关内容", relatedBlock.contains("相关内容：标签B / 标签C / 标签D"), relatedBlock)
    check("link: 说明怎么看", relatedBlock.contains("（要看哪个，说它的名字）"), relatedBlock)
    check(
        "link: 相关内容在块末尾",
        relatedBlock.trimEnd().endsWith("（要看哪个，说它的名字）"),
        relatedBlock.takeLast(40)
    )
    eq("link: 没有关联就不加", null, Recall.formatRelated(emptyList()))
    check(
        "link: 只有一个标签不注入",
        !Recall.formatBlock(sumHit, emptyList()).contains("相关内容：")
    )
    eq(
        "link: 最多五个",
        5,
        Recall.formatRelated(listOf("A", "B", "C", "D", "E", "F", "G"))!!
            .lines().first { it.startsWith("相关内容：") }
            .removePrefix("相关内容：").split(" / ").size
    )

    // 摘要过长 → 截到 500
    val longTag = listOf(
        TagRecord(
            "l", "长摘要", vector = origin, strength = 1.0f, lastDecayAt = 0,
            summary = "长".repeat(600)
        )
    )
    val longHit = Recall.select(origin, longTag, { null }, { users("原话") })
    eq("recall: 摘要截到 500", 500, longHit?.summary?.length)
}

// ---------- 组装：两轮对话后召回 ----------

private class StoredMsg(val seq: Int, val tagId: String, val role: String, val text: String)

private class TagStore {
    private val all = mutableListOf<StoredMsg>()
    private var seq = 0

    fun add(tagId: String, role: String, text: String) {
        all += StoredMsg(seq++, tagId, role, text)
    }

    /** 模拟 DAO：该标签下最近的消息（倒序）。 */
    fun recent(tagId: String): List<MemoryItem> =
        all.filter { it.tagId == tagId }
            .sortedByDescending { it.seq }
            .map { MemoryItem(it.role, it.text) }
}

private fun recallAssemblyTests() {
    val tagId = "t1"
    val store = TagStore()
    // 两轮对话：user 与 assistant 都打上同一个标签（模拟修复后的行为）
    store.add(tagId, "user", "我在做 MyChat 这个 App")
    store.add(tagId, "assistant", "第一轮回答：MyChat 是一个模块化聊天应用")
    store.add(tagId, "user", "MyChat 的模块系统怎么设计")
    store.add(tagId, "assistant", "第二轮回答：模块系统用 HookResult 分层")

    val tags = listOf(TagRecord(tagId, "MyChat 开发", vector = origin, strength = 1.0f, lastDecayAt = 0))
    val hit = Recall.select(origin, tags, { null }, { store.recent(it) })
    val block = hit?.let { Recall.formatBlock(it) } ?: ""

    check("asm: 含第 1 轮用户原话", block.contains("我在做 MyChat 这个 App"), block)
    check("asm: 含第 2 轮用户原话", block.contains("MyChat 的模块系统怎么设计"), block)
    check(
        "asm: 不含任何 assistant 回答",
        !block.contains("第一轮回答") && !block.contains("第二轮回答"),
        block
    )
    check("asm: 不是空回忆", !block.contains(Recall.NO_DETAIL), block)

    // 边界 5：只有 assistant（老数据）→ 兜底
    val onlyAssistant = TagStore()
    onlyAssistant.add(tagId, "assistant", "只有这段回答")
    val fallbackHit = Recall.select(origin, tags, { null }, { onlyAssistant.recent(it) })
    check(
        "asm: 只有 assistant 时给兜底",
        fallbackHit?.text?.contains(Recall.NO_DETAIL) == true,
        fallbackHit?.text
    )

    // 边界 6：user 超过 2 条 → 只取最近 2 条
    val many = TagStore()
    many.add(tagId, "user", "最早的一句")
    many.add(tagId, "user", "中间的一句")
    many.add(tagId, "user", "最近的一句")
    val manyHit = Recall.select(origin, tags, { null }, { many.recent(it) })
    check("asm: 只取最近两条", manyHit?.text?.contains("最近的一句") == true, manyHit?.text)
    check("asm: 不含最早的", manyHit?.text?.contains("最早的一句") == false, manyHit?.text)
}

// ---------- 负反馈：取哪一条标签 ----------

private fun feedbackTests() {
    eq(
        "feedback: 没有 assistant 标签时返回 null",
        null,
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
    check("feedback: 不对 = 否定", MemoryFeedback.isNegative("不对，这不是我要的"))
    check("feedback: 好的 不是否定", !MemoryFeedback.isNegative("好的，谢谢"))

    // 本轮的空回答已在列表里（tagId=null）：必须越过它，取上一轮的
    val withPlaceholder = listOf(
        TaggedTurn("user", "T1", "u1"),
        TaggedTurn("assistant", "T1", "a1"),
        TaggedTurn("user", null, "u2"),
        TaggedTurn("assistant", null, "a2"),
    )
    eq(
        "feedback: 越过本轮空回答取上一轮",
        "T1",
        MemoryFeedback.prevTagIdOf(withPlaceholder, currentUserId = "u2")
    )
    eq(
        "feedback: 同场景负反馈扣的是上一轮",
        "T1",
        MemoryFeedback.penaltyTargetOf("不对，我说的不是这个", withPlaceholder, "u2")
    )

    // 前面已经有两轮，各带不同标签 → 取最近的一条
    val twoTagged = listOf(
        TaggedTurn("user", "T1", "u1"),
        TaggedTurn("assistant", "T1", "a1"),
        TaggedTurn("user", null, "u2"),
        TaggedTurn("assistant", "T2", "a2"),
        TaggedTurn("user", null, "u3"),
    )
    eq(
        "feedback: 取最近一条带标签的",
        "T2",
        MemoryFeedback.prevTagIdOf(twoTagged, currentUserId = "u3")
    )

    // 前面没有 assistant → 不扣
    eq(
        "feedback: 第一条消息不扣",
        null,
        MemoryFeedback.prevTagIdOf(
            listOf(TaggedTurn("user", null, "u1"), TaggedTurn("assistant", null, "a1")),
            currentUserId = "u1"
        )
    )

    // 连续两次否定：第二次仍指向同一个标签（空回答不带标签，不会把目标往前挪）
    val secondRound = listOf(
        TaggedTurn("user", "T1", "u1"),
        TaggedTurn("assistant", "T1", "a1"),
        TaggedTurn("user", null, "u2"),
        TaggedTurn("assistant", null, "a2"),
        TaggedTurn("user", null, "u3"),
        TaggedTurn("assistant", null, "a3"),
    )
    eq(
        "feedback: 连续否定仍指向同一标签",
        "T1",
        MemoryFeedback.penaltyTargetOf("不对", secondRound, "u3")
    )
}

// ---------- 打标后同步内存 ----------

/**
 * 打标返回的 id 必须能进入"内存消息列表"，否则下一轮 prevTagIdOf 永远是 null。
 * 这里模拟 ViewModel 的 patch：把 tagId 写到消息上，再问"上一轮是谁"。
 */
private fun tagSyncTests() {
    var prev = TaggedTurn("user", null)
    var reply = TaggedTurn("assistant", null)
    eq("sync: 打标前取不到", null, MemoryFeedback.prevTagIdOf(listOf(prev, reply)))

    // tag() 返回 id 后分别写回两条消息
    val tagId = "3b222009"
    prev = prev.copy(tagId = tagId)
    reply = reply.copy(tagId = tagId)

    eq("sync: 打标后回复带 tagId", tagId, reply.tagId)
    eq("sync: 打标后用户消息带 tagId", tagId, prev.tagId)
    eq("sync: 下一轮取得到上一条的 tagId", tagId, MemoryFeedback.prevTagIdOf(listOf(prev, reply)))

    eq(
        "sync: 否定时扣的就是这个标签",
        tagId,
        MemoryFeedback.penaltyTargetOf("不对", listOf(prev, reply))
    )
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

    // 5. 30 天不读：指数衰减，τ = 25 + 40*2.0 = 105 → 2.0 * e^(-30/105) ≈ 1.50
    val stale = fresh.copy(strength = 2.0f, lastDecayAt = now - 30 * DAY)
    near("strength: 30 天按指数衰减", 1.503f, TagStrength.current(stale, now))

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
     * 起点被重置：惩罚后 30 天按 τ=85 衰减到 1.5 * e^(-30/85) ≈ 1.05；
     * 若没重置（起点早 30 天）会掉到 1.5 * e^(-60/85) ≈ 0.74。
     */
    val later30 = now + 30 * DAY
    val decayed30 = TagStrength.current(written, later30)
    near("penalty: 30 天后按 τ=85 衰减", 1.054f, decayed30)

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

    // 600 天不读 → archived（τ=131 时，1.9 * e^(-600/131) ≈ 0.019）
    val later = now + 600 * DAY
    val decayed = TagStrength.current(tag, later)
    check("asm: 600 天后归档", TagStrength.depthOf(decayed) == "archived", decayed.toString())
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
