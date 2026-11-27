package mychat.framework.butler

import mychat.framework.embedding.ModuleDocuments
import mychat.framework.hook.ModulePackage

/**
 * 按设置项切换管家。
 *
 * 为什么工厂是惰性 + 缓存：SemanticButler 里的模型只该加载一次，
 * 用户来回切 "semantic"/"keyword" 不该反复读 23MB 模型。
 * 这里只缓存实例，模型本身由外层共享的 TextEmbedder 持有。
 */
class ButlerManager(
    private val factories: Map<String, () -> Butler>,
    private val defaultId: String = SemanticButler.ID
) {

    private val instances = HashMap<String, Butler>()

    fun current(butlerId: String?): Butler {
        val key = butlerId?.takeIf { it.isNotBlank() } ?: defaultId
        instances[key]?.let { return it }
        val made = factories[key]?.invoke()
            ?: factories[defaultId]?.invoke()
            ?: NoButler()
        instances[key] = made
        return made
    }

    /**
     * 路由 + fail-open：任何管家抛异常都当"没路由到"，让调用方走兜底。
     * 判断器坏掉不该让整轮对话失败。
     */
    fun route(
        butlerId: String?,
        query: String,
        modules: List<ModulePackage>
    ): RouteResult {
        if (modules.isEmpty()) return RouteResult(emptyList(), "empty")
        val metas = modules.map { toMeta(it) }
        return runCatching { current(butlerId).route(query, metas) }
            .getOrElse { RouteResult(emptyList(), "error") }
    }

    fun toMeta(pkg: ModulePackage): ModuleMeta = ModuleMeta(
        id = pkg.id,
        name = pkg.manifest.name,
        description = pkg.manifest.description,
        prefix = pkg.manifest.prefix,
        embeddingText = ModuleDocuments.textOf(pkg),
        sampleQueries = pkg.manifest.sampleQueries
    )
}
