package mychat.framework.hook

/**
 * 模块执行引擎的抽象。
 *
 * 为什么要有这一层：现在只有 Rhino（JS），将来可能是 Python / Lua。
 * 调用方（dispatcher）不应该知道"引擎怎么跑脚本"，只按 `MODULE.md` 的
 * `engine:` 字段找到对应引擎就行。
 */
interface ModuleExecutor {

    /** 对应 `MODULE.md` 的 `engine:` 值，如 "js" / "py" / "none"。 */
    val engineId: String

    /** 执行一个模块的一个钩子阶段。 */
    fun run(module: ModulePackage, phase: String, envelope: String, host: HostBridge): ExecutorOutcome
}

sealed interface ExecutorOutcome {

    /** 引擎跑完了，raw 是模块返回的字符串（null 表示"本模块不处理"）。 */
    data class Produced(val raw: String?) : ExecutorOutcome

    /** 引擎没跑（未安装、不适用），交给 fail-open，但要把原因记进 trace。 */
    data class Skipped(val reason: String) : ExecutorOutcome
}

/** 把旧的 [JsExecutor] 适配成 [ModuleExecutor]（只用于兼容既有装配与测试）。 */
class JsExecutorAdapter(private val executor: JsExecutor) : ModuleExecutor {

    override val engineId: String = ENGINE_JS

    override fun run(
        module: ModulePackage,
        phase: String,
        envelope: String,
        host: HostBridge
    ): ExecutorOutcome = ExecutorOutcome.Produced(
        executor.execute(module.id + "/" + module.manifest.entry, envelope, host)
    )

    companion object {
        const val ENGINE_JS = "js"
    }
}

/**
 * 按模块声明的 engine 选执行器。
 *
 * 未知引擎**不报错**，落到 none —— 声明了 `engine: lua` 的模块会被跳过，
 * 而不是让整轮对话失败。
 */
class ExecutorRegistry(private val all: List<ModuleExecutor>) {

    fun forModule(pkg: ModulePackage): ModuleExecutor {
        val wanted = pkg.manifest.engine
        return all.firstOrNull { it.engineId == wanted }
            ?: all.firstOrNull { it.engineId == ENGINE_NONE }
            ?: NoEngine
    }

    companion object {
        const val ENGINE_NONE = "none"

        /** 未知引擎且注册表里没有 none 时的兜底。 */
        private object NoEngine : ModuleExecutor {
            override val engineId: String = ENGINE_NONE
            override fun run(
                module: ModulePackage,
                phase: String,
                envelope: String,
                host: HostBridge
            ): ExecutorOutcome = ExecutorOutcome.Skipped("no_engine")
        }
    }
}
