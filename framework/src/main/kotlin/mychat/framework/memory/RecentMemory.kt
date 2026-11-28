package mychat.framework.memory

/** 标签下最近一条原文的快照。 */
data class RecentItem(val id: String, val text: String, val at: Long)

/**
 * 「最近原文」：摘要天然滞后 —— 这一轮刚聊的内容还压在 L0，摘要还停在上一版。
 *
 * 规则很窄：
 * - 同一个会话里召回：只给摘要（用户自己刚说过的话不用复述）
 * - **跨会话**召回：摘要 + 最近两条原文（带时间），补上摘要落后那一段
 */
object RecentMemory {

    const val MAX = 2

    /** 追加一条，只留最近 [MAX] 条。 */
    fun append(existing: List<RecentItem>, item: RecentItem): List<RecentItem> =
        (existing + item).takeLast(MAX)

    /** 存库形态：`[{"id":"m1","text":"…","at":1700000000000}]`。 */
    fun encode(items: List<RecentItem>): String =
        items.joinToString(separator = ",", prefix = "[", postfix = "]") { item ->
            "{\"id\":\"" + esc(item.id) + "\",\"text\":\"" + esc(item.text) +
                "\",\"at\":" + item.at + "}"
        }

    fun decode(raw: String?): List<RecentItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { parse(raw) }.getOrDefault(emptyList())
    }

    /**
     * 跨会话才追加「最近」。同会话内摘要本来就最新，再说一遍只是浪费。
     *
     * @param sessionStartedAt 当前会话的创建时间；0 表示不知道，按跨会话处理
     */
    fun format(
        summary: String?,
        recent: List<RecentItem>,
        lastSeenAt: Long,
        sessionStartedAt: Long,
        now: Long = System.currentTimeMillis(),
    ): String? {
        val body = summary?.takeIf { it.isNotBlank() }
        val showRecent = recent.isNotEmpty() &&
            (sessionStartedAt <= 0 || lastSeenAt < sessionStartedAt)
        if (!showRecent) return body
        val lines = recent.joinToString("\n") { "[" + stamp(it.at, now) + "] " + it.text }
        return buildString {
            if (body != null) append(body).append("\n\n")
            append("最近：\n").append(lines)
        }
    }

    /** 时间戳：今天只给时分，跨天带上日期。 */
    fun stamp(at: Long, now: Long): String {
        val zone = java.time.ZoneId.systemDefault()
        val t = java.time.Instant.ofEpochMilli(at).atZone(zone)
        val n = java.time.Instant.ofEpochMilli(now).atZone(zone)
        return if (t.toLocalDate() == n.toLocalDate()) {
            "%02d:%02d".format(t.hour, t.minute)
        } else {
            "%d-%02d-%02d %02d:%02d".format(
                t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute
            )
        }
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun parse(raw: String): List<RecentItem> {
        val out = mutableListOf<RecentItem>()
        for (m in ITEM.findAll(raw)) {
            out += RecentItem(
                id = m.groupValues[1],
                text = m.groupValues[2]
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\"),
                at = m.groupValues[3].toLongOrNull() ?: 0L,
            )
        }
        return out.takeLast(MAX)
    }

    private val ITEM = Regex(
        "\\{\"id\":\"([^\"]*)\",\"text\":\"((?:[^\"\\\\]|\\\\.)*)\",\"at\":(\\d+)\\}"
    )
}
