package mychat.framework.hook

import java.io.File

/**
 * 模块树的读取口。
 *
 * 为什么要有这一层：APK 里的模块在 `assets/modules/`，它不是 File —
 * FileModuleTree 读不到。Android 侧实现同一个接口，用 AssetManager 提供
 * list/read；框架其余部分与模块扫描逻辑只认这个接口。
 */
interface ModuleTree {

    /** 目录下的直接子项名（目录与文件都算）。不存在时返回空表。 */
    fun list(dir: String): List<String>

    /** 读文件全文。不存在或读失败返回 null。 */
    fun read(path: String): String?
}

/** 文件系统实现。root 为空时视作当前目录。 */
class FileModuleTree(private val root: File) : ModuleTree {

    override fun list(dir: String): List<String> {
        val f = resolve(dir)
        if (!f.isDirectory) return emptyList()
        return f.list()?.sorted().orEmpty()
    }

    override fun read(path: String): String? {
        val f = resolve(path)
        if (!f.isFile) return null
        return try {
            f.readText()
        } catch (e: Exception) {
            null
        }
    }

    private fun resolve(rel: String): File =
        if (rel.isEmpty()) root else File(root, rel)
}

/** 内存树：测试用，也是 assets 预加载的通用形态。 */
class MapModuleTree(private val files: Map<String, String>) : ModuleTree {

    private val dirs: Set<String> = buildSet {
        for (path in files.keys) {
            var i = path.lastIndexOf('/')
            while (i > 0) {
                add(path.substring(0, i))
                i = path.lastIndexOf('/', i - 1)
            }
        }
    }

    override fun list(dir: String): List<String> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val out = sortedSetOf<String>()
        for (path in files.keys) {
            if (path.startsWith(prefix) && path.length > prefix.length) {
                out += path.substring(prefix.length).substringBefore('/')
            }
        }
        for (path in dirs) {
            if (path.startsWith(prefix) && path.length > prefix.length) {
                out += path.substring(prefix.length).substringBefore('/')
            }
        }
        return out.toList()
    }

    override fun read(path: String): String? = files[path]
}
