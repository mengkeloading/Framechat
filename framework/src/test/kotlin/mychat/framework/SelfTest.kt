package mychat.framework

import mychat.framework.core.ChatEngine
import mychat.framework.core.ChatTransport
import mychat.framework.core.ErrorMapper
import mychat.framework.core.ReplyCollector
import mychat.framework.core.RequestBuilder
import mychat.framework.core.SseParser
import mychat.framework.core.StreamOutcome
import mychat.framework.core.ToolRunner
import mychat.framework.core.TurnResult
import mychat.framework.hook.Attachment
import mychat.framework.hook.AuditLog
import mychat.framework.hook.Caps
import mychat.framework.hook.FrontMatter
import mychat.framework.hook.GatedHostBridge
import mychat.framework.hook.HookAResult
import mychat.framework.hook.HookBResult
import mychat.framework.hook.HookSession
import mychat.framework.hook.HostBridge
import mychat.framework.hook.HostBridgeFactory
import mychat.framework.hook.HostException
import mychat.framework.hook.JsExecutor
import mychat.framework.hook.Message
import mychat.framework.hook.ModuleDispatcher
import mychat.framework.hook.ModuleManifest
import mychat.framework.hook.NeedResolver
import mychat.framework.hook.ModulePackage
import mychat.framework.hook.ModuleRegistry
import mychat.framework.hook.ModuleSource
import mychat.framework.hook.SessionData
import mychat.framework.json.JsonValue
import mychat.framework.json.parseJson
import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess

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
    jsonTests()
    frontMatterTests()
    registryTests()
    bridgeTests()
    dispatcherTests()
    contextWrappingTests()
    builderTests()
    sseTests()
    engineTests()

    println()
    println("PASS $passed / ${passed + failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  - $it") }
        exitProcess(1)
    }
}

// ---------- JSON ----------

private fun jsonTests() {
    val text = """{"a":1,"b":[true,null,"x"],"c":{"d":1.5}}"""
    val v = parseJson(text)
    check("json: 对象解析", v is JsonValue.Obj)
    eq("json: 整数", 1, v?.int("a"))
    eq("json: 数组长度", 3, v?.arr("b")?.size)
    eq("json: 嵌套数字", 1.5, (v?.obj("c")?.get("d") as? JsonValue.Num)?.value)
    eq("json: 原样重编码", parseJson(v?.encode())?.int("a"), 1)

    eq("json: 整数值不带小数点", """{"n":3}""", JsonValue.of("n" to JsonValue.Num(3.0)).encode())
    eq("json: 转义", "\"a\\\"b\\\\c\\nd\"", JsonValue.Str("a\"b\\c\nd").encode())
    eq("json: 反解转义", "a\"b\\c\nd", (parseJson("\"a\\\"b\\\\c\\nd\"") as? JsonValue.Str)?.value)
    eq("json: unicode 转义", "A", (parseJson("\"\\u0041\"") as? JsonValue.Str)?.value)
    eq("json: 中文原样", "中文", (parseJson("\"中文\"") as? JsonValue.Str)?.value)

    check("json: 非法输入返回 null", parseJson("hello") == null)
    check("json: 尾部垃圾返回 null", parseJson("""{"a":1} x""") == null)
    check("json: 未闭合返回 null", parseJson("""{"a":1""") == null)
    check("json: 裸控制字符返回 null", parseJson("\"a\nb\"") == null)

    val deep = "[".repeat(80) + "]".repeat(80)
    check("json: 过深嵌套被拒", parseJson(deep) == null)

    eq("json: null 字段被丢弃", "{}", JsonValue.of("a" to null).encode())
    eq("json: 取缺失键", null, parseJson("{}")?.str("nope"))
}

// ---------- front matter ----------

private const val MODULE_MD = """---
id: local_calc
name: 本地计算
description: 直接算数，不联网
version: 1.2.0
hooks: [input]
inject: summary
capabilities:
  clock.now: 1
  resource.read: 3
---
# 本地计算

正文内容。
"""

private fun frontMatterTests() {
    val fm = FrontMatter.parse(MODULE_MD)
    check("fm: 能解析", fm != null)
    eq("fm: 标量", "local_calc", fm?.scalars?.get("id"))
    eq("fm: 行内列表原样保留", "[input]", fm?.scalars?.get("hooks"))
    eq("fm: 嵌套映射", "3", fm?.maps?.get("capabilities")?.get("resource.read"))
    check("fm: 正文保留", fm?.body?.contains("正文内容") == true)

    val m = ModuleManifest.from(fm!!, "fallback")
    eq("manifest: id", "local_calc", m.id)
    eq("manifest: name", "本地计算", m.name)
    eq("manifest: inject", "summary", m.inject)
    eq("manifest: 能力配额", 3, m.capabilities[Caps.RESOURCE_READ])
    eq("manifest: 行内列表解析成钩子", listOf("input"), m.hooks)
    check("manifest: 声明了 input 钩子", m.handles("input"))
    check("manifest: 没声明 output 钩子", !m.handles("output"))

    val listForm = FrontMatter.parse("---\nhooks:\n  - input\n  - output\n---\n")!!
    eq("fm: 块状列表", listOf("input", "output"), listForm.lists["hooks"])

    check("fm: 缺 front matter 返回 null", FrontMatter.parse("# 标题\n正文") == null)
    check("fm: 未闭合返回 null", FrontMatter.parse("---\nid: x\n") == null)
}

