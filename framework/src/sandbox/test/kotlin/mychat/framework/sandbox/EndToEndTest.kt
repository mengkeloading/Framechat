package mychat.framework.sandbox

import mychat.framework.directive.DirectiveEngine
import mychat.framework.hook.AuditLog
import mychat.framework.hook.FormatRemediation
import mychat.framework.hook.GatedHostBridge
import mychat.framework.hook.HookAResult
import mychat.framework.hook.HookRunner
import mychat.framework.hook.HookSession
import mychat.framework.hook.ModuleManifest
import mychat.framework.hook.ModulePackage
import mychat.framework.hook.ModuleRegistry
import java.io.File
import kotlin.system.exitProcess

/**
 * 组装测试：把 ChatViewModel 里那条链按**同样的顺序**在 JVM 里串一遍。
 *
 * 不覆盖的部分：ChatViewModel 本身的持久化、UI 状态机、真的网络流
 * （那些依赖 Android 运行时，JVM 测试跑不了）。
 * 覆盖的部分：路由 → 注入块 → 钩子 B → 格式检查 → 补救 → 指令清理，
 * 以及这条链上每一环写出的日志顺序。
 */
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

/** 预设回复序列；每次都记录调用次数。 */
private class FakeLlm(private val replies: List<String>) {
    var callCount = 0
    val prompts = mutableListOf<String>()

    fun call(prompt: String): String? {
        prompts += prompt
        val reply = replies.getOrNull(callCount)
        callCount++
        return reply
    }
}

/** 模拟 App 里那份 usage log，同时带上调用顺序。 */
private class FakeLog {
    val lines = mutableListOf<String>()

    fun record(line: String) {
        lines += line
    }

    fun contains(fragment: String): Boolean = lines.any { it.contains(fragment) }

    fun indexOf(fragment: String): Int = lines.indexOfFirst { it.contains(fragment) }
}

fun main() {
    val repoRoot = System.getProperty("mychat.repoRoot")
        ?: run {
            println("SKIP  需要 -Dmychat.repoRoot")
            return
        }
    val registry = ModuleRegistry.scan(
        File(repoRoot, "android/app/src/main/assets/modules")
    ).registry

    scenarioA(registry)
    scenarioB(registry)
    scenarioC(registry)
    scenarioD(registry)
    silentSkipLogs()
    noopEngineScenario(registry)

    println()
    println("PASS $passed / ${passed + failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  - $it") }
        exitProcess(1)
    }
}

// ---------- 组装 ----------

private class Harness(
    registry: ModuleRegistry,
    val llm: FakeLlm,
    executors: mychat.framework.hook.ExecutorRegistry =
        mychat.framework.hook.ExecutorRegistry(
            listOf(mychat.framework.hook.JsExecutorAdapter(RhinoJsExecutor(registry)))
        ),
) {
    val log = FakeLog()
    private val runner = HookRunner(
        source = registry,
        executors = executors,
        onFormatCheck = { id, ok ->
            log.record("format_check=" + id + ":" + (if (ok) "ok" else "missing"))
        },
        onDebug = { log.record(it) }
    )
    private val engine = DirectiveEngine()

    /** 与 ChatViewModel 一致：路由入参 → sel → 钩子 A → 流式 → 钩子 B → 检查 → 补救 → 指令。 */
    fun turn(
        enabled: List<String>,
        selected: Set<String>,
        input: String,
    ): String {
        log.record("routeInput=\"" + input + "\" len=" + input.length)
        log.record("sel=prefix:false route=" + selected + " reason=hit selected=" + selected)

        val session = HookSession(id = "e2e", activeHardModules = enabled)
        val hookA = runner.input(session, input, selected = selected)
        val hookText = hookA.text.ifEmpty { input }

        val hookExtra = (hookA as? HookAResult.Context)?.extra
        if (!hookExtra.isNullOrEmpty()) {
            log.record("inject:len=" + hookExtra.length)
            log.record(hookExtra.take(600))
        }

        val answer = llm.call(buildString {
            append("[user] ").append(hookText)
            if (!hookExtra.isNullOrEmpty()) append("\n[system] ").append(hookExtra)
        }) ?: ""

        val hookB = runner.output(
            session = session,
            rawText = answer,
            input = hookText,
            selected = selected,
        )
        val afterB = hookB.text

        // 指令清理
        var finalAnswer = engine.process(afterB).text.ifBlank { afterB }

        // 格式补救
        val missing = runner.missingFormats(selected, finalAnswer).firstOrNull()
        val structure = missing?.let { runner.formatStructureOf(it) }
        if (missing != null && !structure.isNullOrBlank()) {
            log.record("remediate:start " + missing)
            val remedied = llm.call(
                FormatRemediation.buildPrompt(structure, finalAnswer).answer
            )
            val result = FormatRemediation.apply(
                finalAnswer, remedied, runner.formatMarksOf(missing)
            )
            log.record("remediate:done " + missing + " " + result.status)
            log.record("remediate:cost +" + result.calls + " call")
            if (result.status == "replaced") finalAnswer = result.text
        }

        log.record("loaded=" + enabled.joinToString(",") + " used=unknown (inferred:multiple)")
        return finalAnswer
    }
}

