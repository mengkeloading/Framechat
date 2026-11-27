package com.simplechat.app.hook

import android.content.Context

/**
 * 把调试日志写到 `Download/mychat/usage-<启动时间>.log`。
 *
 * 一次启动一个文件：可以按启动整份拿走，也好清理（只留最近 10 个）。
 * ViewModel 拿不到 Context，所以在 Application.onCreate 里装一次。只在 debug 包用。
 */
object UsageLog {

    private const val PREFIX = "usage-"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var sessionName: String? = null

    /** 本次启动的日志文件名；install 之前访问返回 null。 */
    fun currentName(): String? = sessionName

    fun install(context: Context) {
        appContext = context.applicationContext
        sessionName = PREFIX + EmbedLog.stamp() + ".log"
        runCatching { EmbedLog.prune(context, PREFIX) }
    }

    /** 同一进程里再开一个会话（重启探针用）；正常路径由 install 建。 */
    fun ensureSession(): String? {
        val context = appContext ?: return null
        val name = sessionName ?: (PREFIX + EmbedLog.stamp() + ".log").also { sessionName = it }
        val path = runCatching { EmbedLog.append(context, emptyList(), name) }.getOrNull()
        return path
    }

    /** 返回落点；没装 Context 或写失败返回 null。 */
    fun record(line: String): String? {
        val context = appContext ?: return null
        val name = sessionName ?: (PREFIX + EmbedLog.stamp() + ".log").also { sessionName = it }
        return runCatching { EmbedLog.append(context, listOf(line), name) }.getOrNull()
    }

    /** 明显的分段标记：一轮对话、一次启动。 */
    fun banner(title: String) {
        record("========== " + title + " ==========")
    }
}
