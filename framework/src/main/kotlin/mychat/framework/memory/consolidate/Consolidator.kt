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
}

data class ConsolidateReport(
    val scanned: Int,
    val compressed: Int,
    val skipped: Int,
    val failed: Int,
    val durationMs: Long,
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

    override suspend fun runNow(): ConsolidateReport {
        val start = clock()
        var scanned = 0
        var compressed = 0
        var skipped = 0
        var failed = 0

        val tags = runCatching { source.compressible() }.getOrElse { error ->
            log("consolidate:fail err=" + error.javaClass.simpleName)
            return ConsolidateReport(0, 0, 0, 0, clock() - start)
        }

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
            )
            if (goal == null) {
                skipped++
                continue
            }
            // 单个失败不影响其他标签
            val ok = runCatching { compressor.compress(tag.id, goal) }.getOrElse { false }
            if (ok) compressed++ else failed++
        }

        val report = ConsolidateReport(scanned, compressed, skipped, failed, clock() - start)
        log(
            "consolidate:done scanned=${report.scanned} compressed=${report.compressed} " +
                "skipped=${report.skipped} failed=${report.failed} ms=${report.durationMs}"
        )
        return report
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 12 * 3_600_000L
    }
}
