package mychat.framework.embedding

import mychat.framework.hook.ModulePackage

/**
 * 模块用于向量化的那段文本。
 *
 * 为什么不是只用 description：description 由模块作者提供，质量不可控——
 * 有人写「把模糊的想法拆成任务」，有人写「输入含 X 时注入附加要求」。
 * MODULE.md 正文（尤其「它做什么 / 何时使用」小节）往往有更具体的场景词，
 * 能弥补描述的抽象。取前 [BODY_LIMIT] 个字符，避免把整篇知识库喂进模型。
 */
object ModuleDocuments {

    const val BODY_LIMIT = 600

    fun textOf(pkg: ModulePackage): String = buildString {
        append(pkg.manifest.name)
        append('\n')
        append(pkg.manifest.description)
        val body = pkg.body.take(BODY_LIMIT).trim()
        if (body.isNotEmpty()) {
            append('\n')
            append(body)
        }
    }
}
