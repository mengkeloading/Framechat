package com.simplechat.app.hook

import android.content.Context
import android.util.Log
import mychat.framework.embedding.Candidate
import mychat.framework.embedding.ModuleDocuments
import mychat.framework.embedding.ModuleRouter
import mychat.framework.hook.ModuleRegistry

/**
 * 只做一件事：在真机上验证 bge-small-zh + 模块描述能否把查询路由到正确的模块，
 * 结果写进 `Download/mychat-embed.log`（写不了就落 app 外部私有目录，路径见日志）。
 * 不参与任何业务逻辑，debug 包启动时跑一次。
 */
object EmbedProbe {

    private const val TAG = "MyChat/Embed"

    private class Case(val query: String, val expected: String)

    /** 8 条查询，每条都能说出「应该命中谁」。 */
    private val CASES = listOf(
        Case("帮我写个故事", "skill_novel"),
        Case("帮我续写这段对话", "skill_novel"),
        Case("算一下 12*37", "local_calc"),
        Case("把 100 华氏度换算成摄氏度", "local_calc"),
        Case("这段代码有问题吗", "skill_code"),
        Case("这样写对不对", "skill_code"),
        Case("我想做个记录喝水量的App", "skill_product"),
        Case("这个功能怎么设计", "skill_product"),
    )

    fun run(context: Context) {
        val lines = mutableListOf<String>()
        val embedder = OnnxTextEmbedder(context.assets)
        try {
            lines += "model=${OnnxTextEmbedder.MODEL_ASSET} dim=${embedder.dimension()}"
            lines += "prefix=query-only (bge v1.5 official)"
            lines += "threshold MIN_SCORE=${ModuleRouter.MIN_SCORE} MIN_MARGIN=${ModuleRouter.MIN_MARGIN}"

            // 候选 = assets 里所有带 description 的模块；文档侧不加前缀
            val scan = ModuleRegistry.scan(AssetModuleTree(context.assets, "modules"))
            val candidates = scan.registry.all()
                .filter { ModuleRouter.isRoutable(it.id) }
                .filter { it.manifest.description.isNotBlank() }
                .mapNotNull { pkg ->
                    embedder.embedPassage(ModuleDocuments.textOf(pkg))
                        ?.let { Candidate(pkg.id, it) }
                }
            lines += "candidates=${candidates.size} problems=${scan.problems.size}"

            for (case in CASES) {
                lines += decideOne(embedder, candidates, case)
            }
        } finally {
            embedder.close()
        }

        val name = "embed-" + EmbedLog.stamp() + ".log"
        runCatching { EmbedLog.prune(context, "embed-") }
        val path = EmbedLog.append(context, lines, name)
        Log.d(TAG, "written -> $path")
        lines.forEach { Log.d(TAG, it) }
    }

    private fun decideOne(
        embedder: OnnxTextEmbedder,
        candidates: List<Candidate>,
        case: Case,
    ): String {
        val query = embedder.embedQuery(case.query)
            ?: return "route(${case.query}) = <embed 返回空>"
        val decision = ModuleRouter.decide(query, candidates)
        val top1 = decision.top1
        val name = top1?.name ?: "-"
        val score = String.format("%.4f", top1?.score ?: 0f)
        val margin = String.format("%.4f", decision.margin)
        val top1Ok = decision.primary == case.expected
        val marginOk = decision.modules.isNotEmpty() && decision.margin > ModuleRouter.MIN_MARGIN
        val also = decision.modules.drop(1).joinToString(",").ifEmpty { "-" }
        return "route(${case.query}) -> $name score=$score margin=$margin also=$also " +
            "TOP1=${if (top1Ok) "Y" else "N"} MARGIN=${if (marginOk) "Y" else "N"} " +
            "(期望=${case.expected} 原因=${decision.reason})"
    }
}
