package mychat.framework.embedding

import mychat.framework.butler.RouteResult

/**
 * 路由日志：把"谁赢了、赢多少"写成一行。
 *
 * 分数一定要打出来。只看命中 id 时，一个 0.31 分的勉强命中和 0.72 分的
 * 明确命中长得一模一样，出问题时无法判断该调阈值还是该改模块描述。
 */
object RouteLog {

    fun line(route: RouteResult): String {
        val ranked = route.scores.entries.sortedByDescending { it.value }
        val best = ranked.getOrNull(0)
        val second = ranked.getOrNull(1)
        val margin = if (best != null && second != null) best.value - second.value else 1f
        return "route:score best=" + (best?.key ?: "-") +
            " score=" + fmt(best?.value) +
            " second=" + (second?.key ?: "-") +
            " score2=" + fmt(second?.value) +
            " margin=" + fmt(margin) +
            " reason=" + route.reason
    }

    private fun fmt(v: Float?): String =
        if (v == null) "-" else String.format(java.util.Locale.US, "%.3f", v)
}
