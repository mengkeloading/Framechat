package com.simplechat.app.hook

import android.content.Context
import mychat.framework.log.AppendLog

/**
 * 把调试日志写到 `Download/mychat/usage-<启动时间>.log`。
 *
 * **安装一次、追加多次**：文件名在 install 时定下，之后每次 record 只追加。
 * ViewModel 拿不到 Context，所以在 Application.onCreate 里装一次。只在 debug 包用。
 */
object UsageLog {

    private const val PREFIX = "usage-"

    private val log = AppendLog()

    @Volatile
    private var stampFormatter: (() -> String)? = null

    fun install(context: Context) {
        // 重复调用无副作用：落点只建一次
        if (log.installed) return
        runCatching { EmbedLog.prune(context, PREFIX) }
        stampFormatter = { EmbedLog.timeStamp() }
        log.install { EmbedLog.openSession(context, PREFIX) }
    }

    /** 落点文件名（不含目录）；未安装时为 null。 */
    fun currentName(): String? = if (log.installed) PREFIX + "*.log" else null

    fun record(line: String): Boolean {
        val stamp = stampFormatter?.invoke().orEmpty()
        val text = if (stamp.isEmpty()) line else "[$stamp] " + line
        return log.record(text)
    }

    /** 明显的分段标记：一轮对话、一次启动。 */
    fun banner(title: String) {
        record("========== " + title + " ==========")
    }
}
