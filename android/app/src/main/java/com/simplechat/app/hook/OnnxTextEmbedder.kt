package com.simplechat.app.hook

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import android.util.Log
import mychat.framework.embedding.TextEmbedder
import mychat.framework.embedding.Vectors
import mychat.framework.embedding.WordPieceTokenizer
import java.nio.LongBuffer

/**
 * bge-small-zh-v1.5（ONNX 量化版）的 Android 实现。
 *
 * 分词用框架里的纯 Kotlin WordPiece，不引 DJL —— 那是 Rust JNI，
 * 只发桌面 ABI，Android 上会 `UnsatisfiedLinkError`。
 *
 * 池化用 CLS（last_hidden_state[:, 0]），不是 mean pooling；
 * bge 系列就是按 CLS 训练的，换池化方式相似度会塌。
 */
class OnnxTextEmbedder(
    assets: AssetManager,
    private val modelAsset: String = MODEL_ASSET,
    vocabAsset: String = VOCAB_ASSET,
) : TextEmbedder {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val tokenizer: WordPieceTokenizer
    private val session: OrtSession

    init {
        val vocab = HashMap<String, Int>(40000)
        assets.open(vocabAsset).bufferedReader().useLines { lines ->
            lines.forEachIndexed { index, word -> vocab[word.trim()] = index }
        }
        tokenizer = WordPieceTokenizer(vocab)

        val bytes = assets.open(modelAsset).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(bytes, options)
        // 输入名不一定叫 input_ids/attention_mask/token_type_ids，先打出来
        Log.d(TAG, "inputs=" + session.inputNames.joinToString(",") +
            " outputs=" + session.outputNames.joinToString(","))
    }

    override fun embed(text: String): FloatArray? = embedQuery(text)

    /** 查询侧：带上 bge v1.5 的官方指令前缀，否则相似度会明显偏低。 */
    fun embedQuery(text: String): FloatArray? = embedWithPrefix(QUERY_PREFIX + text)

    /** 文档侧：不加前缀。模块 description 走这里。 */
    fun embedPassage(text: String): FloatArray? = embedWithPrefix(text)

    private fun embedWithPrefix(text: String): FloatArray? {
        val ids = tokenizer.encode(text)
        if (ids.isEmpty()) return null
        val shape = longArrayOf(1L, ids.size.toLong())

        var inputIds: OnnxTensor? = null
        var attention: OnnxTensor? = null
        var typeIds: OnnxTensor? = null
        var result: OrtSession.Result? = null
        try {
            inputIds = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
            attention = OnnxTensor.createTensor(
                env, LongBuffer.wrap(LongArray(ids.size) { 1L }), shape
            )
            typeIds = OnnxTensor.createTensor(
                env, LongBuffer.wrap(LongArray(ids.size)), shape
            )

            val inputs = HashMap<String, OnnxTensor>()
            for (name in session.inputNames) {
                inputs[name] = when {
                    name.contains("mask", ignoreCase = true) -> attention
                    name.contains("type", ignoreCase = true) -> typeIds
                    else -> inputIds
                }
            }

            result = session.run(inputs)
            val out = result.get(0) as? OnnxTensor ?: return null
            val buffer = out.floatBuffer
            // [1, seq, hidden]：CLS 就是前 hidden 个浮点数
            val hidden = buffer.capacity() / ids.size
            if (hidden <= 0) return null
            val vector = FloatArray(hidden) { buffer.get(it) }
            return Vectors.normalize(vector)
        } finally {
            result?.close()
            inputIds?.close()
            attention?.close()
            typeIds?.close()
        }
    }

    fun close() {
        session.close()
    }

    /** bge-small-zh 的隐藏层宽度固定 512。 */
    fun dimension(): Int = DIMENSION

    companion object {
        const val MODEL_ASSET = "models/bge-small-zh.onnx"
        const val VOCAB_ASSET = "models/vocab.txt"
        const val QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章："
        private const val DIMENSION = 512
        private const val TAG = "MyChat/Embed"
    }
}
