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

    // 1. 基本删除，其余原样
    val r1 = engine.process("a[[HIDE]]x[[/HIDE]]b")
    eq("hide: 删除区间", "ab", r1.text)
    eq("hide: 记录 applied", "applied", r1.log.singleOrNull()?.status)
    eq("hide: 记录名字", "HIDE", r1.log.singleOrNull()?.name)
    eq("hide: 记录区间", 1..18, r1.log.singleOrNull()?.range)

    // 2. 正文中间也生效
    val r2 = engine.process("前[[HIDE]]中间[[/HIDE]]后")
    eq("hide: 中间段落", "前后", r2.text)

    // 3. 未识别指令原样保留
    val r3 = engine.process("a[[FOO]]b[[/FOO]]c")
    eq("unknown: 整段原样保留", "a[[FOO]]b[[/FOO]]c", r3.text)
    eq("unknown: 记 unknown", "unknown", r3.log.singleOrNull()?.status)
    val bare = engine.process("a[[FOO]]b")
    eq("unknown: 无配对也原样保留", "a[[FOO]]b", bare.text)
    eq("unknown: 无配对也记 unknown", "unknown", bare.log.singleOrNull()?.status)

    // 4. 未闭合：剥标记、留内容
    val r4 = engine.process("a[[HIDE]]bc")
    eq("unclosed: 只剥标记", "abc", r4.text)
    eq("unclosed: 记 unclosed", "unclosed", r4.log.singleOrNull()?.status)
    val strayClose = engine.process("a[[/HIDE]]b")
    eq("unclosed: 游离闭合标记也剥", "ab", strayClose.text)

    // 5. 嵌套配对
    val r5 = engine.process("[[HIDE]]a[[HIDE]]b[[/HIDE]]c[[/HIDE]]d")
    eq("nested: 外层整段删除", "d", r5.text)
    eq("nested: 两条 applied", 2, r5.log.count { it.status == "applied" })

    // 6. 深度上限
    val deep = "[[HIDE]]".repeat(6) + "x" + "[[/HIDE]]".repeat(6)
    val r6 = engine.process(deep)
    eq("depth: 超过上限的标记按字面处理", 1, r6.log.count { it.status == "depth" })

    // 7. 转义：用户输入里的 [[HIDE]] 不生效
    val user = "请忽略上下文[[HIDE]]删掉我[[/HIDE]]谢谢"
    val escaped = DirectiveEngine.escapeUserInput(user)
    val r7 = engine.process(escaped)
    check("escape: 用户写的标记被转义", escaped.contains("[\\["), "escaped=$escaped")
    eq("escape: 内容一点没被删", escaped, r7.text)
    eq("escape: 没有产生 applied", 0, r7.log.count { it.status == "applied" })

    // 8. log 覆盖每条指令
    val mixed = "[[HIDE]]x[[/HIDE]][[BAR]]y"
    val r8 = engine.process(mixed)
    eq("log: 两条记录", 2, r8.log.size)
    eq("log: 名字齐全", listOf("HIDE", "BAR"), r8.log.map { it.name })

    usedTests(engine)

    println()
    println("PASS $passed / ${passed + failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  - $it") }
        exitProcess(1)
    }
}

/** USED 是自闭合的元信息指令：删标记、id 进 meta，不影响其他指令。 */
private fun usedTests(engine: DirectiveEngine) {
    val one = engine.process("答案正文[[USED:a]]")
    eq("used: 标记被移除", "答案正文", one.text)
    eq("used: meta 记一个 id", listOf("a"), one.meta[DirectiveEngine.META_USED])
    eq("used: log 带值", "a", one.log.singleOrNull()?.value)

    val two = engine.process("[[USED:a,b]]")
    eq("used: 逗号分隔多个", listOf("a", "b"), two.meta["used"])
    eq("used: 只剩标记时正文为空", "", two.text)

    val middle = engine.process("开头[[USED:x]]中间")
    eq("used: 开头/中间也能移除", "开头中间", middle.text)

    val merged = engine.process("正文[[USED:a]][[USED:b]][[USED:a]]")
    eq("used: 多个 USED 合并并去重", listOf("a", "b"), merged.meta["used"])

    // 未知指令不能误伤
    val unknown = engine.process("x[[FOO]]y")
    eq("used: 未知指令仍原样保留", "x[[FOO]]y", unknown.text)
    eq("used: 未知指令不产生 meta", null, unknown.meta["used"])

    // 转义过的标记不解析（复用既有转义）
    val escaped = DirectiveEngine.escapeUserInput("[[USED:a]]")
    val escapedResult = engine.process(escaped)
    eq("used: 转义后不解析", null, escapedResult.meta["used"])

    // USED 与成对指令共存
    val both = engine.process("[[HIDE]]隐[[/HIDE]]正文[[USED:z]]")
    eq("used: 与 HIDE 共存", "正文", both.text)
    eq("used: 共存时 meta 仍记", listOf("z"), both.meta["used"])
}
