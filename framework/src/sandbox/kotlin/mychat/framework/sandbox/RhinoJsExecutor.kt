package mychat.framework.sandbox

import mychat.framework.hook.HostBridge
import mychat.framework.hook.JsExecutor
import mychat.framework.hook.ModuleSource
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.Undefined

/**
 * 用 Rhino 同步执行模块脚本。
 *
 * 为什么不是 WebView：evaluateJavascript 是异步回调，而模块契约要求
 * host.requestLocalAction 同步返回（模块无法 await）。用 WebView 只能在
 * 调用线程上死等回调，主线程会 ANR、子线程会与 UI 线程互等。
 *
 * 沙箱边界：initSafeStandardObjects 去掉 Packages/java/getClass 等入口，
 * 模块拿不到文件系统，能力只能经 host 走闸门。
 */
class RhinoJsExecutor(
    private val source: ModuleSource,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val optimizationLevel: Int = -1
) : JsExecutor {

    override fun execute(scriptRef: String, inputJson: String, host: HostBridge): String? {
        val (pkg, file) = source.resolveScript(scriptRef)
            ?: throw IllegalStateException("module_not_found: $scriptRef")
        val script = pkg.script(file)
            ?: throw IllegalStateException("script_not_found: $scriptRef")

        ensureFactory()
        val ctx = Context.enter()
        DEADLINE.set(System.currentTimeMillis() + timeoutMs)
        try {
            ctx.optimizationLevel = optimizationLevel
            ctx.languageVersion = Context.VERSION_ES6
            val scope = ctx.initSafeStandardObjects()
            val wrapper = "(function (inputJson, host) {\n" +
                "var fetch=undefined, XMLHttpRequest=undefined, localStorage=undefined,\n" +
                "    document=undefined, window=undefined, require=undefined, importScripts=undefined;\n" +
                "return ($script)(inputJson, host);\n" +
                "})"
            val fn = ctx.evaluateString(scope, wrapper, file, 1, null) as? Function
                ?: throw IllegalStateException("module_not_a_function: $scriptRef")
            val result = fn.call(
                ctx, scope, scope,
                arrayOf<Any?>(inputJson, Context.javaToJS(host, scope))
            )
            return when (result) {
                null, Undefined.instance, Scriptable.NOT_FOUND -> null
                is CharSequence -> result.toString()
                else -> Context.toString(result)
            }
        } finally {
            DEADLINE.remove()
            Context.exit()
        }
    }

    /** 超时靠 Rhino 的指令计数器：死循环模块会在下一个检查点被抛出。 */
    private class ObservingFactory : ContextFactory() {
        override fun makeContext(): Context {
            val cx = super.makeContext()
            cx.setInstructionObserverThreshold(INSTRUCTION_CHECK_INTERVAL)
            return cx
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            val deadline = DEADLINE.get() ?: return
            if (System.currentTimeMillis() > deadline) {
                throw JsTimeoutException("module_timeout")
            }
        }
    }

    private fun ensureFactory() {
        if (factoryReady) return
        synchronized(FACTORY_LOCK) {
            if (factoryReady) return
            try {
                ContextFactory.initGlobal(ObservingFactory())
            } catch (e: Throwable) {
                // 全局工厂已经被初始化过（或宿主禁止），退化为无超时执行
            }
            factoryReady = true
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 3000L
        private const val INSTRUCTION_CHECK_INTERVAL = 10_000

        @Volatile
        private var factoryReady = false
        private val FACTORY_LOCK = Any()
        private val DEADLINE = ThreadLocal<Long>()
    }
}

class JsTimeoutException(message: String) : RuntimeException(message)
