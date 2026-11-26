package com.simplechat.app.hook

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mychat.framework.butler.ButlerManager
import mychat.framework.embedding.ModuleSelector
import mychat.framework.hook.ModuleSource

/**
 * 把「这一轮该注入哪些技能」算出来。
 *
 * 判断交给 [ButlerManager]（语义 / 关键词 / 无管家），这里只负责：
 * 取启用模块 → 问管家 → 套兜底规则。
 */
class HookRouter(
    private val source: ModuleSource,
    private val butlers: ButlerManager,
) {

    suspend fun select(enabled: Set<String>, query: String, butlerId: String?): Set<String> {
        if (enabled.isEmpty()) return emptySet()
        val modules = source.all().filter { it.id in enabled }
        if (modules.isEmpty()) return emptySet()
        return withContext(Dispatchers.Default) {
            ModuleSelector.select(modules, butlers.route(butlerId, query, modules))
        }
    }

    /** 管家归一化；异常或未实现都返回 null（调用方用原文）。 */
    fun normalize(butlerId: String?, text: String): String? =
        runCatching { butlers.current(butlerId).normalize(text) }.getOrNull()
}
