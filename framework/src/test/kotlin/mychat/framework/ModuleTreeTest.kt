package mychat.framework

import mychat.framework.embedding.ModuleSelector
import mychat.framework.hook.FileModuleTree
import mychat.framework.hook.MapModuleTree
import mychat.framework.hook.ModulePackage
import mychat.framework.hook.ModuleRegistry
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
    treeEquivalenceTests()
    edgeCaseTests()
    implicitModuleTests()
    duplicatePrefixTests()
    assetHealthTest()

    println()
    println("PASS $passed / ${passed + failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  - $it") }
        exitProcess(1)
    }
}

// ---------- 构造一棵模块树 ----------

private fun tempDir(): File = Files.createTempDirectory("mychat-tree").toFile()

private fun write(root: File, path: String, content: String) {
    val f = File(root, path)
    f.parentFile?.mkdirs()
    f.writeText(content)
}

/**
 * 三个真模块 + 一个没有 MODULE.md 的目录 + 一组嵌套 reference。
 * 嵌套是有意的：assets 里 reference 可能有多层，list/read 都得撑住。
 */
private fun buildTree(root: File) {
    write(
        root, "alpha/MODULE.md",
        """
        |---
        |id: alpha
        |name: 阿尔法
        |description: 第一个
        |hooks: [input]
        |inject: full
        |capabilities:
        |  resource.read: 2
        |---
        |阿尔法正文
        """.trimMargin()
    )
    write(root, "alpha/script/main.js", "function (i, h) { return null; }")
    write(root, "alpha/reference/skill.md", "技能正文")
    write(root, "alpha/reference/nested/deep.md", "深层引用")
    write(
        root, "beta/MODULE.md",
        """
        |---
        |id: beta
        |name: 贝塔
        |hooks: [output]
        |inject: summary
        |---
        |贝塔正文
        """.trimMargin()
    )
    write(root, "beta/script/main.js", "function (i, h) { return null; }")
    // 声明了 hooks 却没有脚本：两边都必须报同一个问题
    write(
        root, "broken/MODULE.md",
        """
        |---
        |id: broken
        |name: 坏了
        |hooks: [input]
        |---
        """.trimMargin()
    )
    // 不是模块：只有说明文件
    write(root, "notes/readme.txt", "不是模块")
}

private fun toMap(root: File): Map<String, String> {
    val base = root.toPath()
    return root.walkTopDown()
        .filter { it.isFile }
        .sortedBy { it.path }
        .associate { it.toPath().let { p -> base.relativize(p).toString().replace(File.separatorChar, '/') } to it.readText() }
}

// ---------- 两种树必须给出同一个结果 ----------

private fun treeEquivalenceTests() {
    val root = tempDir()
    buildTree(root)

    val byFile = ModuleRegistry.scan(root)
    val byMap = ModuleRegistry.scan(MapModuleTree(toMap(root)))

    eq("tree: 模块数一致", byFile.registry.all().size, byMap.registry.all().size)
    eq("tree: 模块 id 一致", byFile.registry.all().map { it.id }, byMap.registry.all().map { it.id })
    eq("tree: 问题清单一致", byFile.problems, byMap.problems)
    check("tree: 报了缺脚本", byFile.problems.any { it.contains("broken") })
    check("tree: 没有 MODULE.md 的目录被忽略", byFile.registry.getPackage("notes") == null)

    for (id in listOf("alpha", "beta")) {
        val a = byFile.registry.getPackage(id)
        val b = byMap.registry.getPackage(id)
        check("tree: $id 两边都存在", a != null && b != null)
        if (a != null && b != null) {
            eq("tree: $id manifest 一致", a.manifest, b.manifest)
            eq("tree: $id 正文一致", a.body, b.body)
            eq("tree: $id 脚本一致", a.scripts, b.scripts)
            eq("tree: $id reference 一致", a.references, b.references)
        }
    }

    eq(
        "tree: 嵌套 reference 可读（file）",
        "深层引用",
        byFile.registry.getReference("alpha", "nested/deep.md")
    )
    eq(
        "tree: 嵌套 reference 可读（map）",
        "深层引用",
        byMap.registry.getReference("alpha", "nested/deep.md")
    )
    eq("tree: MODULE.md 作为 reference 可读", "技能正文", byFile.registry.getReference("alpha", "skill.md"))
    eq("tree: 能力配额一致", 2, byMap.registry.getPackage("alpha")?.manifest?.capabilities?.get("resource.read"))

    // 目录列表本身（资产源实现靠它递归）
    val fileTree = FileModuleTree(root)
    val mapTree = MapModuleTree(toMap(root))
    eq("tree: 根目录列表一致", fileTree.list(""), mapTree.list(""))
    eq("tree: reference 目录列表一致", fileTree.list("alpha/reference"), mapTree.list("alpha/reference"))
    check("tree: 不存在目录返回空表", fileTree.list("ghost").isEmpty() && mapTree.list("ghost").isEmpty())
    eq("tree: 读不存在的文件返回 null", null, mapTree.read("ghost/x.md"))
    eq("tree: 两边读同一文件一致", fileTree.read("beta/script/main.js"), mapTree.read("beta/script/main.js"))
}

