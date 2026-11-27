package mychat.framework.hook

import mychat.framework.json.JsonValue
import mychat.framework.json.parseJson

/**
 * MODULE.md 的 front matter。只支持模块用得到的 YAML 子集：
 * 标量、`- item` 列表、一层嵌套映射（capabilities）。
 * 手写解析是为了零依赖；遇到无法理解的结构不报错，留空由 manifest 兜底。
 */
data class FrontMatter(
    val scalars: Map<String, String>,
    val lists: Map<String, List<String>>,
    val maps: Map<String, Map<String, String>>,
    val body: String
) {
    companion object {
        fun parse(text: String): FrontMatter? {
            val norm = text.replace("\r\n", "\n").replace('\r', '\n')
            val lines = norm.split('\n')
            if (lines.isEmpty() || lines[0].trim() != "---") return null
            var end = -1
            for (i in 1 until lines.size) {
                if (lines[i].trim() == "---") {
                    end = i
                    break
                }
            }
            if (end < 0) return null

            val scalars = LinkedHashMap<String, String>()
            val lists = LinkedHashMap<String, MutableList<String>>()
            val maps = LinkedHashMap<String, MutableMap<String, String>>()
            var current: String? = null

            for (i in 1 until end) {
                val line = lines[i]
                if (line.isBlank()) continue
                val indented = line[0] == ' ' || line[0] == '\t'
                val trimmed = line.trim()
                if (indented && current != null) {
                    if (trimmed.startsWith("-")) {
                        lists.getOrPut(current) { mutableListOf() }
                            .add(unquote(trimmed.removePrefix("-").trim()))
                    } else {
                        val c = trimmed.indexOf(':')
                        if (c > 0) {
                            val k = trimmed.substring(0, c).trim()
                            val v = unquote(trimmed.substring(c + 1).trim())
                            maps.getOrPut(current) { mutableMapOf() }[k] = v
                        }
                    }
                    continue
                }
                val c = line.indexOf(':')
                if (c <= 0) {
                    current = null
                    continue
                }
                val k = line.substring(0, c).trim()
                val v = unquote(line.substring(c + 1).trim())
                if (v.isEmpty()) {
                    current = k
                } else {
                    scalars[k] = v
                    current = null
                }
            }

            val body = lines.subList(end + 1, lines.size).joinToString("\n").trim()
            return FrontMatter(scalars, lists, maps.mapValues { it.value.toMap() }, body)
        }

        private fun unquote(v: String): String {
            if (v.length >= 2 && (v[0] == '"' || v[0] == '\'') && v.last() == v[0]) {
                return v.substring(1, v.length - 1)
            }
            return v
        }
    }
}

data class ModuleManifest(
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "0.0.0",
    val hooks: List<String> = emptyList(),
    val inject: String = "none",
    val entry: String = "main.js",
    val capabilities: Map<String, Int> = emptyMap(),
    val tools: List<JsonValue> = emptyList(),
    /** 可选：输入以「prefix：」开头时强制使用本模块，跳过路由。 */
    val prefix: String? = null,
    /**
     * 可选：用户真实会说的话（示例查询）。
     *
     * 有它就用它建向量 —— 示例比描述更接近查询分布：描述是写给自己看的，
     * 示例是用户嘴里说出来的。
     */
    val sampleQueries: List<String> = emptyList(),
    /** 可选：启用本模块时需要的前置处理，如 normalize。 */
    val needs: List<String> = emptyList(),
    /** 可选：输出必须含的格式标记（至少一个）。留空 = 不检查。 */
    val formatMarks: List<String> = emptyList(),
    /** 必须出现的格式标记（逐项检查，用于补救点名）。 */
    val formatRequired: List<String> = emptyList(),
    /** 约束枚举 id，展开成禁止/要求文案后注入。 */
    val constraints: List<String> = emptyList(),
    /** 可选的「不要这样做」清单，注入时渲染成【禁止】节。 */
    val forbid: List<String> = emptyList(),
    /** 可选的「必须这样做」清单，注入时渲染成【要求】节。 */
    val requires: List<String> = emptyList(),
    /** 执行引擎：js（默认）/ py / none。缺失或空值都当 js。 */
    val engine: String = "js"
) {
    /** phase 用 "input" / "output"，与 MODULE.md 里的写法一致。 */
    fun handles(phase: String): Boolean = hooks.contains(phase)

    companion object {
        fun from(fm: FrontMatter, fallbackId: String): ModuleManifest {
            val id = fm.scalars["id"] ?: fallbackId
            val hooks = fm.lists["hooks"] ?: parseInlineList(fm.scalars["hooks"])
            val caps = fm.maps["capabilities"].orEmpty()
                .mapNotNull { (k, v) -> v.toIntOrNull()?.let { k to it } }
                .toMap()
            return ModuleManifest(
                id = id,
                name = fm.scalars["name"] ?: id,
                description = fm.scalars["description"] ?: "",
                version = fm.scalars["version"] ?: "0.0.0",
                hooks = hooks,
                inject = fm.scalars["inject"] ?: "none",
                // demo 的 MODULE.md 写的是 `entry: script/main.js`，包内脚本表的 key
                // 已经是相对 script/ 的路径，这里统一别名。
                entry = (fm.scalars["entry"] ?: "main.js").removePrefix("script/"),
                capabilities = caps,
                tools = parseInlineTools(fm.scalars["tools"]),
                prefix = fm.scalars["prefix"]?.takeIf { it.isNotBlank() },
                needs = fm.lists["needs"] ?: parseInlineList(fm.scalars["needs"]),
                sampleQueries = fm.lists["sample_queries"]
                    ?: parseInlineList(fm.scalars["sample_queries"]),
                formatMarks = fm.lists["format_marks"]
                    ?: parseInlineList(fm.scalars["format_marks"]),
                formatRequired = fm.lists["format_required"]
                    ?: parseInlineList(fm.scalars["format_required"]),
                constraints = fm.lists["constraints"]
                    ?: parseInlineList(fm.scalars["constraints"]),
                forbid = fm.lists["forbid"] ?: parseInlineList(fm.scalars["forbid"]),
                requires = fm.lists["require"] ?: parseInlineList(fm.scalars["require"]),
                engine = fm.scalars["engine"]?.takeIf { it.isNotBlank() } ?: "js"
            )
        }

        private fun parseInlineList(raw: String?): List<String> {
            if (raw == null) return emptyList()
            val t = raw.trim()
            if (!t.startsWith("[") || !t.endsWith("]")) return listOf(t).filter { it.isNotEmpty() }
            val inner = t.substring(1, t.length - 1).trim()
            if (inner.isEmpty()) return emptyList()
            return inner.split(',').map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }
        }

        private fun parseInlineTools(raw: String?): List<JsonValue> {
            if (raw == null) return emptyList()
            val arr = parseJson(raw.trim()) as? JsonValue.Arr ?: return emptyList()
            return arr.items.filterIsInstance<JsonValue.Obj>()
        }
    }
}
