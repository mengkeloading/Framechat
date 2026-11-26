package mychat.framework.hook

/**
 * 发送前的粗估：中文约 1 token ≈ 1.5 字符。
 *
 * 只为拦"明显超长"的输入——真去数 token 要引分词器，而这个判断只需要
 * 在用户贴进一整篇文章时提前喊停。
 */
object TokenBudget {

    const val MAX_INPUT_TOKENS = 8000

    private const val CHARS_PER_TOKEN = 1.5

    fun estimate(text: String): Int = (text.length / CHARS_PER_TOKEN).toInt()

    fun exceeds(text: String, max: Int = MAX_INPUT_TOKENS): Boolean = estimate(text) > max
}
