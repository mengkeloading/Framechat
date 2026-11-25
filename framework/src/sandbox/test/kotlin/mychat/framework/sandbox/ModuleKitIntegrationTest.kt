package mychat.framework.sandbox

import mychat.framework.hook.AuditLog
import mychat.framework.hook.GatedHostBridge
import mychat.framework.hook.HookAResult
import mychat.framework.hook.HookBResult
import mychat.framework.hook.HookSession
import mychat.framework.hook.Message
import mychat.framework.hook.ModuleDispatcher
import mychat.framework.hook.ModuleRegistry
import mychat.framework.hook.SessionData
import java.io.File
import kotlin.system.exitProcess

/**
 * 用 assets 里的**真模块**验证 ModuleKit shim。
 * 不用桩：桩过了不代表真模块能过，而这些模块是要直接发给用户的。
 */
private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, cond: Boolean, detail: String? = null) {
    if (cond) {
        passed++
    } else {
        failures += name
        println("FAIL  $name" + (detail?.let { "  ($it)" } ?: ""))
    }
}

private fun eq(name: String, expected: Any?, actual: Any?) =
    check(name, expected == actual, "expected=$expected actual=$actual")

fun main() {
    val repoRoot = System.getProperty("mychat.repoRoot")
    if (repoRoot == null) {
        println("SKIP  模块集成测试（未设置 -Dmychat.repoRoot）")
        return
    }
    val modulesDir = File(repoRoot, "android/app/src/main/assets/modules")
    if (!modulesDir.isDirectory) {
        println("FAIL  找不到 assets 模块目录: $modulesDir")
        exitProcess(1)
    }

    val scan = ModuleRegistry.scan(modulesDir)
    val registry = scan.registry
    check("assets: 模块目录可扫描", registry.all().isNotEmpty(), "problems=${scan.problems}")

    val session = HookSession(
        id = "sess-42",
        title = "测试会话",
        activeHardModules = listOf("local_calc", "attach_context", "no_fluff", "debug_ctx")
    )
    val sessionData = SessionData(
        session = session,
        messages = listOf(Message("user", "上一句"), Message("assistant", "上一答"))
    )
    val audit = AuditLog()
    val dispatcher = ModuleDispatcher(
        source = registry,
        executor = RhinoJsExecutor(registry),
        bridges = GatedHostBridge.factory(registry, audit, { sessionData })
    )

    localCalc(dispatcher, session)
    attachContext(dispatcher, session)
    noFluff(dispatcher, session)
    ctxPassthrough(dispatcher, session)

    // shim 有问题必须暴露，不能靠 fail-open 吞掉
    val swallowed = dispatcher.traces.filter { it.message.contains("执行异常") }
    eq("shim: 没有模块因异常被 fail-open 吞掉", emptyList<String>(), swallowed.map { it.module + ": " + it.message })

    println()
    println("PASS $passed / ${passed + failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  - $it") }
        exitProcess(1)
    }
}

/** 真模块 local-calc：本地算数，不发请求。 */
private fun localCalc(dispatcher: ModuleDispatcher, session: HookSession) {
    val result = dispatcher.dispatchInput(session, "算一下 12*37")
    check("local_calc: 返回 local", result is HookAResult.Local, "实际 ${result::class.simpleName}")
    eq("local_calc: 算出 444", "12*37 = 444", result.text)
}

/** 真模块 attach-context：把「附加：」后面的内容变成 extra。 */
private fun attachContext(dispatcher: ModuleDispatcher, session: HookSession) {
    val result = dispatcher.dispatchInput(session.copy(activeHardModules = listOf("attach_context")), "附加：用英文回答")
    check("attach_context: 返回 context", result is HookAResult.Context, "实际 ${result::class.simpleName}")
    val extra = (result as? HookAResult.Context)?.extra.orEmpty()
    check("attach_context: extra 非空", extra.isNotEmpty())
    check("attach_context: extra 带上附加内容", extra.contains("用英文回答"), "extra=$extra")
}

/** 真模块 no-fluff：砍掉结尾套话。 */
private fun noFluff(dispatcher: ModuleDispatcher, session: HookSession) {
    val withFluff = "这是结论。\n\n希望这些能对你有帮助。"
    val result = dispatcher.dispatchOutput(
        session.copy(activeHardModules = listOf("no_fluff")), withFluff
    )
    check("no_fluff: 返回 rewrite", result is HookBResult.Rewrite, "实际 ${result::class.simpleName}")
    val text = result.text
    eq("no_fluff: 只留正文", "这是结论。", text)
    check("no_fluff: 套话被删掉", !text.contains("希望"))
}

/** 真模块 debug-ctx：把看到的 ctx 原样打出来，同时验证信封形态。 */
private fun ctxPassthrough(dispatcher: ModuleDispatcher, session: HookSession) {
    val result = dispatcher.dispatchInput(
        session.copy(activeHardModules = listOf("debug_ctx")), "诊断ctx",
        history = listOf(Message("user", "上一句"), Message("assistant", "上一答"))
    )
    check("debug_ctx: 返回 local", result is HookAResult.Local, "实际 ${result::class.simpleName}")
    val out = result.text

    check("ctx: 模块能读到 sessionId", out.contains("sessionId"), "output=$out")
    check("ctx: 能读到会话标题", out.contains("title=测试会话"), "output=$out")
    check("ctx: 能读到历史条数", out.contains("messages: 2 条"), "output=$out")
    check("ctx: attachments 为空时不炸", out.contains("attachments: 空"), "output=$out")

    // 信封必须是 {text, phase, ctx}：若是裸字符串，parse 会把整串当 text，
    // 就匹配不到「诊断ctx」，也就不会返回 local —— 上一组断言已经排除了这种可能。
    check("信封: phase 为 a", out.contains("phase=a"), "output=$out")
    check("信封: ctx 是对象而非空", out.contains("ctx keys: sessionId"), "output=$out")
}
