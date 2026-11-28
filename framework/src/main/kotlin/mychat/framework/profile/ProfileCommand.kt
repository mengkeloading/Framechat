package mychat.framework.profile

/**
 * `debug:profile key=value …`：在没有设置页的情况下改角色卡。
 *
 * 解析与合并放在框架里，是为了能脱离 App 测「只改给出的字段」这条语义 ——
 * 它最容易写错（漏字段、把空值当成清空）。
 */
object ProfileCommand {

    const val NAME = "debug:profile"

    const val KEY_ROLE = "role"
    const val KEY_TECH = "tech"
    const val KEY_STYLE = "style"
    const val KEY_EXPLAIN = "explain"
    const val KEY_AVOID = "avoid"

    /** 不是 profile 命令返回 null；裸命令返回空 map。 */
    fun parse(text: String, debugBuild: Boolean): Map<String, String>? {
        if (!debugBuild) return null
        val trimmed = text.trim()
        if (trimmed != NAME && !trimmed.startsWith(NAME + " ")) return null
        return parseArgs(trimmed.removePrefix(NAME))
    }

    /** `role=a style=b` → map。空值的键保留为空串（表示清空该字段）。 */
    fun parseArgs(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (token in raw.split(' ').filter { it.isNotBlank() }) {
            val at = token.indexOf('=')
            if (at <= 0) continue
            out[token.substring(0, at).trim()] = token.substring(at + 1).trim()
        }
        return out
    }

    /** 逗号分隔列表：中英文逗号与顿号都认。 */
    fun splitList(raw: String): List<String> =
        raw.split(',', '，', '、').map { it.trim() }.filter { it.isNotEmpty() }

    fun joinList(list: List<String>): String = list.joinToString(",")

    /**
     * 只覆盖给出的字段，其余保留。
     *
     * 没给的字段不动：命令行漏写一个字段不该把配置抹掉。
     * 想清空就显式写 `role=`。
     */
    fun apply(base: UserProfile, args: Map<String, String>): UserProfile {
        var next = base
        args[KEY_ROLE]?.let { next = next.copy(role = it) }
        args[KEY_TECH]?.let { next = next.copy(tech = splitList(it)) }
        args[KEY_STYLE]?.let { next = next.copy(style = it.ifBlank { "平衡" }) }
        args[KEY_EXPLAIN]?.let { next = next.copy(explain = it.equals("true", true) || it == "是") }
        args[KEY_AVOID]?.let { next = next.copy(avoid = splitList(it)) }
        return next
    }

    /** 设置后的回执。 */
    fun updated(profile: UserProfile): String = buildString {
        append("角色卡已更新：\n")
        append("身份：").append(profile.role.ifBlank { "（未设置）" }).append('\n')
        append("风格：").append(profile.style.ifBlank { "平衡" }).append('\n')
        append("讨厌：").append(
            if (profile.avoid.isEmpty()) "（无）" else profile.avoid.joinToString("、")
        )
        if (profile.tech.isNotEmpty()) {
            append('\n').append("技术：").append(profile.tech.joinToString("、"))
        }
        append('\n').append("解释过程：").append(if (profile.explain) "要" else "不要")
    }

    /** 裸命令的回执。 */
    fun describe(profile: UserProfile): String {
        val body = ProfileFormatter.format(profile) ?: return "角色卡：（空）"
        return "当前角色卡：\n" + body.removePrefix("【关于你】\n")
    }
}
