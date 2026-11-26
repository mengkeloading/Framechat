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

        fun scan(root: File): ScanResult = scan(FileModuleTree(root))

        /** 从任意模块树扫描（文件系统 / assets / 内存）。root 为空表示树根。 */
        fun scan(tree: ModuleTree, root: String = ""): ScanResult {
            val pkgs = LinkedHashMap<String, ModulePackage>()
            val problems = mutableListOf<String>()
            for (name in tree.list(root).sorted()) {
                val dir = join(root, name)
                val mdText = tree.read(join(dir, "MODULE.md"))
                if (mdText == null) {
                    // 只有 SKILL.md 的 skill 包：造一个隐式模块（知识型，不挂钩子）
                    val skillText = tree.read(join(dir, SKILL_FILE)) ?: continue
                    val implicit = implicitModule(tree, dir, name, skillText)
                    if (pkgs.containsKey(implicit.manifest.id)) {
                        problems += "$name: 隐式模块 id 与已有模块重复 ${implicit.manifest.id}"
                        continue
                    }
                    pkgs[implicit.manifest.id] = implicit
                    continue
                }
                val fm = FrontMatter.parse(mdText)
                if (fm == null) {
                    problems += "$name: MODULE.md 缺 front matter"
                    continue
                }
                val manifest = ModuleManifest.from(fm, name)
                val scripts = readTree(tree, join(dir, "script")) +
                    readTree(tree, join(dir, "scripts"))
                if (manifest.hooks.isNotEmpty() && scripts[manifest.entry] == null) {
                    problems += "${manifest.id}: hooks=${manifest.hooks} 但 script/${manifest.entry} 不存在"
                }
                if (pkgs.containsKey(manifest.id)) {
                    problems += "$name: 重复的模块 id ${manifest.id}"
                    continue
                }
                pkgs[manifest.id] = ModulePackage(
                    manifest = manifest,
                    body = fm.body,
                    scripts = scripts,
                    references = readTree(tree, join(dir, "reference")) +
                        readTree(tree, join(dir, "references"))
                )
            }
            return ScanResult(ModuleRegistry(pkgs), problems)
        }

        private fun join(dir: String, name: String): String =
            if (dir.isEmpty()) name else "$dir/$name"

        private const val SKILL_FILE = "SKILL.md"

        /**
         * 只有 SKILL.md 的目录 = 一个 skill 包。
         * id 取目录名（连字符转下划线），name/description 优先取 SKILL.md 的 front matter，
         * references 收目录下所有 .md（含 SKILL.md 自己，方便被 skill.read 读到）。
         */
        private fun implicitModule(
            tree: ModuleTree,
            dir: String,
            dirName: String,
            skillText: String
        ): ModulePackage {
            val fm = FrontMatter.parse(skillText)
            val id = dirName.replace('-', '_')
            val manifest = ModuleManifest(
                id = id,
                name = fm?.scalars?.get("name") ?: dirName,
                description = fm?.scalars?.get("description") ?: "",
                hooks = emptyList(),
                entry = "main.js",
                capabilities = emptyMap()
            )
            val refs = readTree(tree, dir).filterKeys { it.endsWith(".md", true) }
            return ModulePackage(manifest, fm?.body.orEmpty(), emptyMap(), refs)
        }

        private fun readTree(tree: ModuleTree, dir: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            val prefix = "$dir/"

            fun walk(current: String) {
                for (name in tree.list(current).sorted()) {
                    val path = join(current, name)
                    if (tree.list(path).isNotEmpty()) {
                        walk(path)
                        continue
                    }
                    tree.read(path)?.let { body ->
                        out[if (path.startsWith(prefix)) path.substring(prefix.length) else path] = body
                    }
                }
            }

            walk(dir)
            return out
        }
    }
}
