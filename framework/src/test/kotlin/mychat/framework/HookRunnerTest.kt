package mychat.framework

import mychat.framework.core.ChatEngine
import mychat.framework.core.ChatTransport
import mychat.framework.core.RequestBuilder
import mychat.framework.core.StreamOutcome
import mychat.framework.core.ToolRunner
import mychat.framework.core.TurnResult
import mychat.framework.hook.AuditLog
import mychat.framework.hook.Caps
import mychat.framework.hook.GatedHostBridge
import mychat.framework.hook.HookAResult
import mychat.framework.hook.HookBResult
import mychat.framework.hook.HookRunner
import mychat.framework.hook.HookSession
import mychat.framework.hook.HostBridge
import mychat.framework.hook.JsExecutor
import mychat.framework.hook.Message
import mychat.framework.hook.ModuleDispatcher
import mychat.framework.hook.ModuleManifest
import mychat.framework.hook.ModulePackage
import mychat.framework.hook.ModuleRegistry
import mychat.framework.json.parseJson
import kotlin.system.exitProcess

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, cond: Boolean, detail: String? = null) {
    if (cond) {
        passed++
    } else {
        failures += name
        println("FAIL  $name" + (detail?.let { "  ($detail)" } ?: ""))
    }
}

private fun eq(name: String, expected: Any?, actual: Any?) =
    check(name, expected == actual, "expected=$expected actual=$actual")

fun main() {
    emptyEnabledTests()
    localShortCircuitTests()
    traceAccumulationTests()
    sessionCapabilityTests()
    prefixTests()
    toolTurnSkipsHookBTests()

    println()
    println("PASS $passed / ${passed + failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  - $it") }
        exitProcess(1)
    }
}

private fun pkg(
    id: String,
    hooks: List<String>,
    caps: Map<String, Int> = emptyMap(),
    prefix: String? = null
): ModulePackage =
    ModulePackage(
        manifest = ModuleManifest(
            id = id,
            name = id,
            hooks = hooks,
            capabilities = caps,
            prefix = prefix
        ),
        body = "",
        scripts = mapOf("main.js" to "x"),
        references = emptyMap()
    )

/** 数调用次数：用来证明"没有走执行器"。 */
private class CountingExecutor(
    private val reply: (String, String, HostBridge) -> String?
) : JsExecutor {
    var calls = 0

    override fun execute(scriptRef: String, inputJson: String, host: HostBridge): String? {
        calls++
        return reply(scriptRef, inputJson, host)
    }
}

private fun emptyEnabledTests() {
    val source = ModuleRegistry.of(listOf(pkg("calc", listOf("input"))))
    val executor = CountingExecutor { _, _, _ -> """{"type":"local","text":"不该被调用"}""" }
    val runner = HookRunner(source, executor)
    val session = HookSession(id = "s")   // activeHardModules 为空

    val result = runner.input(session, "原始输入")
    check("runner: 未启用模块时放行", result is HookAResult.Pass)
    eq("runner: 未启用模块时保留原文", "原始输入", result.text)
    eq("runner: 未启用模块时不碰执行器", 0, executor.calls)
    eq("runner: 未启用模块时没有轨迹", 0, runner.lastTrace().size)

    val out = runner.output(session, "模型输出")
    check("runner: 钩子 B 未启用时放行", out is HookBResult.Pass)
    eq("runner: 钩子 B 未启用时保留原文", "模型输出", out.text)
    eq("runner: 钩子 B 未启用时仍不碰执行器", 0, executor.calls)
    eq("runner: 钩子 B 未启用时不建 dispatcher（无轨迹）", 0, runner.lastTrace().size)

    runner.clearTrace()
    eq("runner: clearTrace 清空", 0, runner.lastTrace().size)
}

private fun localShortCircuitTests() {
    val source = ModuleRegistry.of(listOf(pkg("calc", listOf("input"))))
    val executor = CountingExecutor { _, _, _ -> """{"type":"local","text":"12*37 = 444"}""" }
    val runner = HookRunner(source, executor)
    val session = HookSession(id = "s", activeHardModules = listOf("calc"))

    val result = runner.input(session, "算一下 12*37", history = listOf(Message("user", "上一句")))
    check("runner: 本地短路返回 local", result is HookAResult.Local, "实际 ${result::class.simpleName}")
    eq("runner: 本地短路文本", "12*37 = 444", result.text)
    // 只执行一次：既没有重跑，也没有走到第二次模块
    eq("runner: 本地短路只执行一次模块", 1, executor.calls)
    check("runner: 本地短路有 hookA 轨迹", runner.lastTrace().any { it.tag == "hookA" })
}

