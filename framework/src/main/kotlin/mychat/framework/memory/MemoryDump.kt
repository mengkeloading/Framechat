package mychat.framework.memory

/**
 * `debug:memory` 的输出：一眼看清每个标签现在是什么状态。
 *
 * 强度按**衰减后**的值显示 —— 库里存的 `strength` 是上次写入时的值，
 * 直接打出来会让人误以为标签一直没变淡。
 */
object MemoryDump {

    const val SUMMARY_PREVIEW = 80

    data class Entry(
        val name: String,
        val level: Int,
        val strength: Float,
        val summary: String?,
    )

    fun render(entries: List<Entry>): String {
        if (entries.isEmpty()) return "记忆内容：（空）"
        val lines = entries.map { e ->
            "「" + e.name + "」 L" + e.level +
                " strength=" + fmt(e.strength) +
                " 摘要=" + (e.summary?.take(SUMMARY_PREVIEW) ?: "（无）")
        }
        return "记忆内容：\n" + lines.joinToString("\n")
    }

    private fun fmt(v: Float): String =
        String.format(java.util.Locale.US, "%.2f", v)
}
