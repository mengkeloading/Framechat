package mychat.framework

import mychat.framework.memory.LayeredRecall
import mychat.framework.memory.Recall
import mychat.framework.memory.TagRecord
import mychat.framework.memory.Tagger
import mychat.framework.memory.compress.CompressResult
import mychat.framework.memory.compress.Compressor
import mychat.framework.memory.consolidate.CompressibleTag
import mychat.framework.memory.consolidate.ConsolidationSource
import mychat.framework.memory.consolidate.ConsolidateSettings
import mychat.framework.memory.consolidate.DefaultConsolidator
import kotlin.system.exitProcess

/**
 * 压力测试：只跑真实数量级的场景，验证「还能不能忍」，不验正确性。
 *
 * 都在 JVM 上跑，数字比真机乐观（真机 CPU 更慢）—— 用它看数量级，
 * 不看绝对值。上限放得比预期宽，红了才是真出问题。
 */
private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, cond: Boolean, detail: String? = null) {
    if (cond) {
        passed++
    } else {
        failures += name
        println("FAIL  $name" + (detail?.let { "  ($it)" } ?: ""))
    }
}

private const val NOW = 1_700_000_000_000L
private const val DIM = 512

/** 造一个第 [i] 维为 1 的向量：不同 i 之间正交，方便控制相似度。 */
private fun axis(i: Int): FloatArray = FloatArray(DIM) { if (it == i % DIM) 1f else 0f }

private fun tagAt(index: Int): TagRecord = TagRecord(
    id = "t$index",
    name = "话题$index",
    vector = axis(index),
    strength = 1f,
    lastDecayAt = NOW,
    lastSeenAt = NOW - 86_400_000L,
    recent = listOf(
        mychat.framework.memory.RecentItem("m$index", "第 $index 个话题的原文", NOW - 3_600_000L)
    ),
)

fun main() {
    t1RecallLatency()
    t2InjectionLength()
    t3TagGrowth()
    t4ConsolidateLatency()

    println()
    println("PASS $passed / ${passed + failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  - $it") }
        exitProcess(1)
    }
}

/** T1：1000 个标签的召回耗时。 */
private fun t1RecallLatency() {
    val tags = (0 until 1000).map { tagAt(it) }
    val query = axis(0)

    // 预热：JIT 没起来时第一次总是慢的，别算进平均
    repeat(5) { LayeredRecall.recall("预热", query, tags, log = { }, now = NOW) }

    val rounds = 20
    val start = System.nanoTime()
    repeat(rounds) {
        LayeredRecall.recall("话题7 的进度如何", query, tags, log = { }, now = NOW)
    }
    val avgMs = (System.nanoTime() - start) / 1_000_000.0 / rounds
    println("T1 1000 标签召回平均耗时: " + "%.2f".format(avgMs) + " ms")
    check("T1: 平均 < 100ms", avgMs < 100.0, "%.2f ms".format(avgMs))
}

/** T2：20 个标签的注入块长度（常态上限）。 */
private fun t2InjectionLength() {
    val directory = mychat.framework.memory.DirectoryFormatter.format(
        (0 until 20).map { mychat.framework.memory.TagEntry("t$it", "话题$it", 5 + it, 1.4f) }
    )
    val summary = "决定用 Kotlin 重写模块系统。".repeat(10)
    val hit = mychat.framework.memory.RecallHit(
        tagId = "t0", tagName = "话题0", score = 0.82f,
        text = summary, depth = "full", summary = summary,
    )
    val recall = Recall.formatBlock(hit, related = (1..5).map { "话题$it" })
    val expand = "【展开】关于「话题1」：\n" + "更细的一层内容".repeat(10)
    val memory = mychat.framework.memory.MemoryInjection.compose(directory, recall, expand)!!
    val profile = mychat.framework.profile.ProfileFormatter.format(
        mychat.framework.profile.UserProfile(
            role = "独立开发者，做 Android App",
            tech = listOf("Kotlin", "Android"),
            style = "极简",
            avoid = listOf("空话", "过度设计"),
        )
    )
    val persona = mychat.framework.profile.AssistantPersona.format(true)

    val block = listOfNotNull(persona, profile, memory).joinToString("\n\n")
    println("T2 注入块长度: " + block.length + " 字（20 条目录 + 召回 + 展开 + 角色卡 + 人格）")
    check("T2: 注入 < 8000 字", block.length < 8000, block.length.toString())
}

