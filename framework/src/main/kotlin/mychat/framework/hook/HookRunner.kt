package mychat.framework.hook

/**
 * 一轮对话里的钩子入口。
 *
 * 每个 dispatcher 都会捕获当轮的会话快照（bridge 要靠它回答 data.session），
 * 所以这里按轮新建、但把 traces 累积下来 —— 诊断面板要能看到跨轮的记录。
 */
class HookRunner(
    private val source: ModuleSource,
    private val executor: JsExecutor,
    private val audit: AuditLog = AuditLog(),
    private val maxInvokeDepth: Int = 3,
    /** 格式检查回调（App 层写日志用）。 */
    private val onFormatCheck: ((String, Boolean) -> Unit)? = null,
    /** 调试日志回调。 */
    private val onDebug: ((String) -> Unit)? = null,
) {

    private val traceLog = mutableListOf<Trace>()

    /** 没有启用任何模块时直接放行：不建 dispatcher、不碰执行器。 */
    fun input(
        session: HookSession,
        text: String,
        history: List<Message> = emptyList(),
        attachments: List<Attachment> = emptyList(),
        /** 调用方（路由）算出的选中集合；前缀强制时会覆盖它。 */
        selected: Set<String> = emptySet(),
    ): HookAResult {
        if (session.activeHardModules.isEmpty()) return HookAResult.Pass(text)

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
        return result
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

    /** 选中的模块里，有哪些声明的格式标记一个都没出现。 */
    fun missingFormats(selected: Set<String>, text: String): List<String> =
        selected.mapNotNull { id ->
            val marks = source.getPackage(id)?.manifest?.formatMarks.orEmpty()
            when {
                marks.isEmpty() -> null
                marks.any { text.contains(it) } -> null
                else -> id
            }
        }

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
    fun remediationTarget(selected: Set<String>, text: String): Pair<String, String>? {
        val id = missingFormats(selected, text).firstOrNull() ?: return null
        val structure = formatStructureOf(id)
        if (structure.isNullOrBlank()) {
            onDebug?.invoke("remediate:skip " + id + " reason=no_structure")
            return null
        }
        return id to structure
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
            executor = executor,
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
