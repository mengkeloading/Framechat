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
) {

    private val traceLog = mutableListOf<Trace>()

    /** 没有启用任何模块时直接放行：不建 dispatcher、不碰执行器。 */
    fun input(
        session: HookSession,
        text: String,
        history: List<Message> = emptyList(),
        attachments: List<Attachment> = emptyList(),
    ): HookAResult {
        if (session.activeHardModules.isEmpty()) return HookAResult.Pass(text)
        val dispatcher = dispatcherFor(session, history)
        val result = dispatcher.dispatchInput(session, text, history, attachments)
        synchronized(traceLog) { traceLog += dispatcher.traces }
        return result
    }

    fun output(
        session: HookSession,
        rawText: String,
        input: String? = null,
        history: List<Message> = emptyList(),
        attachments: List<Attachment> = emptyList(),
    ): HookBResult {
        if (session.activeHardModules.isEmpty()) return HookBResult.Pass(rawText)
        val dispatcher = dispatcherFor(session, history)
        val result = dispatcher.dispatchOutput(session, rawText, input, history, attachments)
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
            maxInvokeDepth = maxInvokeDepth
        )
}
