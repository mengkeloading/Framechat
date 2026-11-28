package mychat.framework.butler

/**
 * 翻译策略：本轮要不要让管家把输入"翻译"成更完整的问题。
 *
 * 模块优先。模块是外挂能力，它已经定义了"这种输入该怎么处理"；
 * 管家再翻译一遍，等于在模块规范前面插一段自己的理解，两边会打架。
 * 只有**没命中任何模块**时才翻译 —— 那才是"用户说得模糊、没人接管"的情况。
 */
object TranslatePolicy {

    const val SKIP_MODULE_HIT = "module_hit"
    const val RUN_NO_MODULE = "no_module"

    data class Decision(
        val run: Boolean,
        val reason: String,
    )

    fun decide(routeModules: List<String>): Decision =
        if (routeModules.isEmpty()) {
            Decision(run = true, reason = RUN_NO_MODULE)
        } else {
            Decision(run = false, reason = SKIP_MODULE_HIT)
        }

    /** `translate:skip reason=module_hit route=[skill_code]` */
    fun skipLog(reason: String, routeModules: List<String>): String =
        "translate:skip reason=" + reason + " route=[" + routeModules.joinToString(", ") + "]"

    /** `translate:run profile=true ctx=true len=48→120` */
    fun runLog(profile: Boolean, ctx: Boolean, from: Int, to: Int): String =
        "translate:run profile=" + profile + " ctx=" + ctx + " len=" + from + "→" + to
}
