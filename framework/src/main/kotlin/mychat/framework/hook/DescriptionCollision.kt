package mychat.framework.hook

/**
 * 描述撞车提醒：两个技能模块的 description 几乎一样时，路由会把它们当成
 * 同一个东西，选中哪个全看分数噪声。
 *
 * 只提醒、不影响加载——描述是作者写的，框架无权替它改。
 */
object DescriptionCollision {

    /** 宽松门槛，只抓真正重复的。 */
    const val THRESHOLD = 0.95f

    fun check(
        modules: List<ModulePackage>,
        threshold: Float = THRESHOLD,
        similarity: (String, String) -> Float
    ): List<String> {
        val withSkill = modules.filter { ModuleSelectorSupport.hasSkill(it) }
        val out = mutableListOf<String>()
        for (i in withSkill.indices) {
            for (j in i + 1 until withSkill.size) {
                val a = withSkill[i]
                val b = withSkill[j]
                val sim = similarity(a.manifest.description, b.manifest.description)
                if (sim > threshold) {
                    out += "desc_similar: " + a.id + " / " + b.id +
                        " (" + String.format("%.4f", sim) + ")"
                }
            }
        }
        return out
    }
}

/** 判断「有没有 SKILL.md」的唯一口径，避免各处各写一份。 */
internal object ModuleSelectorSupport {
    fun hasSkill(pkg: ModulePackage): Boolean = pkg.reference("SKILL.md") != null
}
