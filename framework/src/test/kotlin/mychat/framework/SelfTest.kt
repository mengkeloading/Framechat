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
import mychat.framework.butler.PromptByIntent
import mychat.framework.log.AppendLog
import mychat.framework.memory.compress.Compressor
import mychat.framework.memory.consolidate.CompressibleTag
import mychat.framework.memory.consolidate.ConsolidateSettings
import mychat.framework.memory.consolidate.ConsolidationSource
import mychat.framework.memory.consolidate.DefaultConsolidator
import mychat.framework.debug.DebugCommands
import mychat.framework.hook.ReferenceInliner
import mychat.framework.butler.TranslatePolicy
import mychat.framework.profile.ProfileCommand
import mychat.framework.profile.ProfileFormatter
import mychat.framework.profile.UserProfile
import mychat.framework.memory.TagNaming
import mychat.framework.memory.compress.DefaultCompressor
import mychat.framework.memory.compress.Decay
import mychat.framework.memory.compress.LevelPolicy
import mychat.framework.hook.AuditLog
import mychat.framework.hook.ConstraintText
import mychat.framework.hook.Caps
import mychat.framework.hook.DescriptionCollision
import mychat.framework.hook.FormatCheckResult
import mychat.framework.hook.FormatRemediation
import mychat.framework.hook.ManifestAudit
import mychat.framework.hook.FrontMatter
import mychat.framework.hook.GatedHostBridge
import mychat.framework.hook.HookAResult
import mychat.framework.hook.HookBResult
import mychat.framework.hook.HookRunner
import mychat.framework.hook.HookSession
import mychat.framework.hook.HostBridge
import mychat.framework.hook.HostBridgeFactory
import mychat.framework.hook.HostException
import mychat.framework.hook.JsExecutor
import mychat.framework.hook.Message
import mychat.framework.hook.ModuleDispatcher
import mychat.framework.hook.ModuleManifest
import mychat.framework.hook.NeedResolver
import mychat.framework.hook.JudgeWarning
import mychat.framework.hook.OutputJudge
import mychat.framework.hook.TokenBudget
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
    forbidRequireTests()
    guardTests()
    remediationTests()
    formatCheckMissingTests()
    constraintTests()
    intentInjectionTests()
    outputJudgeTests()
    appendLogTests()
    compressTests()
    consolidateTests()
    debugCommandTests()
    routeLogTests()
    referenceTests()
    directoryTests()
    translateTests()
    profileTests()
    tagNamingTests()
    promptTests()
    manifestAuditTests()
    formatCheckTests()
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

// ---------- 格式检查（只记录） ----------

private fun formatCheckTests() {
    val marked = ModulePackage(
        manifest = ModuleManifest(
            "fmt", "格式模块", hooks = listOf("output"),
            formatMarks = listOf("[必须改]", "[建议改]")
        ),
        body = "", scripts = emptyMap(), references = emptyMap()
    )
    val plain = ModulePackage(
        manifest = ModuleManifest("plain", "无声明", hooks = listOf("output")),
        body = "", scripts = emptyMap(), references = emptyMap()
    )
    val d = dispatcherFor(
        sourceOf(marked, plain),
        executor = FakeExecutor { _, _, _ -> """{"type":"pass"}""" }
    )
    val recorded = mutableListOf<String>()
    val dLogged = ModuleDispatcher(
        source = sourceOf(marked, plain),
        executor = FakeExecutor { _, _, _ -> """{"type":"pass"}""" },
        bridges = GatedHostBridge.factory(sourceOf(marked, plain), AuditLog(), { null }),
        onFormatCheck = { result ->
            recorded += "format_check=" + result.moduleId + ":" + result.logValue()
        }
    )
    val session = HookSession(id = "s", activeHardModules = listOf("fmt"))

    dLogged.dispatchOutput(
        session, "[必须改] 第 3 行有越界风险\n[建议改] 可以抽个函数", selected = setOf("fmt")
    )
    check(
        "format: ok 写进日志",
        recorded.any { it == "format_check=fmt:ok" },
        "recorded=$recorded"
    )

    // 调试行：B 入口 + marks 条数，用来判断“哪一环没跑”
    val debugLines = mutableListOf<String>()
    val dDebug = ModuleDispatcher(
        source = sourceOf(marked, plain),
        executor = FakeExecutor { _, _, _ -> """{"type":"pass"}""" },
        bridges = GatedHostBridge.factory(sourceOf(marked, plain), AuditLog(), { null }),
        onDebug = { debugLines += it }
    )
    dDebug.dispatchOutput(session, "[必须改] x\n[建议改] y", selected = setOf("fmt"))
    check(
        "format: marks 条数已记录",
        debugLines.contains("fmt:marks id=fmt size=2"),
        "lines=$debugLines"
    )

    dLogged.dispatchOutput(session, "就是有点问题", selected = setOf("fmt"))
    check(
        "format: missing 写进日志",
        recorded.any { it == "format_check=fmt:missing" },
        "recorded=$recorded"
    )

    d.dispatchOutput(session, "[必须改] 第 3 行有越界风险\n[建议改] 抽个函数", selected = setOf("fmt"))
    eq(
        "format: 含全部标记记 ok",
        "ok",
        d.traces.lastOrNull { it.tag == "format_check" }?.message
    )

    d.clearTraces()
    d.dispatchOutput(session, "就是有点问题", selected = setOf("fmt"))
    eq(
        "format: 不含标记记 missing",
        "missing",
        d.traces.lastOrNull { it.tag == "format_check" }?.message
    )

    d.clearTraces()
    d.dispatchOutput(session, "任意输出", selected = setOf("plain"))
    eq("format: 没声明的模块不检查", 0, d.traces.count { it.tag == "format_check" })

    d.clearTraces()
    d.dispatchOutput(session, "任意输出", selected = emptySet())
    eq("format: 没选中就不检查", 0, d.traces.count { it.tag == "format_check" })
}

// ---------- forbid / require 注入 ----------

