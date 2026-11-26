package mychat.framework.butler

/**
 * 「没有管家」这个状态本身。
 *
 * 为什么不用 null：null 会被当成"还没初始化"，而这个状态是**用户的明确选择**——
 * 关掉所有自动判断，只靠前缀强制。有一个实体才能把它写进日志、写进设置。
 */
class NoButler : Butler {

    override val id: String = ID
    override val name: String = "无管家"

    override fun route(query: String, modules: List<ModuleMeta>): RouteResult =
        RouteResult(emptyList(), "no_butler")

    override fun classify(text: String): String? = null

    override fun normalize(text: String): String? = null

    override fun judge(output: String, spec: String): JudgeResult? = null

    override fun similarity(a: String, b: String): Float = 0f

    companion object {
        const val ID = "none"
    }
}
