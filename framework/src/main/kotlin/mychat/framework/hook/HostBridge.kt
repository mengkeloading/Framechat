package mychat.framework.hook

import mychat.framework.json.JsonValue
import mychat.framework.json.parseJson

/** 模块只能通过 host 拿能力；拿不到文件系统、registry 或 API Key。 */
fun interface HostBridge {
    /** 同步调用 —— 模块无法 await。 */
    fun requestLocalAction(action: String, payloadJson: String): String
}

fun interface HostBridgeFactory {
    fun create(pkg: ModulePackage): HostBridge
}

/**
 * JS 模块执行器。ref 形如 `pkg_id/script/main.js` 或只给 `pkg_id`。
 * 必须同步返回：沙箱实现（WebView / J2V8）自己负责把异步桥接成阻塞，
 * 否则模块里的 .trim() 之类会在 Promise 上炸掉。
 */
fun interface JsExecutor {
    fun execute(scriptRef: String, inputJson: String, host: HostBridge): String?
}

class HostException(message: String) : Exception(message)

data class AuditEntry(
    val module: String,
    val action: String,
    val ok: Boolean,
    val error: String?,
    val at: Long
)

/** 全局审计，等价于 magisk.log。 */
class AuditLog(private val limit: Int = 200) {
    private val entries = ArrayDeque<AuditEntry>()

    @Synchronized
    fun record(entry: AuditEntry) {
        entries.addLast(entry)
        while (entries.size > limit) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<AuditEntry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()
}

/** 带能力闸门与配额的 HostBridge。未声明 = 抛异常 = fail-open 当 pass。 */
class GatedHostBridge(
    private val pkg: ModulePackage,
    private val source: ModuleSource,
    private val audit: AuditLog,
    private val sessionData: () -> SessionData?,
    private val clock: () -> String = { java.time.Instant.now().toString() },
    private val now: () -> Long = { System.currentTimeMillis() }
) : HostBridge {

    private val used = HashMap<String, Int>()

    override fun requestLocalAction(action: String, payloadJson: String): String {
        val quota = pkg.manifest.capabilities[action]
            ?: throw fail(action, "not_declared", "capability_not_declared: $action")
        val n = (used[action] ?: 0) + 1
        used[action] = n
        if (n > quota) throw fail(action, "quota_exceeded", "quota_exceeded: $action ($quota)")

        return when (action) {
            Caps.RESOURCE_READ -> {
                val name = parseJson(payloadJson)?.str("name")
                val content = name?.let { source.getReference(pkg.id, it) }
                if (content == null) throw fail(action, "not_found", "resource_not_found: $name")
                ok(action)
                content
            }
            Caps.CLOCK_NOW -> {
                ok(action)
                clock()
            }
            Caps.DATA_SESSION -> {
                val data = sessionData() ?: throw fail(action, "no_session", "no_session")
                ok(action)
                JsonValue.of(
                    "session" to data.session.toJson(),
                    "messages" to JsonValue.array(
                        data.messages.takeLast(SESSION_MESSAGE_LIMIT).map { it.toJson() }
                    )
                ).encode()
            }
            else -> throw fail(action, "not_implemented", "capability_not_implemented: $action")
        }
    }

    private fun ok(action: String) {
        audit.record(AuditEntry(pkg.id, action, true, null, now()))
    }

    private fun fail(action: String, code: String, message: String): HostException {
        audit.record(AuditEntry(pkg.id, action, false, code, now()))
        return HostException(message)
    }

    companion object {
        const val SESSION_MESSAGE_LIMIT = 20

        fun factory(
            source: ModuleSource,
            audit: AuditLog,
            sessionData: () -> SessionData?,
            clock: () -> String = { java.time.Instant.now().toString() }
        ): HostBridgeFactory = HostBridgeFactory { pkg ->
            GatedHostBridge(pkg, source, audit, sessionData, clock)
        }
    }
}
