package mychat.framework.memory.compress

import kotlin.math.exp

/**
 * 强度衰减：艾宾浩斯遗忘曲线（1885）的指数形式 `R = e^(-t/S)`。
 *
 * ```
 * strength(t) = strength * e^(-days / τ)
 * τ = 7 + 10 * strength + 3 * readCount        （天）
 * ```
 *
 * 为什么用指数而不是线性：线性每天掉固定量，某天会突然归零；指数先快后慢、
 * 永远不到零 —— 符合"不会彻底忘，只是难想起"。τ 随强度与使用次数增长，
 * 所以越重要、越常用的话题掉得越慢。
 */
object Decay {

    private const val DAY_MS = 86_400_000.0

    fun strengthAt(
        strength: Float,
        lastDecayAt: Long,
        now: Long,
        readCount: Int,
    ): Float {
        val days = ((now - lastDecayAt) / DAY_MS).toFloat()
        // 时钟回拨或同一毫秒内重复读：不动
        if (days <= 0f) return strength
        val tau = 7f + strength * 10f + readCount * 3f
        return strength * exp(-days / tau)
    }
}
