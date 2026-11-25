package com.simplechat.app.hook

import android.content.Context
import android.util.Log
import mychat.framework.embedding.Vectors

/**
 * 只做一件事：在真机上证明 bge-small-zh 能跑通，并把 8 条余弦值写进
 * `Download/mychat-embed.log`（写不了就落 app 外部私有目录，路径见日志）。
 * 不参与任何业务逻辑，debug 包启动时跑一次。
 */
object EmbedProbe {

    private const val TAG = "MyChat/Embed"

    private class Case(
        val query: String,
        val passage: String,
        val bound: Float,
        /** true = 期望大于 bound（应匹配），false = 期望小于（不应匹配）。 */
        val atLeast: Boolean,
    )

    private val CASES = listOf(
        Case("帮我写个故事", "小说写作", 0.6f, true),
        Case("帮我写个故事", "算一下 12*37", 0.35f, false),
        Case("这段代码有问题吗", "代码审查", 0.6f, true),
        Case("算一下 12*37", "本地计算", 0.6f, true),
        Case("算一下 12*37", "小说写作", 0.4f, false),
        Case("这段代码有问题吗", "小说写作", 0.4f, false),
        Case("我想做个记录喝水量的App", "产品需求拆解", 0.6f, true),
        Case("我想做个记录喝水量的App", "代码审查", 0.4f, false),
    )

    fun run(context: Context) {
        val lines = mutableListOf<String>()
        val embedder = OnnxTextEmbedder(context.assets)
        try {
            lines += "model=${OnnxTextEmbedder.MODEL_ASSET} dim=${embedder.dimension()}"
            lines += "prefix=query-only (bge v1.5 official)"
            for (case in CASES) {
                lines += measure(embedder, case)
            }
        } finally {
            embedder.close()
        }

        val path = EmbedLog.append(context, lines)
        Log.d(TAG, "written -> $path")
        lines.forEach { Log.d(TAG, it) }
    }

    /** 查询侧走 embedQuery（带前缀），模块描述侧走 embedPassage（不带）。 */
    private fun measure(embedder: OnnxTextEmbedder, case: Case): String {
        val q = embedder.embedQuery(case.query)
        val p = embedder.embedPassage(case.passage)
        val head = "cos(${case.query}, ${case.passage})"
        if (q == null || p == null) {
            return "$head = <embed 返回空 q=${q != null} p=${p != null}>"
        }
        val value = Vectors.cosine(q, p)
        val expect = if (case.atLeast) "期望>${case.bound}" else "期望<${case.bound}"
        val ok = if (case.atLeast) value > case.bound else value < case.bound
        return "$head=" + String.format("%.4f", value) + " ($expect) " + if (ok) "OK" else "MISS"
    }
}
