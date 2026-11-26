package mychat.framework.hook

/**
 * 「本轮用了哪个模块」的**推断**。
 *
 * 模型自报的 `[[!USED:x]]` 不可靠（会复述提示里的占位符、会漏写），所以不再依赖它。
 * 在路由接入之前，用加载到的模块数量反推：
 * - 只加载了 1 个 → 就是它
 * - 加载了多个 → 无法判断，记 unknown（不要随便挑一个充数）
 */
object UsageInference {

    data class Result(val used: String?, val reason: String)

    const val REASON_SINGLE = "single"
    const val REASON_MULTIPLE = "multiple"
    const val REASON_NONE = "none"

    fun infer(loaded: Collection<String>): Result = when {
        loaded.isEmpty() -> Result(null, REASON_NONE)
        loaded.size == 1 -> Result(loaded.first(), REASON_SINGLE)
        else -> Result(null, REASON_MULTIPLE)
    }
}