/**
 * T3：100 条消息会新建多少标签。
 *
 * 两种输入都跑：
 * - 正交（每条都是全新话题）：上界，理论上就是 100 个，只打印不判定
 * - 聚类（40 个话题各说 2~3 次，带噪声）：更接近真实对话，判定 < 60
 */
private fun t3TagGrowth() {
    fun run(messages: List<Pair<FloatArray, String>>): Int {
        val tags = mutableListOf<TagRecord>()
        var created = 0
        for ((vector, text) in messages) {
            val decision = Tagger.decide(vector, tags, text)
            if (decision is mychat.framework.memory.TagDecision.Create) {
                created++
                tags += TagRecord(
                    id = "n$created", name = decision.name, vector = vector,
                    strength = 1f, lastDecayAt = NOW,
                )
            }
        }
        return created
    }

    val orthogonal = (0 until 100).map { i ->
        axis(i) to "我想聊聊第 $i 件事，它跟别的事都不一样"
    }
    println("T3 100 条正交消息（上界，每条都是新话题）: " + run(orthogonal) + " 个")

    val rnd = java.util.Random(7)
    val clustered = (0 until 100).map { i ->
        val topic = i % 40
        val vector = FloatArray(DIM) { d ->
            val base = if (d == topic) 1f else 0f
            base + (rnd.nextFloat() - 0.5f) * 0.04f
        }
        vector to "继续聊第 $topic 个话题，第 ${i / 40 + 1} 次提到它"
    }
    val realistic = run(clustered)
    println("T3 100 条聚类消息（40 个话题各说 2~3 次）: " + realistic + " 个")
    check("T3: 聚类输入新建 < 60", realistic < 60, realistic.toString())
}

/** T4：1000 个标签、每个都该压一次，看整理耗时（压缩器是假的，不调模型）。 */
private fun t4ConsolidateLatency() {
    val day = 86_400_000L
    val tags = (0 until 1000).map {
        CompressibleTag(
            id = "t$it", strength = 0.4f, readCount = 1, lastDecayAt = NOW - 200 * day,
            currentLevel = 1, createdAt = NOW - 300 * day, lastCompressedAt = NOW - 40 * day,
            messageCount = 5,
        )
    }
    val compressor = object : Compressor {
        override suspend fun compress(tagId: String, toLevel: Int): CompressResult =
            CompressResult.Done(toLevel)
    }
    val source = object : ConsolidationSource {
        override suspend fun compressible(): List<CompressibleTag> = tags
    }
    val settings = object : ConsolidateSettings {
        override suspend fun lastConsolidateAt(): Long = 0L
        override suspend fun setLastConsolidateAt(t: Long) = Unit
    }

    val start = System.nanoTime()
    val report = kotlinx.coroutines.runBlocking {
        DefaultConsolidator(
            source = source,
            compressor = compressor,
            settings = settings,
            clock = { NOW },
            maxPerRun = 1000,
            log = { },
        ).runNow()
    }
    val ms = (System.nanoTime() - start) / 1_000_000
    println(
        "T4 1000 标签整理耗时: " + ms + " ms（扫描 " + report.scanned +
            "，压缩 " + report.compressed + "）"
    )
    check("T4: < 5 秒", ms < 5000, ms.toString() + " ms")
    check("T4: 全部压完", report.compressed == 1000, report.compressed.toString())
}
