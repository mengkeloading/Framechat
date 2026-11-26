package mychat.framework.sandbox

import mychat.framework.hook.AuditLog
import mychat.framework.hook.ExecutorOutcome
import mychat.framework.hook.ExecutorRegistry
import mychat.framework.hook.FrontMatter
import mychat.framework.hook.GatedHostBridge
import mychat.framework.hook.HookSession
import mychat.framework.hook.ModuleManifest
import mychat.framework.hook.ModulePackage
import mychat.framework.hook.ModuleRegistry
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

private fun manifestOf(md: String): ModuleManifest? =
    FrontMatter.parse(md)?.let { ModuleManifest.from(it, "fallback") }

fun main() {
    engineFieldTests()
    registryTests()

    println()
    println("PASS $passed / ${passed + failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  - $it") }
        exitProcess(1)
    }
}

private fun engineFieldTests() {
    eq(
        "engine: 没写时默认 js",
        "js",
        manifestOf("---\nid: a\nname: a\n---\n")?.engine
    )
    eq(
        "engine: 空值等价于 js",
        "js",
        manifestOf("---\nid: a\nname: a\nengine:\n---\n")?.engine
    )
    eq(
        "engine: none",
        "none",
        manifestOf("---\nid: a\nname: a\nengine: none\n---\n")?.engine
    )
    eq(
        "engine: py",
        "py",
        manifestOf("---\nid: a\nname: a\nengine: py\n---\n")?.engine
    )
}

private fun pkg(engine: String): ModulePackage = ModulePackage(
    manifest = ModuleManifest("m_" + engine, "m_" + engine, engine = engine),
    body = "",
    scripts = mapOf("main.js" to "function (i, h) { return null; }"),
    references = emptyMap()
)

private fun registryTests() {
    val registry = ModuleRegistry.of(listOf(pkg("js"), pkg("py"), pkg("none")))
    val executors = ExecutorRegistry(
        listOf(
            RhinoJsExecutor(registry),
            PythonExecutor(),
            NoopExecutor,
        )
    )

    check(
        "registry: js 模块拿到 RhinoJsExecutor",
        executors.forModule(pkg("js")) is RhinoJsExecutor
    )
    check(
        "registry: py 模块拿到 PythonExecutor",
        executors.forModule(pkg("py")) is PythonExecutor
    )

    val pyOutcome = executors.forModule(pkg("py")).run(
        pkg("py"), "a", "{}",
        GatedHostBridge(pkg("py"), registry, AuditLog(), { null })
    )
    check("registry: py 返回 Skipped", pyOutcome is ExecutorOutcome.Skipped)
    eq(
        "registry: py 的跳过原因",
        "python_not_available",
        (pyOutcome as? ExecutorOutcome.Skipped)?.reason
    )

    // 未知引擎：落到 none，且不抛异常
    val luaOutcome = executors.forModule(pkg("lua")).run(
        pkg("lua"), "a", "{}",
        GatedHostBridge(pkg("lua"), registry, AuditLog(), { null })
    )
    check("registry: 未知引擎落到 NoopExecutor", luaOutcome is ExecutorOutcome.Skipped)

    // 声明 engine: none 的模块（隐式 skill 包）不会崩
    val noneOutcome = executors.forModule(pkg("none")).run(
        pkg("none"), "a", "{}",
        GatedHostBridge(pkg("none"), registry, AuditLog(), { null })
    )
    check("registry: engine=none 返回 Skipped", noneOutcome is ExecutorOutcome.Skipped)
}
