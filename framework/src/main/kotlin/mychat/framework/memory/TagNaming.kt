package mychat.framework.memory

/**
 * 标签命名：卡片笔记规范，纯代码不调模型。
 *
 * 名字要短、要能一眼认出话题。用户原话里"我在""帮我"这类引导词对识别话题没帮助，
 * 结尾的"吧""呢"同理；12 字是手机上一行能读完的长度。
 */
object TagNaming {

    private val PREFIXES = listOf("我在", "帮我", "我想", "请问", "能不能", "可以")
    private val SUFFIXES = listOf("吧", "呢", "啊", "吗", "了")

    const val MAX_LENGTH = 12
    const val FALLBACK = "未命名话题"

    fun nameOf(firstUserMessage: String): String {
        var s = firstUserMessage.trim()
        PREFIXES.firstOrNull { s.startsWith(it) }?.let { s = s.removePrefix(it).trim() }
        SUFFIXES.firstOrNull { s.endsWith(it) }?.let { s = s.dropLast(1).trim() }
        if (s.length > MAX_LENGTH) s = s.take(MAX_LENGTH).trim()
        return s.ifBlank { FALLBACK }
    }
}
