package mychat.framework.hook

/**
 * 前缀 = 强制指令。
 *
 * 输入以「词：」开头时，直接用对应模块，跳过路由与判断。
 * 前缀本身**不入模**：返回的 [Match.text] 已经去掉前缀，而落库保留原文。
 */
object PrefixRouter {

    data class Match(
        /** 被强制的模块 id。 */
        val module: String,
        /** 去掉前缀后的文本，用于组包。 */
        val text: String,
        /** 命中的前缀词。 */
        val prefix: String
    )

    /** 中英文冒号都算。 */
    private val SEPARATORS = charArrayOf('：', ':')

    /**
     * 按 modules 的顺序找第一个匹配；两个模块同 prefix 时取第一个（调用方负责记日志）。
     */
    fun resolve(input: String, modules: List<ModulePackage>): Match? {
        for (pkg in modules) {
            val prefix = pkg.manifest.prefix?.takeIf { it.isNotBlank() } ?: continue
            if (input.startsWith(prefix)) {
                val rest = input.substring(prefix.length)
                if (rest.isEmpty() || rest[0] !in SEPARATORS) continue
                val body = rest.substring(1).trim()
                return Match(pkg.id, body, prefix)
            }
        }
        return null
    }
}
