package mychat.framework.hook

/**
 * 管家提供的输入归一化。返回 null 表示"这一轮没处理"（用原文）。
 * 与 Butler.normalize 同签名，但这里只关心"有没有做"，不关心谁做的。
 */
fun interface TextNormalizer {
    fun normalize(text: String): String?
}
