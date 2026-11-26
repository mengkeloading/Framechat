package mychat.framework.sandbox

import mychat.framework.directive.DirectiveEngine
import mychat.framework.hook.AuditLog
import mychat.framework.hook.GatedHostBridge
import mychat.framework.hook.HookAResult
import mychat.framework.hook.HookBResult
import mychat.framework.hook.HookRunner
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
    skillHub(dispatcher, session)
    skillNovelForced(dispatcher, session)
    noFluffViaRunner(registry)
    directiveEndToEnd(registry)

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
    val s = session.copy(activeHardModules = listOf("attach_context"))

    // 没被强制 → 不注入
    val normal = dispatcher.dispatchInput(s, "用英文回答")
    check("attach_context: 非强制时不注入", normal is HookAResult.Pass, "实际 ${normal::class.simpleName}")

    // 被「附加：」强制（框架已剥前缀，模块只看到正文）
    val forced = dispatcher.dispatchInput(
        s, "用英文回答", selected = setOf("attach_context"), forced = true
    )
    check("attach_context: 强制时返回 context", forced is HookAResult.Context, "实际 ${forced::class.simpleName}")
    val extra = (forced as? HookAResult.Context)?.extra.orEmpty()
    check("attach_context: extra 带上附加内容", extra.contains("用英文回答"), extra)
    check("attach_context: 被包装成技能块", extra.contains("id: attach_context"))
}

/** skill_novel 只在被前缀强制时返回 context（看 ctx.forced）。 */
private fun skillNovelForced(dispatcher: ModuleDispatcher, session: HookSession) {
    val novel = session.copy(activeHardModules = listOf("skill_novel"))

    val normal = dispatcher.dispatchInput(novel, "随便问一句")
    check("skill_novel: 非强制时不注入", normal is HookAResult.Pass, "实际 ${normal::class.simpleName}")

    val forced = dispatcher.dispatchInput(
        novel, "写一段雨夜",
        selected = setOf("skill_novel"), forced = true
    )
    check("skill_novel: 强制时返回 context", forced is HookAResult.Context, "实际 ${forced::class.simpleName}")
    val extra = (forced as? HookAResult.Context)?.extra.orEmpty()
    check("skill_novel: 注入了 SKILL.md 正文", extra.contains("小说写作规范"), extra.take(120))
    check("skill_novel: 被包装成技能块", extra.contains("id: skill_novel"))
}

/** 真模块 no-fluff：把结尾套话包成 [[!HIDE]]，由指令引擎删掉。 */
private fun noFluff(dispatcher: ModuleDispatcher, session: HookSession) {
    val s = session.copy(activeHardModules = listOf("no_fluff"))
    val withFluff = "这是结论。\n\n希望这些能对你有帮助。"
    val result = dispatcher.dispatchOutput(s, withFluff)
    check("no_fluff: 返回 rewrite", result is HookBResult.Rewrite, "实际 ${result::class.simpleName}")
    val text = result.text
    eq(
        "no_fluff: 产出 HIDE 包裹",
        "这是结论。\n[[!HIDE]]希望这些能对你有帮助。[[!/HIDE]]",
        text
    )
    check("no_fluff: 不再当场删（原文仍在）", text.contains("希望这些能对你有帮助"))

    // 带实质内容的段落（含「核心/是」）一律不包
    val substantive = "总之，协程的三个核心是 A、B、C。"
    val kept = dispatcher.dispatchOutput(s, substantive)
    check("no_fluff: 「总之…核心是」不包", kept is HookBResult.Pass, "实际 ${kept::class.simpleName}")
    eq("no_fluff: 实质性段落原样返回", substantive, kept.text)

    // 整条都是寒暄（单段）时，包了会变成整条 HIDE → 模块返回 null
    val greeting = "你好！有什么需要帮忙的吗？直接说就行。"
    val greetingKept = dispatcher.dispatchOutput(s, greeting)
    check("no_fluff: 整条寒暄不包", greetingKept is HookBResult.Pass, "实际 ${greetingKept::class.simpleName}")
    eq("no_fluff: 寒暄原样返回", greeting, greetingKept.text)

    // 模型常用单换行分段：没有空行时只看最后一行
    val singleBreak = "你好！有什么我可以帮你的吗？\n如果你接着想聊 Kotlin 协程，可以说…其他问题也随时问"
    val single = dispatcher.dispatchOutput(s, singleBreak)
    check("no_fluff: 单换行也包", single is HookBResult.Rewrite, "实际 ${single::class.simpleName}")
    eq(
        "no_fluff: 只包最后一行",
        "你好！有什么我可以帮你的吗？\n[[!HIDE]]如果你接着想聊 Kotlin 协程，可以说…其他问题也随时问[[!/HIDE]]",
        single.text
    )
}

