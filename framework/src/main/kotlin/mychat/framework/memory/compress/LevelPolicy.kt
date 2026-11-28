package mychat.framework.memory.compress

/**
 * 该给哪一层、什么时候压。
 *
 * 两层不同的判断：
 * - [displayLevel]：**现在**给模型多细的内容（按强度）
 * - [goalLevel]：**现在**该不该往下压一层（按强度 + 距上次压缩的时间）
 *
 * 分开是因为两者方向相反：强度高时要细（L1），但强度高时不该压；
 * 用一个函数兼顾会让"刚强化过的标签立刻被压"。
 */
object LevelPolicy {

    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000.0

    /** 巩固窗口：新建后 48 小时内不压缩（新记忆最脆弱的阶段）。 */
    private const val CONSOLIDATION_MS = 48L * HOUR_MS

    /** 给哪一层：1 = 最详细，4 = 只有一句话。 */
    fun displayLevel(strength: Float): Int = when {
        strength >= 1.5f -> 1
        strength >= 0.5f -> 2
        strength >= 0.2f -> 3
        else -> 4
    }

    /**
     * 该压到第几层；null = 本轮不压。
     *
     * 间隔从 `lastCompressedAt` 算而不是 `createdAt`：压过一次之后重新计时，
     * 否则一个半年前建的标签会每一轮都被判定为"该压"。
     */
    /**
     * @param force 跳过 48 小时巩固窗口（手动触发用）
     * @param ignoreInterval 连 7/14/30 天的间隔也不要（完全强制）
     */
    fun goalLevel(
        strength: Float,
        currentLevel: Int,
        createdAt: Long,
        lastCompressedAt: Long,
        now: Long,
        force: Boolean = false,
        ignoreInterval: Boolean = false,
    ): Int? {
        return dueResult(strength, currentLevel, createdAt, lastCompressedAt, now, force, ignoreInterval).level
    }

    /**
     * 不只是"压不压"，还要说清**为什么**不压。
     *
     * 「不该压」和「已经够简」混在同一个数字里，就没法判断到底是
     * 阈值太保守，还是时间窗口太长。
     */
    enum class Due {
        /** 该压 */
        DUE,

        /** 48 小时巩固窗口内 */
        CONSOLIDATING,

        /** 强度还高（>= 1.5），不需要压 */
        STRONG,

        /** 已经在目标层或更细 */
        AT_LEVEL,

        /** 距上次压缩不足 7 / 14 / 30 天 */
        INTERVAL,
    }

    data class DueResult(val level: Int?, val due: Due)

    fun dueResult(
        strength: Float,
        currentLevel: Int,
        createdAt: Long,
        lastCompressedAt: Long,
        now: Long,
        force: Boolean = false,
        ignoreInterval: Boolean = false,
    ): DueResult {
        if (!force && now - createdAt < CONSOLIDATION_MS) return DueResult(null, Due.CONSOLIDATING)

        val target = when {
            strength < 0.2f -> 4
            strength < 0.5f -> 3
            strength < 1.5f -> 2
            else -> return DueResult(null, Due.STRONG)
        }
        // 逐层：一次只往下压一层。从 L0 直接跳到 L2 会丢掉 L1 这一环，
        // 而 L2 的 sourceRefs 指向 L1 —— 中间那层不在，回溯就断了。
        if (target <= currentLevel) return DueResult(null, Due.AT_LEVEL)

        // 间隔：越往下压等得越久（压得越狠越不该频繁重压）
        val days = if (ignoreInterval) Double.MAX_VALUE else (now - lastCompressedAt) / DAY_MS
        val minDays = when (currentLevel) {
            0 -> 0.0    // 原文压成 L1 不需要等
            1 -> 7.0
            2 -> 14.0
            else -> 30.0
        }
        if (days < minDays) return DueResult(null, Due.INTERVAL)
        return DueResult(currentLevel + 1, Due.DUE)
    }

    /** 各层的字数上限。 */
    fun targetLength(level: Int): Int = when (level) {
        1 -> 200
        2 -> 100
        3 -> 40
        else -> 12
    }

    /** 还能不能再往细里展开（L0 = 原文，到顶）。 */
    fun canExpand(level: Int): Boolean = level > 0
}
