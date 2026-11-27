package mychat.framework.hook

/**
 * SKILL.md 里提到的参考文献，按需塞进注入块。
 *
 * 为什么不一次性全塞：一个 checklist 三千字，塞进去就是三千 token 的固定开销，
 * 而多数轮次根本用不到它。所以按大小分档 —— 小的给全文，中的给开头，大的只留一行
 * 让模型知道"有这么个东西"，需要时再要。
 */
object ReferenceInliner {

    /** 注入块的总预算（SKILL.md + 全部附件）。 */
    const val BUDGET = 2000

    /** 不超过这个长度给全文。 */
    const val FULL_MAX = 500

    /** 不超过这个长度给摘要。 */
    const val SUMMARY_MAX = 1500

    /** 摘要给前多少字。 */
    const val SUMMARY_PREVIEW = 200

    const val MODE_FULL = "full"
    const val MODE_SUMMARY = "summary"
    const val MODE_DIR = "dir"

    private val PATTERN = Regex("(?:reference|references)/[\\w\\-/]+\\.md")

    /** SKILL.md 里出现的引用路径（去重、保持出现顺序）。 */
    fun scan(skillBody: String): List<String> =
        PATTERN.findAll(skillBody).map { it.value }.distinct().toList()

    /**
     * 返回 SKILL.md 正文 + 附件块。
     *
     * [lookup] 传入引用路径，返回文件内容（找不到返回 null）。
     * [log] 收 refs:scan / refs:inline / refs:missing 三类日志。
     */
    fun render(
        skillBody: String,
        lookup: (String) -> String?,
        skillId: String = "",
        log: (String) -> Unit = {},
        budget: Int = BUDGET,
    ): String {
        val paths = scan(skillBody)
        if (paths.isEmpty()) return skillBody

        val items = mutableListOf<Item>()
        for (path in paths) {
            val text = lookup(path)
            if (text.isNullOrEmpty()) {
                log("refs:missing path=$path")
                continue
            }
            items += Item(name = path.substringAfterLast('/'), size = text.length, text = text)
        }
        // found = 扫到几个路径；success = 其中真的读到了几个
        log("refs:scan skill=$skillId found=${paths.size} success=${items.size}")
        if (items.isEmpty()) return skillBody

        // 先按大小定初始档位
        for (item in items) item.mode = initialMode(item.size)

        var total = skillBody.length + items.sumOf { it.rendered().length }
        while (total > budget) {
            // 从大到小砍：每次挑"当前最占地方"的那个降一档
            val victim = items
                .filter { it.mode != MODE_DIR }
                .maxByOrNull { it.rendered().length }
            if (victim == null) break
            victim.mode = if (victim.mode == MODE_FULL) MODE_SUMMARY else MODE_DIR
            total = skillBody.length + items.sumOf { it.rendered().length }
        }

        for (item in items) {
            log("refs:inline file=${item.name} size=${item.size} mode=${item.mode}")
        }

        return buildString {
            append(skillBody)
            for (item in items) append(item.rendered())
        }
    }

    private fun initialMode(size: Int): String = when {
        size <= FULL_MAX -> MODE_FULL
        size <= SUMMARY_MAX -> MODE_SUMMARY
        else -> MODE_DIR
    }

    private class Item(val name: String, val size: Int, val text: String) {
        var mode: String = MODE_FULL

        fun rendered(): String = when (mode) {
            MODE_FULL -> "\n\n【附件：" + name + "】\n" + text
            MODE_SUMMARY -> "\n\n【附件：" + name + "（共 " + size + " 字）】\n" +
                text.take(SUMMARY_PREVIEW) + "…\n（需要完整内容请说明）"
            else -> "\n\n【附件：" + name + "（共 " + size + " 字）】\n首行：" + firstLine()
        }

        private fun firstLine(): String =
            text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
    }
}
