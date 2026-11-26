package mychat.framework.hook

/**
 * 格式检查的结论。
 *
 * 两个层次：
 * - `format_marks`：**任一**出现就算用了格式（"用了"和"用全"是两回事）
 * - `format_required`：声明了就逐个检查，用于补救时点名缺哪个
 *
 * 为什么这么分：`skill_code` 的规范本身写着"没问题的层级直接省略"，
 * 要求三个分级标记全出现就会把合规回答判成不合规，每轮白跑一次补救。
 */
data class FormatCheckResult(
    val moduleId: String,
    /** 模块声明的分级标记（任一即可）。 */
    val marks: List<String>,
    /** 必须出现的标记（逐项检查）。 */
    val required: List<String>,
    /** 实际缺的 required。 */
    val missing: List<String>,
    /** marks/required 里是否至少出现了一个。 */
    val anyUsed: Boolean
) {
    val ok: Boolean get() = anyUsed && missing.isEmpty()

    /** 一个标记都没用 —— 补救时要重写格式，不是补齐。 */
    val noFormatAtAll: Boolean get() = !anyUsed

    fun logValue(): String = when {
        ok -> "ok"
        noFormatAtAll -> "missing"
        else -> "missing(" + missing.joinToString(",") + ")"
    }

    companion object {
        /** marks 与 required 都为空时返回 null（不检查）。 */
        fun of(
            moduleId: String,
            marks: List<String>,
            text: String,
            required: List<String> = emptyList()
        ): FormatCheckResult? {
            if (marks.isEmpty() && required.isEmpty()) return null
            val anyUsed = marks.any { text.contains(it) } || required.any { text.contains(it) }
            val missing = required.filterNot { text.contains(it) }
            return FormatCheckResult(moduleId, marks, required, missing, anyUsed)
        }
    }
}
