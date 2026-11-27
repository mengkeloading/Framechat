package mychat.framework.memory

/**
 * 记忆为什么没跑。
 *
 * 单独抽出来是因为"没跑"是最难查的状态：所有调用点都返回 null，
 * 日志里什么都不显示，看着就像"记忆从来没启用过"。四种原因必须能分开。
 */
object MemoryGate {

    /** 返回跳过原因；能跑时返回 null。 */
    fun offReason(
        enabled: Boolean,
        embedderReady: Boolean,
        hasModules: Boolean,
    ): String? = when {
        !enabled -> "disabled"
        !embedderReady -> "embedder_not_ready"
        !hasModules -> "no_modules"
        else -> null
    }
}
