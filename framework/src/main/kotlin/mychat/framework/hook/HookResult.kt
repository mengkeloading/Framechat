package mychat.framework.hook

/** 钩子 A 的返回值。text 是放行文本（可被改写），extra 是追加的 system 块。 */
sealed interface HookAResult {
    val text: String

    data class Pass(override val text: String) : HookAResult
    data class Local(override val text: String) : HookAResult
    data class Context(override val text: String, val extra: String) : HookAResult
}

/** 钩子 B 的返回值。Pass 表示原样展示模型输出。 */
sealed interface HookBResult {
    val text: String

    data class Pass(override val text: String) : HookBResult
    data class Rewrite(override val text: String) : HookBResult

    /** 转调另一个模块；payload 成为被调用模块收到的 text。 */
    data class Invoke(
        override val text: String,
        val moduleName: String,
        val payload: String
    ) : HookBResult
}

/** 调度轨迹，供调试面板与单测断言。 */
data class Trace(
    val tag: String,
    val module: String?,
    val message: String
)
