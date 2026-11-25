package mychat.framework.json

/**
 * 零依赖 JSON。模块边界的入参/出参都是 JSON 字符串，框架不能为此引入
 * kotlinx.serialization（会带来编译器插件与运行时依赖）。
 * 解析失败一律返回 null，交给调用方按 fail-open 处理。
 */
sealed class JsonValue {

    data class Obj(val fields: Map<String, JsonValue>) : JsonValue() {
        operator fun get(key: String): JsonValue? = fields[key]
    }

    data class Arr(val items: List<JsonValue>) : JsonValue()

    data class Str(val value: String) : JsonValue()

    data class Num(val value: Double) : JsonValue()

    data class Bool(val value: Boolean) : JsonValue()

    object Null : JsonValue()

    fun encode(): String = StringBuilder(64).also { write(it) }.toString()

    fun write(sb: StringBuilder) {
        when (this) {
            is Obj -> {
                sb.append('{')
                var first = true
                for ((k, v) in fields) {
                    if (!first) sb.append(',')
                    first = false
                    appendString(sb, k)
                    sb.append(':')
                    v.write(sb)
                }
                sb.append('}')
            }
            is Arr -> {
                sb.append('[')
                items.forEachIndexed { idx, v ->
                    if (idx > 0) sb.append(',')
                    v.write(sb)
                }
                sb.append(']')
            }
            is Str -> appendString(sb, value)
            is Num -> sb.append(numberText(value))
            is Bool -> sb.append(if (value) "true" else "false")
            Null -> sb.append("null")
        }
    }

    fun str(key: String): String? = ((this as? Obj)?.fields?.get(key) as? Str)?.value

    fun int(key: String): Int? = ((this as? Obj)?.fields?.get(key) as? Num)?.value?.toInt()

    fun bool(key: String): Boolean? = ((this as? Obj)?.fields?.get(key) as? Bool)?.value

    fun arr(key: String): List<JsonValue>? = ((this as? Obj)?.fields?.get(key) as? Arr)?.items

    fun obj(key: String): Obj? = (this as? Obj)?.fields?.get(key) as? Obj

    fun objects(key: String): List<Obj> =
        arr(key).orEmpty().mapNotNull { it as? Obj }

    fun strings(key: String): List<String> =
        arr(key).orEmpty().mapNotNull { (it as? Str)?.value }

    companion object {
        fun of(vararg pairs: Pair<String, JsonValue?>): Obj {
            val m = LinkedHashMap<String, JsonValue>(pairs.size * 2)
            for ((k, v) in pairs) if (v != null) m[k] = v
            return Obj(m)
        }

        fun array(items: List<JsonValue>): Arr = Arr(items)
    }
}

private fun numberText(v: Double): String {
    if (v.isNaN() || v.isInfinite()) return "null"
    if (v == Math.floor(v) && Math.abs(v) < 1e15) return v.toLong().toString()
    return v.toString()
}

private fun appendString(sb: StringBuilder, s: String) {
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else ->
                if (c < ' ') sb.append(String.format("\\u%04x", c.code))
                else sb.append(c)
        }
    }
    sb.append('"')
}

class JsonError(message: String) : Exception(message)

fun parseJson(text: String?): JsonValue? {
    if (text == null) return null
    return try {
        Reader(text).parse()
    } catch (e: JsonError) {
        null
    }
}

private class Reader(private val s: String) {
    private var i = 0
    private var depth = 0

    fun parse(): JsonValue {
        skipWs()
        val v = readValue()
        skipWs()
        if (i != s.length) throw JsonError("trailing content at $i")
        return v
    }

    private fun readValue(): JsonValue {
        if (i >= s.length) throw JsonError("unexpected end")
        return when (val c = s[i]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> JsonValue.Str(readString())
            't' -> literal("true", JsonValue.Bool(true))
            'f' -> literal("false", JsonValue.Bool(false))
            'n' -> literal("null", JsonValue.Null)
            else -> if (c == '-' || c in '0'..'9') readNumber() else throw JsonError("bad char $c")
        }
    }

    private fun literal(word: String, v: JsonValue): JsonValue {
        if (!s.startsWith(word, i)) throw JsonError("bad literal at $i")
        i += word.length
        return v
    }

    private fun readObject(): JsonValue.Obj {
        enter()
        i++
        val m = LinkedHashMap<String, JsonValue>()
        skipWs()
        if (i < s.length && s[i] == '}') {
            i++
            depth--
            return JsonValue.Obj(m)
        }
        while (true) {
            skipWs()
            if (i >= s.length || s[i] != '"') throw JsonError("expected key at $i")
            val k = readString()
            skipWs()
            if (i >= s.length || s[i] != ':') throw JsonError("expected colon at $i")
            i++
            skipWs()
            m[k] = readValue()
            skipWs()
            if (i >= s.length) throw JsonError("unterminated object")
            when (s[i]) {
                ',' -> i++
                '}' -> { i++; depth--; return JsonValue.Obj(m) }
                else -> throw JsonError("expected , or } at $i")
            }
        }
    }

    private fun readArray(): JsonValue.Arr {
        enter()
        i++
        val items = ArrayList<JsonValue>()
        skipWs()
        if (i < s.length && s[i] == ']') {
            i++
            depth--
            return JsonValue.Arr(items)
        }
        while (true) {
            skipWs()
            items.add(readValue())
            skipWs()
            if (i >= s.length) throw JsonError("unterminated array")
            when (s[i]) {
                ',' -> i++
                ']' -> { i++; depth--; return JsonValue.Arr(items) }
                else -> throw JsonError("expected , or ] at $i")
            }
        }
    }

    private fun enter() {
        depth++
        if (depth > 64) throw JsonError("nesting too deep")
    }

    private fun readString(): String {
        if (s[i] != '"') throw JsonError("expected string at $i")
        i++
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) throw JsonError("unterminated string")
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i >= s.length) throw JsonError("unterminated escape")
                    when (val e = s[i++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) throw JsonError("bad unicode escape")
                            val hex = s.substring(i, i + 4)
                            val code = hex.toIntOrNull(16) ?: throw JsonError("bad unicode $hex")
                            sb.append(code.toChar())
                            i += 4
                        }
                        else -> throw JsonError("bad escape $e")
                    }
                }
                else -> {
                    if (c < ' ') throw JsonError("raw control char in string")
                    sb.append(c)
                }
            }
        }
    }

    private fun readNumber(): JsonValue.Num {
        val start = i
        if (s[i] == '-') i++
        while (i < s.length && s[i] in '0'..'9') i++
        if (i < s.length && s[i] == '.') {
            i++
            while (i < s.length && s[i] in '0'..'9') i++
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            while (i < s.length && s[i] in '0'..'9') i++
        }
        val raw = s.substring(start, i)
        val d = raw.toDoubleOrNull() ?: throw JsonError("bad number $raw")
        return JsonValue.Num(d)
    }

    private fun skipWs() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
    }
}