// ---------- registry ----------

private fun tempDir(): File = Files.createTempDirectory("mychat-test").toFile()

private fun writeModule(
    root: File,
    dir: String,
    md: String,
    script: String? = null,
    refs: Map<String, String> = emptyMap()
) {
    val d = File(root, dir)
    File(d, "script").mkdirs()
    File(d, "reference").mkdirs()
    File(d, "MODULE.md").writeText(md)
    if (script != null) File(d, "script/main.js").writeText(script)
    refs.forEach { (name, content) ->
        val f = File(File(d, "reference"), name)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }
}

private fun registryTests() {
    val root = tempDir()
    writeModule(
        root, "local_calc",
        """
        |---
        |id: local_calc
        |name: 本地计算
        |description: 算数
        |hooks: [input]
        |capabilities:
        |  resource.read: 2
        |---
        |正文
        """.trimMargin(),
        script = "function (inputJson, host) { return null; }",
        refs = mapOf("skill.md" to "技能正文")
    )
    writeModule(
        root, "formatter",
        """
        |---
        |id: formatter
        |name: 格式化
        |hooks: [output]
        |---
        """.trimMargin(),
        script = "function (inputJson, host) { return null; }"
    )
    writeModule(
        root, "broken",
        """
        |---
        |id: broken
        |name: 坏了
        |hooks: [input]
        |---
        """.trimMargin()
    )
    File(root, "README.txt").writeText("不是模块")

    val scan = ModuleRegistry.scan(root)
    val reg = scan.registry

    eq("registry: 模块数", 3, reg.all().size)
    eq("registry: 按钩子过滤", listOf("local_calc"), reg.idsForPhase(listOf("local_calc", "formatter"), "input"))
    eq("registry: 未启用的模块不参与", emptyList<String>(), reg.idsForPhase(emptyList(), "input"))
    eq("registry: reference 可读", "技能正文", reg.getReference("local_calc", "skill.md"))
    eq("registry: MODULE.md 可读", true, reg.getReference("local_calc", "MODULE.md")?.contains("正文"))
    eq("registry: 不存在的 reference", null, reg.getReference("local_calc", "nope.md"))
    check("registry: 缺脚本被报出来", scan.problems.any { it.contains("broken") })
    eq("registry: 脚本解析", "main.js", reg.resolveScript("formatter")?.second)
    eq("registry: 显式脚本路径", "main.js", reg.resolveScript("formatter/script/main.js")?.second)
    eq("registry: 未知脚本", null, reg.resolveScript("ghost"))
}

// ---------- host bridge ----------

private fun sourceOf(vararg packages: ModulePackage): ModuleSource =
    ModuleRegistry.of(packages.toList())

private fun pkg(
    id: String,
    hooks: List<String> = emptyList(),
    inject: String = "none",
    caps: Map<String, Int> = emptyMap(),
    body: String = "",
    description: String = "",
    tools: List<JsonValue> = emptyList(),
    refs: Map<String, String> = emptyMap()
): ModulePackage = ModulePackage(
    manifest = ModuleManifest(
        id = id,
        name = id,
        description = description,
        version = "1.0.0",
        hooks = hooks,
        inject = inject,
        entry = "main.js",
        capabilities = caps,
        tools = tools
    ),
    body = body,
    scripts = mapOf("main.js" to "x"),
    references = refs
)

