package mychat.framework.directive

/**
 * B 与 UI 之间的指令契约。
 *
 * **命名空间**：只有 `[[!` 开头的是框架标记；`[[` 单独出现一律当普通文本。
 * 理由：`[[` 在代码、Markdown、wiki link 里都常见，容易误伤。
 */
interface Directive {
    val name: String

    /** inner 是已经处理过的内层文本；返回替换后的文本。 */
    fun apply(inner: String): String
}

data class DirectiveTrace(
    val name: String,
    /** applied | unknown | unclosed | depth | ignored */
    val status: String,
    /** 在原始文本里的区间；未识别的闭合标记只记标记本身。 */
    val range: IntRange?,
    /** 元信息型指令携带的值（如 USED 的模块 id 列表）。 */
    val value: String? = null
)

data class ProcessResult(
    val text: String,
    val log: List<DirectiveTrace>,
    /** 元信息型指令的产出，例如 used=[skill_code, skill_novel]。 */
    val meta: Map<String, List<String>> = emptyMap()
)

/** 删除 `[[!HIDE]]…[[!/HIDE]]` 之间的内容。 */
object HideDirective : Directive {
    override val name: String = "HIDE"

    override fun apply(inner: String): String = ""
}

/**
 * 只识别、不实现的指令名，占位给 Compose UI 就位后接。
 * 登记在这里的名字不会当成 unknown，但也不会被执行。
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
     * - 不认识的框架标记（`[[!FOO:…]]`）剥离并记 unknown；
     * - 只有开标记没有闭标记（或反过来）→ 剥离标记、保留内容，记 unclosed；
     * - 最后清掉所有残留的框架标记与泄漏的框架段落（【已加载技能】/【使用声明】）。
     */
    fun process(raw: String): ProcessResult {
        val log = mutableListOf<DirectiveTrace>()
        val meta = LinkedHashMap<String, MutableList<String>>()
        // 自闭合的元信息指令先处理掉，剩下的才是成对指令
        val withoutExpand = extractExpand(raw, log, meta)
        val withoutMeta = extractUsed(withoutExpand, log, meta)
        val rendered = render(withoutMeta, 0, withoutMeta.length, depth = 0, log = log)
        val stripped = stripMarkers(rendered)
        val cleaned = stripFrameworkSections(stripped)
        // 删掉指令块后往往会留下一个孤立的换行（「正文\n[[!HIDE]]…」）
        return ProcessResult(cleaned.trimEnd(), log, meta)
    }

    /** `[[!USED:a,b]]`：删标记、把 id 收进 meta。不是包裹型，不需要闭合。 */
    private fun extractUsed(
        raw: String,
        log: MutableList<DirectiveTrace>,
        meta: MutableMap<String, MutableList<String>>,
    ): String {
        if (!raw.contains(MARKER_PREFIX + USED_NAME + ":")) return raw
        val out = StringBuilder(raw.length)
        var cursor = 0
        for (m in USED_PATTERN.findAll(raw)) {
            out.append(raw, cursor, m.range.first)
            val ids = m.groupValues[1]
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            if (ids.isEmpty()) {
                log += DirectiveTrace(USED_NAME, "ignored", m.range)
            } else {
                val used = meta.getOrPut(META_USED) { mutableListOf() }
                for (id in ids) if (id !in used) used += id
                log += DirectiveTrace(USED_NAME, "applied", m.range, ids.joinToString(","))
            }
            cursor = m.range.last + 1
        }
        out.append(raw, cursor, raw.length)
        return out.toString()
    }

    /**
     * `[[!EXPAND:标签名]]`：删标记、把名字收进 meta，最多留 [MAX_EXPANDS] 个。
     *
     * 为什么限量：每多展开一个标签，下一轮就多一段内容。模型一次要三个以上，
     * 说明它自己也说不清想看什么，多给只会更乱。
     */
    private fun extractExpand(
        raw: String,
        log: MutableList<DirectiveTrace>,
        meta: MutableMap<String, MutableList<String>>,
    ): String {
        if (!raw.contains(MARKER_PREFIX + EXPAND_NAME + ":")) return raw
        val out = StringBuilder(raw.length)
        var cursor = 0
        val names = meta.getOrPut(META_EXPAND) { mutableListOf() }
        for (m in EXPAND_PATTERN.findAll(raw)) {
            out.append(raw, cursor, m.range.first)
            val name = m.groupValues[1].trim()
            if (name.isEmpty()) {
                log += DirectiveTrace(EXPAND_NAME, "ignored", m.range)
            } else if (names.size >= MAX_EXPANDS) {
                log += DirectiveTrace(EXPAND_NAME, "ignored", m.range, name)
            } else {
                if (name !in names) names += name
                log += DirectiveTrace(EXPAND_NAME, "applied", m.range, name)
            }
            cursor = m.range.last + 1
        }
        out.append(raw, cursor, raw.length)
        return out.toString()
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
                // 认识的指令多了个闭合标记才叫未闭合；不认识的标记直接丢
                if (directive == null) {
                    log += DirectiveTrace(name, "unknown", m.range)
                } else {
                    log += DirectiveTrace(name, "unclosed", m.range)
                }
                i = m.range.last + 1
                continue
            }

            // 不认识的框架标记：剥离，但里面的内容继续处理
            if (directive == null) {
                log += DirectiveTrace(name, "unknown", m.range)
                i = m.range.last + 1
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

    /** 同名标记要配对：`[[!HIDE]]a[[!HIDE]]b[[!/HIDE]]c[[!/HIDE]]` 只匹配最外层。 */
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

    /** 兜底：任何漏网的框架标记都不该出现在用户眼前。 */
    private fun stripMarkers(text: String): String =
        if (!text.contains(MARKER_PREFIX)) text else LEFTOVER.replace(text, "")

    /**
     * 模型可能把系统提示里的结构照抄出来（【已加载技能】/【使用声明】）。
     * 这里整段剥掉：从标题行开始，到空行、或到下一个 `【…】` 标题为止。
     */
    private fun stripFrameworkSections(text: String): String {
        if (!text.contains("【已加载技能】") && !text.contains("【使用声明】")) return text
        // 没有结束标记时只删标题那一行，避免把后面的正常内容一起吃掉
        val hasBlockEnd = text.contains("【技能结束】")
        val out = mutableListOf<String>()
        var skipping = false
        for (line in text.lines()) {
            val t = line.trim()
            if (skipping) {
                when {
                    t.startsWith("【技能结束】") -> skipping = false
                    t.startsWith("【") -> {
                        skipping = false
                        out += line
                    }
                    else -> Unit
                }
                continue
            }
            if (t.startsWith("【已加载技能】")) {
                skipping = hasBlockEnd
                continue
            }
            if (t.startsWith("【使用声明】")) {
                skipping = true
                continue
            }
            out += line
        }
        return out.joinToString("\n")
    }

    companion object {
        const val MAX_DEPTH = 5

        const val USED_NAME = "USED"
        const val META_USED = "used"

        const val EXPAND_NAME = "EXPAND"
        const val META_EXPAND = "expand"

        /** 一次最多展开两个：再多就是噪音。 */
        const val MAX_EXPANDS = 2

        /** 框架专属命名空间。`[[` 后面没有 `!` 的一律不是标记。 */
        const val MARKER_PREFIX = "[[!"

        private val MARKER =
            Regex("\\[\\[!(/?)([A-Za-z][A-Za-z0-9]*)(?::([^\\]]*))?\\]\\]")
        private val USED_PATTERN = Regex("\\[\\[!USED:([^\\]]+)\\]\\]")
        private val EXPAND_PATTERN = Regex("\\[\\[!EXPAND:([^\\]]+)\\]\\]")
        private val LEFTOVER = Regex("\\[\\[![^\\]]*\\]\\]")

        /**
         * 用户输入里的框架标记必须失效，否则用户能自己写 `[[!HIDE]]` 把上下文删掉。
         * 只转义 `[[!`：普通 `[[` 出现在代码或 wiki link 里很正常，不能动。
         */
        fun escapeUserInput(text: String): String = text.replace(MARKER_PREFIX, "[\\[!")

        /**
         * 组包时对**所有 user 消息**（含历史）转义，assistant 不动 ——
         * 否则模型就见不到合法指令的样例，学不会输出。
         * 落库走的是原文，这个函数只影响发给上游的 content。
         */
        fun escapeForRequest(content: String, isUser: Boolean): String =
            if (isUser) escapeUserInput(content) else content
    }
}
