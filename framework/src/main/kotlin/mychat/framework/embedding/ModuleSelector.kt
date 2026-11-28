package mychat.framework.embedding

import mychat.framework.hook.ModulePackage

/**
 * 把「本轮要注入哪些技能」算成 selected 集合。
 *
 * 规则：
 * 1. 没有启用模块 → 空集（不跑路由，省掉 embedding 计算）
 * 2. 路由命中 → 命中项（最多两个，见 [ModuleRouter]）
 * 3. 路由为空 → **空集，不兜底**
 *
 * 第 3 条曾经是「兜底注入所有带 SKILL.md 的模块」，理由是"路由是优化，不是必需"。
 * 实测证明那个理由是错的：用户问「帮我写个 Java 的 hello world」，路由给 0.404 落空，
 * 于是小说写作和代码审查两套规范一起被塞进去 —— 模型从此以为这一轮要"审查"。
 * 兜底只在"没命中"时省事，代价却是**误伤**：注入的规范会改写回答方式。
 *
 * 完全陌生的任务本来也不该套任何模块的规范：没有规范时模型的默认行为就是对的。
 */
object ModuleSelector {

    /** 一个模块有没有可注入的规范正文。 */
    fun hasSkill(pkg: ModulePackage): Boolean = pkg.reference(SKILL_FILE) != null

    fun select(
        modules: List<ModulePackage>,
        /** 管家给的路由结果；为空就是「没命中」，不再兜底。 */
        route: mychat.framework.butler.RouteResult,
    ): Set<String> {
        if (modules.isEmpty()) return emptySet()
        return route.modules.toSet()
    }

    private const val SKILL_FILE = "SKILL.md"
}