private fun bridgeTests() {
    val p = pkg(
        "m1",
        caps = mapOf(Caps.RESOURCE_READ to 1, Caps.CLOCK_NOW to 1, Caps.DATA_SESSION to 5),
        body = "正文"
    )
    val source = sourceOf(p)
    val audit = AuditLog()
    val session = HookSession(id = "s1", title = "测试")
    val bridge = GatedHostBridge(
        pkg = p,
        source = source,
        audit = audit,
        sessionData = { SessionData(session, (1..25).map { Message("user", "m$it") }) },
        clock = { "2026-01-01T00:00:00Z" }
    )

    eq(
        "bridge: resource.read",
        "正文",
        bridge.requestLocalAction(Caps.RESOURCE_READ, """{"name":"MODULE.md"}""")
    )
    check("bridge: 审计记录成功", audit.snapshot().last().ok)

    var quotaHit = false
    try {
        bridge.requestLocalAction(Caps.RESOURCE_READ, """{"name":"MODULE.md"}""")
    } catch (e: HostException) {
        quotaHit = true
    }
    check("bridge: 配额用尽抛异常", quotaHit)
    eq("bridge: 配额失败写审计", "quota_exceeded", audit.snapshot().last().error)

    eq("bridge: clock.now", "2026-01-01T00:00:00Z", bridge.requestLocalAction(Caps.CLOCK_NOW, "{}"))

    val sessionJson = parseJson(bridge.requestLocalAction(Caps.DATA_SESSION, "{}"))
    eq("bridge: data.session 带会话", "s1", sessionJson?.obj("session")?.str("id"))
    eq("bridge: data.session 只给 20 条", 20, sessionJson?.arr("messages")?.size)

    // skill.read：只能读别的模块的 SKILL.md
    val reader = pkg("reader", caps = mapOf(Caps.SKILL_READ to 1))
    val target = pkg("target_mod", refs = mapOf("SKILL.md" to "目标模块的 SKILL"))
    val readBridge = GatedHostBridge(reader, sourceOf(reader, target), audit, { null })
    eq(
        "bridge: skill.read 拿到正文",
        "目标模块的 SKILL",
        readBridge.requestLocalAction(Caps.SKILL_READ, """{"id":"target_mod"}""")
    )
    eq("bridge: skill.read 记下目标", "target_mod", audit.snapshot().last().target)

    var skillQuota = false
    try {
        readBridge.requestLocalAction(Caps.SKILL_READ, """{"id":"target_mod"}""")
    } catch (e: HostException) {
        skillQuota = true
    }
    check("bridge: skill.read 配额生效", skillQuota)

    var skillMissing = false
    val freshReader = pkg("reader2", caps = mapOf(Caps.SKILL_READ to 3))
    val freshBridge = GatedHostBridge(freshReader, sourceOf(freshReader, target), audit, { null })
    try {
        freshBridge.requestLocalAction(Caps.SKILL_READ, """{"id":"ghost"}""")
    } catch (e: HostException) {
        skillMissing = true
    }
    check("bridge: skill.read 不存在时抛异常", skillMissing)
    eq("bridge: skill.read 失败记 not_found", "not_found", audit.snapshot().last().error)

    val noCap = ModulePackage(
        ModuleManifest("m2", "m2", capabilities = mapOf("fake.action" to 1)),
        "", mapOf("main.js" to "x"), emptyMap()
    )
    val b2 = GatedHostBridge(noCap, sourceOf(noCap), audit, { null })
    var undeclared = false
    try {
        b2.requestLocalAction(Caps.CLOCK_NOW, "{}")
    } catch (e: HostException) {
        undeclared = true
    }
    check("bridge: 未声明能力抛异常", undeclared)
    eq("bridge: 未声明写审计", "not_declared", audit.snapshot().last().error)

    var notImpl = false
    try {
        b2.requestLocalAction("fake.action", "{}")
    } catch (e: HostException) {
        notImpl = true
    }
    check("bridge: 未实现的能力抛异常", notImpl)
}

// ---------- dispatcher ----------

private class FakeExecutor(
    private val handler: (String, String, HostBridge) -> String?
) : JsExecutor {
    val calls = mutableListOf<String>()

    override fun execute(scriptRef: String, inputJson: String, host: HostBridge): String? {
        calls += scriptRef
        return handler(scriptRef, inputJson, host)
    }
}

private fun dispatcherFor(
    source: ModuleSource,
    audit: AuditLog = AuditLog(),
    sessionData: () -> SessionData? = { null },
    executor: JsExecutor
): ModuleDispatcher = ModuleDispatcher(
    source = source,
    executor = executor,
    bridges = GatedHostBridge.factory(source, audit, sessionData)
)

private fun replyOf(json: String): (String, String, HostBridge) -> String? =
    { _, _, _ -> json }

