package mychat.framework.hook

data class JudgeWarning(
    /** repeat | vague | boilerplate */
    val rule: String,
    /** 人看的说明。 */
    val detail: String,
    /** 日志用的紧凑参数，如 `len=15` / `word=建议优化`。 */
    val logArg: String,
)

/**
 * 输出质检：只记日志，不改内容、不重试、不拦截。
 *
 * 为什么是"只记"：判错一次就把回答拦下来，代价远大于收益。先攒够样本，
 * 再决定哪条规则值得动手。
 */
object OutputJudge {

    /** 复述检测的窗口长度。 */
    const val WINDOW = 15

    private val VAGUE_WORDS = listOf("建议优化", "可以更好", "根据实际情况", "视情况而定")

    private val BOILERPLATE_WORDS = listOf("作为一个AI", "作为AI", "我是一个语言模型", "我无法真正")

    fun judge(
        text: String,
        userInput: String,
        constraints: List<String>,
    ): List<JudgeWarning> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<JudgeWarning>()

        // 1. repeat：用户输入里连续 15 字原样出现在回答里。只报一次。
        val trimmedInput = userInput.trim()
        if (trimmedInput.length >= WINDOW) {
            for (start in 0..trimmedInput.length - WINDOW) {
                val window = trimmedInput.substring(start, start + WINDOW)
                if (text.contains(window)) {
                    out += JudgeWarning(
                        rule = "repeat",
                        detail = "复述了输入：" + trimmedInput.take(20),
                        logArg = "len=" + WINDOW,
                    )
                    break
                }
            }
        }

        // 2. vague：只有模块显式要了 no_vague 才查 —— 闲聊里"可以更好"是正常表达
        if (constraints.contains("no_vague")) {
            for (word in VAGUE_WORDS) {
                if (text.contains(word)) {
                    out += JudgeWarning("vague", "空话：" + word, "word=" + word)
                }
            }
        }

        // 3. boilerplate：所有模块都查，这是模型身份泄漏，与话题无关
        for (word in BOILERPLATE_WORDS) {
            if (text.contains(word)) {
                out += JudgeWarning("boilerplate", "套话：" + word, "word=" + word)
            }
        }

        return out
    }
}
