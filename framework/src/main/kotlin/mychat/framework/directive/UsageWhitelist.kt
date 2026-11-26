package mychat.framework.directive

/**
 * `[[USED:x]]` 的白名单校验。
 *
 * 为什么需要：模型会**复述提示里的占位符**——系统提示里写了 `[[USED:模块id]]`，
 * 它照抄一遍，于是正文里多出一条"声明使用了模块『模块id』"。这类值不是合法模块，
 * 混进统计会污染"加载了但没用 / 用了没声明"的判断。
 *
 * 只采信「本轮已加载的模块 id」，其余丢弃（但原值保留在 meta 里供日志排查）。
 */
object UsageWhitelist {

    fun filter(declared: List<String>, loadedIds: Set<String>): List<String> =
        declared.filter { it in loadedIds }

    fun invalid(declared: List<String>, loadedIds: Set<String>): List<String> =
        declared.filter { it !in loadedIds }
}
