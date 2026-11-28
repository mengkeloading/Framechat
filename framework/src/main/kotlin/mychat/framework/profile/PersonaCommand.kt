package mychat.framework.profile

/**
 * `debug:persona [on|off]`：开关助手人格段。
 *
 * 不复用 debug:profile，是因为它开关的是**框架自带内容**，
 * 不是用户填的角色卡 —— 两者含义不同。
 */
object PersonaCommand {

    const val NAME = "debug:persona"

    /** 解析结果：set 为 null 表示只看状态。 */
    data class Request(val set: Boolean?)

    /** 不是本命令返回 null。 */
    fun parse(text: String, debugBuild: Boolean): Request? {
        if (!debugBuild) return null
        val trimmed = text.trim()
        if (trimmed == NAME) return Request(null)
        if (!trimmed.startsWith(NAME + " ")) return null
        return when (trimmed.removePrefix(NAME).trim().lowercase()) {
            "on", "开", "开启", "true" -> Request(true)
            "off", "关", "关闭", "false" -> Request(false)
            // 写了别的就当查询，不猜用户想干嘛
            else -> Request(null)
        }
    }

    fun describe(enabled: Boolean): String =
        if (enabled) "助手风格：开启" else "助手风格：关闭"

    fun updated(enabled: Boolean): String =
        if (enabled) "助手风格已开启" else "助手风格已关闭"
}
