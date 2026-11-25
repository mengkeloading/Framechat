package mychat.framework.embedding

/**
 * BERT 中文用的 WordPiece 分词。
 *
 * 为什么不用现成的 DJL tokenizers：那是 Rust JNI，只发桌面 ABI，
 * Android 上必挂 `UnsatisfiedLinkError`。这里只做「够 bge-zh 用」的子集：
 * 汉字/标点逐字切、拉丁串小写后贪心最长匹配、未知词整词退化成 [UNK]。
 */
class WordPieceTokenizer(
    vocab: Map<String, Int>,
    private val maxTokens: Int = 512
) {

    private val vocab: Map<String, Int> = vocab

    private val cls = vocab["[CLS]"] ?: 101
    private val sep = vocab["[SEP]"] ?: 102
    private val unk = vocab["[UNK]"] ?: 100

    /** 返回带 [CLS]/[SEP]、截断到 [maxTokens] 的 id 序列。 */
    fun encode(text: String): LongArray {
        val ids = ArrayList<Int>(maxTokens)
        ids += cls
        for (token in basicTokens(text)) {
            for (id in wordPieces(token)) {
                if (ids.size >= maxTokens - 1) break
                ids += id
            }
            if (ids.size >= maxTokens - 1) break
        }
        ids += sep
        return LongArray(ids.size) { ids[it].toLong() }
    }

    /** 基本切分：空白断词、汉字与标点各自成词、其余按小写累成词。 */
    internal fun basicTokens(text: String): List<String> {
        val out = ArrayList<String>()
        val word = StringBuilder()

        fun flush() {
            if (word.isNotEmpty()) {
                out += word.toString().lowercase()
                word.setLength(0)
            }
        }

        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val chars = Character.charCount(cp)
            val ch = String(Character.toChars(cp))
            when {
                Character.isWhitespace(cp) -> flush()
                isCjk(cp) -> {
                    flush()
                    out += ch
                }
                isPunctuation(cp) -> {
                    flush()
                    out += ch
                }
                else -> word.append(ch)
            }
            i += chars
        }
        flush()
        return out
    }

    /** 贪心最长匹配：先试整词，不行就缩短，续接片段加 `##`。 */
    internal fun wordPieces(token: String): List<Int> {
        if (token.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var found = -1
            while (start < end) {
                val piece = if (start == 0) token.substring(start, end) else "##" + token.substring(start, end)
                val id = vocab[piece]
                if (id != null) {
                    found = id
                    break
                }
                end--
            }
            if (found < 0) return listOf(unk)
            out += found
            start = end
        }
        return out
    }

    private fun isCjk(cp: Int): Boolean =
        cp in 0x4E00..0x9FFF ||
            cp in 0x3400..0x4DBF ||
            cp in 0xF900..0xFAFF ||
            cp in 0x3040..0x30FF

    private fun isPunctuation(cp: Int): Boolean {
        if (cp in 0x21..0x2F || cp in 0x3A..0x40 || cp in 0x5B..0x60 || cp in 0x7B..0x7E) return true
        if (cp in 0x3000..0x303F || cp in 0xFF00..0xFF65) return true
        return when (Character.getType(cp)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }
}