private fun dispatcherTests() {
    val passPkg = pkg("p", hooks = listOf("input"))
    val localPkg = pkg("loc", hooks = listOf("input"))
    val ctxPkg = pkg("ctx", hooks = listOf("input"))
    val outPkg = pkg("out", hooks = listOf("output"))
    val invPkg = pkg("inv", hooks = listOf("output"))
    val targetPkg = pkg("target", hooks = listOf("output"))
    val boomPkg = pkg("boom", hooks = listOf("input"))
    val source = sourceOf(passPkg, localPkg, ctxPkg, outPkg, invPkg, targetPkg, boomPkg)

    val executor = FakeExecutor { ref, _, _ ->
        when {
            ref.startsWith("p/") -> """{"type":"pass","text":"改过的输入"}"""
            ref.startsWith("loc/") -> """{"type":"local","text":"本地答案"}"""
            ref.startsWith("ctx/") -> """{"type":"context","text":"上下文输入","extra":"额外约束"}"""
            ref.startsWith("out/") -> """{"type":"rewrite","text":"改写后"}"""
            ref.startsWith("inv/") -> """{"type":"invoke","moduleName":"target","payload":"转过去"}"""
            ref.startsWith("target/") -> """{"type":"rewrite","text":"目标模块改写"}"""
            ref.startsWith("boom/") -> throw IllegalStateException("模块炸了")
            else -> null
        }
    }
    val d = dispatcherFor(source, executor = executor)

    val s0 = HookSession(id = "s")
    val r0 = d.dispatchInput(s0, "原始输入")
    eq("dispatch: 未启用任何模块时原样放行", "原始输入", r0.text)
    check("dispatch: 未启用时返回 pass", r0 is HookAResult.Pass)

    val sPass = HookSession(id = "s", activeHardModules = listOf("p"))
    val rPass = d.dispatchInput(sPass, "原始输入")
    eq("dispatch: pass 也能改写入参", "改过的输入", rPass.text)

    val sLocal = HookSession(id = "s", activeHardModules = listOf("p", "loc"))
    val beforeLocal = executor.calls.size
    val rLocal = d.dispatchInput(sLocal, "算 1+1")
    check("dispatch: local 短路", rLocal is HookAResult.Local)
    eq("dispatch: local 文本", "本地答案", rLocal.text)
    eq("dispatch: local 后不再调用后续模块", 2, executor.calls.size - beforeLocal)

    val sCtx = HookSession(id = "s", activeHardModules = listOf("ctx"))
    val rCtx = d.dispatchInput(sCtx, "原始输入", selected = setOf("ctx"))
    check("dispatch: context 返回", rCtx is HookAResult.Context)
    eq("dispatch: context 改写输入", "上下文输入", rCtx.text)
    check(
        "dispatch: context extra 保留原文",
        (rCtx as HookAResult.Context).extra.contains("额外约束")
    )

    val sBoom = HookSession(id = "s", activeHardModules = listOf("boom"))
    val rBoom = d.dispatchInput(sBoom, "原始输入")
    check("dispatch: 执行异常 fail-open 为 pass", rBoom is HookAResult.Pass)
    eq("dispatch: fail-open 保留原文", "原始输入", rBoom.text)

    val sBad = HookSession(id = "s", activeHardModules = listOf("bad"))
    val badSource = sourceOf(pkg("bad", hooks = listOf("input")))
    val dBad = dispatcherFor(badSource, executor = FakeExecutor(replyOf("不是 JSON")))
    check("dispatch: 非法 JSON fail-open", dBad.dispatchInput(sBad, "x") is HookAResult.Pass)

    val dIllegal = dispatcherFor(
        sourceOf(pkg("bad", hooks = listOf("input"))),
        executor = FakeExecutor(replyOf("""{"type":"rewrite","text":"y"}"""))
    )
    check("dispatch: A 阶段返回 rewrite 视为 fail-open", dIllegal.dispatchInput(sBad, "x") is HookAResult.Pass)

    val sOut = HookSession(id = "s", activeHardModules = listOf("out"))
    val rOut = d.dispatchOutput(sOut, "模型输出")
    check("dispatch: B 阶段 rewrite", rOut is HookBResult.Rewrite)
    eq("dispatch: rewrite 文本", "改写后", rOut.text)

    val sInv = HookSession(id = "s", activeHardModules = listOf("inv"))
    val rInv = d.dispatchOutput(sInv, "模型输出")
    check("dispatch: invoke 转到目标模块", rInv is HookBResult.Rewrite)
    eq("dispatch: invoke 目标结果", "目标模块改写", rInv.text)

    val cycleSource = sourceOf(pkg("loop", hooks = listOf("output")))
    val dCycle = dispatcherFor(
        cycleSource,
        executor = FakeExecutor { _, _, _ -> """{"type":"invoke","moduleName":"loop"}""" }
    )
    val rCycle = dCycle.dispatchOutput(HookSession(id = "s", activeHardModules = listOf("loop")), "原文")
    check("dispatch: invoke 去重后回落 pass", rCycle is HookBResult.Pass)
    eq("dispatch: 去重后保留原文", "原文", rCycle.text)

    val chain = (1..5).map { pkg("hop$it", hooks = listOf("output")) }
    val hopSource = sourceOf(*chain.toTypedArray())
    val dHop = dispatcherFor(
        hopSource,
        executor = FakeExecutor { ref, _, _ ->
            val n = ref.substringAfter("hop").substringBefore("/").toInt()
            """{"type":"invoke","moduleName":"hop${n + 1}"}"""
        }
    )
    val rHop = dHop.dispatchOutput(HookSession(id = "s", activeHardModules = listOf("hop1")), "原文")
    check("dispatch: invoke 深度上限后回落 pass", rHop is HookBResult.Pass)
    check("dispatch: 有 invoke 中止轨迹", dHop.traces.any { it.message.contains("invoke 中止") })

    // 信封与 ctx
    var seen: JsonValue? = null
    val envSource = sourceOf(pkg("env", hooks = listOf("input")))
    val dEnv = dispatcherFor(
        envSource,
        executor = FakeExecutor { _, input, _ -> seen = parseJson(input); null }
    )
    val sEnv = HookSession(id = "sess-1", activeHardModules = listOf("env"))
    dEnv.dispatchInput(
        sEnv, "用户输入",
        history = (1..25).map { Message("user", "h$it") },
        attachments = listOf(Attachment("a.txt", "text/plain", 10, text = "不该被看到")),
        forced = true
    )
    eq("信封: forced 标记透传", true, seen?.obj("ctx")?.bool("forced"))
    eq("信封: phase", "a", seen?.str("phase"))
    eq("信封: text", "用户输入", seen?.str("text"))
    eq("信封: sessionId", "sess-1", seen?.obj("ctx")?.str("sessionId"))
    eq("信封: 历史最多 20 条", 20, seen?.obj("ctx")?.arr("messages")?.size)
    eq("信封: 会话对象可见", "sess-1", seen?.obj("ctx")?.obj("session")?.str("id"))
    eq("信封: 附件只给元数据", null, seen?.obj("ctx")?.arr("attachments")?.firstOrNull()?.str("text"))
    eq("信封: 附件名可见", "a.txt", seen?.obj("ctx")?.arr("attachments")?.firstOrNull()?.str("name"))
    eq("信封: 钩子 A 不带 ctx.input", null, seen?.obj("ctx")?.str("input"))

    var seenB: JsonValue? = null
    val dB = dispatcherFor(
        sourceOf(pkg("envb", hooks = listOf("output"))),
        executor = FakeExecutor { _, input, _ -> seenB = parseJson(input); null }
    )
    dB.dispatchOutput(HookSession(id = "s", activeHardModules = listOf("envb")), "模型输出", "用户输入")
    eq("信封: 钩子 B 带 ctx.input", "用户输入", seenB?.obj("ctx")?.str("input"))
}

