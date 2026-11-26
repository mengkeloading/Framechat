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
        private val SCRIPT_DIRS = listOf("script", "scripts")
        private val REFERENCE_DIRS = listOf("reference", "references")

        fun of(list: List<ModulePackage>): ModuleRegistry =
            ModuleRegistry(list.associateBy { it.id })

        fun scan(root: File): ScanResult = scan(FileModuleTree(root))

        /** 从任意模块树扫描（文件系统 / assets / 内存）。root 为空表示树根。 */
        fun scan(tree: ModuleTree, root: String = ""): ScanResult {
            val pkgs = LinkedHashMap<String, ModulePackage>()
            val problems = mutableListOf<String>()
            for (name in tree.list(root).sorted()) {
                val dir = join(root, name)
                val text = tree.read(join(dir, "MODULE.md"))
                if (text != null) {
                    mkModule(dir, name, text, tree, pkgs, problems)
                } else {
                    // 没有 MODULE.md 但有 SKILL.md → 隐式模块（兼容 skill 包）
                    mkImplicit(dir, name, tree)?.let { pkg ->
                        if (pkgs.containsKey(pkg.id)) {
                            problems += "$name: 重复的模块 id ${pkg.id}"
                        } else {
                            pkgs[pkg.id] = pkg
                        }
                    }
                }
            }
            checkDuplicatePrefixes(pkgs, problems)
            problems += ManifestAudit.auditGuides(pkgs.values.toList())
            return ScanResult(ModuleRegistry(pkgs), problems)
        }

        /** 同一前缀声明两次：谁是强制入口就不确定了，必须报出来。 */
        private fun checkDuplicatePrefixes(
            pkgs: Map<String, ModulePackage>,
            problems: MutableList<String>
        ) {
            val grouped = sortedMapOf<String, MutableList<String>>()
            for (pkg in pkgs.values) {
                val prefix = pkg.manifest.prefix?.takeIf { it.isNotBlank() } ?: continue
                grouped.getOrPut(prefix) { mutableListOf() } += pkg.id
            }
            for ((prefix, ids) in grouped) {
                if (ids.size > 1) {
                    problems += "dup_prefix: 重复前缀 \"$prefix\": " + ids.sorted().joinToString(",")
                }
            }
        }

        private fun mkModule(
            dir: String,
            name: String,
            text: String,
            tree: ModuleTree,
            pkgs: MutableMap<String, ModulePackage>,
            problems: MutableList<String>,
        ) {
            val fm = FrontMatter.parse(text)
            if (fm == null) {
                problems += "$name: MODULE.md 缺 front matter"
                return
            }
            val manifest = ModuleManifest.from(fm, name)
            val scripts = readTreeAny(tree, dir, SCRIPT_DIRS)
            if (manifest.hooks.isNotEmpty() && scripts[manifest.entry] == null) {
                problems += "${manifest.id}: hooks=${manifest.hooks} 但 script/${manifest.entry} 不存在"
            }
            if (pkgs.containsKey(manifest.id)) {
                problems += "$name: 重复的模块 id ${manifest.id}"
                return
            }
            pkgs[manifest.id] = ModulePackage(
                manifest = manifest,
                body = fm.body,
                scripts = scripts,
                references = readTreeAny(tree, dir, REFERENCE_DIRS)
            )
        }

        /**
         * 只有 SKILL.md 的目录：当成一个不挂钩子的纯知识模块。
         * references 收目录下所有 .md（含 SKILL.md 自己），
         * 这样子框架 / 其他模块仍能通过 skill.read 读到它。
         */
        private fun mkImplicit(dir: String, name: String, tree: ModuleTree): ModulePackage? {
            val skill = tree.read(join(dir, "SKILL.md")) ?: return null
            val fm = FrontMatter.parse(skill)
            val id = name.replace('-', '_')
            val manifest = ModuleManifest(
                id = id,
                name = fm?.scalars?.get("name") ?: name,
                description = fm?.scalars?.get("description") ?: "",
                hooks = emptyList(),
                entry = "",
                engine = "none"
            )
            val refs = LinkedHashMap<String, String>()
            refs.putAll(readTreeAny(tree, dir, REFERENCE_DIRS))
            refs["SKILL.md"] = skill
            for ((path, content) in readTree(tree, dir)) {
                if (path.endsWith(".md")) refs[path] = content
            }
            return ModulePackage(
                manifest = manifest,
                body = fm?.body.orEmpty(),
                scripts = emptyMap(),
                references = refs
            )
        }

        /** script/script(s)、reference/reference(s) 都认，两个都在就合并。 */
        private fun readTreeAny(tree: ModuleTree, dir: String, names: List<String>): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (n in names) out.putAll(readTree(tree, join(dir, n)))
            return out
        }

        private fun join(dir: String, name: String): String =
            if (dir.isEmpty()) name else "$dir/$name"

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
