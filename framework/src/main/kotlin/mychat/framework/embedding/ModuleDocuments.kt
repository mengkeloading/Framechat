package mychat.framework.embedding

import mychat.framework.hook.ModulePackage

/**
 * 模块用于向量化的那段文本。
 *
 * 只用 description 不够（作者写得抽象，比如「把模糊的想法拆成任务」），
 * 但把 SKILL.md 全文塞进去更糟：`## 流程`、`## 硬约束`、`## 它不做什么`
 * 是所有模块共有的模板结构，进向量后变成共同噪声，反而拉低区分度
 * （实测 8 条查询从 8/8 TOP1 掉到 6/8）。
 *
 * 所以只取「何时使用」一节；没有这一节就退回 description。
 */
object ModuleDocuments {

    const val SECTION_HEADING = "何时使用"

    private const val SKILL_FILE = "SKILL.md"

    fun textOf(pkg: ModulePackage): String = buildString {
        append(pkg.manifest.name)
        append('\n')
        append(pkg.manifest.description)
        val section = whenSection(pkg)
        if (section.isNotEmpty()) {
            append('\n')
            append(section)
        }
    }

    /**
     * 「何时使用」优先到 reference/SKILL.md 里找，再退到 MODULE.md 正文，
     * 都没有就返回空串（调用方只剩 description）。
     */
    fun whenSection(pkg: ModulePackage): String {
        val fromSkill = pkg.reference(SKILL_FILE)
            ?.let { sectionOf(it, SECTION_HEADING) }
            .orEmpty()
        if (fromSkill.isNotEmpty()) return fromSkill
        return sectionOf(pkg.body, SECTION_HEADING)
    }

    /** 取 `## 何时使用` 到下一个 `## ` 之间的内容；找不到返回空串。 */
    fun sectionOf(body: String, heading: String): String {
        if (body.isEmpty()) return ""
        val lines = body.split('\n')
        var start = -1
        for (i in lines.indices) {
            if (isHeading(lines[i]) && lines[i].removePrefix("##").trim().startsWith(heading)) {
                start = i + 1
                break
            }
        }
        if (start < 0) return ""
        val collected = mutableListOf<String>()
        for (i in start until lines.size) {
            val line = lines[i]
            if (isHeading(line)) break
            collected += line
        }
        return collected.joinToString("\n").trim()
    }

    private fun isHeading(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("## ") || trimmed == "##"
    }
}