private fun forbidRequireTests() {
    val strict = ModulePackage(
        manifest = ModuleManifest(
            "strict", "严格模块", description = "有约束的模块", hooks = emptyList(),
            forbid = listOf("不要开场白", "不要结尾总结"),
            requires = listOf("必须给出最小修改版")
        ),
        body = "", scripts = emptyMap(), references = mapOf("SKILL.md" to "规范正文")
    )
    val loose = ModulePackage(
        manifest = ModuleManifest("loose", "宽松模块", description = "无约束", hooks = emptyList()),
        body = "", scripts = emptyMap(), references = mapOf("SKILL.md" to "宽松正文")
    )
    val d = dispatcherFor(
        sourceOf(strict, loose),
        executor = FakeExecutor { _, _, _ -> null }
    )

    val both = d.dispatchInput(
        HookSession(id = "s", activeHardModules = listOf("strict")), "输入",
        selected = setOf("strict")
    )
    val extra = (both as? HookAResult.Context)?.extra.orEmpty()
    check("forbid: 含【禁止】节", extra.contains("【禁止】"), extra)
    check("forbid: 含第一条禁止项", extra.contains("- 不要开场白"), extra)
    check("require: 含【要求】节", extra.contains("【要求】"))
    check("require: 含第一条要求项", extra.contains("- 必须给出最小修改版"))
    check("forbid: 排在【要求】之前", extra.indexOf("【禁止】") < extra.indexOf("【要求】"))

    val none = d.dispatchInput(
        HookSession(id = "s", activeHardModules = listOf("loose")), "输入",
        selected = setOf("loose")
    )
    val noneExtra = (none as? HookAResult.Context)?.extra.orEmpty()
    check("forbid: 空声明不出【禁止】", !noneExtra.contains("【禁止】"))
    check("forbid: 空声明不出【要求】", !noneExtra.contains("【要求】"))

    // 硬编码的格式提醒必须已删干净
    val repoRoot = System.getProperty("mychat.repoRoot")
    if (repoRoot != null) {
        val source = File(repoRoot, "framework/src/main/kotlin/mychat/framework/hook/ModuleDispatcher.kt")
        check("forbid: 能找到 ModuleDispatcher 源码", source.isFile)
        if (source.isFile) {
            check(
                "forbid: 硬编码格式提醒已删除",
                !source.readText().contains("严格遵守上述规范中的输出格式要求")
            )
        }
    }
}

// ---------- 发送前的 token 粗估 / 描述撞车 ----------

private fun guardTests() {
    eq("token: 短文本不超限", false, TokenBudget.exceeds("你好"))
    eq("token: 超长文本会拦", true, TokenBudget.exceeds("字".repeat(13000)))
    eq("token: 估算按 1.5 字符一个 token", 6666, TokenBudget.estimate("字".repeat(10000)))

    fun pkg(id: String, desc: String) = ModulePackage(
        manifest = ModuleManifest(id, id, description = desc),
        body = "", scripts = emptyMap(), references = mapOf("SKILL.md" to "规范")
    )
    val similar = DescriptionCollision.check(
        listOf(pkg("a", "同样的描述"), pkg("b", "同样的描述"))
    ) { _, _ -> 0.99f }
    check("desc: 高度相似会提醒", similar.any { it.startsWith("desc_similar") }, similar.toString())

    val distinct = DescriptionCollision.check(
        listOf(pkg("a", "写小说"), pkg("b", "算数"))
    ) { _, _ -> 0.4f }
    eq("desc: 不同描述不提醒", emptyList<String>(), distinct)

    // 没有 SKILL.md 的模块不参与比较
    val plain = ModulePackage(
        manifest = ModuleManifest("p", "p", description = "x"),
        body = "", scripts = emptyMap(), references = emptyMap()
    )
    eq(
        "desc: 无 SKILL.md 的模块不参与",
        emptyList<String>(),
        DescriptionCollision.check(listOf(plain, plain)) { _, _ -> 1f }
    )
}

// ---------- 指数衰减与层策略 ----------

private fun compressTests() {
    val day = 86_400_000L
    val t0 = 1_700_000_000_000L

    // 1. 同一时刻不变
    eq("decay: 0 天不变", 1.0f, Decay.strengthAt(1.0f, t0, t0, 0))
    eq("decay: 时钟回拨不变", 1.0f, Decay.strengthAt(1.0f, t0 + day, t0, 0))

    // 2. strength=1.0, readCount=0, 30 天 → e^(-30/65) ≈ 0.630
    val thirty = Decay.strengthAt(1.0f, t0, t0 + 30 * day, 0)
    check("decay: 30 天剩约 0.630", kotlin.math.abs(thirty - 0.630f) < 0.02f, thirty.toString())

    // 2b. 90 天正好落到 0.25 附近：τ=65 → e^(-90/65) ≈ 0.250
    val ninety = Decay.strengthAt(1.0f, t0, t0 + 90 * day, 0)
    check("decay: 90 天剩约 0.25", kotlin.math.abs(ninety - 0.25f) < 0.02f, ninety.toString())

    // 2c. 常用且强（s=3, r=10 → τ=245）：90 天后仍然很牢
    val sticky = Decay.strengthAt(3.0f, t0, t0 + 90 * day, 10)
    check("decay: 常用强标签 90 天后 > 0.6", sticky > 0.6f, sticky.toString())

    // 2d. 老标签（lastDecayAt=0）：锚点取 now，读出来强度不变
    check("decay: 锚点为 0 时不衰减", Decay.strengthAt(1.0f, t0, t0, 0) == 1.0f)

    // 3. 同样 30 天，强标签（τ=145）比弱标签（τ=65）掉得慢
    val strong = Decay.strengthAt(3.0f, t0, t0 + 30 * day, 0)
    val weak = Decay.strengthAt(1.0f, t0, t0 + 30 * day, 0)
    check("decay: 强度高的掉得慢", strong / 3.0f > weak / 1.0f, "strong=$strong weak=$weak")

    // 4. 同样 30 天、strength=1.0：readCount=10（τ=165）比 0（τ=65）掉得慢
    val used = Decay.strengthAt(1.0f, t0, t0 + 30 * day, 10)
    check("decay: 常用的掉得慢", used > weak, "used=$used weak=$weak")

    // 5. 巩固窗口：新建 1 小时不压
    eq(
        "level: 48 小时内不压",
        null,
        LevelPolicy.goalLevel(0.1f, 0, createdAt = t0, lastCompressedAt = t0, now = t0 + 3_600_000L)
    )

    // 6. 强度 0.15、已到 L3、60 天未压 → L4
    eq(
        "level: 极弱 60 天压到 L4",
        4,
        LevelPolicy.goalLevel(0.15f, 3, t0 - 100 * day, t0, t0 + 60 * day)
    )

    // 7. 强度 1.0、已到 L1、8 天未压 → L2
    eq(
        "level: 中等 8 天压到 L2",
        2,
        LevelPolicy.goalLevel(1.0f, 1, t0 - 100 * day, t0, t0 + 8 * day)
    )

    // 8b. 归档阈值 0.08
    eq("archive: 0.09 不归档", "name_only", mychat.framework.memory.TagStrength.depthOf(0.09f))
    eq("archive: 0.07 归档", "archived", mychat.framework.memory.TagStrength.depthOf(0.07f))

    // 7b. 逐层：L0 只压到 L1，不会一步跳 L2
    eq(
        "level: L0 先压 L1",
        1,
        LevelPolicy.goalLevel(1.0f, 0, t0, lastCompressedAt = t0, now = t0 + day, force = true)
    )
    eq(
        "level: L1 八天后压 L2",
        2,
        LevelPolicy.goalLevel(1.0f, 1, t0, lastCompressedAt = t0, now = t0 + 8 * day, force = true)
    )
    eq(
        "level: 已到目标层不压",
        null,
        LevelPolicy.goalLevel(1.0f, 2, t0, lastCompressedAt = t0, now = t0 + 99 * day, force = true)
    )
    eq(
        "level: 极弱也从 L1 开始",
        1,
        LevelPolicy.goalLevel(0.3f, 0, t0, lastCompressedAt = t0, now = t0 + day, force = true)
    )
    eq(
        "level: L1 不满七天不压",
        null,
        LevelPolicy.goalLevel(1.0f, 1, t0, lastCompressedAt = t0, now = t0 + 5 * day, force = true)
    )

    // 8a-1. force：新建 1 小时也能压（跳过巩固窗口）
    eq(
        "level: force 跳过巩固窗口",
        2,
        LevelPolicy.goalLevel(
            1.0f, 1, createdAt = t0, lastCompressedAt = t0 - 8 * day, now = t0 + 3_600_000L,
            force = true,
        )
    )
    // 8a-2. ignoreInterval：刚压过的也能重压
    eq(
        "level: ignoreInterval 忽略间隔",
        2,
        LevelPolicy.goalLevel(
            1.0f, 1, createdAt = t0 - 100 * day, lastCompressedAt = t0, now = t0,
            force = true, ignoreInterval = true,
        )
    )
    // 8a-3. 不加 force 时，新建 1 小时仍然不压
    eq(
        "level: 默认仍有巩固窗口",
        null,
        LevelPolicy.goalLevel(
            1.0f, 1, createdAt = t0, lastCompressedAt = t0 - 8 * day, now = t0 + 3_600_000L
        )
    )

    // 8. displayLevel 的边界
    eq("level: 1.5 → L1", 1, LevelPolicy.displayLevel(1.5f))
    eq("level: 1.499 → L2", 2, LevelPolicy.displayLevel(1.499f))
    eq("level: 0.5 → L2", 2, LevelPolicy.displayLevel(0.5f))
    eq("level: 0.499 → L3", 3, LevelPolicy.displayLevel(0.499f))
    eq("level: 0.2 → L3", 3, LevelPolicy.displayLevel(0.2f))
    eq("level: 0.199 → L4", 4, LevelPolicy.displayLevel(0.199f))
}

