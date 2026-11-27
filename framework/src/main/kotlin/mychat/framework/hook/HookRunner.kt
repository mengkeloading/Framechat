package mychat.framework.hook

/**
 * 一轮对话里的钩子入口。
 *
 * 每个 dispatcher 都会捕获当轮的会话快照（bridge 要靠它回答 data.session），
 * 所以这里按轮新建、但把 traces 累积下来 —— 诊断面板要能看到跨轮的记录。
 */
class HookRunner(
    private val source: ModuleSource,
    private val executors: ExecutorRegistry,
    private val audit: AuditLog = AuditLog(),
    private val maxInvokeDepth: Int = 3,
    /** 格式检查回调（App 层写日志用）。 */
    private val onFormatCheck: ((FormatCheckResult) -> Unit)? = null,
    /** 调试日志回调。 */
    private val onDebug: ((String) -> Unit)? = null,
) {

    /** 兼容入口：只给一个 JsExecutor 时，等价于只注册了 js 引擎。 */
    constructor(
        source: ModuleSource,
        executor: JsExecutor,
        audit: AuditLog = AuditLog(),
        maxInvokeDepth: Int = 3,
        onFormatCheck: ((FormatCheckResult) -> Unit)? = null,
        onDebug: ((String) -> Unit)? = null
    ) : this(
        source = source,
        executors = ExecutorRegistry(listOf(JsExecutorAdapter(executor))),
        audit = audit,
        maxInvokeDepth = maxInvokeDepth,
        onFormatCheck = onFormatCheck,
        onDebug = onDebug
    )

    private val traceLog = mutableListOf<Trace>()

    /** 没有启用任何模块时直接放行：不建 dispatcher、不碰执行器。 */
    fun input(
        session: HookSession,
        text: String,
        history: List<Message> = emptyList(),
        attachments: List<Attachment> = emptyList(),
        /** 调用方（路由）算出的选中集合；前缀强制时会覆盖它。 */
        selected: Set<String> = emptySet(),
        /** 类型片段（【对话类型】）；空则不加。 */
        intentText: String? = null,
    ): HookAResult {
        // 没有启用任何模块也要给类型片段：闲聊唯一拿到的就是它
        if (session.activeHardModules.isEmpty()) {
            return withIntent(HookAResult.Pass(text), text, intentText)
        }

        /*
         * 前缀强制：启用范围内的模块若声明了 `prefix`，输入以「词：」开头就直接用它，
         * 跳过路由判断。前缀只有一个（第一个匹配的模块），且**不进信封**。
         */
        val enabled = session.activeHardModules.mapNotNull { source.getPackage(it) }
        val forced = PrefixRouter.resolve(text, enabled)
        val chain = if (forced == null) {
            session.activeHardModules
        } else {
            listOf(forced.module) + session.activeHardModules.filter { it != forced.module }
        }
        val hookSession = if (forced == null) session else session.copy(activeHardModules = chain)
        val hookText = forced?.text ?: text

        // 前缀没命中却长得像前缀：用户会以为"我明明打了前缀"，必须留痕
        if (forced == null && looksLikePrefix(text)) {
            onDebug?.invoke("prefix:miss \"" + text.take(30) + "\"")
        }

        val dispatcher = dispatcherFor(hookSession, history)
        val result = dispatcher.dispatchInput(
            session = hookSession,
            rawInput = hookText,
            history = history,
            attachments = attachments,
            selected = if (forced == null) selected else setOf(forced.module),
            forced = forced != null
        )
        synchronized(traceLog) {
            if (forced != null) {
                traceLog += Trace("prefix", forced.module, "forced by ${forced.prefix}")
            }
            traceLog += dispatcher.traces
        }
        return withIntent(result, text, intentText)
    }

    /**
     * 把类型片段插到最前面。
     *
     * 顺序是刻意的：先定调（这段是什么类型的对话），再给规范（这个领域怎么做）。
     * 反过来模型会先被规范带偏，把闲聊也答成分级清单。
     */
    private fun withIntent(result: HookAResult, text: String, intentText: String?): HookAResult {
        if (intentText.isNullOrBlank()) return result
        val block = "【对话类型】\n" + intentText
        return when (result) {
            is HookAResult.Local -> result
            is HookAResult.Pass -> HookAResult.Context(text, block)
            is HookAResult.Context ->
                if (result.extra.isBlank()) {
                    HookAResult.Context(result.text, block)
                } else {
                    HookAResult.Context(result.text, block + "\n\n" + result.extra)
                }
        }
    }

    fun output(
        session: HookSession,
        rawText: String,
        input: String? = null,
        history: List<Message> = emptyList(),
        attachments: List<Attachment> = emptyList(),
        selected: Set<String> = emptySet(),
    ): HookBResult {
        if (session.activeHardModules.isEmpty()) return HookBResult.Pass(rawText)
        onDebug?.invoke(
            "hookB:in selected=" + selected.joinToString(",") + " textLen=" + rawText.length
        )
        val dispatcher = dispatcherFor(session, history)
        val result = dispatcher.dispatchOutput(session, rawText, input, history, attachments, selected)
        synchronized(traceLog) { traceLog += dispatcher.traces }
        return result
    }

    /** 最近若干条调度轨迹，按发生顺序。 */
    fun lastTrace(limit: Int = 40): List<Trace> = synchronized(traceLog) {
        traceLog.takeLast(limit)
    }

    /** 选中的模块里，各自的格式检查结果（只含声明了 format_marks 的模块）。 */
    fun checkFormats(selected: Set<String>, text: String): List<FormatCheckResult> =
        selected.mapNotNull { id ->
            val manifest = source.getPackage(id)?.manifest
            FormatCheckResult.of(
                id,
                manifest?.formatMarks.orEmpty(),
                text,
                manifest?.formatRequired.orEmpty()
            )
        }

    /** 只返回缺标记的那些模块。 */
    fun missingFormats(selected: Set<String>, text: String): List<FormatCheckResult> =
        checkFormats(selected, text).filterNot { it.ok }

    /** 从模块的 SKILL.md 里取「输出格式/结构」节，给补救提示用。 */
    fun formatStructureOf(id: String): String? {
        val skill = source.getPackage(id)?.reference("SKILL.md") ?: return null
        for (heading in FORMAT_HEADINGS) {
            val section = mychat.framework.embedding.ModuleDocuments.sectionOf(skill, heading)
            if (section.isNotEmpty()) return section
        }
        return null
    }

    private companion object {
        val FORMAT_HEADINGS = listOf("输出格式", "输出结构")
    }

    /** 模块声明的格式标记。 */
    fun formatMarksOf(id: String): List<String> =
        source.getPackage(id)?.manifest?.formatMarks.orEmpty()

    /**
     * 需要补救时返回 (模块 id, 输出结构)；不需要补救返回 null。
     * 需要补救但拿不到结构时记一行 skip —— 静默跳过最难查。
     */
    fun remediationTarget(selected: Set<String>, text: String): Pair<FormatCheckResult, String>? {
        val check = missingFormats(selected, text).firstOrNull() ?: return null
        val id = check.moduleId
        val structure = formatStructureOf(id)
        if (structure.isNullOrBlank()) {
            onDebug?.invoke("remediate:skip " + id + " reason=no_structure")
            return null
        }
        return check to structure
    }

    fun clearTrace() = synchronized(traceLog) { traceLog.clear() }

    /** 「词：」或「词:」开头、且冒号前有字 —— 用户在试图用前缀。 */
    private fun looksLikePrefix(text: String): Boolean {
        val idx = text.indexOfFirst { it == '：' || it == ':' }
        return idx in 1..12 && text.length > idx + 1
    }

    private fun dispatcherFor(session: HookSession, history: List<Message>): ModuleDispatcher =
        ModuleDispatcher(
            source = source,
            executors = executors,
            bridges = GatedHostBridge.factory(
                source = source,
                audit = audit,
                sessionData = { SessionData(session, history) }
            ),
            maxInvokeDepth = maxInvokeDepth,
            onFormatCheck = onFormatCheck,
            onDebug = onDebug
        )
}