// ---------- 技能包装 ----------

private fun contextWrappingTests() {
    val one = pkg("one", hooks = listOf("input"), description = "甲模块的说明")
    val two = pkg("two", hooks = listOf("input"), description = "乙模块的说明")
    val source = sourceOf(one, two)
    val executor = FakeExecutor { ref, _, _ ->
        when {
            ref.startsWith("one/") -> """{"type":"context","text":"","extra":"规范甲正文"}"""
            ref.startsWith("two/") -> """{"type":"context","text":"","extra":"规范乙正文"}"""
            else -> null
        }
    }
    val d = dispatcherFor(source, executor = executor)

    // 单模块
    val single = d.dispatchInput(
        HookSession(id = "s", activeHardModules = listOf("one")), "输入",
        selected = setOf("one")
    )
    val singleExtra = (single as HookAResult.Context).extra
    check("wrap: 有开头标记", singleExtra.contains("【已加载技能】"))
    check("wrap: 有 id", singleExtra.contains("id: one"))
    check("wrap: 有 name", singleExtra.contains("name: one"))
    check("wrap: 有说明", singleExtra.contains("说明：甲模块的说明"))
    check("wrap: 有结束标记", singleExtra.contains("【技能结束】"))
    check("wrap: extra 原文一字不差", singleExtra.contains("规范甲正文"))

    // 两模块：两个独立块
    val both = d.dispatchInput(
        HookSession(id = "s", activeHardModules = listOf("one", "two")), "输入",
        selected = setOf("one", "two")
    )
    val bothExtra = (both as HookAResult.Context).extra
    eq("wrap: 两个块", 2, Regex("【已加载技能】").findAll(bothExtra).count())
    eq("wrap: 两个结束标记", 2, Regex("【技能结束】").findAll(bothExtra).count())
    check("wrap: 两段原文都在", bothExtra.contains("规范甲正文") && bothExtra.contains("规范乙正文"))
    check("wrap: 块顺序跟启用顺序", bothExtra.indexOf("甲模块的说明") < bothExtra.indexOf("乙模块的说明"))

    // 使用声明：有技能才追加，且在所有块之后
    check("wrap: 有技能时追声声明要求", bothExtra.contains("【使用声明】"))
    check("wrap: 声明在最后一个块之后", bothExtra.lastIndexOf("【技能结束】") < bothExtra.indexOf("【使用声明】"))
    check("wrap: 声明带例子", bothExtra.contains("[[!USED:skill_code]]"))

    // 未被选中的模块：context 仍然执行，但结果被丢弃
    val notSelected = d.dispatchInput(
        HookSession(id = "s", activeHardModules = listOf("one")), "输入"
    )
    check("wrap: 未选中时不收集 context", notSelected is HookAResult.Pass, "实际 ${notSelected::class.simpleName}")

    // 多模块都返回 context，但只选中一个 → 只包装被选中的
    val onlyTwo = d.dispatchInput(
        HookSession(id = "s", activeHardModules = listOf("one", "two")), "输入",
        selected = setOf("two")
    )
    val onlyTwoExtra = (onlyTwo as? HookAResult.Context)?.extra.orEmpty()
    eq("wrap: 只包装选中模块", 1, Regex("【已加载技能】").findAll(onlyTwoExtra).count())
    check("wrap: 选中模块的内容在", onlyTwoExtra.contains("规范乙正文"))
    check("wrap: 未选中模块的内容不在", !onlyTwoExtra.contains("规范甲正文"))

    // 注入职责上移：hooks: [] 的知识型模块被选中时，框架直接读 SKILL.md
    val knowledge = ModulePackage(
        manifest = ModuleManifest("knowledge", "知识包", description = "纯知识"),
        body = "",
        scripts = emptyMap(),
        references = mapOf("SKILL.md" to "知识包正文")
    )
    val emptySkill = ModulePackage(
        manifest = ModuleManifest("empty_skill", "空的", description = "没 SKILL"),
        body = "",
        scripts = emptyMap(),
        references = emptyMap()
    )
    val injection = dispatcherFor(
        sourceOf(knowledge, emptySkill),
        executor = FakeExecutor { _, _, _ -> null }
    )

    val injected = injection.dispatchInput(
        HookSession(id = "s", activeHardModules = listOf("knowledge")), "输入",
        selected = setOf("knowledge")
    )
    check("inject: 知识型模块也能注入", injected is HookAResult.Context, "实际 ${injected::class.simpleName}")
    val injectedExtra = (injected as? HookAResult.Context)?.extra.orEmpty()
    check("inject: 用的是 SKILL.md 正文", injectedExtra.contains("知识包正文"), injectedExtra)
    check("inject: 被包装成技能块", injectedExtra.contains("id: knowledge"))
    check(
        "inject: 留下 skill_injected 轨迹",
        injection.traces.any { it.module == "knowledge" && it.message == "skill_injected" }
    )

    // 选中但既没 extra 也没 SKILL.md → trace，不产生空块
    val nothing = injection.dispatchInput(
        HookSession(id = "s", activeHardModules = listOf("empty_skill")), "输入",
        selected = setOf("empty_skill")
    )
    check("inject: 没内容时不产生空块", nothing is HookAResult.Pass, "实际 ${nothing::class.simpleName}")
    check(
        "inject: 记 selected_but_empty",
        injection.traces.any { it.module == "empty_skill" && it.message == "selected_but_empty" }
    )

    // needs：选中模块要求 normalize
    eq(
        "needs: 选中模块要求 normalize",
        true,
        NeedResolver.needsNormalize(
            listOf(
                ModulePackage(
                    ModuleManifest("n1", "n1", needs = listOf("normalize")),
                    "", emptyMap(), emptyMap()
                )
            ),
            setOf("n1")
        )
    )
    eq(
        "needs: 未选中时不算",
        false,
        NeedResolver.needsNormalize(
            listOf(
                ModulePackage(
                    ModuleManifest("n1", "n1", needs = listOf("normalize")),
                    "", emptyMap(), emptyMap()
                )
            ),
            emptySet()
        )
    )
    eq("needs: 没写 needs 时不算", false, NeedResolver.needsNormalize(listOf(one), setOf("one")))

    // 无技能（pass）→ 不追声明
    val passOnly = dispatcherFor(
        sourceOf(pkg("plain", hooks = listOf("input"))),
        executor = FakeExecutor { _, _, _ -> """{"type":"pass"}""" }
    )
    val passed = passOnly.dispatchInput(HookSession(id = "s", activeHardModules = listOf("plain")), "输入")
    check("wrap: 无技能时仍是 pass", passed is HookAResult.Pass)
    eq("wrap: pass 不产生 extra", "输入", passed.text)
}