// ---------- 空树与缺目录 ----------

private fun edgeCaseTests() {
    val empty = tempDir()
    val emptyScan = ModuleRegistry.scan(empty)
    eq("edge: 空目录没有模块", 0, emptyScan.registry.all().size)
    eq("edge: 空目录没有问题项", emptyList<String>(), emptyScan.problems)

    val emptyMap = ModuleRegistry.scan(MapModuleTree(emptyMap()))
    eq("edge: 空树没有模块", 0, emptyMap.registry.all().size)

    val missingRoot = File(empty, "does-not-exist")
    eq("edge: 目录不存在也没有模块", 0, ModuleRegistry.scan(missingRoot).registry.all().size)

    // 模块里一个空目录（assets 解压会留下空目录）：不该报错，也不该变成模块
    val withEmptyDir = tempDir()
    File(withEmptyDir, "hollow/script").mkdirs()
    val scan = ModuleRegistry.scan(withEmptyDir)
    eq("edge: 空目录不算模块", 0, scan.registry.all().size)
    eq("edge: 空目录不报问题", emptyList<String>(), scan.problems)

    // 只有 front matter 没有正文
    val bare = tempDir()
    write(bare, "bare/MODULE.md", "---\nid: bare\nname: 裸\n---\n")
    write(bare, "bare/script/main.js", "function (i, h) { return null; }")
    val bareScan = ModuleRegistry.scan(bare)
    eq("edge: 无正文也能加载", "bare", bareScan.registry.all().firstOrNull()?.id)
    eq("edge: 无正文不报问题", emptyList<String>(), bareScan.problems)
}

// ---------- skill 包兼容（只有 SKILL.md 的目录） ----------

private fun implicitModuleTests() {
    val root = tempDir()

    // 只 有 SKILL.md 的目录 → 隐式模块
    write(
        root, "novel-writing/SKILL.md",
        "---\nname: 小说写作\ndescription: 写小说用的\n---\n\n## 何时使用\n写故事时\n"
    )
    // 标准目录名：reference/ + script/
    write(root, "std/MODULE.md", "---\nid: std\nname: 标准\nhooks: []\n---\n")
    write(root, "std/reference/SKILL.md", "标准正文")
    // 复数目录名：references/ + scripts/
    write(root, "alt/MODULE.md", "---\nid: alt\nname: 复数\nhooks: [input]\n---\n")
    write(root, "alt/scripts/main.js", "function (i, h) { return null; }")
    write(root, "alt/references/SKILL.md", "复数正文")

    val scan = ModuleRegistry.scan(root)
    eq("implicit: 三个模块都能扫到", 3, scan.registry.all().size)
    eq("implicit: 扫描无问题", emptyList<String>(), scan.problems)

    val implicit = scan.registry.getPackage("novel_writing")
    check("implicit: 能扫到 novel_writing", implicit != null)
    if (implicit != null) {
        eq("implicit: hooks 为空", emptyList<String>(), implicit.manifest.hooks)
        eq("implicit: hasSkill=true", true, ModuleSelector.hasSkill(implicit))
    }
    if (implicit != null) {
        eq("implicit: name 取自 front matter", "小说写作", implicit.manifest.name)
        eq("implicit: description 取自 front matter", "写小说用的", implicit.manifest.description)
        eq("implicit: 不挂钩子", emptyList<String>(), implicit.manifest.hooks)
        check("implicit: SKILL.md 进了 references", implicit.reference("SKILL.md")?.contains("写故事时") == true)
    }

    eq("dirs: reference/ 可读", "标准正文", scan.registry.getReference("std", "SKILL.md"))
    eq("dirs: references/ 可读", "复数正文", scan.registry.getReference("alt", "SKILL.md"))
    check(
        "dirs: scripts/ 目录也认",
        scan.registry.getPackage("alt")?.script("main.js") != null
    )
}

