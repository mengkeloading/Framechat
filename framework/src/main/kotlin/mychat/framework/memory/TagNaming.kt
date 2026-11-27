package mychat.framework.memory

/**
 * 标签命名：卡片笔记规范，纯代码不调模型。
 *
 * 名字要短、要能一眼认出话题。用户原话里"我在""帮我"这类引导词对识别话题没帮助，
 * 结尾的"吧""呢"同理；12 字是手机上一行能读完的长度。
 */
object TagNaming {

    private val PREFIXES = listOf("我在", "帮我", "我想", "请问", "能不能", "可以")

    /** 结尾的语气词：整句只删一个（"写个故事吧" → "写个故事"）。 */
    private val SUFFIXES = listOf("吧", "呢", "啊", "吗", "了")

    /**
     * 截断后可能停在虚词上（"用 Kotlin 重写这"），那半截字没有信息量，
     * 一并去掉。含指示代词是因为硬截最容易落在"这/那/个"上。
     */
    private val TAIL_PARTICLES =
        listOf("吗", "呢", "吧", "啊", "了", "的", "着", "么", "这", "那", "个", "些")

    const val MAX_LENGTH = 12
    const val FALLBACK = "未命名话题"

    fun nameOf(firstUserMessage: String): String {
        var s = firstUserMessage.trim()
        PREFIXES.firstOrNull { s.startsWith(it) }?.let { s = s.removePrefix(it).trim() }
        SUFFIXES.firstOrNull { s.endsWith(it) }?.let { s = s.dropLast(1).trim() }
        if (s.length > MAX_LENGTH) s = trimTail(s.take(MAX_LENGTH).trim())
        return s.ifBlank { FALLBACK }
    }

    /**
     * 去掉结尾的虚词。**删空就返回原文** —— 输入整句都是语气词时（"嗯呢吧"），
     * 给空标题不如给原文，至少能认出这轮聊了什么。
     */
    private fun trimTail(s: String): String {
        var out = s
        while (out.isNotEmpty() && TAIL_PARTICLES.any { out.endsWith(it) }) {
            out = out.dropLast(1).trimEnd()
        }
        return out.ifBlank { s }
    }
}
