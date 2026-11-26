package mychat.framework.hook

import mychat.framework.embedding.ModuleDocuments

/**
 * 模块声明的配对检查：光有 Sensor（format_marks）或光有 Guide（forbid/require），
 * 都会让某一侧的能力空转。
 *
 * 三条规则都来自踩过的坑：
 * - 输出模块没有 Guide → 模型只知道"要改"，不知道"改成什么样"
 * - 有 marks 却没有输出结构 → 补救永远拿不到模板（曾静默失效过一轮）
 * - 有 marks 却没有 Guide → 检查得出 missing，但没有可执行的修正要求
 */
object ManifestAudit {

    val STRUCTURE_HEADINGS = listOf("输出格式", "输出结构")

    fun auditGuides(pkgs: List<ModulePackage>): List<String> {
        val problems = mutableListOf<String>()
        for (p in pkgs) {
            val hasOutput = p.manifest.hooks.contains("output")
            val hasGuide = p.manifest.forbid.isNotEmpty() || p.manifest.requires.isNotEmpty()
            val hasMarks = p.manifest.formatMarks.isNotEmpty()
            val hasStructure = structureOf(p) != null

            if (hasOutput && !hasGuide) {
                problems += "no_guides: " + p.id + "（hooks=[output] 但 forbid/require 都为空）"
            }
            if (hasMarks && !hasStructure) {
                problems += "no_structure: " + p.id +
                    "（声明了 format_marks 但 SKILL.md 无「输出格式」节）"
            }
            if (hasMarks && !hasGuide) {
                problems += "sensor_without_guide: " + p.id +
                    "（有 format_marks 但没有 forbid/require）"
            }
        }
        return problems
    }

    fun structureOf(p: ModulePackage): String? {
        val skill = p.reference("SKILL.md") ?: return null
        for (heading in STRUCTURE_HEADINGS) {
            val section = ModuleDocuments.sectionOf(skill, heading)
            if (section.isNotEmpty()) return section
        }
        return null
    }
}
