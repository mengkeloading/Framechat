package mychat.framework.sandbox

import mychat.framework.hook.Attachment
import mychat.framework.hook.AuditLog
import mychat.framework.hook.Caps
import mychat.framework.hook.GatedHostBridge
import mychat.framework.hook.HookAResult
import mychat.framework.hook.HookSession
import mychat.framework.hook.ModuleDispatcher
import mychat.framework.hook.ModuleRegistry
import mychat.framework.hook.ModuleSource
import mychat.framework.hook.SessionData
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
    executorTests()
    sandboxTests()
    timeoutTest()
    moduleLoaderShimTests()
    dispatchIntegrationTests()

    println()
    println("PASS $passed / ${passed + failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  - $it") }
        exitProcess(1)
    }
}

private fun tempDir(): File = Files.createTempDirectory("mychat-rhino").toFile()

private fun writeModule(
    root: File,
    dir: String,
    md: String,
    script: String,
    refs: Map<String, String> = emptyMap()
) {
    val d = File(root, dir)
    File(d, "script").mkdirs()
    File(d, "reference").mkdirs()
    File(d, "MODULE.md").writeText(md)
    File(d, "script/main.js").writeText(script)
    refs.forEach { (name, content) -> File(File(d, "reference"), name).writeText(content) }
}

private fun mdOf(id: String, hooks: String, caps: String = ""): String {
    val capBlock = if (caps.isEmpty()) "" else "capabilities:\n$caps\n"
    return "---\nid: $id\nname: $id\nhooks: [$hooks]\n$capBlock---\n"
}

private class Spec(
    val id: String,
    val script: String,
    val caps: String = "",
    val refs: Map<String, String> = emptyMap()
)

private fun sourceWith(vararg specs: Spec): ModuleSource {
    val root = tempDir()
    for (s in specs) {
        writeModule(root, s.id, mdOf(s.id, "input,output", s.caps), s.script, s.refs)
    }
    return ModuleRegistry.scan(root).registry
}

private fun executorTests() {
    val source = sourceWith(
        Spec("nulls", "function (inputJson, host) { return null; }"),
        Spec("echo", "function (inputJson, host) { return inputJson; }"),
        Spec(
            "clock",
            "function (inputJson, host) { return host.requestLocalAction('clock.now', '{}'); }",
            caps = "  clock.now: 1\n"
        ),
        Spec("reply", "function (inputJson, host) { return JSON.stringify({type:'local', text:'本地答案'}); }")
    )
    val ex = RhinoJsExecutor(source)

    val audit = AuditLog()
    val session = HookSession(id = "s1")
    fun bridge(id: String) = GatedHostBridge(
        pkg = source.getPackage(id)!!,
        source = source,
        audit = audit,
        sessionData = { SessionData(session) },
        clock = { "2026-11-25T00:00:00Z" }
    )

    eq("rhino: 返回 null", null, ex.execute("nulls", "{}", bridge("nulls")))
    check(
        "rhino: 能读 inputJson",
        ex.execute("echo", """{"text":"你好"}""", bridge("echo"))?.contains("你好") == true
    )
    eq(
        "rhino: 经 host 调 clock.now",
        "2026-11-25T00:00:00Z",
        ex.execute("clock", "{}", bridge("clock"))
    )
    eq(
        "rhino: 返回 JSON 字符串",
        """{"type":"local","text":"本地答案"}""",
        ex.execute("reply", "{}", bridge("reply"))
    )
    check("rhino: 能力调用写审计", audit.snapshot().any { it.action == Caps.CLOCK_NOW && it.ok })

    var missing = false
    try {
        ex.execute("ghost", "{}", bridge("nulls"))
    } catch (e: Exception) {
        missing = true
    }
    check("rhino: 未知模块抛异常（由 dispatcher fail-open）", missing)

    var notAFunction = false
    val badSource = sourceWith(Spec("bad", "1 + 1"))
    try {
        RhinoJsExecutor(badSource).execute("bad", "{}", bridge("nulls"))
    } catch (e: Exception) {
        notAFunction = true
    }
    check("rhino: 脚本不是函数时抛异常", notAFunction)
}

