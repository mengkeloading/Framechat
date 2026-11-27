package mychat.framework.hook

import mychat.framework.json.JsonValue
import mychat.framework.json.parseJson

/**
 * 钩子调度：只调度、只合并，执行一律交给 JsExecutor。
 * 契约见 docs/FRAMEWORK.md 第 1-3 节：异常一律 fail-open 当 pass。
 */
class ModuleDispatcher(
    private val source: ModuleSource,
    private val executors: ExecutorRegistry,
    private val bridges: HostBridgeFactory,
    private val maxInvokeDepth: Int = 3,
    private val historyLimit: Int = 20,
    /** 格式检查结果的外部回调（App 层写日志用；框架不依赖 Android）。 */
    private val onFormatCheck: ((FormatCheckResult) -> Unit)? = null,
    /** 调试日志回调。 */
    private val onDebug: ((String) -> Unit)? = null
) {

    /** 兼容入口：只给一个 JsExecutor 时，等价于只注册了 js 引擎。 */
    constructor(
        source: ModuleSource,
        executor: JsExecutor,
        bridges: HostBridgeFactory,
        maxInvokeDepth: Int = 3,
        historyLimit: Int = 20,
        onFormatCheck: ((FormatCheckResult) -> Unit)? = null,
        onDebug: ((String) -> Unit)? = null
    ) : this(
        source = source,
        executors = ExecutorRegistry(listOf(JsExecutorAdapter(executor))),
        bridges = bridges,
        maxInvokeDepth = maxInvokeDepth,
        historyLimit = historyLimit,
        onFormatCheck = onFormatCheck,
        onDebug = onDebug
    )

    private val log = mutableListOf<Trace>()

    val traces: List<Trace> get() = synchronized(log) { log.toList() }

    fun clearTraces() = synchronized(log) { log.clear() }

    fun dispatchInput(
        session: HookSession,
        rawInput: String,
        history: List<Message> = emptyList(),
        attachments: List<Attachment> = emptyList(),
        /** 只有这些模块的 context 会被收集（前缀强制 / 路由结果）。 */
        selected: Set<String> = emptySet(),
        /** 本轮是否由前缀强制触发。 */
        forced: Boolean = false
    ): HookAResult {
        val chain = source.idsForPhase(session.activeHardModules, HOOK_INPUT)
        // 链为空但选择了知识型模块时仍要往下走：注入由框架补，不靠钩子
        if (chain.isEmpty() && selected.isEmpty()) return HookAResult.Pass(rawInput)

        val ctx = contextJson(session, null, history, attachments, forced)
        var text = rawInput
        val blocks = mutableListOf<SkillBlock>()

        for (id in chain) {
            val reply = call(id, envelope(rawInput, PHASE_A, ctx), PHASE_A) ?: continue
            when (reply.type) {
                "local" -> {
                    trace("hookA", id, "local")
                    return HookAResult.Local(reply.text ?: "")
                }
                "context" -> {
                    trace("hookA", id, "context")
                    // 执行全跑、收集只看 selected：没被选中的模块的 context 丢掉
                    if (id in selected) {
                        reply.extra?.takeIf { it.isNotEmpty() }?.let { extra ->
                            val pkg = source.getPackage(id)
                            blocks += SkillBlock(
                                id = id,
                                name = pkg?.manifest?.name ?: id,
                                description = pkg?.manifest?.description.orEmpty(),
                                extra = extra,
                                forbids = expandedForbids(pkg),
                                requires = expandedRequires(pkg)
                            )
                        }
                    }
                    reply.text?.takeIf { it.isNotEmpty() }?.let { text = it }
                }
                else -> {
                    trace("hookA", id, "pass")
                    reply.text?.takeIf { it.isNotEmpty() }?.let { text = it }
                }
            }
        }

        appendSkillFallbacks(blocks, session, selected)
        return if (blocks.isEmpty()) {
            HookAResult.Pass(text)
        } else {
            HookAResult.Context(text, renderContext(blocks))
        }
    }

    /**
     * 注入职责上移：selected 里的模块自己没返回 context 时，
     * 框架直接读它的 SKILL.md 补上。
     *
     * 这样 `hooks: []` 的知识型模块（skill_code / skill_product）被路由命中时
     * 也能真的注入内容，而不是“选中了但什么都没发生”。
     */
    private fun appendSkillFallbacks(
        blocks: MutableList<SkillBlock>,
        session: HookSession,
        selected: Set<String>,
    ) {
        if (selected.isEmpty()) return
        for (id in session.activeHardModules.filter { it in selected }) {
            if (blocks.any { it.id == id }) continue
            val pkg = source.getPackage(id)
            if (pkg == null) {
                trace("hookA", id, "selected_but_missing")
                continue
            }
            val content = pkg.reference(SKILL_FILE)
            if (content.isNullOrBlank()) {
                trace("hookA", id, "selected_but_empty")
                onDebug?.invoke("inject:skip " + id + " reason=no_skill")
                continue
            }
            // 注入的是「知识正文」，不含 SKILL.md 的 front matter（name/keywords 是给工具看的）
            val body = FrontMatter.parse(content)?.body?.takeIf { it.isNotEmpty() } ?: content
            blocks += SkillBlock(
                id = id,
                name = pkg.manifest.name,
                description = pkg.manifest.description,
                extra = body,
                forbids = expandedForbids(pkg),
                requires = expandedRequires(pkg)
            )
            trace("hookA", id, "skill_injected")
        }
    }

    /**
     * 把各模块的 extra 包成带身份的技能块，再追一段使用声明要求。
     *
     * 包装放在框架侧统一做：模块作者只写“规范正文”，不用关心格式，
     * 也不会出现有的模块写了 id、有的没写。身份信息让模型能把它看到的
     * 每段规范对应到具体模块，末尾的声明要求才有东西可写。
     */
    private fun renderContext(blocks: List<SkillBlock>): String = buildString {
        for (b in blocks) {
            append("【已加载技能】\n")
            append("id: ").append(b.id).append('\n')
            append("name: ").append(b.name).append('\n')
            append("说明：").append(b.description).append('\n')
            append('\n')
            append("规范：\n")
            append(b.extra).append('\n')
            if (b.forbids.isNotEmpty() || b.requires.isNotEmpty()) append('\n')
            if (b.forbids.isNotEmpty()) {
                append("【禁止】\n")
                for (line in b.forbids) append("- ").append(line).append('\n')
                append('\n')
            }
            if (b.requires.isNotEmpty()) {
                append("【要求】\n")
                for (line in b.requires) append("- ").append(line).append('\n')
                append('\n')
            }
            append("【技能结束】\n\n")
        }
        append(USAGE_DECLARATION)
    }

    private class SkillBlock(
        val id: String,
        val name: String,
        val description: String,
        val extra: String,
        val forbids: List<String> = emptyList(),
        val requires: List<String> = emptyList()
    )

    fun dispatchOutput(
        session: HookSession,
        rawText: String,
        input: String? = null,
        history: List<Message> = emptyList(),
        attachments: List<Attachment> = emptyList(),
        /** 本轮被选中的模块；目前只用来做格式检查。 */
        selected: Set<String> = emptySet()
    ): HookBResult {
        val result = computeOutput(session, rawText, input, history, attachments)
        checkFormats(selected, result.text)
        judgeOutput(selected, result.text, input.orEmpty())
        return result
    }

    /**
     * 格式检查：只记录、不补救。
     * 先搞清楚「格式不遵守」的频率，再决定要不要让模型重来。
     */
    /** 输出质检：只记日志，不拦截、不重试。 */
    private fun judgeOutput(selected: Set<String>, text: String, userInput: String) {
        if (selected.isEmpty()) return
        val constraints = selected.flatMap { id ->
            source.getPackage(id)?.manifest?.constraints.orEmpty()
        }
        val warnings = OutputJudge.judge(text, userInput, constraints.distinct())
        for (warning in warnings) {
            onDebug?.invoke("judge:" + warning.rule + " " + warning.logArg)
        }
    }

    /** 返回每个「声明了 format_marks」的模块的检查结果；没声明的模块不出现。 */
    fun checkFormats(selected: Set<String>, text: String): List<FormatCheckResult> {
        if (selected.isEmpty()) return emptyList()
        val results = mutableListOf<FormatCheckResult>()
        for (id in selected) {
            val manifest = source.getPackage(id)?.manifest
            val marks = manifest?.formatMarks.orEmpty()
            val required = manifest?.formatRequired.orEmpty()
            onDebug?.invoke("fmt:marks id=" + id + " size=" + marks.size)
            val result = FormatCheckResult.of(id, marks, text, required) ?: continue
            trace("format_check", id, if (result.ok) "ok" else "missing")
            onFormatCheck?.invoke(result)
            results += result
        }
        return results
    }

    private fun computeOutput(
        session: HookSession,
        rawText: String,
        input: String? = null,
        history: List<Message> = emptyList(),
        attachments: List<Attachment> = emptyList()
    ): HookBResult {
        val chain = source.idsForPhase(session.activeHardModules, HOOK_OUTPUT)
        if (chain.isEmpty()) return HookBResult.Pass(rawText)

        val ctx = contextJson(session, input, history, attachments)
        var hit: Reply? = null
        var hitName: String? = null
        for (id in chain) {
            val r = call(id, envelope(rawText, PHASE_B, ctx), PHASE_B)
            if (r == null) continue
            if (r.type != "pass") {
                hit = r
                hitName = id
                break
            }
            trace("hookB", id, "pass")
        }
        if (hit == null) return HookBResult.Pass(rawText)

        var depth = 0
        val visited = mutableListOf<String>()
        var current: Reply? = hit
        var currentName = hitName

        while (current != null) {
            when (current.type) {
                "rewrite" -> {
                    trace("hookB", currentName, "rewrite")
                    return HookBResult.Rewrite(current.text?.takeIf { it.isNotEmpty() } ?: rawText)
                }
                "invoke" -> {
                    val target = current.moduleName
                    depth += 1
                    if (depth > maxInvokeDepth || target.isNullOrEmpty() ||
                        source.getPackage(target) == null || visited.contains(target)
                    ) {
                        trace("hookB", currentName, "invoke 中止 depth=$depth -> pass")
                        return HookBResult.Pass(rawText)
                    }
                    visited += target
                    currentName = target
                    val payload = current.payload?.takeIf { it.isNotEmpty() } ?: rawText
                    current = call(target, envelope(payload, PHASE_B, ctx), PHASE_B)
                }
                else -> return HookBResult.Pass(rawText)
            }
        }
        return HookBResult.Pass(rawText)
    }

    private fun call(id: String, envJson: String, phase: String): Reply? {
        val pkg = source.getPackage(id)
        if (pkg == null) {
            trace("hook", id, "模块不存在 fail-open")
            return null
        }
        val outcome = try {
            executors.forModule(pkg).run(pkg, phase, envJson, bridges.create(pkg))
        } catch (e: Exception) {
            trace("hook", id, "执行异常 fail-open: ${e.message}")
            return null
        }
        val raw = when (outcome) {
            is ExecutorOutcome.Skipped -> {
                trace("hook", id, "skipped: ${outcome.reason}")
                return null
            }
            is ExecutorOutcome.Produced -> outcome.raw
        }
        if (raw.isNullOrEmpty()) return null

        val obj = parseJson(raw) as? JsonValue.Obj
        if (obj == null) {
            trace("hook", id, "非法 JSON fail-open")
            return null
        }
        val type = obj.str("type")
        if (type == null || !isLegal(type, phase)) {
            trace("hook", id, "phase=$phase 非法类型 $type fail-open")
            return null
        }
        val extra = obj["extra"]?.let { if (it is JsonValue.Str) it.value else it.encode() }
        return Reply(
            type = type,
            text = obj.str("text"),
            extra = extra,
            moduleName = obj.str("moduleName"),
            payload = obj.str("payload")
        )
    }

    private fun envelope(text: String, phase: String, ctx: JsonValue): String =
        JsonValue.of(
            "text" to JsonValue.Str(text),
            "phase" to JsonValue.Str(phase),
            "ctx" to ctx
        ).encode()

    private fun contextJson(
        session: HookSession,
        input: String?,
        history: List<Message>,
        attachments: List<Attachment>,
        forced: Boolean = false
    ): JsonValue = JsonValue.of(
        "sessionId" to JsonValue.Str(session.id),
        "session" to session.toJson(),
        "messages" to JsonValue.array(history.takeLast(historyLimit).map { it.toJson() }),
        "attachments" to JsonValue.array(attachments.map { it.toMetaJson() }),
        "input" to input?.let { JsonValue.Str(it) },
        "forced" to JsonValue.Bool(forced)
    )

    /** 枚举文案在前，自由文本在后：枚举是"该模块的骨架"，自由文本是补充。 */
    private fun expandedForbids(pkg: ModulePackage?): List<String> {
        if (pkg == null) return emptyList()
        return ConstraintText.forbids(pkg.manifest.constraints) + pkg.manifest.forbid
    }

    private fun expandedRequires(pkg: ModulePackage?): List<String> {
        if (pkg == null) return emptyList()
        return ConstraintText.requires(pkg.manifest.constraints) + pkg.manifest.requires
    }

    private fun trace(tag: String, module: String?, message: String) {
        synchronized(log) { log.add(Trace(tag, module, message)) }
    }

    private class Reply(
        val type: String,
        val text: String?,
        val extra: String?,
        val moduleName: String?,
        val payload: String?
    )

    companion object {
        const val PHASE_A = "a"
        const val PHASE_B = "b"
        const val HOOK_INPUT = "input"
        const val HOOK_OUTPUT = "output"
        private const val SKILL_FILE = "SKILL.md"

        /** 只在真的注入了技能时追加，没技能就不花这份 token。 */
        const val USAGE_DECLARATION = "【使用声明】\n" +
            "如果你在回答中遵循了上述任一技能的规范，请在回答最后一行写：\n" +
            "[[!USED:技能id]]\n" +
            "多个用逗号分隔。遵循了多个就都写，一个都没遵循就不要写。\n" +
            "例如：[[!USED:skill_code]]"

        private val A_TYPES = setOf("pass", "local", "context")
        private val B_TYPES = setOf("pass", "rewrite", "invoke")

        fun isLegal(type: String, phase: String): Boolean =
            if (phase == PHASE_A) A_TYPES.contains(type) else B_TYPES.contains(type)
    }
}
