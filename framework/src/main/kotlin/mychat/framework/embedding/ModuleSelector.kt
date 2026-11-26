package mychat.framework.embedding

import mychat.framework.hook.ModulePackage

/**
 * 把「本轮要注入哪些技能」算成 selected 集合。
 *
 * 规则：
 * 1. 没有启用模块 → 空集（不跑路由，省掉 embedding 计算）
 * 2. 路由命中 → 命中项（最多两个，见 [ModuleRouter]）
 * 3. 路由为空（分值不够 / 分不开）→ 兜底注入所有带 SKILL.md 的模块
 *
 * 兜底是**故意**的：路由是优化，不是必需；最坏情况是多花 token，不是漏用能力。
 */
object ModuleSelector {

    /** 一个模块有没有可注入的规范正文。 */
    fun hasSkill(pkg: ModulePackage): Boolean = pkg.reference(SKILL_FILE) != null

    fun fallback(modules: List<ModulePackage>): Set<String> =
        modules.filter { hasSkill(it) }.map { it.id }.toSet()

    fun select(
        modules: List<ModulePackage>,
        /** 查询向量；null 表示算不出来（模型没加载 / 空文本），直接走兜底。 */
        query: FloatArray?,
        candidateVectors: List<Candidate>,
    ): Set<String> {
        if (modules.isEmpty()) return emptySet()
        if (query != null) {
            val decision = ModuleRouter.decide(query, candidateVectors)
            if (decision.modules.isNotEmpty()) return decision.modules.toSet()
        }
        return fallback(modules)
    }

    private const val SKILL_FILE = "SKILL.md"
}