// ---------- 批量整理 ----------

private class FakeTags(private val tags: List<CompressibleTag>) : ConsolidationSource {
    override suspend fun compressible(): List<CompressibleTag> = tags
}

private class FakeSettings(var at: Long) : ConsolidateSettings {
    override suspend fun lastConsolidateAt(): Long = at
    override suspend fun setLastConsolidateAt(t: Long) { at = t }
}

private class FakeCompressor(
    private val result: (String) -> Boolean = { true },
) : Compressor {
    val calls = mutableListOf<String>()
    override suspend fun compress(tagId: String, toLevel: Int): Boolean {
        calls += tagId
        return result(tagId)
    }
}

private fun consolidateTests() = kotlinx.coroutines.runBlocking {
    val hour = 3_600_000L
    val day = 86_400_000L
    val now = 1_700_000_000_000L

    // 一个"该压"的标签（已到 L1、超过 7 天没压）和两个"不该压"的（刚建 / 刚压过）
    val compressible = CompressibleTag(
        id = "t1", strength = 1.0f, readCount = 0, lastDecayAt = now,
        currentLevel = 1, createdAt = now - 100 * day, lastCompressedAt = now - 8 * day,
    )
    val fresh = compressible.copy(id = "t2", createdAt = now - hour, lastCompressedAt = 0)
    val justDone = compressible.copy(id = "t3", lastCompressedAt = now - hour)

    val logs = mutableListOf<String>()
    var settings = FakeSettings(now - hour)
    var compressor = FakeCompressor()
    fun consolidator(tags: List<CompressibleTag>) = DefaultConsolidator(
        source = FakeTags(tags),
        compressor = compressor,
        settings = settings,
        clock = { now },
        log = { logs += it },
    )

    // 3. 12 小时内不跑
    val notDue = consolidator(listOf(compressible)).maybeRun()
    eq("consolidate: 未到期不扫描", 0, notDue.scanned)
    eq("consolidate: 未到期不调 compressor", 0, compressor.calls.size)

    // 4. 超过 12 小时执行，并更新 lastConsolidateAt
    settings = FakeSettings(now - 13 * hour)
    logs.clear()
    val due = consolidator(listOf(compressible)).maybeRun()
    eq("consolidate: 到期后扫描", 1, due.scanned)
    eq("consolidate: 到期后更新时间", now, settings.at)

    // 5. 三个标签：一个该压，两个不该压
    settings = FakeSettings(0)
    logs.clear()
    compressor = FakeCompressor()
    val report = consolidator(listOf(compressible, fresh, justDone)).runNow()
    eq("consolidate: 扫描 3 个", 3, report.scanned)
    eq("consolidate: 只压 1 个", 1, report.compressed)
    eq("consolidate: 跳过 2 个", 2, report.skipped)
    eq("consolidate: 跳过全是 notDue", 2, report.notDue)

    // 6/7. 一个失败不影响其他
    compressor = FakeCompressor { it == "t2" }
    val mixed = listOf(compressible, compressible.copy(id = "t2"))
    val partial = consolidator(mixed).runNow()
    eq("consolidate: 失败计数", 1, partial.failed)
    eq("consolidate: 成功计数", 1, partial.compressed)

    // 9. 单次上限：10 个到期标签，只压 5 个，capped=true
    settings = FakeSettings(0)
    logs.clear()
    val many = (1..10).map { compressible.copy(id = "m$it") }
    val cappedReport = DefaultConsolidator(
        source = FakeTags(many),
        compressor = FakeCompressor(),
        settings = settings,
        clock = { now },
        maxPerRun = 5,
        log = { logs += it },
    ).runNow()
    eq("consolidate: 上限 5", 5, cappedReport.compressed)
    eq("consolidate: capped=true", true, cappedReport.capped)

    // 10. 3 个到期标签，上限 5 → 全压，capped=false
    val three = (1..3).map { compressible.copy(id = "n$it") }
    val uncapped = DefaultConsolidator(
        source = FakeTags(three),
        compressor = FakeCompressor(),
        settings = settings,
        clock = { now },
        maxPerRun = 5,
        log = { logs += it },
    ).runNow()
    eq("consolidate: 未触顶", false, uncapped.capped)

    // 10b. 没有原文：noSource+1，不算失败
    val noMsg = compressible.copy(id = "empty", messageCount = 0)
    val emptyReport = DefaultConsolidator(
        source = FakeTags(listOf(noMsg)),
        compressor = FakeCompressor(),
        settings = FakeSettings(0),
        clock = { now },
        log = { logs += it },
    ).runNow()
    eq("consolidate: 无原文计 noSource", 1, emptyReport.noSource)
    eq("consolidate: 无原文不算失败", 0, emptyReport.failed)

    // 10c. 有原文但压缩失败：failed+1，noSource 不动
    val failing = DefaultConsolidator(
        source = FakeTags(listOf(compressible.copy(id = "has", messageCount = 3))),
        compressor = FakeCompressor { false },
        settings = FakeSettings(0),
        clock = { now },
        log = { logs += it },
    ).runNow()
    eq("consolidate: 失败计 failed", 1, failing.failed)
    eq("consolidate: 失败不算 noSource", 0, failing.noSource)

    // 10d. 强度还高 → 不需要压（noNeed），不算窗口
    val strong = compressible.copy(id = "strong", strength = 2.0f, messageCount = 5)
    val strongReport = DefaultConsolidator(
        source = FakeTags(listOf(strong)),
        compressor = FakeCompressor(),
        settings = FakeSettings(0),
        clock = { now },
        log = { logs += it },
    ).runNow()
    eq("consolidate: 强度高计 noNeed", 1, strongReport.notDueStrength)
    eq("consolidate: 强度高不算窗口", 0, strongReport.notDueWindow)

    // 10e. 刚建的标签 → 巩固窗口，不算 noNeed
    val newborn = compressible.copy(
        id = "newborn", strength = 1.0f, createdAt = now - 60_000L, messageCount = 5,
    )
    val freshReport = DefaultConsolidator(
        source = FakeTags(listOf(newborn)),
        compressor = FakeCompressor(),
        settings = FakeSettings(0),
        clock = { now },
        log = { logs += it },
    ).runNow()
    eq("consolidate: 窗口内计 window", 1, freshReport.notDueWindow)
    eq("consolidate: 窗口内不算 noNeed", 0, freshReport.notDueStrength)

    // 11. 抛异常的 compressor：failed+1，循环继续
    val throwing = object : Compressor {
        val seen = mutableListOf<String>()
        override suspend fun compress(tagId: String, toLevel: Int): Boolean {
            seen += tagId
            if (tagId == "x1") error("boom")
            return true
        }
    }
    val mixed2 = listOf(
        compressible.copy(id = "x1"),
        compressible.copy(id = "x2"),
    )
    val survived = DefaultConsolidator(
        source = FakeTags(mixed2),
        compressor = throwing,
        settings = FakeSettings(0),
        clock = { now },
        log = { logs += it },
    ).runNow()
    eq("consolidate: 异常计失败", 1, survived.failed)
    eq("consolidate: 异常后继续", 1, survived.compressed)
    eq("consolidate: 两个都试过", listOf("x1", "x2"), throwing.seen)

    // 8. 终态日志字段完整
    val done = logs.lastOrNull { it.contains("consolidate:done") }
    check(
        "consolidate: 终态日志完整",
        done != null && done.contains("scanned=") && done.contains("compressed=") &&
            done.contains("window=") && done.contains("noNeed=") && done.contains("noSource=") &&
            done.contains("failed=") && done.contains("ms="),
        done ?: "缺日志"
    )
}

