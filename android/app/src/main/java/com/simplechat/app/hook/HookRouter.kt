package com.simplechat.app.hook

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import mychat.framework.butler.ButlerManager
import mychat.framework.embedding.ModuleSelector
import mychat.framework.hook.ModuleSource
import mychat.framework.hook.NeedResolver
import mychat.framework.hook.PrefixRouter

/**
 * 把「这一轮该注入哪些技能、入模前要不要先归一化」算出来。
 *
 * 判断交给 [ButlerManager]（语义 / 关键词 / 无管家），这里只负责：
 * 取启用模块 → 问管家 → 套兜底规则 → 看模块有没有 needs。
 */
class HookRouter(
    private val source: ModuleSource,
    private val butlers: ButlerManager,
) {

    private companion object {
        const val TAG = "MyChat/Sel"
    }

    data class Prepared(
        /** 要注入的模块。 */
        val selected: Set<String>,
        /** 选中模块里有 `needs: [normalize]`。 */
        val normalize: Boolean,
        /** 路由原因，供日志。 */
        val reason: String,
        /** 管家直接给出的命中项（未经兜底），供日志。 */
        val routeModules: List<String> = emptyList(),
        /** 前缀命中（只用于日志；实际强制在 HookRunner 里做）。 */
        val prefixModule: String? = null,
    )

    suspend fun prepare(enabled: Set<String>, query: String, butlerId: String?): Prepared {
        if (enabled.isEmpty()) return Prepared(emptySet(), false, "no_modules")
        val modules = source.all().filter { it.id in enabled }
        if (modules.isEmpty()) return Prepared(emptySet(), false, "no_modules")
        return withContext(Dispatchers.Default) {
            val route = butlers.route(butlerId, query, modules)
            val selected = ModuleSelector.select(modules, route)
            Log.d(
                TAG,
                "candidates=" + modules.joinToString { "${it.id}(skill=${ModuleSelector.hasSkill(it)})" }
            )
            Log.d(TAG, "route=${route.modules} reason=${route.reason} selected=$selected")
            Prepared(
                selected = selected,
                normalize = NeedResolver.needsNormalize(modules, selected),
                reason = route.reason,
                routeModules = route.modules,
                prefixModule = PrefixRouter.resolve(query, modules)?.module
            )
        }
    }

    /** 归一化：管家没实现（返回 null）或抛异常都当"没归一化"。 */
    suspend fun normalize(text: String, butlerId: String?): String? =
        withContext(Dispatchers.Default) {
            runCatching { butlers.current(butlerId).normalize(text) }.getOrNull()
        }
}
