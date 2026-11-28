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
    val raw = "[[!HIDE]]秘密[[!/HIDE]]正文"

    // 1. user 消息在组包时被转义
    eq(
        "escape: user 消息被转义",
        "[\\[!HIDE]]秘密[\\[!/HIDE]]正文",
        DirectiveEngine.escapeForRequest(raw, isUser = true)
    )

    // 2. 落库原文不受影响
    eq("escape: 原文保持不变", "[[!HIDE]]秘密[[!/HIDE]]正文", raw)
    eq("escape: 未转义的原文确实会被删内容", "正文", engine.process(raw).text)

    // EXPAND：删标记、收名字、最多两个
    val one = engine.process("这是回答 [[!EXPAND:标签A]]")
    eq("expand: 标记从正文删掉", "这是回答", one.text)
    eq("expand: 收进 meta", listOf("标签A"), one.meta[DirectiveEngine.META_EXPAND])

    val two = engine.process("回答\n[[!EXPAND:标签A]]\n[[!EXPAND:标签B]]")
    eq(
        "expand: 两个都收",
        listOf("标签A", "标签B"),
        two.meta[DirectiveEngine.META_EXPAND]
    )

    val three = engine.process(
        "回答 [[!EXPAND:A]] [[!EXPAND:B]] [[!EXPAND:C]]"
    )
    eq("expand: 只取前两个", listOf("A", "B"), three.meta[DirectiveEngine.META_EXPAND])
    check("expand: 第三个不留在正文", !three.text.contains("EXPAND"), three.text)

    // 3. 历史里的 user 消息同样转义
    val history = listOf("第一条[[!HIDE]]a", "第二条", "第三条[[!/HIDE]]b")
    eq(
        "escape: 历史 user 消息逐条转义",
        listOf("第一条[\\[!HIDE]]a", "第二条", "第三条[\\[!/HIDE]]b"),
        history.map { DirectiveEngine.escapeForRequest(it, isUser = true) }
    )

    // 4. assistant 不转义
    eq(
        "escape: assistant 消息不转义",
        "[[!HIDE]]示例[[!/HIDE]]",
        DirectiveEngine.escapeForRequest("[[!HIDE]]示例[[!/HIDE]]", isUser = false)
    )

    // 5. 转义后不再被解析
    val escaped = DirectiveEngine.escapeForRequest(raw, isUser = true)
    val processed = engine.process(escaped)
    eq("escape: 转义后不被解析", escaped, processed.text)
    eq("escape: 转义后没有 applied", 0, processed.log.count { it.status == "applied" })

    // 6. 普通 [[ 不受影响（这是加 ! 的理由）
    eq(
        "escape: 普通双方括号不动",
        "代码 a[[0]] 与 [[链接]]",
        DirectiveEngine.escapeForRequest("代码 a[[0]] 与 [[链接]]", isUser = true)
    )

    // 7. 幂等
    eq("escape: 幂等", escaped, DirectiveEngine.escapeForRequest(escaped, isUser = true))

    println()
    println("PASS $passed / ${passed + failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  - $it") }
        exitProcess(1)
    }
}