// ---------- 重复前缀 ----------

private fun duplicatePrefixTests() {
    fun writePrefixModule(root: File, dir: String, id: String, prefix: String) {
        write(root, "$dir/MODULE.md", "---\nid: $id\nname: $id\nprefix: $prefix\nhooks: []\n---\n")
    }

    val dup = tempDir()
    writePrefixModule(dup, "a", "a", "附加")
    writePrefixModule(dup, "b", "b", "附加")
    val dupProblems = ModuleRegistry.scan(dup).problems
    check(
        "prefix: 重复前缀被报出来",
        dupProblems.any { it.startsWith("dup_prefix") },
        dupProblems.joinToString("; ")
    )

    val ok = tempDir()
    writePrefixModule(ok, "a", "a", "附加")
    writePrefixModule(ok, "b", "b", "小说")
    eq("prefix: 不同前缀不报", emptyList<String>(), ModuleRegistry.scan(ok).problems)
}

// ---------- assets 自身的健康度（demo 已冻结，不再逐模块对比） ----------

private fun assetHealthTest() {
    val repoRoot = System.getProperty("mychat.repoRoot")
    if (repoRoot == null) {
        println("SKIP  assets 健康校验（未设置 -Dmychat.repoRoot）")
        return
    }
    val assetRoot = File(repoRoot, "android/app/src/main/assets/modules")
    check("assets: 模块目录存在", assetRoot.isDirectory, assetRoot.path)
    if (!assetRoot.isDirectory) return

    val scan = ModuleRegistry.scan(assetRoot)
    val ids = scan.registry.all().map { it.id }.sorted()

    check("assets: 能扫描到模块", ids.isNotEmpty())
    // 配对审计会报"输出模块缺 Guide"，那是真实缺口，不该被断言抹平
    check(
        "assets: 没有重复前缀或结构缺失",
        scan.problems.none { it.startsWith("dup_prefix") || it.startsWith("no_structure") },
        scan.problems.joinToString("; ")
    )
    check(
        "assets: skill_code 的 Guide/Sensor/结构三项齐全",
        scan.problems.none { it.contains("skill_code") },
        scan.problems.joinToString("; ")
    )

    // 只钉住「必须有」的关键模块：新增模块直接进 assets，不再与 demo 对比
    val required = listOf("local_calc", "attach_context", "no_fluff", "debug_ctx")
    val missing = required - ids.toSet()
    eq("assets: 关键模块齐全", emptyList<String>(), missing)

    println("  模块清单（${ids.size} 个）: ${ids.joinToString(", ")}")

    // format_marks 是钩子 B 做格式检查的依据，解析不到就白声明了
    val code = scan.registry.getPackage("skill_code")
    check("assets: skill_code 存在", code != null)
    if (code != null) {
        eq(
            "assets: skill_code 的 format_marks 已解析",
            listOf("[必须改]", "[建议改]", "[可选]"),
            code.manifest.formatMarks
        )
    }
}
