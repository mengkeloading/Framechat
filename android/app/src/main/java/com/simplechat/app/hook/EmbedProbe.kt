package com.simplechat.app.hook

import android.content.Context
import android.util.Log
import mychat.framework.embedding.Vectors

/**
 * 只做一件事：在真机上证明 bge-small-zh 能跑通，并把三条余弦值写进
 * `Download/mychat-embed.log`（写不了就落 app 外部私有目录，路径见日志）。
 * 不参与任何业务逻辑，debug 包启动时跑一次。
 */
object EmbedProbe {

    private const val TAG = "MyChat/Embed"

    fun run(context: Context) {
        val lines = mutableListOf<String>()
        val embedder = OnnxTextEmbedder(context.assets)
        try {
            lines += "model=${OnnxTextEmbedder.MODEL_ASSET} dim=${embedder.dimension()}"
            cosineOf(embedder, "帮我写个故事", "小说写作", lines)
            cosineOf(embedder, "帮我写个故事", "算一下 12*37", lines)
            cosineOf(embedder, "这段代码有问题吗", "代码审查", lines)
        } finally {
            embedder.close()
        }

        val path = EmbedLog.append(context, lines)
        Log.d(TAG, "written -> $path")
        lines.forEach { Log.d(TAG, it) }
    }

    private fun cosineOf(
        embedder: OnnxTextEmbedder,
        left: String,
        right: String,
        lines: MutableList<String>,
    ) {
        val a = embedder.embed(left)
        val b = embedder.embed(right)
        if (a == null || b == null) {
            lines += "cos($left, $right) = <embed 返回空 left=${a != null} right=${b != null}>"
            return
        }
        lines += "cos($left, $right)=" + String.format("%.4f", Vectors.cosine(a, b))
    }
}
