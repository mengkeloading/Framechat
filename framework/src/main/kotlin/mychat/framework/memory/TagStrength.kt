package mychat.framework.memory

import mychat.framework.memory.compress.Decay

/**
 * 记忆强度：惰性衰减 + 命中的奖励 + 负反馈的惩罚。
 *
 * 不引入后台任务 —— 每次召回时按"上次衰减到现在多少天"现算。
 * 好处是不用闹钟、不用 WorkManager，坏处是强度只在被读到时才更新，
 * 而那正是它唯一有用的时刻。
 */
object TagStrength {

    const val MAX = 3.0f
    const val INITIAL = 1.0f
    const val BOOST = 0.3f
    const val PENALTY_USER_NEGATIVE = 0.5f
    const val PENALTY_JUDGE = 0.2f
    const val PENALTY_DELETE = 0.3f

    /** 低于它就归档，不再参与召回。 */
    const val ARCHIVE_BELOW = 0.08f

    /** 召回加权时的参考强度：1.5 以上不再加分。 */
    private const val FULL_WEIGHT_AT = 1.5f

    /**
     * 当前强度（含衰减）。
     *
     * 公式在 [mychat.framework.memory.compress.Decay]：指数衰减，τ 随强度与
     * 使用次数增长。这里只负责"上次衰减到现在"的时间锚点。
     */
    fun current(tag: TagRecord, now: Long): Float {
        val anchor = if (tag.lastDecayAt > 0) tag.lastDecayAt else now
        return Decay.strengthAt(tag.strength, anchor, now, tag.readCount)
    }

    /** 召回加权：强度不到 1.5 时按比例打折，避免弱记忆抢走强记忆的位置。 */
    fun weight(strength: Float): Float = (strength / FULL_WEIGHT_AT).coerceIn(0f, 1f)

    fun depthOf(strength: Float): String = when {
        strength < ARCHIVE_BELOW -> "archived"
        strength < 0.5f -> "name_only"
        strength < 1.5f -> "summary"
        else -> "full"
    }

    /** 命中一次：+0.3，封顶 3.0，重置衰减起点。 */
    fun boosted(tag: TagRecord, now: Long): TagRecord {
        val next = (current(tag, now) + BOOST).coerceAtMost(MAX)
        return tag.copy(
            strength = next,
            readCount = tag.readCount + 1,
            lastReadAt = now,
            lastDecayAt = now,
        )
    }

    /** 负反馈：不低于 0。 */
    fun penalized(tag: TagRecord, now: Long, amount: Float): TagRecord {
        val next = (current(tag, now) - amount).coerceAtLeast(0f)
        return tag.copy(strength = next, lastDecayAt = now)
    }

    /** 恢复：强度回到初始值，深度自然回到 summary。 */
    fun restored(tag: TagRecord, now: Long): TagRecord =
        tag.copy(strength = INITIAL, lastDecayAt = now)
}