private fun sandboxTests() {
    val source = sourceWith(
        Spec("packages", "function (i, h) { return String(typeof Packages); }"),
        Spec("getclass", "function (i, h) { return String(typeof getClass); }"),
        Spec("doc", "function (i, h) { return String(typeof document); }"),
        Spec("loopHost", "function (i, h) { return h.requestLocalAction('resource.read','{\"name\":\"skill.md\"}'); }")
    )
    val ex = RhinoJsExecutor(source)
    val audit = AuditLog()

    fun bridge(id: String) = GatedHostBridge(
        pkg = source.getPackage(id)!!,
        source = source,
        audit = audit,
        sessionData = { null }
    )

    eq("sandbox: 拿不到 Packages", "undefined", ex.execute("packages", "{}", bridge("packages")))
    eq("sandbox: 拿不到 getClass", "undefined", ex.execute("getclass", "{}", bridge("getclass")))
    eq("sandbox: 宿主全局被遮蔽", "undefined", ex.execute("doc", "{}", bridge("doc")))

    // 未声明能力 → 抛异常 → 调用方 fail-open
    var denied = false
    try {
        ex.execute("loopHost", "{}", bridge("loopHost"))
    } catch (e: Exception) {
        denied = true
    }
    check("sandbox: 未声明 resource.read 被拒", denied)
    check("sandbox: 拒绝写审计", audit.snapshot().any { it.action == Caps.RESOURCE_READ && !it.ok })
}

private fun timeoutTest() {
    val source = sourceWith(Spec("spin", "function (i, h) { while (true) {} }"))
    val ex = RhinoJsExecutor(source, timeoutMs = 300)
    val started = System.currentTimeMillis()
    var timedOut = false
    try {
        ex.execute("spin", "{}", GatedHostBridge(source.getPackage("spin")!!, source, AuditLog(), { null }))
    } catch (e: Throwable) {
        timedOut = e is JsTimeoutException || (e.cause is JsTimeoutException) ||
            e.message?.contains("module_timeout") == true
    }
    val elapsed = System.currentTimeMillis() - started
    check("timeout: 死循环被打断", timedOut, "elapsed=${elapsed}ms")
    check("timeout: 打断及时", elapsed < 2000, "elapsed=${elapsed}ms")
}

private fun moduleLoaderShimTests() {
    val root = tempDir()
    writeModule(
        root, "a_skill", mdOf("a_skill", "input"),
        "function (i, h) { return null; }",
        refs = mapOf("SKILL.md" to "技能正文")
    )
    writeModule(root, "b_plain", mdOf("b_plain", "input"), "function (i, h) { return null; }")
    writeModule(
        root, "probe", mdOf("probe", "input"),
        """
        function (i, h) {
          var pkgs = ModuleLoader.listPackages();
          var first = pkgs[0];
          var keys = Object.keys(first).sort();
          function hasSkill(id) {
            for (var k = 0; k < pkgs.length; k++) {
              if (pkgs[k].id === id) return pkgs[k].hasSkill;
            }
            return null;
          }
          return JSON.stringify({
            count: pkgs.length,
            keys: keys,
            getScript: String(typeof ModuleLoader.getScript),
            getReference: String(typeof ModuleLoader.getReference),
            aSkill: hasSkill('a_skill'),
            bSkill: hasSkill('b_plain')
          });
        }
        """.trimIndent()
    )
    val registry = ModuleRegistry.scan(root).registry
    val probe = RhinoJsExecutor(registry).execute(
        "probe", "{}",
        GatedHostBridge(registry.getPackage("probe")!!, registry, AuditLog(), { null })
    )
    val json = parseJson(probe ?: "")
    eq("shim: 返回所有包", 3, json?.int("count"))
    eq(
        "shim: 只有四个字段",
        listOf("description", "hasSkill", "id", "name"),
        json?.arr("keys")?.mapNotNull { (it as? mychat.framework.json.JsonValue.Str)?.value }
    )
    eq("shim: 不暴露 getScript", "undefined", json?.str("getScript"))
    eq("shim: 不暴露 getReference", "undefined", json?.str("getReference"))
    eq("shim: 有 SKILL.md 的 hasSkill=true", true, json?.bool("aSkill"))
    eq("shim: 没 SKILL.md 的 hasSkill=false", false, json?.bool("bSkill"))
}

