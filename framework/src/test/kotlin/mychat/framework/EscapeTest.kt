package mychat.framework

import mychat.framework.directive.DirectiveEngine
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
    val engine = DirectiveEngine()
    val raw = "[[HIDE]]秘密[[/HIDE]]正文"

    // 1. user 消息在组包时被转义
    eq(
        "escape: user 消息被转义",
        "[\\[HIDE]]秘密[\\[/HIDE]]正文",
        DirectiveEngine.escapeForRequest(raw, isUser = true)
    )

    // 2. 落库原文不受影响（转义只发生在组包那一份）
    eq("escape: 原文保持不变", "[[HIDE]]秘密[[/HIDE]]正文", raw)
    eq("escape: 未转义的原文确实会被删内容", "正文", engine.process(raw).text)

    // 3. 历史里的 user 消息同样转义
    val history = listOf("第一条[[HIDE]]a", "第二条", "第三条[[/HIDE]]b")
    val escapedHistory = history.map { DirectiveEngine.escapeForRequest(it, isUser = true) }
    eq(
        "escape: 历史 user 消息逐条转义",
        listOf("第一条[\\[HIDE]]a", "第二条", "第三条[\\[/HIDE]]b"),
        escapedHistory
    )

    // 4. assistant 不转义，模型才学得会输出合法指令
    eq(
        "escape: assistant 消息不转义",
        "[[HIDE]]示例[[/HIDE]]",
        DirectiveEngine.escapeForRequest("[[HIDE]]示例[[/HIDE]]", isUser = false)
    )

    // 5. 转义后的文本再走指令引擎不会被解析
    val escaped = DirectiveEngine.escapeForRequest(raw, isUser = true)
    val processed = engine.process(escaped)
    eq("escape: 转义后不被解析", escaped, processed.text)
    eq("escape: 转义后没有 applied", 0, processed.log.count { it.status == "applied" })

    // 6. 幂等：重复转义不会叠加反斜杠
    eq("escape: 幂等", escaped, DirectiveEngine.escapeForRequest(escaped, isUser = true))

    println()
    println("PASS $passed / ${passed + failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  - $it") }
        exitProcess(1)
    }
}
