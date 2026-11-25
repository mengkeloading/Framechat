package mychat.framework.embedding

/**
 * 文本向量化。实现按平台不同（Android 用 ONNX Runtime + bge-small-zh），
 * 路由与判断只依赖这个接口。
 */
interface TextEmbedder {

    /** 返回 L2 归一化后的向量；空文本或无法编码时返回 null。 */
    fun embed(text: String): FloatArray?
}

object Vectors {

    /** 两个等长向量的余弦相似度；长度不一致或零向量返回 0。 */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na <= 0.0 || nb <= 0.0) return 0f
        return (dot / (Math.sqrt(na) * Math.sqrt(nb))).toFloat()
    }

    /** L2 归一化，原地修改并返回同一个数组。 */
    fun normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x * x
        if (sum <= 0.0) return v
        val inv = (1.0 / Math.sqrt(sum)).toFloat()
        for (i in v.indices) v[i] *= inv
        return v
    }

    /**
     * 取相似度最高的候选；低于阈值视为「没有合适的」，返回 null。
     * 路由的判定规则就这一条，不放在调用方。
     */
    fun top1(
        query: FloatArray,
        candidates: List<Pair<String, FloatArray>>,
        threshold: Float = DEFAULT_THRESHOLD
    ): String? {
        var best: String? = null
        var bestScore = threshold
        for ((name, vec) in candidates) {
            val score = cosine(query, vec)
            if (score >= bestScore) {
                bestScore = score
                best = name
            }
        }
        return best
    }

    const val DEFAULT_THRESHOLD = 0.55f
}