private fun traceAccumulationTests() {
    val source = ModuleRegistry.of(listOf(pkg("calc", listOf("input"))))
    val executor = CountingExecutor { _, _, _ -> """{"type":"pass","text":""}""" }
    val runner = HookRunner(source, executor)
    val session = HookSession(id = "s", activeHardModules = listOf("calc"))

    runner.input(session, "第一轮")
    val afterFirst = runner.lastTrace().size
    runner.input(session, "第二轮")
    val afterSecond = runner.lastTrace().size

    check("runner: 每轮都留下轨迹", afterFirst > 0, "afterFirst=$afterFirst")
    check("runner: 轨迹跨轮累积", afterSecond > afterFirst, "$afterFirst -> $afterSecond")
    eq("runner: 两轮各执行一次", 2, executor.calls)
}

private fun sessionCapabilityTests() {
    // 模块声明了 data.session，返回它看到的 session.id
    val source = ModuleRegistry.of(
        listOf(pkg("probe", listOf("input"), mapOf(Caps.DATA_SESSION to 3)))
    )
    val executor = CountingExecutor { _, _, host ->
        val raw = host.requestLocalAction(Caps.DATA_SESSION, "{}")
        val id = parseJson(raw)?.obj("session")?.str("id")
        """{"type":"local","text":"sid=$id"}"""
    }
    val runner = HookRunner(source, executor)
    val session = HookSession(id = "sess-abc", activeHardModules = listOf("probe"))

    val result = runner.input(session, "x", history = listOf(Message("user", "hi")))
    eq("runner: data.session 拿到当轮会话", "sid=sess-abc", result.text)
}

/** 前缀 = 强制指令：剥前缀后再入模，落库仍用原文（调用方保存的那个字符串）。 */
private fun prefixTests() {
    var seen = ""
    val novel = pkg("skill_novel", listOf("input"), prefix = "小说")
    val twin = pkg("twin", listOf("input"), prefix = "小说")
    val source = ModuleRegistry.of(listOf(novel, twin))
    val executor = CountingExecutor { _, input, _ ->
        seen = parseJson(input)?.str("text") ?: ""
        """{"type":"pass","text":""}"""
    }
    val runner = HookRunner(source, executor)
    val session = HookSession(id = "s", activeHardModules = listOf("skill_novel"))

    val original = "小说：写一段雨夜"
    val forced = runner.input(session, original)
    eq("prefix: 去前缀后入模", "写一段雨夜", seen)
    eq("prefix: 组包文本不含前缀", "写一段雨夜", forced.text)
    eq("prefix: 原文变量不变", "小说：写一段雨夜", original)
    check("prefix: 留下强制轨迹", runner.lastTrace().any { it.tag == "prefix" })

    runner.input(session, "小说:英文冒号也算")
    eq("prefix: 英文冒号也识别", "英文冒号也算", seen)

    runner.clearTrace()
    runner.input(session, "帮我写个故事")
    eq("prefix: 无前缀时原样入模", "帮我写个故事", seen)
    eq("prefix: 无前缀时没有强制轨迹", 0, runner.lastTrace().count { it.tag == "prefix" })

    runner.clearTrace()
    val both = HookSession(id = "s", activeHardModules = listOf("skill_novel", "twin"))
    runner.input(both, "小说：歧义")
    eq(
        "prefix: 同前缀取第一个",
        "skill_novel",
        runner.lastTrace().firstOrNull { it.tag == "prefix" }?.module
    )
}

/**
 * 硬约束 4：有 tool_calls 的轮次不跑钩子 B。
 * 用 ChatEngine 跑一个「每轮都冒工具调用」的流，统计模块被以 phase=b 调用的次数。
 */
private fun toolTurnSkipsHookBTests() {
    val source = ModuleRegistry.of(listOf(pkg("fmt", listOf("output"))))
    var phaseBCalls = 0
    val executor = JsExecutor { _, inputJson, _ ->
        if (parseJson(inputJson)?.str("phase") == "b") phaseBCalls++
        """{"type":"rewrite","text":"不该发生"}"""
    }
    val dispatcher = ModuleDispatcher(
        source = source,
        executor = executor,
        bridges = GatedHostBridge.factory(source, AuditLog(), { null })
    )
    val engine = ChatEngine(
        dispatcher = dispatcher,
        builder = RequestBuilder(source),
        transport = ChatTransport { _, onLine ->
            onLine(
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"calc","arguments":"{}"}}]}}]}"""
            )
            StreamOutcome.success()
        },
        tools = ToolRunner { _, _ -> "{}" }
    )
    val result = engine.runTurn(
        HookSession(id = "s", activeHardModules = listOf("fmt"), maxToolRounds = 2),
        "hi"
    )
    check("engine: 全是工具轮时被截断", result is TurnResult.Truncated, "实际 ${result::class.simpleName}")
    eq("hookB: 工具轮次一次都没 dispatchOutput", 0, phaseBCalls)
}
