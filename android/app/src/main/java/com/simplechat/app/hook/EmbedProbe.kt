package com.simplechat.app.hook

import android.content.res.AssetManager
import android.util.Log
import mychat.framework.embedding.Vectors

/**
 * 只做一件事：在真机上证明 bge-small-zh 能跑通，并把三条余弦值打到日志。
 * 不参与任何业务逻辑，debug 包启动时跑一次。
 */
object EmbedProbe {

    private const val TAG = "MyChat/Embed"

    fun run(assets: AssetManager) {
        val embedder = OnnxTextEmbedder(assets)
        try {
            cosineOf(embedder, "帮我写个故事", "小说写作")
            cosineOf(embedder, "帮我写个故事", "算一下 12*37")
            cosineOf(embedder, "这段代码有问题吗", "代码审查")
        } finally {
            embedder.close()
        }
    }

    private fun cosineOf(embedder: OnnxTextEmbedder, left: String, right: String) {
        val a = embedder.embed(left)
        val b = embedder.embed(right)
        if (a == null || b == null) {
            Log.w(TAG, "embed 返回空: left=${a != null} right=${b != null} dim=${a?.size}")
            return
        }
        Log.d(TAG, "cos($left, $right) = " + String.format("%.4f", Vectors.cosine(a, b)))
    }
}