/** 真模块 skill_hub：靠 ModuleLoader shim 拿包列表，不再静默 null。 */
private fun skillHub(dispatcher: ModuleDispatcher, session: HookSession) {
    val result = dispatcher.dispatchInput(
        session.copy(activeHardModules = listOf("skill_hub")), "帮我写个故事",
        selected = setOf("skill_hub")
    )
    check("skill_hub: 返回 context", result is HookAResult.Context, "实际 ${result::class.simpleName}")
    val extra = (result as? HookAResult.Context)?.extra.orEmpty()
    check("skill_hub: 有技能目录", extra.contains("【技能目录·自动装载】"), extra)
    check("skill_hub: 列出代码审查", extra.contains("代码审查"))
    check("skill_hub: 列出小说写作", extra.contains("小说写作"))
    check("skill_hub: 注入了正文而非只列名", extra.contains("## 技能："))
    check("skill_hub: 被包装成技能块", extra.contains("id: skill_hub"))
    eq(
        "skill_hub: 没有执行异常",
        0,
        dispatcher.traces.count { it.module == "skill_hub" && it.message.contains("执行异常") }
    )
}

/** 真模块 no_fluff 经 HookRunner.output 改写（钩子 B 端到端）。 */
private fun noFluffViaRunner(registry: ModuleRegistry) {
    val runner = HookRunner(registry, RhinoJsExecutor(registry))
    val session = HookSession(id = "s-b", activeHardModules = listOf("no_fluff"))
    val raw = "这是结论。\n\n希望这些能对你有帮助。"
    val result = runner.output(session, raw)
    check("runner+b: 返回 rewrite", result is HookBResult.Rewrite, "实际 ${result::class.simpleName}")
    check("runner+b: 输出包含 [[!HIDE]]", result.text.contains("[[!HIDE]]"))
    eq(
        "runner+b: 正文 + 包裹后的套话",
        "这是结论。\n[[!HIDE]]希望这些能对你有帮助。[[!/HIDE]]",
        result.text
    )
    check("runner+b: 留下 hookB 轨迹", runner.lastTrace().any { it.tag == "hookB" })
}

/** 端到端：真模块产出 HIDE → 真引擎删掉 → 只剩正文。 */
private fun directiveEndToEnd(registry: ModuleRegistry) {
    val runner = HookRunner(registry, RhinoJsExecutor(registry))
    val session = HookSession(id = "s-e2e", activeHardModules = listOf("no_fluff"))

    val hooked = runner.output(session, "正文\n\n希望这些能对你有帮助。")
    eq(
        "e2e: 模块产出 HIDE 包裹",
        "正文\n[[!HIDE]]希望这些能对你有帮助。[[!/HIDE]]",
        hooked.text
    )

    val result = DirectiveEngine().process(hooked.text)
    eq("e2e: 引擎处理后只剩正文", "正文", result.text)
    check(
        "e2e: 有 HIDE applied 记录",
        result.log.any { it.name == "HIDE" && it.status == "applied" }
    )
    check("e2e: 套话确实不在结果里", !result.text.contains("希望"))
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