// ---------- 调试命令 ----------

private fun debugCommandTests() {
    check("debug: 包里识别命令", DebugCommands.isCompressCommand("debug:compress", true))
    check(
        "debug: 允许前后空格",
        DebugCommands.isCompressCommand("  debug:compress\n", true)
    )
    check(
        "debug: release 包当普通消息",
        !DebugCommands.isCompressCommand("debug:compress", false)
    )
    check(
        "debug: 别的文本不是命令",
        !DebugCommands.isCompressCommand("压缩一下记忆", true)
    )
    check("debug: memory 在 debug 包识别", DebugCommands.isMemoryCommand("debug:memory", true))
    check(
        "debug: memory 在 release 当普通消息",
        !DebugCommands.isMemoryCommand("debug:memory", false)
    )

    val dump = mychat.framework.memory.MemoryDump.render(
        listOf(
            mychat.framework.memory.MemoryDump.Entry("MyChat 开发", 1, 1.234f, "用 Kotlin 做的"),
            mychat.framework.memory.MemoryDump.Entry("旧话题", 0, 0.05f, null),
        )
    )
    // 强度：0 锚点不能算出 0.00
    val now0 = 1_700_000_000_000L
    val fresh = mychat.framework.memory.TagStrength.currentOf(1.0f, now0, 0, now0)
    eq("strength: 刚建的就是 1.0", 1.0f, fresh)
    val legacy = mychat.framework.memory.TagStrength.currentOf(1.0f, 0L, 0, now0)
    eq("strength: 0 锚点按现在算", 1.0f, legacy)
    // 同一标签，两种算法必须一致
    val record = mychat.framework.memory.TagRecord("t", "名", vector = floatArrayOf(1f), strength = 1.0f, lastDecayAt = 0L)
    eq(
        "strength: current 与 currentOf 一致",
        mychat.framework.memory.TagStrength.current(record, now0),
        mychat.framework.memory.TagStrength.currentOf(1.0f, 0L, 0, now0)
    )
    val freshDump = mychat.framework.memory.MemoryDump.render(
        listOf(
            mychat.framework.memory.MemoryDump.Entry("新标签", 0, fresh, null)
        )
    )
    check("strength: dump 显示 1.00", freshDump.contains("strength=1.00"), freshDump)

    check(
        "debug: memory 输出含名字/层/强度/摘要",
        dump.contains("「MyChat 开发」") && dump.contains("L1") &&
            dump.contains("strength=1.23") && dump.contains("用 Kotlin 做的") &&
            dump.contains("（无）"),
        dump
    )
}

// ---------- 标签命名 ----------

private fun tagNamingTests() {
    // 截断落在「这个」上，虚词一并去掉
    eq("naming: 去「我在」", "做 MyChat", TagNaming.nameOf("我在做 MyChat 这个 App"))
    eq("naming: 去「帮我」", "写个故事", TagNaming.nameOf("帮我写个故事"))
    eq("naming: 12 字以内不动", "今天天气怎么样", TagNaming.nameOf("今天天气怎么样"))
    eq("naming: 全空给兜底", "未命名话题", TagNaming.nameOf("   "))
    eq("naming: 去结尾语气词", "这个东西怎么用", TagNaming.nameOf("这个东西怎么用呢"))
    // 截断后停在虚词上 → 去掉，别留半截"这"
    val cut = TagNaming.nameOf("我想用 Kotlin 重写这个模块吗")
    check("naming: 不以「这」结尾", !cut.endsWith("这"), cut)
    eq("naming: 末尾不是虚词就不动", "我看看这个函数", TagNaming.nameOf("我看看这个函数"))
    // 整句都是虚词：删空就退回截断结果，不能给空标题
    val particles = TagNaming.nameOf("吗呢吧啊了吗呢吧啊了吗呢吧")
    check("naming: 全是虚词不返回空", particles.isNotEmpty(), particles)
    check("naming: 长度不超 12", cut.length <= TagNaming.MAX_LENGTH, cut)
    check(
        "naming: 不超过 12 字",
        TagNaming.nameOf("我想问一下记忆系统的强度到底是怎么算的").length <= 12,
        TagNaming.nameOf("我想问一下记忆系统的强度到底是怎么算的")
    )
}

