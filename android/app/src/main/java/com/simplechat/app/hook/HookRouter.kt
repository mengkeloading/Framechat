package com.simplechat.app.hook

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import mychat.framework.butler.ButlerManager
import mychat.framework.butler.Intent
import mychat.framework.butler.PromptByIntent
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
        /** 类型判定（chat/code/...）；null = 管家不判断。 */
        val intent: String? = null,
        /** 类型片段，注入到【对话类型】；null = 不加。 */
        val intentText: String? = null,
        /** 闲聊且没有前缀：不跑路由，只注入类型片段。 */
        val skipRoute: Boolean = false,
        /** 路由分数（best/second/margin），供日志。 */
        val routeScore: String = "",
    )

    suspend fun prepare(enabled: Set<String>, query: String, butlerId: String?): Prepared {
        val modules = source.all().filter { it.id in enabled }
        /*
         * 先判类型：闲聊不该背上模块规范。
         * 成本账：CHAT 只注入约 40 字片段、不注入模块 → 省 500~900 字；
         * CODE/WRITING 多 40 字。净效果是闲聊省九成，专业场景多 5%。
         */
        val intent = withContext(Dispatchers.Default) {
            runCatching { butlers.current(butlerId).classify(query) }.getOrNull()
        }
        val intentText = PromptByIntent.textOf(intent)
        if (modules.isEmpty()) {
            return Prepared(
                emptySet(), false, "no_modules",
                intent = intent, intentText = intentText
            )
        }
        val prefix = PrefixRouter.resolve(query, modules)?.module
        // 前缀是用户显式命令，永远优先于类型判断
        if (intent == Intent.CHAT.id && prefix == null) {
            return Prepared(
                selected = emptySet(),
                normalize = false,
                reason = "chat_skip",
                prefixModule = null,
                intent = intent,
                intentText = intentText,
                skipRoute = true,
            )
        }
        return withContext(Dispatchers.Default) {
            val route = butlers.route(butlerId, query, modules, intent)
            val selected = ModuleSelector.select(modules, route)
            Log.d(
                TAG,
                "candidates=" + modules.joinToString { "${it.id}(skill=${ModuleSelector.hasSkill(it)})" }
            )
            Log.d(TAG, "route=${route.modules} reason=${route.reason} selected=$selected")
            Log.d(TAG, mychat.framework.embedding.RouteLog.line(route))
            Prepared(
                selected = selected,
                normalize = NeedResolver.needsNormalize(modules, selected),
                reason = route.reason,
                routeModules = route.modules,
                routeScore = mychat.framework.embedding.RouteLog.line(route),
                prefixModule = prefix,
                intent = intent,
                intentText = intentText,
            )
        }
    }

    /** 归一化：管家没实现（返回 null）或抛异常都当"没归一化"。 */
    suspend fun normalize(text: String, butlerId: String?): String? =
        withContext(Dispatchers.Default) {
            runCatching { butlers.current(butlerId).normalize(text) }.getOrNull()
        }
}
