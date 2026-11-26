package com.simplechat.app.hook

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mychat.framework.embedding.Candidate
import mychat.framework.embedding.ModuleDocuments
import mychat.framework.embedding.ModuleSelector
import mychat.framework.embedding.TextEmbedder
import mychat.framework.hook.ModulePackage
import mychat.framework.hook.ModuleSource
import java.util.concurrent.ConcurrentHashMap

/**
 * 把「这一轮该注入哪些技能」算出来（selected 集合）。
 *
 * 模块向量按 (id, 文本) 缓存：改过 description / 何时使用节的模块会自动重算，
 * 没改的直接复用 —— 19 个模块每次重算约 0.5 秒，不该花在这上面。
 */
class HookRouter(
    private val source: ModuleSource,
    private val embedder: TextEmbedder,
) {

    private class Cached(val text: String, val vector: FloatArray)

    private val cache = ConcurrentHashMap<String, Cached>()

    suspend fun select(enabled: Set<String>, query: String): Set<String> {
        if (enabled.isEmpty()) return emptySet()
        val modules = source.all().filter { it.id in enabled }
        if (modules.isEmpty()) return emptySet()

        return withContext(Dispatchers.Default) {
            val queryVector = embedder.embedQuery(query)
            val candidates = if (queryVector == null) {
                emptyList()
            } else {
                modules.mapNotNull { pkg ->
                    vectorOf(pkg)?.let { Candidate(pkg.id, it) }
                }
            }
            ModuleSelector.select(modules, queryVector, candidates)
        }
    }

    private fun vectorOf(pkg: ModulePackage): FloatArray? {
        val text = ModuleDocuments.textOf(pkg)
        cache[pkg.id]?.let { if (it.text == text) return it.vector }
        val vector = embedder.embedPassage(text) ?: return null
        cache[pkg.id] = Cached(text, vector)
        Log.d(TAG, "embedded ${pkg.id} dim=${vector.size}")
        return vector
    }

    companion object {
        private const val TAG = "MyChat/Route"
    }
}
