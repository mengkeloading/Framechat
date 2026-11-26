package mychat.framework.hook

/**
 * 「启用这些模块之前，需要先做点什么」。
 *
 * 目前只有 `needs: [normalize]` 一种：模块要求输入先被归一化，
 * 但具体归一化由管家提供（规则 / 小模型 / 云端），模块自己不知道怎么做。
 */
object NeedResolver {

    const val NORMALIZE = "normalize"

    fun needsNormalize(modules: List<ModulePackage>, selected: Set<String>): Boolean =
        modules.any { it.id in selected && it.manifest.needs.contains(NORMALIZE) }
}
