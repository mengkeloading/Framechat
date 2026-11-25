package mychat.framework.hook

import java.io.File

/** 一个已加载的模块包：manifest + MODULE.md 正文 + 脚本 + reference 文件。 */
class ModulePackage(
    val manifest: ModuleManifest,
    val body: String,
    val scripts: Map<String, String>,
    val references: Map<String, String>
) {
    val id: String get() = manifest.id

    fun script(file: String): String? = scripts[file]

    fun reference(name: String): String? =
        if (name == "MODULE.md") body else references[name]
}

/**
 * 模块只读视图。dispatcher 只通过它取包、取 reference ——
 * 拿不到文件系统，也拿不到别的模块的脚本。
 */
interface ModuleSource {
    fun getPackage(id: String): ModulePackage?

    fun all(): List<ModulePackage>

    /** 按 session 里的启用顺序过滤出声明了该钩子的模块。 */
    fun idsForPhase(chain: List<String>, phase: String): List<String> =
        chain.filter { getPackage(it)?.manifest?.handles(phase) == true }

    /** 解析 `id` 或 `id/script/main.js`。 */
    fun resolveScript(ref: String): Pair<ModulePackage, String>? {
        val slash = ref.indexOf('/')
        val pkg = if (slash < 0) getPackage(ref) else getPackage(ref.substring(0, slash))
        pkg ?: return null
        val file = if (slash < 0) pkg.manifest.entry else ref.substring(slash + 1)
        return pkg to file.removePrefix("script/")
    }

    fun getReference(id: String, name: String): String? = getPackage(id)?.reference(name)
}

/** 运行期扫描 assets/modules/<目录>/ 得到模块表。 */
class ModuleRegistry(private val packages: Map<String, ModulePackage>) : ModuleSource {

    override fun getPackage(id: String): ModulePackage? = packages[id]

    override fun all(): List<ModulePackage> = packages.values.toList()

    data class ScanResult(val registry: ModuleRegistry, val problems: List<String>)

    companion object {
        fun of(list: List<ModulePackage>): ModuleRegistry =
            ModuleRegistry(list.associateBy { it.id })

        fun scan(root: File): ScanResult {
            val pkgs = LinkedHashMap<String, ModulePackage>()
            val problems = mutableListOf<String>()
            val dirs = root.listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()
            for (dir in dirs) {
                val md = File(dir, "MODULE.md")
                if (!md.isFile) continue
                val text = try {
                    md.readText()
                } catch (e: Exception) {
                    problems += "${dir.name}: MODULE.md 读取失败"
                    continue
                }
                val fm = FrontMatter.parse(text)
                if (fm == null) {
                    problems += "${dir.name}: MODULE.md 缺 front matter"
                    continue
                }
                val manifest = ModuleManifest.from(fm, dir.name)
                val scripts = readTree(File(dir, "script"))
                if (manifest.hooks.isNotEmpty() && scripts[manifest.entry] == null) {
                    problems += "${manifest.id}: hooks=${manifest.hooks} 但 script/${manifest.entry} 不存在"
                }
                if (pkgs.containsKey(manifest.id)) {
                    problems += "${dir.name}: 重复的模块 id ${manifest.id}"
                    continue
                }
                pkgs[manifest.id] = ModulePackage(
                    manifest = manifest,
                    body = fm.body,
                    scripts = scripts,
                    references = readTree(File(dir, "reference"))
                )
            }
            return ScanResult(ModuleRegistry(pkgs), problems)
        }

        private fun readTree(dir: File): Map<String, String> {
            if (!dir.isDirectory) return emptyMap()
            val out = LinkedHashMap<String, String>()
            val base = dir.toPath()
            dir.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.path }
                .forEach { f ->
                    val rel = base.relativize(f.toPath()).toString()
                        .replace(File.separatorChar, '/')
                    try {
                        out[rel] = f.readText()
                    } catch (e: Exception) {
                        // 单个文件读不到不影响整个包
                    }
                }
            return out
        }
    }
}
