package com.simplechat.app.ui.nav

/**
 * `debug:icon [id]`：查看或切换记忆入口图标。
 *
 * 解析放在 app 侧而不是框架：可选值就是 [IconVariant] 这个枚举本身，
 * 放进框架得多维护一份字符串清单，迟早对不上。
 */
object IconCommand {

    const val NAME = "debug:icon"

    fun matches(text: String): Boolean {
        val t = text.trim()
        return t == NAME || t.startsWith(NAME + " ")
    }

    /** 请求的图标 id；裸命令返回 null（= 只查看）。 */
    fun requested(text: String): String? {
        val rest = text.trim().removePrefix(NAME).trim()
        return rest.ifEmpty { null }
    }

    fun describe(current: String) = buildString {
        append("当前图标：").append(IconVariant.of(current).label)
            .append("（").append(IconVariant.of(current).id).append("）\n")
        append("可选：").append(IconVariant.summary()).append('\n')
        append("用法：debug:icon <id>")
    }
}
