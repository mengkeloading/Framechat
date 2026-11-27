package mychat.framework.debug

/**
 * 调试命令：只在 debug 包里生效，release 里同一条文本按普通消息发出去。
 *
 * 单独抽出来是因为"是否把它当命令"这个判断同时受文本与构建类型影响，
 * 埋在 ViewModel 的 if 里就只能靠真机验证。
 */
object DebugCommands {

    const val COMPRESS = "debug:compress"

    fun isCompressCommand(text: String, debugBuild: Boolean): Boolean =
        debugBuild && text.trim() == COMPRESS
}
