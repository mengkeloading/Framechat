package mychat.framework.sandbox

import mychat.framework.hook.ExecutorOutcome
import mychat.framework.hook.HostBridge
import mychat.framework.hook.ModuleExecutor
import mychat.framework.hook.ModulePackage

/** 纯知识型模块（`engine: none`）：没有脚本，什么都不执行。 */
object NoopExecutor : ModuleExecutor {

    override val engineId: String = "none"

    override fun run(
        module: ModulePackage,
        phase: String,
        envelope: String,
        host: HostBridge
    ): ExecutorOutcome = ExecutorOutcome.Skipped("no_script")
}

/**
 * Python 模块占位。
 *
 * 声明 `engine: py` 的模块不会崩，只会被跳过并在 trace 里写明原因。
 * 将来接 Chaquopy 时只改这一个类。
 */
class PythonExecutor : ModuleExecutor {

    override val engineId: String = "py"

    override fun run(
        module: ModulePackage,
        phase: String,
        envelope: String,
        host: HostBridge
    ): ExecutorOutcome = ExecutorOutcome.Skipped("python_not_available")
}
