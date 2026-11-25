package com.simplechat.app.hook

import android.content.Context

/**
 * 把「本轮加载了哪些模块 / 模型声明用了哪些」追加到 `Download/mychat-usage.log`。
 *
 * ViewModel 拿不到 Context，所以在 Application.onCreate 里装一次。
 * 只在 debug 包里用，release 不调用。
 */
object UsageLog {

    const val FILE_NAME = "mychat-usage.log"

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /** 返回落点；没装 Context 或写失败返回 null。 */
    fun record(line: String): String? {
        val context = appContext ?: return null
        return runCatching { EmbedLog.append(context, listOf(line), FILE_NAME) }.getOrNull()
    }
}