// ---------- 压缩 prompt ----------

private fun promptTests() {
    val prompt = DefaultCompressor.buildPrompt(
        input = "[user] 我决定用 Kotlin\n[assistant] 好的",
        maxLen = 200,
        level = 1,
    )
    check(
        "prompt: 含四要素",
        prompt.contains("决定") && prompt.contains("待办") &&
            prompt.contains("未决") && prompt.contains("事实"),
        prompt.take(200)
    )
    check("prompt: 要求丢弃寒暄", prompt.contains("寒暄"), prompt)
    check("prompt: 不用列表符号", prompt.contains("不要用列表符号"), prompt)
    check("prompt: 字数限制在里面", prompt.contains("200 字以内"), prompt)
    check(
        "prompt: L3 更短",
        DefaultCompressor.buildPrompt("x", 40, 3).contains("不超过 40 字"),
        DefaultCompressor.buildPrompt("x", 40, 3)
    )
}

// ---------- 角色卡 ----------

private fun profileTests() {
    // 1. 空 profile 不注入
    eq("profile: 空角色卡不注入", null, ProfileFormatter.format(UserProfile()))
    eq(
        "profile: 只有 style 也不注入",
        null,
        ProfileFormatter.format(UserProfile(style = "极简"))
    )

    // 2. 只有 role
    eq(
        "profile: 只有身份",
        // explain 默认 false，会显式写「不解释过程」：比不写更明确
        "【关于你】\n独立开发者；不解释过程",
        ProfileFormatter.format(UserProfile(role = "独立开发者"))
    )

    // 3. style=极简 → 偏好极简
    val minimal = ProfileFormatter.format(
        UserProfile(role = "独立开发者，做 Android App", style = "极简")
    )!!
    check("profile: 注入偏好", minimal.contains("偏好极简"), minimal)

    // 4. avoid → 讨厌 X
    val avoids = ProfileFormatter.format(
        UserProfile(role = "独立开发者", avoid = listOf("空话", "过度设计"))
    )!!
    check("profile: 注入讨厌项", avoids.contains("讨厌空话、过度设计"), avoids)

    // 5. 正文不超过 60 字
    val long = ProfileFormatter.format(
        UserProfile(
            role = "独立开发者，做 Android App，写小说，也做点后端",
            tech = listOf("Kotlin", "Android", "Ktor", "PostgreSQL"),
            style = "详细",
            avoid = listOf("空话", "过度设计", "啰嗦", "没有示例"),
            explain = true,
        )
    )!!
    val body = long.lines().drop(1).joinToString("\n")
    check(
        "profile: 正文不超过 60 字",
        body.length <= ProfileFormatter.MAX_LENGTH,
        body.length.toString()
    )

    // ---- debug:profile 命令 ----

    // 1. 无参数 → 显示当前值（解析成空 map）
    val bare = ProfileCommand.parse("debug:profile", debugBuild = true)
    eq("cmd: 裸命令解析为空参数", 0, bare?.size)
    check(
        "cmd: 裸命令显示当前值",
        ProfileCommand.describe(UserProfile(role = "独立开发者")).contains("独立开发者"),
        ProfileCommand.describe(UserProfile(role = "独立开发者"))
    )

    // 2. 带参数 → 只改给出的字段
    val args = ProfileCommand.parse(
        "debug:profile role=独立开发者 style=极简", debugBuild = true
    )!!
    val merged = ProfileCommand.apply(
        UserProfile(role = "旧的", style = "平衡", avoid = listOf("空话"), explain = true),
        args
    )
    eq("cmd: 覆盖身份", "独立开发者", merged.role)
    eq("cmd: 覆盖风格", "极简", merged.style)
    eq("cmd: 保留讨厌项", listOf("空话"), merged.avoid)
    check("cmd: 保留解释开关", merged.explain)

    // 3/4. 逗号分隔解析
    val csv = ProfileCommand.parse(
        "debug:profile tech=Kotlin,Android avoid=空话,过度设计", debugBuild = true
    )!!
    val csvProfile = ProfileCommand.apply(UserProfile(role = "x"), csv)
    eq("cmd: tech 解析成列表", listOf("Kotlin", "Android"), csvProfile.tech)
    eq("cmd: avoid 解析成列表", listOf("空话", "过度设计"), csvProfile.avoid)
    eq("cmd: 中文逗号也行", listOf("A", "B"), ProfileCommand.splitList("A，B"))

    // 5. release 下当普通消息
    eq("cmd: release 不识别", null, ProfileCommand.parse("debug:profile", debugBuild = false))
    eq("cmd: 普通文本不识别", null, ProfileCommand.parse("你好", debugBuild = true))

    // 回执格式
    val receipt = ProfileCommand.updated(
        UserProfile(role = "独立开发者", style = "极简", avoid = listOf("空话", "过度设计"))
    )
    check("cmd: 回执含身份", receipt.contains("身份：独立开发者"), receipt)
    check("cmd: 回执含风格", receipt.contains("风格：极简"), receipt)
    check("cmd: 回执含讨厌", receipt.contains("讨厌：空话、过度设计"), receipt)

    // 平衡是默认值，不写出来
    check(
        "profile: 默认风格不占字",
        !ProfileFormatter.format(UserProfile(role = "开发者"))!!.contains("偏好")
    )
}

// ---------- 翻译策略：模块优先 ----------

private fun translateTests() {
    // 命中模块：不翻译
    val hit = TranslatePolicy.decide(listOf("skill_code"))
    check("translate: 命中模块不翻译", !hit.run, hit.toString())
    eq("translate: 原因是 module_hit", "module_hit", hit.reason)

    // 没命中：可以翻译
    val none = TranslatePolicy.decide(emptyList())
    check("translate: 没命中才翻译", none.run, none.toString())
    eq("translate: 原因是 no_module", "no_module", none.reason)

    // 日志格式
    eq(
        "translate: skip 日志格式",
        "translate:skip reason=module_hit route=[skill_code, math_guard]",
        TranslatePolicy.skipLog("module_hit", listOf("skill_code", "math_guard"))
    )
    eq(
        "translate: run 日志格式",
        "translate:run profile=true ctx=true len=48→120",
        TranslatePolicy.runLog(profile = true, ctx = true, from = 48, to = 120)
    )
}

