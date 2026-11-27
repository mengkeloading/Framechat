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
    fun goalLevel(
        strength: Float,
        currentLevel: Int,
        createdAt: Long,
        lastCompressedAt: Long,
        now: Long,
    ): Int? {
        if (now - createdAt < CONSOLIDATION_MS) return null

        val days = (now - lastCompressedAt) / DAY_MS
        return when {
            strength < 0.2f && currentLevel < 4 && days > 30 -> 4
            strength < 0.5f && currentLevel < 3 && days > 14 -> 3
            strength < 1.5f && currentLevel < 2 && days > 7 -> 2
            else -> null
        }
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
