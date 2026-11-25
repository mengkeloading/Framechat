package mychat.framework

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
    assetSyncTest()

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

// ---------- assets 与 demo 的模块集合必须一致 ----------

private fun assetSyncTest() {
    val repoRoot = System.getProperty("mychat.repoRoot")
    if (repoRoot == null) {
        println("SKIP  模块同步校验（未设置 -Dmychat.repoRoot）")
        return
    }
    val demoRoot = File(repoRoot, "chat-demo-main/modules")
    val assetRoot = File(repoRoot, "android/app/src/main/assets/modules")

    if (!demoRoot.isDirectory || !assetRoot.isDirectory) {
        check("sync: 两个模块目录都存在", false, "demo=${demoRoot.isDirectory} assets=${assetRoot.isDirectory}")
        return
    }

    val demo = ModuleRegistry.scan(demoRoot)
    val asset = ModuleRegistry.scan(assetRoot)
    val demoIds = demo.registry.all().map { it.id }.sorted()
    val assetIds = asset.registry.all().map { it.id }.sorted()

    eq("sync: 模块 id 集合一致", demoIds, assetIds)
    eq("sync: 模块数量一致", demoIds.size, assetIds.size)
    eq("sync: assets 侧没有加载问题", emptyList<String>(), asset.problems)
    eq("sync: demo 侧没有加载问题", emptyList<String>(), demo.problems)

    val missing = demoIds - assetIds.toSet()
    val extra = assetIds - demoIds.toSet()
    if (missing.isNotEmpty() || extra.isNotEmpty()) {
        println("  demo 有、assets 没有: $missing")
        println("  assets 有、demo 没有: $extra")
    }
    println("  模块清单（${assetIds.size} 个）: ${assetIds.joinToString(", ")}")
}