private fun dispatchIntegrationTests() {
    val root = tempDir()
    writeModule(
        root, "local_calc",
        mdOf("local_calc", "input"),
        """
        function (inputJson, host) {
          var inp = JSON.parse(inputJson);
          if (inp.text.indexOf('算:') !== 0) return null;
          return JSON.stringify({type: 'local', text: '本地：' + inp.text.slice(2)});
        }
        """.trimIndent()
    )
    writeModule(
        root, "skill_reader",
        mdOf("skill_reader", "input", "  resource.read: 2"),
        """
        function (inputJson, host) {
          var skill = host.requestLocalAction('resource.read', '{"name":"skill.md"}');
          return JSON.stringify({type: 'context', text: inputJson.text, extra: skill});
        }
        """.trimIndent(),
        refs = mapOf("skill.md" to "技能正文：先问清楚再动手")
    )
    writeModule(
        root, "exploder",
        mdOf("exploder", "input"),
        "function (i, h) { throw new Error('模块炸了'); }"
    )
    val registry = ModuleRegistry.scan(root).registry
    val audit = AuditLog()
    val dispatcher = ModuleDispatcher(
        source = registry,
        executor = RhinoJsExecutor(registry),
        bridges = GatedHostBridge.factory(registry, audit, { SessionData(HookSession("s"), emptyList()) })
    )

    val local = dispatcher.dispatchInput(
        HookSession(id = "s", activeHardModules = listOf("local_calc")), "算:12*37"
    )
    check("integration: local 短路", local is HookAResult.Local)
    eq("integration: local 文本", "本地：12*37", local.text)

    val unhandled = dispatcher.dispatchInput(
        HookSession(id = "s", activeHardModules = listOf("local_calc")), "普通问题"
    )
    eq("integration: 模块返回 null 视为 pass", "普通问题", unhandled.text)

    val ctxResult = dispatcher.dispatchInput(
        HookSession(id = "s", activeHardModules = listOf("skill_reader")), "写代码"
    )
    check("integration: context 生效", ctxResult is HookAResult.Context)
    check(
        "integration: reference 经 host 读到",
        (ctxResult as HookAResult.Context).extra.contains("技能正文：先问清楚再动手")
    )

    val boom = dispatcher.dispatchInput(
        HookSession(id = "s", activeHardModules = listOf("exploder")), "原文"
    )
    check("integration: 抛异常的模块 fail-open", boom is HookAResult.Pass)
    eq("integration: fail-open 保留原文", "原文", boom.text)
    check("integration: 记录了执行异常", dispatcher.traces.any { it.message.contains("执行异常") })

    // 附件只给元数据
    var seen: String? = null
    val envRoot = tempDir()
    writeModule(envRoot, "peek", mdOf("peek", "input"), """
        function (inputJson, host) {
          var inp = JSON.parse(inputJson);
          return JSON.stringify({type: 'pass', text: String(inp.ctx.attachments[0].name)
            + '|' + String(inp.ctx.attachments[0].text)});
        }
    """.trimIndent())
    val envRegistry = ModuleRegistry.scan(envRoot).registry
    val envDispatcher = ModuleDispatcher(
        envRegistry,
        RhinoJsExecutor(envRegistry),
        GatedHostBridge.factory(envRegistry, AuditLog(), { null })
    )
    val envResult = envDispatcher.dispatchInput(
        HookSession(id = "s", activeHardModules = listOf("peek")), "x",
        attachments = listOf(Attachment("a.txt", text = "正文"))
    )
    seen = envResult.text
    eq("integration: 模块只看到附件名", "a.txt|undefined", seen)
}
