package mychat.framework

import mychat.framework.directive.DirectiveEngine
import mychat.framework.directive.UsageWhitelist
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
    directiveTests(engine)
    namespaceTests(engine)
    cleanupTests(engine)
    usedTests(engine)
    whitelistTests(engine)

    println()
    println("PASS $passed / ${passed + failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  - $it") }
        exitProcess(1)
    }
}

// ---------- 成对指令 ----------

private fun directiveTests(engine: DirectiveEngine) {
    val r1 = engine.process("a[[!HIDE]]x[[!/HIDE]]b")
    eq("hide: 删除区间", "ab", r1.text)
    eq("hide: 记录 applied", "applied", r1.log.singleOrNull()?.status)
    eq("hide: 记录名字", "HIDE", r1.log.singleOrNull()?.name)
    eq("hide: 记录区间", 1..20, r1.log.singleOrNull()?.range)

    eq("hide: 中间段落", "前后", engine.process("前[[!HIDE]]中间[[!/HIDE]]后").text)

    val r4 = engine.process("a[[!HIDE]]bc")
    eq("unclosed: 只剥标记", "abc", r4.text)
    eq("unclosed: 记 unclosed", "unclosed", r4.log.singleOrNull()?.status)

    val r5 = engine.process("[[!HIDE]]a[[!HIDE]]b[[!/HIDE]]c[[!/HIDE]]d")
    eq("nested: 外层整段删除", "d", r5.text)
    eq("nested: 两条 applied", 2, r5.log.count { it.status == "applied" })

    val deep = "[[!HIDE]]".repeat(6) + "x" + "[[!/HIDE]]".repeat(6)
    eq("depth: 超过上限的标记按字面处理", 1, engine.process(deep).log.count { it.status == "depth" })
}

// ---------- 命名空间：只有 [[! 是标记 ----------

private fun namespaceTests(engine: DirectiveEngine) {
    // 普通 [[…]] 全部当文本
    val plain = engine.process("代码 a[[0]] 与 wiki [[链接]] 都不该被动")
    eq("ns: 普通双方括号原样", "代码 a[[0]] 与 wiki [[链接]] 都不该被动", plain.text)
    eq("ns: 普通双方括号不产生日志", 0, plain.log.size)

    val old = engine.process("a[[HIDE]]x[[/HIDE]]b")
    eq("ns: 旧写法 [[HIDE]] 不再生效", "a[[HIDE]]x[[/HIDE]]b", old.text)

    val oldUsed = engine.process("正文[[USED:a]]")
    eq("ns: 旧写法 [[USED]] 原样保留", "正文[[USED:a]]", oldUsed.text)
    eq("ns: 旧写法不产生 meta", null, oldUsed.meta["used"])

    // 未识别的框架标记：剥离 + unknown
    val unknown = engine.process("a[[!FOO:x]]b")
    eq("ns: 未知框架标记被剥离", "ab", unknown.text)
    eq("ns: 未知框架标记记 unknown", "unknown", unknown.log.singleOrNull()?.status)

    // 用户输入转义：只动 [[!
    val user = "用户写[[!HIDE]]和[[普通]]"
    val escaped = DirectiveEngine.escapeUserInput(user)
    eq("ns: 只转义 [[!", "用户写[\\[!HIDE]]和[[普通]]", escaped)
    val escapedResult = engine.process(escaped)
    eq("ns: 转义后不解析", 0, escapedResult.log.count { it.status == "applied" })
    check("ns: 转义后正文不变", escapedResult.text == escaped, escapedResult.text)
}

// ---------- 回传统一清理 ----------

private fun cleanupTests(engine: DirectiveEngine) {
    // 残留标记一律不留给用户
    val leftover = engine.process("正文[[!FOO:bar]]尾巴")
    check("cleanup: 不留框架标记", !leftover.text.contains("[[!"), leftover.text)

    // 模型复述系统提示的结构 → 整段剥离
    val skills = engine.process(
        "答案第一行\n" +
            "【已加载技能】\nid: skill_novel\nname: 小说写作\n说明：…\n\n规范：\n…\n【技能结束】\n答案第二行"
    )
    eq("cleanup: 剥掉技能块", "答案第一行\n答案第二行", skills.text)

    val declaration = engine.process(
        "正文\n【使用声明】\n如果你在回答中遵循了上述任一技能的规范…\n[[!USED:技能id]]\n例如：[[!USED:skill_code]]"
    )
    eq("cleanup: 剥掉使用声明段", "正文", declaration.text)

    // 普通【】不受影响
    val normal = engine.process("【我的笔记】今天做了三件事")
    eq("cleanup: 普通【】保留", "【我的笔记】今天做了三件事", normal.text)

    // 技能块之后的正常【】标题要保住
    val mixed = engine.process("【已加载技能】\nid: x\n【技能结束】\n【结论】就这样")
    eq("cleanup: 块后标题保留", "【结论】就这样", mixed.text)
}

// ---------- USED ----------

private fun usedTests(engine: DirectiveEngine) {
    val one = engine.process("答案正文[[!USED:a]]")
    eq("used: 标记被移除", "答案正文", one.text)
    eq("used: meta 记一个 id", listOf("a"), one.meta[DirectiveEngine.META_USED])
    eq("used: log 带值", "a", one.log.singleOrNull()?.value)

    val two = engine.process("[[!USED:a,b]]")
    eq("used: 逗号分隔多个", listOf("a", "b"), two.meta["used"])
    eq("used: 只剩标记时正文为空", "", two.text)

    eq("used: 开头/中间也能移除", "开头中间", engine.process("开头[[!USED:x]]中间").text)

    val merged = engine.process("正文[[!USED:a]][[!USED:b]][[!USED:a]]")
    eq("used: 多个 USED 合并并去重", listOf("a", "b"), merged.meta["used"])

    val both = engine.process("[[!HIDE]]隐[[!/HIDE]]正文[[!USED:z]]")
    eq("used: 与 HIDE 共存", "正文", both.text)
    eq("used: 共存时 meta 仍记", listOf("z"), both.meta["used"])
}

// ---------- 白名单 ----------

private fun whitelistTests(engine: DirectiveEngine) {
    val loaded = setOf("local_calc", "skill_code")

    eq(
        "whitelist: 不存在的模块被过滤",
        emptyList<String>(),
        UsageWhitelist.filter(listOf("模块id"), loaded)
    )
    eq(
        "whitelist: 已加载的模块通过",
        listOf("local_calc"),
        UsageWhitelist.filter(listOf("local_calc"), loaded)
    )
    eq(
        "whitelist: 混合时只留合法",
        listOf("skill_code"),
        UsageWhitelist.filter(listOf("模块id", "skill_code", "ghost"), loaded)
    )
    eq(
        "whitelist: 能拿到非法清单用于日志",
        listOf("模块id", "ghost"),
        UsageWhitelist.invalid(listOf("模块id", "skill_code", "ghost"), loaded)
    )

    val declared = engine.process("正文[[!USED:模块id,skill_code]]")
    eq(
        "whitelist: meta 保留原值",
        listOf("模块id", "skill_code"),
        declared.meta[DirectiveEngine.META_USED]
    )
    eq(
        "whitelist: 对 meta 做过滤后只剩合法",
        listOf("skill_code"),
        UsageWhitelist.filter(declared.meta[DirectiveEngine.META_USED].orEmpty(), loaded)
    )
}
