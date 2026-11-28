package com.simplechat.app.hook

import com.simplechat.app.db.TagDao
import com.simplechat.app.db.TagEntity
import com.simplechat.app.db.TagSummaryDao
import mychat.framework.memory.Recall

/**
 * `[[!EXPAND:名字]]` 的落地：把标签展开成"更细一层"的内容。
 *
 * 层数越小越细：L0 是原文，L1 是整段摘要，L2 是短摘要。
 * 展开永远只走一步 —— 模型要的是"再给点细节"，不是"全倒出来"。
 */
class ExpandResolver(
    private val tags: TagDao,
    private val summaries: TagSummaryDao,
    private val log: (String) -> Unit = {},
) {

    /** 原文最多带多少字：这已经是一屏的量，再多就得靠后续轮次继续要。 */
    private val rawLimit = 500

    data class Hit(val name: String, val fromLevel: Int, val toLevel: Int, val text: String)

    /** 按名字找标签并取细一层；找不到记 expand:miss。 */
    suspend fun resolve(name: String): Hit? {
        val tag = findByName(name)
        if (tag == null) {
            log("expand:miss name=$name")
            return null
        }
        val from = tag.currentLevel
        log("expand:request tag=" + tag.id.take(8) + " from=L$from")

        val text = if (from >= 1) {
            // 有摘要就先给上一层的摘要；该层没压过就退回原文
            val finer = summaries.at(tag.id, from - 1)?.text?.takeIf { it.isNotBlank() }
                ?: tag.summary?.takeIf { it.isNotBlank() }
            finer ?: rawOf(tag)
        } else {
            rawOf(tag)
        }
        if (text.isBlank()) return null
        val to = if (from >= 1) maxOf(0, from - 1) else 0
        log("expand:inject tag=" + tag.id.take(8) + " from=L$from to=L$to len=" + text.length)
        return Hit(tag.name, from, to, text)
    }

    private suspend fun rawOf(tag: TagEntity): String =
        tags.recentUserContents(tag.id, 8).joinToString("\n---\n").take(rawLimit)

    private suspend fun findByName(name: String): TagEntity? =
        tags.all().firstOrNull { it.name == name }

    /** 把若干命中拼成注入块；没有命中返回 null。 */
    fun format(hits: List<Hit>): String? {
        if (hits.isEmpty()) return null
        return hits.joinToString("\n\n") { h ->
            "【展开】关于「" + h.name + "」：\n" + h.text
        }
    }

    companion object {
        /** 与 [Recall.NO_DETAIL] 一致：展开不出来时不要编。 */
        const val EMPTY = Recall.NO_DETAIL
    }
}
