package mychat.framework.hook

/**
 * 按模块声明的 `intent` 过滤候选。
 *
 * 为什么在路由**之前**筛，而不是算完分数再排掉：
 * 分数是相对的，排掉一个高分模块会让第二名递补上来 —— 那还是错的模块。
 * 类型不对的模块从一开始就不该参与比较。
 */
object ModuleIntentFilter {

    fun filter(modules: List<ModulePackage>, intent: String?): List<ModulePackage> {
        if (intent.isNullOrBlank()) return modules
        return modules.filter { pkg ->
            val declared = pkg.manifest.intent
            declared == null || declared.equals(intent, ignoreCase = true)
        }
    }
}
