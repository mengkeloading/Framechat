package mychat.framework.memory.consolidate

import mychat.framework.memory.compress.Compressor
import mychat.framework.memory.compress.Decay
import mychat.framework.memory.compress.LevelPolicy

/**
 * 批量整理：隔一段时间扫一遍标签，把该压的压下去。
 *
 * 只做压缩。衰减是**惰性**的（每次读取现算），不需要批量写回 —— 批量写回
 * 反而会让"长期没打开 App"的那批标签在启动瞬间集体掉一大截，看起来很突然。
 */
interface Consolidator {
    /** 距上次超过 interval 才真正执行；没到期返回全 0 的报告 */
    suspend fun maybeRun(): ConsolidateReport

    /** 无条件执行一次（手动触发 / 测试） */
    suspend fun runNow(): ConsolidateReport

    /**
     * 忽略 12 小时窗口、巩固窗口与压缩间隔，把到期的一次压完。
     *
     * [onProgress] 在每次压缩前调一次：(已完成, 总数)。
     */
    suspend fun runNowIgnoringWindow(
        onProgress: (suspend (done: Int, total: Int) -> Unit)? = null,
    ): ConsolidateReport
}

data class ConsolidateReport(
    val scanned: Int,
    val compressed: Int,
    val skipped: Int,
    val failed: Int,
    val durationMs: Long,
    /** true = 触到单次上限，还有没处理的标签 */
    val capped: Boolean = false,
    /** 强度/间隔没到，本轮不该压 */
    val notDue: Int = 0,
    /** 标签下没有可用的对话原文 */
    val noSource: Int = 0,
)

/** 整理需要知道的标签快照（强度是**落库值**，衰减在这里算）。 */
data class CompressibleTag(
    val id: String,
    val strength: Float,
    val readCount: Int,
    val lastDecayAt: Long,
    val currentLevel: Int,
    val createdAt: Long,
    val lastCompressedAt: Long,
    /** 标签下有几条原文消息；0 = 压不出东西，-1 = 调用方没提供 */
    val messageCount: Int = -1,
)

/** 取可压缩的标签。App 侧用 Room 实现。 */
interface ConsolidationSource {
    suspend fun compressible(): List<CompressibleTag>
}

/** 上次整理时间。App 侧用 DataStore 实现。 */
interface ConsolidateSettings {
    suspend fun lastConsolidateAt(): Long
    suspend fun setLastConsolidateAt(t: Long)
}

class DefaultConsolidator(
    private val source: ConsolidationSource,
    private val compressor: Compressor,
    private val settings: ConsolidateSettings,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val maxPerRun: Int = DEFAULT_MAX_PER_RUN,
    private val log: (String) -> Unit = {},
) : Consolidator {

    override suspend fun maybeRun(): ConsolidateReport {
        val now = clock()
        val last = settings.lastConsolidateAt()
        if (now - last < intervalMs) {
            log("consolidate:skip reason=not_due elapsed=${now - last} interval=$intervalMs")
            return ConsolidateReport(0, 0, 0, 0, 0)
        }
        val report = runNow()
        settings.setLastConsolidateAt(now)
        return report
    }

    override suspend fun runNow(): ConsolidateReport = execute(force = false)

    override suspend fun runNowIgnoringWindow(
        onProgress: (suspend (done: Int, total: Int) -> Unit)?,
    ): ConsolidateReport = execute(force = true, onProgress = onProgress)

    private suspend fun execute(
        force: Boolean,
        onProgress: (suspend (done: Int, total: Int) -> Unit)? = null,
    ): ConsolidateReport {
        val start = clock()
        var scanned = 0
        var compressed = 0
        var skipped = 0
        var failed = 0

        val tags = runCatching { source.compressible() }.getOrElse { error ->
            log("consolidate:fail err=" + error.javaClass.simpleName)
            return ConsolidateReport(0, 0, 0, 0, clock() - start)
        }

        var capped = false
        // 进度分母：上限与实际候选数取小，避免显示 "0/5" 而实际只有 2 个
        val allowed = minOf(maxPerRun, tags.size)
        var notDue = 0
        var noSource = 0
        for (tag in tags) {
            scanned++
            val strength = Decay.strengthAt(
                strength = tag.strength,
                lastDecayAt = if (tag.lastDecayAt > 0) tag.lastDecayAt else start,
                now = start,
                readCount = tag.readCount,
            )
            val goal = LevelPolicy.goalLevel(
                strength = strength,
                currentLevel = tag.currentLevel,
                createdAt = tag.createdAt,
                lastCompressedAt = tag.lastCompressedAt,
                now = start,
                force = force,
                ignoreInterval = force,
            )
            if (goal == null) {
                skipped++
                notDue++
                continue
            }
            // 没有原文就没法压：不算失败，也不算"该压没压"
            if (tag.messageCount == 0) {
                skipped++
                noSource++
                continue
            }
            // 配额只数"真的要压的"：前几个都不该压，不该用掉配额
            if (compressed + failed >= maxPerRun) {
                capped = true
                break
            }
            onProgress?.invoke(compressed + failed, allowed)
            // 单个失败不影响其他标签
            val ok = runCatching { compressor.compress(tag.id, goal) }.getOrElse { false }
            if (ok) compressed++ else failed++
        }

        val report = ConsolidateReport(
            scanned = scanned,
            compressed = compressed,
            skipped = skipped,
            failed = failed,
            durationMs = clock() - start,
            capped = capped,
            notDue = notDue,
            noSource = noSource,
        )
        log(
            "consolidate:done scanned=${report.scanned} compressed=${report.compressed} " +
                "notDue=${report.notDue} noSource=${report.noSource} " +
                "failed=${report.failed} capped=${report.capped} ms=${report.durationMs}"
        )
        return report
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 12 * 3_600_000L

        /** 单次整理最多压几个：一次调模型太多会很慢，也会把内存预算吃满。 */
        const val DEFAULT_MAX_PER_RUN = 5
    }
}
