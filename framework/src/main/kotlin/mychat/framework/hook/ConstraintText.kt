package mychat.framework.hook

/**
 * 约束的封闭词表。
 *
 * 为什么不用自由文本：自由文本没法检查、没法统计，同一个意思在十个模块里
 * 会写成十种说法；而枚举能被审计（哪个模块声明了哪些约束）、能被复用、
 * 也能被翻译。
 *
 * 未知枚举一律不注入并报到 problems —— 打错字的约束必须可见，否则模块
 * 会静默少一条要求。
 */
object ConstraintText {

    enum class Kind { FORBID, REQUIRE }

    data class Entry(val id: String, val kind: Kind, val text: String)

    private val all = listOf(
        Entry("no_preamble", Kind.FORBID, "不要开场白（如\"好的\"、\"我来看看\"）"),
        Entry("no_summary", Kind.FORBID, "不要结尾总结"),
        Entry("no_followup_question", Kind.FORBID, "不要问\"还需要什么\""),
        Entry("no_heading", Kind.FORBID, "不要用 Markdown 标题组织内容"),
        Entry("no_emoji", Kind.FORBID, "不要用 emoji"),
        Entry(
            "no_vague", Kind.FORBID,
            "不许出现\"建议优化\"\"可以更好\"这类空话，每条必须能直接落地"
        ),
        Entry("no_full_rewrite", Kind.FORBID, "不要整份重写"),

        Entry("each_item_three_parts", Kind.REQUIRE, "每条必须写全：在哪、为什么、改成什么"),
        Entry("minimal_patch", Kind.REQUIRE, "必须给出最小修改版"),
        Entry("state_uncertainty", Kind.REQUIRE, "不确定处写「需要确认：…」，不要猜"),
        Entry("no_hallucination", Kind.REQUIRE, "代码没问题就说没问题，不要凑问题"),
    )

    fun find(id: String): Entry? = all.firstOrNull { it.id == id }

    /** 把模块声明的 id 展开成文案；未知 id 收进 unknown。 */
    fun resolve(ids: List<String>): Pair<List<String>, List<String>> {
        val texts = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        for (id in ids) {
            val entry = find(id)
            if (entry == null) unknown += id else texts += entry.text
        }
        return texts to unknown
    }

    fun forbids(ids: List<String>): List<String> =
        ids.mapNotNull { find(it)?.takeIf { e -> e.kind == Kind.FORBID }?.text }

    fun requires(ids: List<String>): List<String> =
        ids.mapNotNull { find(it)?.takeIf { e -> e.kind == Kind.REQUIRE }?.text }

    /** 模块声明的未知约束（启动时进 problems）。 */
    fun unknownIds(ids: List<String>): List<String> = ids.filter { find(it) == null }
}
