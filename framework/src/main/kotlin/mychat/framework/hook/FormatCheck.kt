package mychat.framework.hook

/**
 * 格式检查的完整结论。
 *
 * 只说"缺"不够——补救提示词需要**点名缺哪个标记**，否则模型只能猜着重排，
 * 这就是"前馈"和"指令"的区别。
 */
data class FormatCheckResult(
    val moduleId: String,
    /** 模块声明的全部标记。 */
    val required: List<String>,
    /** 实际缺的那些。 */
    val missing: List<String>
) {
    val ok: Boolean get() = missing.isEmpty()

    /** 日志用：ok / missing([必须改],[建议改]) */
    fun logValue(): String =
        if (ok) "ok" else "missing(" + missing.joinToString(",") + ")"

    companion object {
        /** 声明了 format_marks 的模块才算检查对象；没声明返回 null。 */
        fun of(moduleId: String, required: List<String>, text: String): FormatCheckResult? {
            if (required.isEmpty()) return null
            val missing = required.filterNot { text.contains(it) }
            return FormatCheckResult(moduleId, required, missing)
        }
    }
}