// ---------- request builder ----------

private fun builderTests() {
    val skill = pkg("skill", inject = "full", body = "技能正文")
    val helper = pkg("helper", inject = "summary", description = "帮个忙")
    val toolPkg = pkg(
        "toolly",
        hooks = listOf("input"),
        tools = listOf(JsonValue.of("type" to JsonValue.Str("function")))
    )
    val source = sourceOf(skill, helper, toolPkg)
    val builder = RequestBuilder(source)

    val session = HookSession(id = "s", activeSoftModules = listOf("skill", "helper"), activeHardModules = listOf("toolly"))
    val built = builder.build(session, emptyList(), userInput = "你好")
    val systemText = (built.messages.first() as JsonValue.Obj).str("content").orEmpty()
    check("builder: full 注入正文", systemText.contains("技能正文"))
    check("builder: summary 注入一行", systemText.contains("- helper：帮个忙"))
    eq("builder: 用户消息在最后", "user", (built.messages.last() as JsonValue.Obj).str("role"))
    eq("builder: 请求体 model", "deepseek-chat", built.body.str("model"))
    eq("builder: 请求体 stream", true, built.body.bool("stream"))
    eq("builder: tools 来自 manifest", 1, built.body.arr("tools")?.size)

    val overridden = RequestBuilder(source).build(
        session.copy(systemPrompt = "只用这个"), emptyList(), userInput = "hi"
    )
    eq(
        "builder: systemPrompt 覆盖软模块注入",
        "只用这个",
        (overridden.messages.first() as JsonValue.Obj).str("content")
    )

    val noTools = builder.build(session.copy(activeHardModules = emptyList()), emptyList(), userInput = "hi")
    eq("builder: 没声明 tools 就不带", null, noTools.body.arr("tools"))

    val history = listOf(Message("user", "上一句"), Message("assistant", "上一答"))
    val withHistory = builder.build(session.copy(activeSoftModules = emptyList()), history, userInput = "本轮")
    eq("builder: 历史顺序", "上一句", (withHistory.messages[0] as JsonValue.Obj).str("content"))
    eq("builder: 本轮输入追加", 3, withHistory.messages.size)

    val dedupe = builder.build(
        session.copy(activeSoftModules = emptyList()),
        listOf(Message("user", "本轮")), userInput = "本轮"
    )
    eq("builder: 重复的输入不追加", 1, dedupe.messages.size)

    val withAttach = builder.build(
        session.copy(activeSoftModules = emptyList()),
        emptyList(), "看附件", attachments = listOf(Attachment("a.txt", text = "文件内容"))
    )
    val attachMsg = (withAttach.messages.last() as JsonValue.Obj)
    eq("builder: 附件作为 system", "system", attachMsg.str("role"))
    check("builder: 附件内容进请求", attachMsg.str("content")?.contains("文件内容") == true)

    val withExtra = builder.build(
        session.copy(activeSoftModules = emptyList()),
        emptyList(), "输入", hookAExtra = "额外约束"
    )
    eq("builder: 额外约束是最后一条", "额外约束", (withExtra.messages.last() as JsonValue.Obj).str("content"))

    val withToolMsg = builder.build(
        session.copy(activeSoftModules = emptyList()),
        emptyList(), null,
        pendingToolMessages = listOf(Message("tool", "结果", "call_1"))
    )
    eq("builder: 工具消息带 tool_call_id", "call_1", (withToolMsg.messages.last() as JsonValue.Obj).str("tool_call_id"))

    val longHistory = (1..20).map { Message(if (it % 2 == 1) "user" else "assistant", "字".repeat(200) + it) }
    val trimmed = builder.build(session.copy(activeSoftModules = emptyList(), contextBudget = 200), longHistory, "hi")
    check("builder: 超预算会裁剪", trimmed.stats.dropped > 0, "dropped=${trimmed.stats.dropped}")
    check("builder: 裁剪后至少留一条历史", trimmed.stats.kept >= 1)
    val firstKept = (trimmed.messages.firstOrNull { (it as JsonValue.Obj).str("role") != "system" } as? JsonValue.Obj)
    eq("builder: 历史不以孤立助手回复开头", "user", firstKept?.str("role"))

    val assistantOnly = listOf(Message("user", "问"), Message("assistant", "答"))
    val keptOne = RequestBuilder(source, defaultBudget = 30).build(
        session.copy(activeSoftModules = emptyList()), assistantOnly, null
    )
    eq("builder: 至少保留最后一条", "答", (keptOne.messages.last() as JsonValue.Obj).str("content"))
}

