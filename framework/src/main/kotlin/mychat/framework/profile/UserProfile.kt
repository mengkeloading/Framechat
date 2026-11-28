package mychat.framework.profile

/**
 * 用户手写的角色卡：告诉模型"我是谁、我要什么风格"。
 *
 * 全部字段可空/可空串 —— 没填的项不注入，不猜。
 * 这层只做事实陈述，不做推断：写"独立开发者"就是独立开发者，
 * 不去猜技术栈、不去猜偏好。
 */
data class UserProfile(
    /** 身份，如「独立开发者，做 Android App」。 */
    val role: String = "",
    val tech: List<String> = emptyList(),
    /** 极简 / 平衡 / 详细 */
    val style: String = "平衡",
    /** 要不要解释过程 */
    val explain: Boolean = false,
    /** 讨厌的做法，如「空话」「过度设计」 */
    val avoid: List<String> = emptyList(),
)

/**
 * 角色卡 → 一行注入文本。
 *
 * 为什么要压到 60 字：这段每轮都在上下文里，且它影响的是语气而非内容。
 * 写长了会挤掉真正需要模型看的东西（技能规范、回忆），收益却是负的。
 */
object ProfileFormatter {

    const val MAX_LENGTH = 60

    /**
     * role 为空直接返回 null：没有身份信息的角色卡只是噪音。
     * 风格/厌恶这些是**修饰**，没有主语时不该单独出现。
     */
    fun format(profile: UserProfile): String? {
        val role = profile.role.trim()
        if (role.isEmpty()) return null

        val parts = mutableListOf<String>()
        parts += role

        val style = profile.style.trim()
        if (style.isNotEmpty() && style != "平衡") parts += "偏好" + style

        val avoid = profile.avoid.map { it.trim() }.filter { it.isNotEmpty() }
        if (avoid.isNotEmpty()) parts += "讨厌" + avoid.joinToString("、")

        val tech = profile.tech.map { it.trim() }.filter { it.isNotEmpty() }
        if (tech.isNotEmpty()) parts += "常用" + tech.joinToString("、")

        // 正反都给：写「不解释过程」比不写更明确，模型不会自己脑补要不要讲步骤
        parts += if (profile.explain) "要解释过程" else "不解释过程"


        val body = trimTo(parts.joinToString("；"), MAX_LENGTH)
        return "【关于你】\n" + body
    }

    /** 超长时优先砍后面的修饰，保留身份与风格。 */
    private fun trimTo(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.take(max)
        val lastSep = cut.lastIndexOf('；')
        return if (lastSep > 0) cut.substring(0, lastSep) else cut
    }
}
