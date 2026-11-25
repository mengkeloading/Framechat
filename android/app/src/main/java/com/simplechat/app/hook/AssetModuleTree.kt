package com.simplechat.app.hook

import android.content.res.AssetManager
import mychat.framework.hook.ModuleTree

/**
 * 把 APK 里的 `assets/modules/` 暴露成框架要的模块树。
 *
 * assets 不是 File，所以框架那边只认 [ModuleTree] 这个读取口；
 * 扫描、front matter 解析、脚本与 reference 的加载都在框架里，这里只做 IO。
 */
class AssetModuleTree(
    private val assets: AssetManager,
    private val root: String,
) : ModuleTree {

    override fun list(dir: String): List<String> =
        runCatching { assets.list(join(dir))?.sorted()?.toList().orEmpty() }
            .getOrDefault(emptyList())

    override fun read(path: String): String? = runCatching {
        assets.open(join(path)).bufferedReader().use { it.readText() }
    }.getOrNull()

    private fun join(path: String): String = if (path.isEmpty()) root else "$root/$path"
}