// ---------- SSE ----------

private fun sseTests() {
    val line = """data: {"choices":[{"delta":{"content":"你"}}]}"""
    eq("sse: 取正文", "你", SseParser.extractDelta(line))
    eq("sse: 非 data 行", null, SseParser.extractDelta("event: ping"))
    eq("sse: DONE", null, SseParser.extractDelta("data: [DONE]"))
    check("sse: DONE 识别", SseParser.isDone("data: [DONE]"))
    eq(
        "sse: 取思考",
        "想一下",
        SseParser.extractReasoning("""data: {"choices":[{"delta":{"reasoning_content":"想一下"}}]}""")
    )
    val tc = SseParser.extractToolCall(
        """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"calc","arguments":"{\"a\":"}}]}}]}"""
    )
    eq("sse: 工具名", "calc", tc?.name)
    eq("sse: 工具 id", "c1", tc?.id)

    val collector = ReplyCollector()
    collector.accept("""data: {"choices":[{"delta":{"content":"答"}}]}""")
    collector.accept("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"calc","arguments":"{\"a\":"}}]}}]}""")
    collector.accept("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1}"}}]}}]}""")
    collector.accept("""data: {"choices":[{"delta":{"reasoning_content":"内部"}}]}""")
    eq("collector: 正文", "答", collector.content())
    eq("collector: 思考单独存放", "内部", collector.reasoning())
    eq("collector: 工具参数拼接", """{"a":1}""", collector.toolCalls().firstOrNull()?.argsJson)
    eq("collector: 工具数量", 1, collector.toolCalls().size)
}

// ---------- engine ----------

