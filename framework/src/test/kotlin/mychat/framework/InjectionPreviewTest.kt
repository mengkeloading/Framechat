package mychat.framework

import mychat.framework.hook.AuditLog
import mychat.framework.hook.GatedHostBridge
import mychat.framework.hook.HookAResult
import mychat.framework.hook.HookSession
import mychat.framework.hook.JsExecutor
import mychat.framework.hook.ModuleDispatcher
import mychat.framework.hook.ModuleRegistry
import mychat.framework.memory.Recall
import mychat.framework.memory.RecallHit
import java.io.File
import kotlin.system.exitProcess

/**
 * 把注入块原样打出来，并和快照比对。
 *
 * 为什么要打印：注入块是"模型实际看到的东西"，改 Markdown 之后到底变成什么样，
 * 光看文件看不出来（包装、禁止/要求节、使用声明都是拼出来的）。
 * 为什么要快照：这些块会漂——改一个标点，模型的行为就可能变。
 */
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

fun main() {
    val repoRoot = System.getProperty("mychat.repoRoot")
        ?: run {
            println("SKIP  需要 -Dmychat.repoRoot")
            return
        }
    val registry = ModuleRegistry.scan(
        File(repoRoot, "android/app/src/main/assets/modules")
    ).registry
    val snapshotDir = File(repoRoot, "framework/src/test/resources/snapshot")
    val update = System.getenv("SNAPSHOT_UPDATE") == "true"

    preview(registry, snapshotDir, update, "skill_code")
    preview(registry, snapshotDir, update, "skill_novel")
    previewWithMemory(registry)

    println()
    println("PASS $passed / ${passed + failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  - $it") }
        exitProcess(1)
    }
}

private fun preview(
    registry: ModuleRegistry,
    snapshotDir: File,
    update: Boolean,
    id: String,
) {
    val block = blockOf(registry, id)
    println("========== INJECT $id ==========")
    println(block)
    println("========== LEN=${block.length} ==========")
    val hasIntent = block.contains("【对话类型】")
    val hasForbid = block.contains("【禁止】")
    val hasRequire = block.contains("【要求】")
    val hasFormat = block.contains("## 输出格式")
    println("========== 含【对话类型】=$hasIntent 含【禁止】=$hasForbid 含【要求】=$hasRequire ==========")
    println("========== 含「## 输出格式」=$hasFormat ==========")

    check("$id: 注入块非空", block.isNotBlank())
    check("$id: 含【对话类型】", hasIntent)
    if (id == "skill_code") {
        fun sectionLines(title: String): List<String> {
            val start = block.indexOf(title)
            if (start < 0) return emptyList()
            val rest = block.substring(start + title.length)
            val next = rest.indexOf("【")
            val body = if (next < 0) rest else rest.substring(0, next)
            return body.lines().filter { it.startsWith("- ") }
        }
        val forbids = sectionLines("【禁止】")
        val requires = sectionLines("【要求】")
        check("skill_code: 禁止 5 条", forbids.size == 5, forbids.toString())
        check("skill_code: 要求 3 条", requires.size == 3, requires.toString())
    }
    if (id == "skill_code") {
        check("skill_code: 含【禁止】", hasForbid)
        check("skill_code: 含【要求】", hasRequire)
        check("skill_code: SKILL.md 含输出格式节", hasFormat)
    }

    snapshotDir.mkdirs()
    val file = File(snapshotDir, "${id}_block.txt")
    if (!file.exists()) {
        file.writeText(block)
        check("$id: 首次生成快照", true)
        println("  (snapshot created: ${file.path})")
        return
    }
    val expected = file.readText()
    if (update) {
        file.writeText(block)
        check("$id: 快照已按 SNAPSHOT_UPDATE 覆盖", true)
        return
    }
    val same = expected == block
    check("$id: 快照一致", same)
    if (!same) printDiff(expected, block)
}

/**
 * 带记忆的完整注入块：目录 + 召回 + 类型 + 技能。
 *
 * 这段是模型实际看到的顺序。单测断言只能验字段，读起来顺不顺要整段看，
 * 所以在 CI 日志里原样打出来。
 */
private fun previewWithMemory(registry: ModuleRegistry) {
    val entries = listOf(
        mychat.framework.memory.TagEntry("a", "标签A", 5, 1.5f),
        mychat.framework.memory.TagEntry("b", "标签B", 2, 0.4f),
    )
    val directory = mychat.framework.memory.DirectoryFormatter.format(entries)
    val recall = Recall.formatBlock(
        RecallHit(
            tagId = "a",
            tagName = "标签A",
            score = 0.82f,
            text = "决定用 Kotlin 重写模块系统",
            depth = "full",
            summary = "决定用 Kotlin 重写模块系统",
        )
    )
    val memoryBlock = mychat.framework.memory.MemoryInjection.compose(directory, recall)

    val runner = mychat.framework.hook.HookRunner(
        source = registry,
        executor = JsExecutor { _, _, _ -> null }
    )
    val result = runner.input(
        session = HookSession(id = "preview-mem", activeHardModules = listOf("skill_code")),
        text = "预览用输入",
        selected = setOf("skill_code"),
        intentText = mychat.framework.butler.PromptByIntent.textOf("code"),
        memoryText = memoryBlock,
    )
    val block = (result as? HookAResult.Context)?.extra.orEmpty()

    println("========== INJECT with memory ==========")
    println(block)
    println("========== LEN=${block.length} ==========")

    check("memory: 输出含【记忆目录】", block.contains("【记忆目录】"), block.take(120))
    val dirAt = block.indexOf("【记忆目录】")
    val recallAt = block.indexOf("【回忆】")
    check("memory: 目录在回忆之前", dirAt >= 0 && recallAt > dirAt, "dir=$dirAt recall=$recallAt")
    check("memory: 弱标签标淡化", block.contains("标签B（2 条，正在淡化）"), block.take(200))
    check("memory: 召回给了摘要", block.contains("决定用 Kotlin 重写模块系统"), block.take(400))
}

private fun blockOf(registry: ModuleRegistry, id: String): String {
    // 走 HookRunner（而不是 dispatcher）：预览要还原 App 的真实拼装，含【对话类型】
    val runner = mychat.framework.hook.HookRunner(
        source = registry,
        executor = JsExecutor { _, _, _ -> null }
    )
    val intent = if (id == "skill_code") "code" else "writing"
    val result = runner.input(
        session = HookSession(id = "preview", activeHardModules = listOf(id)),
        text = "预览用输入",
        selected = setOf(id),
        intentText = mychat.framework.butler.PromptByIntent.textOf(intent)
    )
    return (result as? HookAResult.Context)?.extra.orEmpty()
}

private fun printDiff(expected: String, actual: String) {
    val a = expected.lines()
    val b = actual.lines()
    println("---------- SNAPSHOT DIFF ----------")
    for (i in 0 until maxOf(a.size, b.size)) {
        val left = a.getOrNull(i)
        val right = b.getOrNull(i)
        if (left != right) {
            println("line ${i + 1}:")
            println("  expected: ${left ?: "<none>"}")
            println("  actual  : ${right ?: "<none>"}")
            break
        }
    }
    println("-----------------------------------")
}
