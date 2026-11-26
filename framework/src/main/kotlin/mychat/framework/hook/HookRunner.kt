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
        val dispatcher = dispatcherFor(session, history)
        val result = dispatcher.dispatchOutput(session, rawText, input, history, attachments, selected)
        synchronized(traceLog) { traceLog += dispatcher.traces }
        return result
    }

    /** 最近若干条调度轨迹，按发生顺序。 */
    fun lastTrace(limit: Int = 40): List<Trace> = synchronized(traceLog) {
        traceLog.takeLast(limit)
    }

    fun clearTrace() = synchronized(traceLog) { traceLog.clear() }

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
            onFormatCheck = onFormatCheck
        )
}