private const val GOOD = "[必须改] a!! 会崩\n- 在哪：第 1 行\n- 为什么：NPE\n- 改成什么：a?.b"
private const val BAD = "1. **问题**：a!! 会崩"

private fun harness(registry: ModuleRegistry, vararg replies: String): Harness =
    Harness(registry, FakeLlm(replies.toList()))

// ---------- A. 命中 + 格式合规 ----------

private fun scenarioA(registry: ModuleRegistry) {
    val h = harness(registry, GOOD)
    val answer = h.turn(listOf("skill_code"), setOf("skill_code"), "这段代码有问题吗")

    eq("A: 只调一次模型", 1, h.llm.callCount)
    check("A: 日志含 format_check=ok", h.log.contains("format_check=skill_code:ok"), h.log.lines.toString())
    check("A: 没有触发补救", !h.log.contains("remediate:start"))
    check("A: 回答保留格式", answer.contains("[必须改]"), answer)
    assertOrder(h, "A")
}

// ---------- B. 不合规 → 补救成功 ----------

private fun scenarioB(registry: ModuleRegistry) {
    val h = harness(registry, BAD, GOOD)
    val answer = h.turn(listOf("skill_code"), setOf("skill_code"), "这段代码有问题吗")

    eq("B: 调两次模型", 2, h.llm.callCount)
    check("B: 日志含 format_check=missing", h.log.contains("format_check=skill_code:missing"))
    check("B: 日志含 replaced", h.log.contains("remediate:done skill_code replaced"))
    check("B: 最终回答是补救后的", answer.contains("[必须改]"), answer)
    check("B: 补救不再包含粗体旧格式", !answer.contains("**问题**"), answer)
    assertOrder(h, "B")
}

// ---------- C. 补救也失败 ----------

private fun scenarioC(registry: ModuleRegistry) {
    val h = harness(registry, BAD, BAD)
    val answer = h.turn(listOf("skill_code"), setOf("skill_code"), "这段代码有问题吗")

    eq("C: 还是只补一次", 2, h.llm.callCount)
    check("C: 日志含 still_missing", h.log.contains("remediate:done skill_code still_missing"))
    eq("C: 用原回答", BAD, answer)
    assertOrder(h, "C")
}

// ---------- D. 无预处理模块 ----------

private fun scenarioD(registry: ModuleRegistry) {
    val h = harness(registry, "你好呀")
    val answer = h.turn(emptyList(), emptySet(), "你好")

    eq("D: 只调一次模型", 1, h.llm.callCount)
    check("D: sel 行显示空集合", h.log.contains("sel=prefix:false route=[] reason=hit selected=[]"))
    eq("D: 回答原样透传", "你好呀", answer)
    check("D: 没有注入日志", !h.log.contains("inject:len"))
}

