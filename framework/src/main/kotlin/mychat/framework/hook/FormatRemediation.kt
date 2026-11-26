package mychat.framework.hook

/**
 * 格式补救：模型没按格式输出时，**再问它一次**，要求只重排不改内容。
 *
 * 只补救一次，不循环——补救是最后手段，不是常态；连补救都失败说明
 * 这条规范本身有问题（或模型能力不够），这时候保留原回答比反复重试好。
 */
object FormatRemediation {

    const val MAX_ATTEMPTS = 1

    /** 四段消息：system 补一句、user 给结构、assistant 放原答案、user 下指令。 */
    data class Prompt(
        val systemSuffix: String,
        val structure: String,
        val answer: String,
        val finalInstruction: String
    )

    data class Result(
        val text: String,
        /** ok | replaced | still_missing | error */
        val status: String,
        /** 额外发起的模型调用次数（0 或 1）。 */
        val calls: Int
    )

    fun buildPrompt(check: FormatCheckResult, structure: String, answer: String): Prompt = Prompt(
        systemSuffix = "严格按下面的格式输出，不要改动已有内容，不要加解释。",
        structure = buildString {
            append("上一条回答缺少以下格式标记，请补齐：\n")
            for (mark in check.missing) {
                append("缺少：").append(mark).append('\n')
            }
            append("\n每条问题写成这样：\n")
            append(structure)
        },
        answer = answer,
        finalInstruction = "只输出补齐格式后的完整回答。"
    )

    /**
     * @param marks 模块声明的格式标记；一个都没出现才算 missing
     * @param call  发起一次模型调用并返回全文；抛异常或返回 null 都算失败
     */
    fun remediate(
        answer: String,
        moduleId: String,
        marks: List<String>,
        structure: String,
        call: (Prompt) -> String?
    ): Result {
        val check = FormatCheckResult.of(moduleId, marks, answer) ?: return Result(answer, "ok", 0)
        if (check.ok) return Result(answer, "ok", 0)
        if (structure.isBlank()) return Result(answer, "error", 0)

        val remedied = try {
            call(buildPrompt(check, structure, answer))
        } catch (e: Exception) {
            return Result(answer, "error", 1)
        }
        return apply(answer, remedied, marks)
    }

    /** 已经拿到补救结果时用这个：纯判定，不发起调用。 */
    fun apply(original: String, remedied: String?, marks: List<String>): Result {
        if (remedied.isNullOrBlank()) return Result(original, "error", 1)
        if (marks.any { remedied.contains(it) }) return Result(remedied, "replaced", 1)
        return Result(original, "still_missing", 1)
    }
}