private fun engineFor(
    source: ModuleSource,
    transport: ChatTransport,
    tools: ToolRunner = ToolRunner { _, _ -> """{"ok":true}""" },
    executor: JsExecutor
): ChatEngine {
    val dispatcher = dispatcherFor(source, executor = executor)
    return ChatEngine(dispatcher, RequestBuilder(source), transport, tools)
}

private fun engineTests() {
    // 本地短路：一次请求都不发
    var transportCalls = 0
    val localSource = sourceOf(pkg("calc", hooks = listOf("input")))
    val localEngine = engineFor(
        localSource,
        { _, _ -> transportCalls++; StreamOutcome.success() },
        executor = FakeExecutor(replyOf("""{"type":"local","text":"等于 444"}"""))
    )
    val local = localEngine.runTurn(HookSession(id = "s", activeHardModules = listOf("calc")), "12*37")
    check("engine: 本地短路", local is TurnResult.Local)
    eq("engine: 本地答案", "等于 444", (local as TurnResult.Local).text)
    eq("engine: 本地短路不发请求", 0, transportCalls)

    // 钩子 B 改写
    val outSource = sourceOf(pkg("fmt", hooks = listOf("output")))
    val rewriteEngine = engineFor(
        outSource,
        { _, onLine ->
            onLine("""data: {"choices":[{"delta":{"content":"原始输出"}}]}""")
            StreamOutcome.success()
        },
        executor = FakeExecutor(replyOf("""{"type":"rewrite","text":"整理后"}"""))
    )
    val rewritten = rewriteEngine.runTurn(HookSession(id = "s", activeHardModules = listOf("fmt")), "hi")
    check("engine: 钩子 B 生效", rewritten is TurnResult.Reply && rewritten.rewritten)
    eq("engine: 改写结果", "整理后", (rewritten as TurnResult.Reply).text)

    // 工具轮次不跑钩子 B
    val bodies = mutableListOf<String>()
    var call = 0
    val toolSource = sourceOf(pkg("fmt", hooks = listOf("output")))
    val toolEngine = engineFor(
        toolSource,
        { body, onLine ->
            bodies += body
            call++
            if (call == 1) {
                onLine("""data: {"choices":[{"delta":{"content":"先算一下"}}]}""")
                onLine("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"calc","arguments":"{\"a\":1}"}}]}}]}""")
            } else {
                onLine("""data: {"choices":[{"delta":{"content":"结果是 1"}}]}""")
            }
            StreamOutcome.success()
        },
        tools = ToolRunner { name, args -> """{"tool":"$name","args":$args}""" },
        executor = FakeExecutor(replyOf("""{"type":"rewrite","text":"整理后"}"""))
    )
    val toolTurn = toolEngine.runTurn(
        HookSession(id = "s", activeHardModules = listOf("fmt")), "算一下"
    )
    eq("engine: 工具轮次跑两轮", 2, bodies.size)
    check("engine: 第二轮请求带工具结果", bodies[1].contains("\"role\":\"tool\""))
    check("engine: 第二轮请求带未改写的助手原文", bodies[1].contains("先算一下"))
    check("engine: 工具轮次不被钩子 B 改写", !bodies[1].contains("整理后"))
    eq("engine: 最终答案是钩子 B 改写后的", "整理后", (toolTurn as TurnResult.Reply).text)
    eq("engine: 轮次计数", 2, toolTurn.rounds)

    // 工具轮次上限
    val cappedEngine = engineFor(
        toolSource,
        { _, onLine ->
            onLine("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"calc","arguments":"{}"}}]}}]}""")
            StreamOutcome.success()
        },
        executor = FakeExecutor(replyOf("""{"type":"pass"}"""))
    )
    val capped = cappedEngine.runTurn(
        HookSession(id = "s", activeHardModules = listOf("fmt"), maxToolRounds = 2), "一直调工具"
    )
    check("engine: 超过工具轮次上限", capped is TurnResult.Truncated)
    eq("engine: 中断时的轮次", 2, (capped as TurnResult.Truncated).rounds)

    // 传输失败
    val failEngine = engineFor(
        sourceOf(pkg("fmt", hooks = listOf("output"))),
        { _, _ -> StreamOutcome.failure("http_401 unauthorized") },
        executor = FakeExecutor { _, _, _ -> "null" }
    )
    val failed = failEngine.runTurn(HookSession(id = "s"), "hi")
    check("engine: 传输失败返回 Failed", failed is TurnResult.Failed)
    val failedResult = failed as TurnResult.Failed
    check("engine: 401 归为鉴权文案", failedResult.message.contains("鉴权"))
    check("engine: 401 不重试", !failedResult.retryable)

    // 错误映射
    eq("error: 429", "请求过于频繁（429），请稍后重试", ErrorMapper.map("http_429 rate"))
    eq("error: 网络", "网络异常，请检查连接", ErrorMapper.map("timeout after 30s"))
    check("error: 工具失败回 JSON", ErrorMapper.mapJsError("炸了").contains("\"ok\":false"))
}
