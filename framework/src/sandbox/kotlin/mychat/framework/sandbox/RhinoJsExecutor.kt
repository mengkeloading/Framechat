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
            // 模块依赖的全局：semantics 与 demo 的 js/module/modulekit.js 一致
            ctx.evaluateString(scope, prelude(), "modulekit", 1, null)
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

    /**
     * 模块可见的全局：ModuleKit + 裁剪版的 ModuleLoader。
     *
     * ModuleLoader 只给元数据（id/name/description/hasSkill）——
     * 不提供 getScript / getReference：模块**无权读别人的内容**，
     * 要读自己的 reference 走 host.requestLocalAction('resource.read', …)。
     */
    private fun prelude(): String {
        val packages = source.all().joinToString(",") { pkg ->
            val hasSkill = if (pkg.reference(SKILL_FILE) != null) "true" else "false"
            "{id:" + js(pkg.id) +
                ",name:" + js(pkg.manifest.name) +
                ",description:" + js(pkg.manifest.description) +
                ",hasSkill:" + hasSkill + "}"
        }
        return MODULE_KIT + "\n" +
            "var ModuleLoader = {\n" +
            "  listPackages: function () { return [" + packages + "]; }\n" +
            "};\n"
    }

    /** 足够用的 JS 字符串字面量转义（模块名/描述会带引号和换行）。 */
    private fun js(text: String): String {
        val sb = StringBuilder(text.length + 2)
        sb.append('"')
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
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
        private const val SKILL_FILE = "SKILL.md"

        /**
         * 与 demo 的 modulekit.js 同语义：模块只认 parse / reply / isLegal。
         * 缺了它，19 个模块里 15 个会 ReferenceError，再被 fail-open 吞掉 ——
         * 表现是「模块明明启用了却什么都不做」。
         */
        private const val MODULE_KIT = """
            var ModuleKit = {
              parse: function (inputJson) {
                if (typeof inputJson !== 'string') return { text: '', phase: 'a', ctx: {} };
                var t = inputJson.replace(/^\s+/, '');
                if (t.charAt(0) === '{') {
                  try {
                    var o = JSON.parse(t);
                    if (o && typeof o === 'object' && typeof o.text === 'string') {
                      return { text: o.text, phase: o.phase || 'a', ctx: o.ctx || {} };
                    }
                  } catch (e) { }
                }
                return { text: inputJson, phase: 'a', ctx: {} };
              },
              reply: function (o) { return JSON.stringify(o); },
              isLegal: function (type, phase) {
                var A = ['pass', 'local', 'context'];
                var B = ['pass', 'rewrite', 'invoke'];
                return (phase === 'a' ? A : B).indexOf(type) >= 0;
              }
            };
        """

        @Volatile
        private var factoryReady = false
        private val FACTORY_LOCK = Any()
        private val DEADLINE = ThreadLocal<Long>()
    }
}

class JsTimeoutException(message: String) : RuntimeException(message)
