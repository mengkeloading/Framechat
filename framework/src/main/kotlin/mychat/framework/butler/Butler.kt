package mychat.framework.butler

/**
 * 管家：本轮「该注入什么、该不该动手」的决策者。
 *
 * 为什么要有这一层：路由、分类、归一化、结果质检，本质都是"用一个判断器
 * 代替模型瞎猜"。现在只有向量路由一种实现，将来可能是规则 / 小模型 / 云端，
 * 调用方不应该关心是哪种。
 */
interface Butler {

    val id: String

    val name: String

    /** 该注入哪些模块。空列表 = 交给兜底（全量 hasSkill）。 */
    fun route(query: String, modules: List<ModuleMeta>): RouteResult

    /** 输入分类（话题/意图）。当前实现都返回 null。 */
    fun classify(text: String): String?

    /** 输入归一化（散乱 → 有序）。当前实现都返回 null。 */
    fun normalize(text: String): String?

    /** 结果质检。当前实现都返回 null。 */
    fun judge(output: String, spec: String): JudgeResult?

    /** 两段文本的相似度，0..1。没有模型的实现在这里返回 0。 */
    fun similarity(a: String, b: String): Float
}

/**
 * 路由用的模块视图。
 *
 * [embeddingText] 由 [mychat.framework.embedding.ModuleDocuments] 预先算好
 * （name + description + 「何时使用」节），管家只管用它建向量，不关心原文从哪来。
 */
data class ModuleMeta(
    val id: String,
    val name: String,
    val description: String,
    val prefix: String? = null,
    val embeddingText: String = "",
    /** 用户真实说法；非空时优先用它们建向量，取与查询最像的那条。 */
    val sampleQueries: List<String> = emptyList(),
    /** 该模块只服务这种输入类型；null = 不限。 */
    val intent: String? = null,
)

data class RouteResult(
    val modules: List<String>,
    /** hit | below_min | margin | empty | no_butler | error | no_vector */
    val reason: String,
    val scores: Map<String, Float> = emptyMap()
)

data class JudgeResult(val ok: Boolean, val reason: String?)
