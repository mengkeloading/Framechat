package mychat.framework.butler

import mychat.framework.profile.UserProfile

/** 翻译时能看到的上下文：类型、上一轮产物、上一轮用户说了什么。 */
data class TranslateContext(
    /** chat / code / writing */
    val intent: String = "",
    /** 上一轮的产物（代码块 / 回答片段） */
    val lastArtifact: String? = null,
    /** 上一轮用户说了什么 */
    val lastUserText: String? = null,
)

interface Translator {
    /** 返回 null 表示不翻译（用原话）。 */
    fun translate(text: String, profile: UserProfile, ctx: TranslateContext): String?
}

/**
 * 规则版翻译：不调模型，只把「用户没说、但模型该知道」的东西补成显式约束。
 *
 * 为什么值得做：用户说「再改改」时，模型唯一能参考的只有上下文里那段代码；
 * 把"上一轮的产物是什么""要不要解释"写成标签，比指望它从对话历史里推断稳得多。
 *
 * 为什么不调模型：每轮多一次请求的延迟和成本换不来多少准确性，规则能覆盖的部分先用规则。
 */
object RuleTranslator : Translator {

    const val TAG_REQUEST = "[请求]"
    const val TAG_POINT = "[指向]"
    const val TAG_RULE = "[约束]"
    const val TAG_AVOID = "[避免]"

    /** 膨胀上限：译文最多是原文的几倍。 */
    const val MAX_RATIO = 3

    /** 但要有个下限，否则「再改改」这种三个字的话连一条约束都放不下。 */
    const val MIN_BUDGET = 200

    override fun translate(text: String, profile: UserProfile, ctx: TranslateContext): String? {
        if (profile.isEmpty()) return null

        val request = TAG_REQUEST + " " + text.trim()
        val optional = mutableListOf<String>()

        ctx.lastArtifact?.takeIf { it.isNotBlank() }?.let {
            optional += TAG_POINT + " 上一轮的产物：" + it.trim().take(80)
        }
        if (profile.style == "极简") optional += TAG_RULE + " 简短，只给结论"
        if (!profile.explain) optional += TAG_RULE + " 不解释过程"
        if (profile.avoid.isNotEmpty()) {
            optional += TAG_AVOID + " " + profile.avoid.joinToString("、")
        }

        // 只有 [请求] 一行等于没增强，不如不翻译：多出来的一行只会让缓存前缀变长
        if (optional.isEmpty()) return null

        val budget = maxOf(text.trim().length * MAX_RATIO, MIN_BUDGET)
        var kept: List<String> = optional
        while (kept.isNotEmpty() && (request.length + kept.sumOf { it.length + 1 }) > budget) {
            kept = kept.dropLast(1)
        }
        if (kept.isEmpty()) return null

        return (listOf(request) + kept).joinToString("\n")
    }
}

/** 用户什么都没填的角色卡：不翻译、也不注入。 */
fun UserProfile.isEmpty(): Boolean =
    role.isBlank() && tech.isEmpty() && avoid.isEmpty() &&
        style == "平衡" && !explain

/**
 * 该不该翻译：命中模块就不翻译 —— 模块规范已经定了"这种输入怎么处理"，
 * 再叠一层翻译会和它打架。
 */
object TranslateGate {
    fun shouldTranslate(routeModules: List<String>): Boolean = routeModules.isEmpty()
}
