package mychat.framework.core

import mychat.framework.json.JsonValue

/** 错误分类都集中在这里：别处只管抛，不判断文案。 */
object ErrorMapper {

    fun map(message: String?): String {
        val m = message?.trim().orEmpty()
        if (m.isEmpty()) return "发生错误：未知"
        return when {
            m.startsWith("http_401") -> "鉴权失败（401），请检查 API Key"
            m.startsWith("http_429") -> "请求过于频繁（429），请稍后重试"
            m.startsWith("http_5") -> "服务端错误，请稍后重试"
            m.contains("timeout", ignoreCase = true) ||
                m.contains("network", ignoreCase = true) ||
                m.contains("Failed to fetch", ignoreCase = true) -> "网络异常，请检查连接"
            m.startsWith("module_not_found") -> "模块不存在：$m"
            else -> "发生错误：$m"
        }
    }

    fun retryable(message: String?): Boolean =
        !message.orEmpty().startsWith("http_401")

    /** 工具执行失败时回给模型的 JSON。 */
    fun mapJsError(message: String?): String = JsonValue.of(
        "ok" to JsonValue.Bool(false),
        "error" to JsonValue.Str(message ?: "unknown")
    ).encode()
}