// ---------- 记忆目录 ----------

private fun directoryTests() {
    fun e(name: String, count: Int, strength: Float) =
        mychat.framework.memory.TagEntry(name, name, count, strength)

    // 1. 0 个标签：不注入
    eq("dir: 空不注入", null, mychat.framework.memory.DirectoryFormatter.format(emptyList()))

    // 2. 1 个标签：也不注入
    eq(
        "dir: 一个标签不注入",
        null,
        mychat.framework.memory.DirectoryFormatter.format(listOf(e("只有一个", 3, 1.0f)))
    )

    // 3. 2 个标签：两行
    val two = mychat.framework.memory.DirectoryFormatter.format(
        listOf(e("A", 12, 1.2f), e("B", 2, 0.9f))
    )
    check("dir: 两个标签两行", two != null && two.lines().size == 3, two)
    check("dir: 带条数", two != null && two.contains("- A（12 条）") && two.contains("- B（2 条）"), two)

    // 4. 12 个标签：只给前 10（按强度降序）
    val many = (1..12).map { e("T" + it, it, it / 10f) }
    val top = mychat.framework.memory.DirectoryFormatter.format(many)!!
    eq("dir: 最多十项", 11, top.lines().size) // 标题 + 10 行
    check("dir: 最强的最靠前", top.lines()[1].startsWith("- T12"), top)

    // 5. 弱标签标「正在淡化」
    val fading = mychat.framework.memory.DirectoryFormatter.format(
        listOf(e("新", 3, 1.0f), e("旧", 1, 0.3f))
    )!!
    check("dir: 弱标签标记淡化", fading.contains("- 旧（1 条，正在淡化）"), fading)
    check("dir: 强标签不标", !fading.contains("- 新（3 条，正在淡化）"), fading)

    // 展开块：排在回忆之后
    val expandBlock = "【展开】关于「标签A」：\n更细的内容"
    val withExpand = mychat.framework.memory.MemoryInjection.compose(
        top, "【回忆】关于「A」的过往记录：旧内容", expandBlock
    )!!
    check("expand: 注入含【展开】", withExpand.contains("【展开】关于「标签A」："), withExpand)
    check(
        "expand: 在回忆之后",
        withExpand.indexOf("【展开】") > withExpand.indexOf("【回忆】"),
        withExpand.take(80)
    )
    check(
        "expand: 清空后不注入",
        !mychat.framework.memory.MemoryInjection.compose(top, null, null)!!.contains("【展开】")
    )

    // 10. 目录排在回忆之前
    val composed = mychat.framework.memory.MemoryInjection.compose(
        top, "【回忆】关于「A」的过往记录：旧内容"
    )!!
    val dirAt = composed.indexOf("【记忆目录】")
    val recallAt = composed.indexOf("【回忆】")
    check("dir: 排在回忆之前", dirAt == 0 && recallAt > dirAt, composed.take(40))
}

// ---------- references 按需加载 ----------

private fun referenceTests() {
    val logs = mutableListOf<String>()

    // 1. 没有引用 → 正文原样
    val plain = "只有正文，没有附件。"
    eq(
        "refs: 无引用不变",
        plain,
        ReferenceInliner.render(plain, { null }, log = { logs += it })
    )
    check("refs: 无引用不打 inline 日志", !logs.any { it.contains("refs:inline") })

    // 2. 小文件（300 字）→ 全文
    val small = "小文件正文".repeat(60) // 300 字
    val body2 = "看 reference/small.md"
    val r2 = ReferenceInliner.render(body2, { if (it.endsWith("small.md")) small else null })
    check("refs: 小文件给全文", r2.contains(small), r2.length.toString())
    check("refs: 小文件标 full", r2.contains("【附件：small.md】"), r2)

    // 3. 中文件（1000 字）→ 前 200 字 + 共 N 字
    val mid = "中".repeat(1000)
    val r3 = ReferenceInliner.render("见 references/mid.md", { if (it.endsWith("mid.md")) mid else null })
    check("refs: 中文件给前 200 字", r3.contains("中".repeat(200)), r3.length.toString())
    check("refs: 中文件标字数", r3.contains("（共 1000 字）"), r3)
    check("refs: 中文件提示可索取", r3.contains("需要完整内容请说明"), r3)

    // 4. 大文件（3000 字）→ 只有目录项
    val big = "第一行标题\n" + "大".repeat(2995)
    val r4 = ReferenceInliner.render("见 reference/big.md", { if (it.endsWith("big.md")) big else null })
    check("refs: 大文件只留首行", r4.contains("首行：第一行标题"), r4)
    check("refs: 大文件不塞正文", !r4.contains("大大大"), r4.length.toString())

    // 5. 超出预算 → 大的先降级
    val a = "甲".repeat(400)
    val b = "乙".repeat(480)
    val logs5 = mutableListOf<String>()
    val r5 = ReferenceInliner.render(
        "见 reference/a.md 与 reference/b.md",
        { path -> when { path.endsWith("a.md") -> a; path.endsWith("b.md") -> b; else -> null } },
        log = { logs5 += it },
        // 只够放下 a 的全文 + b 的摘要：b 更大，先降它
        budget = 700,
    )
    check(
        "refs: 超预算时大的先降级",
        logs5.any { it.contains("file=b.md") && it.contains("mode=summary") } &&
            logs5.any { it.contains("file=a.md") && it.contains("mode=full") },
        logs5.joinToString(" | ")
    )

    // 6. 文件不存在 → 跳过 + 日志
    logs.clear()
    val r6 = ReferenceInliner.render(
        "见 reference/gone.md",
        { null },
        skillId = "skill_code",
        log = { logs += it },
    )
    eq("refs: 缺失文件不改正文", "见 reference/gone.md", r6)
    check("refs: 缺失有日志", logs.contains("refs:missing path=reference/gone.md"), logs.joinToString())
    check(
        "refs: 扫描日志带 found/success",
        logs.contains("refs:scan skill=skill_code found=1 success=0"),
        logs.joinToString()
    )
}

// ---------- 路由日志 ----------

private fun routeLogTests() {
    val route = mychat.framework.butler.RouteResult(
        modules = listOf("skill_code"),
        reason = "hit",
        scores = linkedMapOf("skill_code" to 0.812f, "skill_novel" to 0.604f),
    )
    val line = mychat.framework.embedding.RouteLog.line(route)
    check(
        "route: 含 best 与分数",
        line.contains("best=skill_code") && line.contains("score=0.812"),
        line
    )
    check("route: 含 margin", line.contains("margin=0.208"), line)

    val single = mychat.framework.embedding.RouteLog.line(
        mychat.framework.butler.RouteResult(emptyList(), "not_ready")
    )
    check(
        "route: 没有候选也不崩",
        single.contains("best=-") && single.contains("reason=not_ready"),
        single
    )
}

