package mychat.framework.directive

/**
 * B 与 UI 之间的指令契约。
 *
 * 指令写在模型输出里，形如 `[[HIDE]]…[[/HIDE]]`；本轮只实现「纯文本上就能生效」的，
 * 需要 UI 的（折叠/表格/卡片）只留接口。
 */
interface Directive {
    val name: String

    /** inner 是已经处理过的内层文本；返回替换后的文本。 */
    fun apply(inner: String): String
}

data class DirectiveTrace(
    val name: String,
    /** applied | unknown | unclosed | depth */
    val status: String,
    /** 在原始文本里的区间；未识别的闭合标记只记标记本身。 */
    val range: IntRange?
)

data class ProcessResult(val text: String, val log: List<DirectiveTrace>)

/** 删除 `[[HIDE]]…[[/HIDE]]` 之间的内容。 */
object HideDirective : Directive {
    override val name: String = "HIDE"

    override fun apply(inner: String): String = ""
}

/**
 * 只识别、不实现的指令名，占位给 Compose UI 就位后接。
 * 在这里登记的名字不会被当成 unknown。
 */
object DirectiveNames {
    const val FOLD = "FOLD"
    const val TABLE = "TABLE"
    const val CARD = "CARD"
}

class DirectiveEngine(
    private val directives: Map<String, Directive> = mapOf(HideDirective.name to HideDirective),
) {

    /**
     * 处理一条模型输出。规则：
     * - 认识的指令（当前只有 HIDE）由内向外执行，嵌套深度上限 [MAX_DEPTH]；
     * - 不认识的标记原样保留，记 unknown；
     * - 只有开标记没有闭标记（或反过来）→ 剥离标记本身、保留内容，记 unclosed。
     */
    fun process(raw: String): ProcessResult {
        val log = mutableListOf<DirectiveTrace>()
        val text = render(raw, 0, raw.length, depth = 0, log = log)
        return ProcessResult(text, log)
    }

    private fun render(
        raw: String,
        from: Int,
        to: Int,
        depth: Int,
        log: MutableList<DirectiveTrace>
    ): String {
        val out = StringBuilder()
        var i = from
        while (i < to) {
            val m = MARKER.find(raw, i)
            if (m == null || m.range.first >= to) {
                out.append(raw, i, to)
                break
            }
            out.append(raw, i, m.range.first)
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].uppercase()
            val directive = directives[name]

            if (closing) {
                // 认识的指令多了个闭合标记才叫未闭合；不认识的标记整体保留
                if (directive == null) {
                    log += DirectiveTrace(name, "unknown", m.range)
                    out.append(raw, m.range.first, m.range.last + 1)
                } else {
                    log += DirectiveTrace(name, "unclosed", m.range)
                }
                i = m.range.last + 1
                continue
            }

            // 不认识的指令先判：有配对就整段保留，没配对就只保留标记
            if (directive == null) {
                val outerClose = findMatchingClose(raw, name, m.range.last + 1, to)
                if (outerClose == null) {
                    log += DirectiveTrace(name, "unknown", m.range)
                    out.append(raw, m.range.first, m.range.last + 1)
                    i = m.range.last + 1
                } else {
                    log += DirectiveTrace(name, "unknown", m.range.first..outerClose.range.last)
                    out.append(raw, m.range.first, outerClose.range.last + 1)
                    i = outerClose.range.last + 1
                }
                continue
            }

            val close = findMatchingClose(raw, name, m.range.last + 1, to)
            if (close == null) {
                log += DirectiveTrace(name, "unclosed", m.range)
                i = m.range.last + 1
                continue
            }

            if (depth >= MAX_DEPTH) {
                log += DirectiveTrace(name, "depth", m.range.first..close.range.last)
                out.append(raw, m.range.first, close.range.last + 1)
                i = close.range.last + 1
                continue
            }

            val inner = render(raw, m.range.last + 1, close.range.first, depth + 1, log)
            log += DirectiveTrace(name, "applied", m.range.first..close.range.last)
            out.append(directive.apply(inner))
            i = close.range.last + 1
        }
        return out.toString()
    }

    /** 同名标记要配对：`[[HIDE]]a[[HIDE]]b[[/HIDE]]c[[/HIDE]]` 只匹配最外层。 */
    private fun findMatchingClose(raw: String, name: String, from: Int, to: Int): MatchResult? {
        var depth = 1
        var i = from
        while (i < to) {
            val m = MARKER.find(raw, i) ?: return null
            if (m.range.first >= to) return null
            if (m.groupValues[2].uppercase() == name) {
                depth += if (m.groupValues[1] == "/") -1 else 1
                if (depth == 0) return m
            }
            i = m.range.last + 1
        }
        return null
    }

    companion object {
        const val MAX_DEPTH = 5

        private val MARKER = Regex("\\[\\[(/?)([A-Za-z][A-Za-z0-9]*)\\]\\]")

        /**
         * 用户输入里的 `[[` 必须失效，否则用户能自己写 `[[HIDE]]` 把上下文删掉。
         * 转义后 `[[` 变成 `[\[`，不再匹配标记正则。
         */
        fun escapeUserInput(text: String): String = text.replace("[[", "[\\[")
    }
}
