package mychat.framework.butler

/**
 * 对话类型。
 *
 * 为什么要分：闲聊不该背上 900 字的代码审查规范，而专业问题不该只有一句
 * "自然对话"。类型决定注入哪一段提示词——这是省 token 最直接的一刀。
 */
enum class Intent(val id: String) {

    /** 闲聊、天气、寒暄。 */
    CHAT("chat"),

    /** 代码审查、报错、重构。 */
    CODE("code"),

    /** 故事、文案、续写。 */
    WRITING("writing"),

    /** 计算、换算。 */
    CALC("calc"),

    /** 知识问答、概念解释。 */
    QUESTION("question");

    companion object {
        fun fromId(id: String?): Intent? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 每种类型对应的注入片段。
 *
 * 片段要短：它的作用是"定调"，不是"教学"。写长了就退化成又一份规范，
 * 那就失去了按类型注入的意义。
 */
object PromptByIntent {

    val MAP: Map<Intent, String> = mapOf(
        Intent.CHAT to "自然对话，不要格式化，不要列表，不要标题。",
        Intent.CODE to "给出可执行的修改，最小改动，不要整份重写。",
        Intent.WRITING to "感官先行，具体细节，结尾留白，不用「突然」。",
        Intent.CALC to "只给结果，不要解释推导过程。",
        Intent.QUESTION to "先说结论，再说理由，不超过三段。",
    )

    /** 注入块里用的整段文本；未知类型（null）返回 null。 */
    fun textOf(id: String?): String? = fromId(id)?.let { MAP[it] }

    fun fromId(id: String?): Intent? = Intent.fromId(id)
}