// ---------- 日志：一次安装、多次追加 ----------

private fun appendLogTests() {
    var creates = 0
    val log = AppendLog {
        creates++
        mychat.framework.log.LogSink { }
    }

    // 安装两次只建一个落点
    log.install()
    log.install()
    eq("log: 安装两次只创建一次", 1, creates)

    // 装之前写：丢弃，不崩
    var freshCreates = 0
    val fresh = AppendLog {
        freshCreates++
        mychat.framework.log.LogSink { }
    }
    eq("log: 未安装时写入返回 false", false, fresh.record("x"))
    eq("log: 未安装时不创建落点", 0, freshCreates)

    // 连续 100 条 → 100 行，仍只有一个落点
    val bulk = mutableListOf<String>()
    var bulkCreates = 0
    val bulkLog = AppendLog {
        bulkCreates++
        mychat.framework.log.LogSink { bulk += it }
    }
    bulkLog.install()
    repeat(100) { bulkLog.record("line " + it) }
    eq("log: 100 条只建一个落点", 1, bulkCreates)
    eq("log: 100 条正好 100 行", 100, bulk.size)
}

// ---------- 输出质检 ----------

private fun outputJudgeTests() {
    val input = "请帮我看看这段代码有没有并发问题，我要重构一下"
    val window = input.substring(0, OutputJudge.WINDOW)

    val repeated = OutputJudge.judge("先复述一下：" + window + "。然后……", input, emptyList())
    check("judge: 连续 15 字 → repeat", repeated.any { it.rule == "repeat" }, repeated.toString())

    val short = OutputJudge.judge("只重复：" + input.take(10), input, emptyList())
    check("judge: 10 字不报", short.none { it.rule == "repeat" }, short.toString())

    val vagueWith = OutputJudge.judge("这里建议优化一下", input, listOf("no_vague"))
    check("judge: 有 no_vague 时报 vague", vagueWith.any { it.rule == "vague" }, vagueWith.toString())

    val vagueWithout = OutputJudge.judge("这里建议优化一下", input, emptyList())
    check("judge: 无 no_vague 不报", vagueWithout.none { it.rule == "vague" }, vagueWithout.toString())

    val boiler = OutputJudge.judge("作为AI，我不能回答", input, emptyList())
    check("judge: 作为AI → boilerplate", boiler.any { it.rule == "boilerplate" }, boiler.toString())

    eq("judge: 干净输出为空", emptyList<JudgeWarning>(), OutputJudge.judge("这段代码没问题", input, listOf("no_vague")))

    val multi = OutputJudge.judge("作为AI，这里建议优化，" + window, input, listOf("no_vague"))
    check("judge: 多规则同时命中", multi.map { it.rule }.toSet().size >= 3, multi.toString())

    eq("judge: 空输入不崩", emptyList<JudgeWarning>(), OutputJudge.judge("", "", emptyList()))
}

// ---------- 类型片段注入 ----------

private fun intentInjectionTests() {
    val code = ModulePackage(
        manifest = ModuleManifest("c", "代码审查", hooks = emptyList()),
        body = "",
        scripts = emptyMap(),
        references = mapOf("SKILL.md" to "# 规范\n\n## 输出格式\n[必须改] x\n")
    )
    val registry = ModuleRegistry.of(listOf(code))
    val runner = HookRunner(
        source = registry,
        executor = FakeExecutor { _, _, _ -> null }
    )
    val session = HookSession(id = "s", activeHardModules = listOf("c"))

    // CHAT + 无选中 → 只有类型片段
    val chatOnly = runner.input(
        session, "今天天气怎么样", selected = emptySet(),
        intentText = PromptByIntent.textOf("chat")
    ) as HookAResult.Context
    check("intent: 只有类型片段时也注入", chatOnly.extra.contains("【对话类型】"), chatOnly.extra)
    check("intent: 不含技能块", !chatOnly.extra.contains("【已加载技能】"), chatOnly.extra)

    // CODE + 选中 → 两段都有，且类型在前
    val both = runner.input(
        session, "这段代码有问题吗", selected = setOf("c"),
        intentText = PromptByIntent.textOf("code")
    ) as HookAResult.Context
    check("intent: 类型 + 技能都在", both.extra.contains("【对话类型】") && both.extra.contains("【已加载技能】"), both.extra)
    check(
        "intent: 类型片段在技能块之前",
        both.extra.indexOf("【对话类型】") < both.extra.indexOf("【已加载技能】"),
        both.extra
    )

    // NoButler：intentText 为 null → 行为同现在
    val plain = runner.input(session, "问一句", selected = setOf("c")) as HookAResult.Context
    check("intent: 无类型时不加对话类型节", !plain.extra.contains("【对话类型】"), plain.extra)
}

// ---------- 约束枚举 ----------

private fun constraintTests() {
    fun pkg(id: String, constraints: List<String>, forbid: List<String>) = ModulePackage(
        manifest = ModuleManifest(
            id, id,
            hooks = emptyList(),
            constraints = constraints,
            forbid = forbid
        ),
        body = "",
        scripts = emptyMap(),
        references = mapOf("SKILL.md" to "# 规范\n\n正文\n")
    )

    fun blockOf(p: ModulePackage): String {
        val registry = mychat.framework.hook.ModuleRegistry.of(listOf(p))
        val d = ModuleDispatcher(
            source = registry,
            executor = FakeExecutor { _, _, _ -> null },
            bridges = GatedHostBridge.factory(registry, AuditLog(), { null })
        )
        val r = d.dispatchInput(
            HookSession(id = "s", activeHardModules = listOf(p.id)),
            "问题",
            selected = setOf(p.id)
        )
        return (r as? HookAResult.Context)?.extra.orEmpty()
    }

    // 5. 枚举展开成文案
    val preamble = blockOf(pkg("c1", listOf("no_preamble"), emptyList()))
    check("constraint: no_preamble 展开", preamble.contains("不要开场白"), preamble)

    // 6. 未知枚举不注入，且能报出来
    val unknownBlock = blockOf(pkg("c2", listOf("make_it_nice"), emptyList()))
    check("constraint: 未知枚举不注入", !unknownBlock.contains("make_it_nice"), unknownBlock)
    check("constraint: 未知枚举被识别", ConstraintText.unknownIds(listOf("make_it_nice")) == listOf("make_it_nice"))

    // 7. 枚举在前，自由文本在后
    val mixed = blockOf(pkg("c3", listOf("no_summary"), listOf("自定义禁止项")))
    val iEnum = mixed.indexOf("不要结尾总结")
    val iFree = mixed.indexOf("自定义禁止项")
    check("constraint: 枚举排在自由文本之前", iEnum in 0 until iFree, "enum=$iEnum free=$iFree")

    // 8. 只有 forbid 时照常注入（向后兼容）
    val onlyForbid = blockOf(pkg("c4", emptyList(), listOf("只有自由文本")))
    check("constraint: 只有 forbid 也能注入", onlyForbid.contains("只有自由文本"), onlyForbid)

    // 9. 要求类进【要求】，禁止类进【禁止】
    val both = blockOf(pkg("c5", listOf("no_emoji", "minimal_patch"), emptyList()))
    val iForbid = both.indexOf("【禁止】")
    val iRequire = both.indexOf("【要求】")
    check(
        "constraint: 禁止类进禁止节",
        iForbid >= 0 && iRequire > iForbid && both.indexOf("不要用 emoji") in iForbid until iRequire,
        both
    )
    check(
        "constraint: 要求类进要求节",
        both.indexOf("必须给出最小修改版") > iRequire,
        both
    )
}