// ---------- 引擎可替换 ----------

/** 换成 Noop 引擎后链路仍通：知识型模块不依赖脚本引擎。 */
private fun noopEngineScenario(registry: ModuleRegistry) {
    val h = Harness(
        registry = registry,
        llm = FakeLlm(listOf("写成这样\n\n希望有帮助")),
        executors = mychat.framework.hook.ExecutorRegistry(listOf(NoopExecutor)),
    )
    val answer = h.turn(listOf("skill_novel"), setOf("skill_novel"), "帮我写个故事")

    eq("noop: LLM 仍被调用一次", 1, h.llm.callCount)
    check("noop: 注入块仍产出", h.log.contains("inject:len"), h.log.lines.toString())
    check("noop: 没有抛异常（拿到回答）", answer.isNotEmpty())
    check(
        "noop: 日志里能看到注入长度",
        h.log.lines.any { it.startsWith("inject:len=") },
        h.log.lines.toString()
    )
}

// ---------- 静默跳过的日志 ----------

private fun silentSkipLogs() {
    val log = mutableListOf<String>()

    val marked = ModulePackage(
        manifest = ModuleManifest(
            "fmt_nostruct", "有标记没结构",
            hooks = emptyList(),
            formatMarks = listOf("[必须改]")
        ),
        body = "",
        scripts = emptyMap(),
        references = mapOf("SKILL.md" to "# 规范\n\n## 何时使用\n随便\n")
    )
    val noSkill = ModulePackage(
        manifest = ModuleManifest("no_skill", "没有规范", hooks = emptyList()),
        body = "",
        scripts = emptyMap(),
        references = emptyMap()
    )
    val source = ModuleRegistry.of(listOf(marked, noSkill))
    val runner = HookRunner(
        source = source,
        executor = RhinoJsExecutor(source),
        onDebug = { line -> log += line }
    )

    // 1. 声明了格式标记，但 SKILL.md 里没有「输出格式」节 → 补救被跳过要说出来
    val target = runner.remediationTarget(setOf("fmt_nostruct"), "没有标记的回答")
    check("skip: 拿不到结构时返回 null", target == null)
    check("skip: 记 remediate:skip", log.any { it.startsWith("remediate:skip") }, log.toString())

    // 2. 输入长得像前缀，但没有模块命中 → 用户需要知道为什么没生效
    log.clear()
    runner.input(
        HookSession(id = "s", activeHardModules = listOf("fmt_nostruct")),
        "附加：用英文回答",
        selected = emptySet()
    )
    check("skip: 记 prefix:miss", log.any { it.startsWith("prefix:miss") }, log.toString())

    // 3. 被选中但没有 SKILL.md → 注入跳过要留痕
    log.clear()
    runner.input(
        HookSession(id = "s", activeHardModules = listOf("no_skill")),
        "随便问一句",
        selected = setOf("no_skill")
    )
    check("skip: 记 inject:skip", log.any { it.startsWith("inject:skip") }, log.toString())
}

// ---------- 公共断言 ----------

private fun assertOrder(h: Harness, tag: String) {
    if (h.log.contains("inject:len")) {
        val sel = h.log.indexOf("sel=")
        val inject = h.log.indexOf("inject:len")
        val fmt = h.log.indexOf("format_check")
        check("$tag: 顺序 sel → inject → format_check", sel < inject && inject < fmt,
            "sel=$sel inject=$inject fmt=$fmt")
    }
    val order = listOf("routeInput", "sel=", "format_check")
    var last = -1
    var ok = true
    for (key in order) {
        val idx = h.log.indexOf(key)
        if (idx < 0 || idx < last) {
            ok = false
            break
        }
        last = idx
    }
    check("$tag: 日志顺序 routeInput → sel → format_check", ok, h.log.lines.toString())
}