// ---------- 格式检查：缺哪些 / 配对审计 ----------

private fun formatCheckMissingTests() {
    val marks = listOf("[必须改]", "[建议改]", "[可选]", "[无问题]")

    // 1. 任一标记出现即 ok
    val anyOne = FormatCheckResult.of("m", marks, "[必须改] 第 3 行越界")
    eq("check: 用到任一标记即 ok", true, anyOne?.ok)
    eq("check: 没有 required 时 missing 为空", emptyList<String>(), anyOne?.missing)

    // 2. 一个都没有 → missing（noFormatAtAll）
    val none = FormatCheckResult.of("m", marks, "1. **问题**：会崩")
    eq("check: 一个标记都没有 = 不 ok", false, none?.ok)
    eq("check: 一个标记都没有走重写", true, none?.noFormatAtAll)
    eq("check: 一个标记都没有的日志", "missing", none?.logValue())

    // 3. [无问题] 也算用了格式
    val clean = FormatCheckResult.of("m", marks, "[无问题] 逻辑正确，无并发风险")
    eq("check: [无问题] 也算 ok", true, clean?.ok)

    // 4. format_required 逐项点名
    val req = FormatCheckResult.of(
        "m", listOf("[建议改]"), "[建议改] 抽个函数", listOf("[必须改]")
    )
    eq("check: required 缺项被点名", listOf("[必须改]"), req?.missing)
    eq("check: required 缺项 = 不 ok", false, req?.ok)

    // 两种补救话术
    val rewrite = FormatRemediation.buildPrompt(none!!, "模板正文", "原回答")
    check(
        "prompt: 完全没用格式时要求重写",
        rewrite.structure.contains("没有使用分级标记"),
        rewrite.structure
    )
    check("prompt: 重写话术不含「重新组织」", !rewrite.structure.contains("重新组织"))
    check("prompt: 带上结构模板", rewrite.structure.contains("模板正文"))
    eq("prompt: 原回答原样", "原回答", rewrite.answer)

    val fill = FormatRemediation.buildPrompt(req!!, "模板正文", "原回答")
    check("prompt: 缺 required 时点名", fill.structure.contains("缺少：[必须改]"), fill.structure)
    check("prompt: 补齐话术含模板", fill.structure.contains("模板正文"))
}

private fun manifestAuditTests() {
    val noGuide = ModulePackage(
        manifest = ModuleManifest("out_no_guide", "输出无指南", hooks = listOf("output")),
        body = "", scripts = emptyMap(), references = emptyMap()
    )
    val (p1, i1) = ManifestAudit.auditGuides(listOf(noGuide))
    check("audit: 输出模块缺 Guide 只作 info", i1.any { it.startsWith("no_guides") }, i1.toString())
    eq("audit: no_guides 不进 problems", emptyList<String>(), p1)

    val marksNoStructure = ModulePackage(
        manifest = ModuleManifest(
            "fmt_no_struct", "有标记没结构",
            hooks = emptyList(),
            formatMarks = listOf("[必须改]"),
            forbid = listOf("不要废话")
        ),
        body = "", scripts = emptyMap(),
        references = mapOf("SKILL.md" to "# 规范\n\n## 何时使用\n随便\n")
    )
    val (p2, _) = ManifestAudit.auditGuides(listOf(marksNoStructure))
    check("audit: 有 marks 没结构仍算 problem", p2.any { it.startsWith("no_structure") }, p2.toString())
}

// ---------- 格式补救 ----------

private fun remediationTests() {
    val marks = listOf("[必须改]", "[建议改]")
    val structure = "[必须改] 问题\n- 在哪\n- 为什么\n- 改成什么"
    fun checkOf(answer: String) = FormatCheckResult.of("m", marks, answer)!!

    var calls = 0
    val okResult = FormatRemediation.remediate(
        "[必须改] 已有标记，[建议改] 也有", "m", marks, structure
    ) {
        calls++
        "不该被调用"
    }
    eq("remedy: 已合规时不补救", 0, calls)
    eq("remedy: 已合规 status=ok", "ok", okResult.status)
    eq("remedy: 已合规时 calls=0", 0, okResult.calls)
    eq("remedy: apply 认可含标记", "replaced", FormatRemediation.apply("原答", "[必须改] 好了", checkOf("原答")).status)

    calls = 0
    val replaced = FormatRemediation.remediate("随便答了一句", "m", marks, structure) {
        calls++
        "[必须改] 重排好了"
    }
    eq("remedy: missing 时补救一次", 1, calls)
    eq("remedy: 补救成功 status=replaced", "replaced", replaced.status)
    eq("remedy: 用补救后的文本", "[必须改] 重排好了", replaced.text)

    calls = 0
    val stillMissing = FormatRemediation.remediate("答得乱七八糟", "m", marks, structure) {
        calls++
        "还是没按格式"
    }
    eq("remedy: 补救无效时保留原答", "答得乱七八糟", stillMissing.text)
    eq("remedy: status=still_missing", "still_missing", stillMissing.status)

    calls = 0
    val errored = FormatRemediation.remediate("原答", "m", marks, structure) {
        calls++
        throw IllegalStateException("网络炸了")
    }
    eq("remedy: 异常时保留原答", "原答", errored.text)
    eq("remedy: status=error", "error", errored.status)
    eq("remedy: 异常时 calls=1", 1, errored.calls)

    // 没声明格式 / 没结构就不该发请求
    calls = 0
    eq(
        "remedy: 无声明不发请求",
        0,
        FormatRemediation.remediate("答", "m", emptyList(), structure) { calls++; null }.calls
    )
    eq("remedy: 只有 prompt 构造才走 call", 0, calls)
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
