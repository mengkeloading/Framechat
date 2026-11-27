package mychat.framework.log

/** 一行行的追加目标。 */
fun interface LogSink {
    fun append(text: String)
}

/**
 * 一次安装、多次追加。
 *
 * 为什么单独抽出来：安装与写入必须是两件事。写一次就"重新安装"一次的话，
 * 每次都会新建落点 —— 这正是日志爆炸的根因（96 个文件 vs 96 行）。
 */
class AppendLog(private var factory: (() -> LogSink?)? = null) {

    private var sink: LogSink? = null

    /** factory 被真正调用的次数；测试用来断言"只创建一次"。 */
    var created: Int = 0
        private set

    val installed: Boolean get() = sink != null

    /**
     * 返回是否真的新建了落点。重复调用无副作用。
     * [create] 可以在安装时才给出（Context 往往晚于对象创建）。
     */
    fun install(create: (() -> LogSink?)? = null): Boolean {
        if (sink != null) return false
        val createdSink = (create ?: factory)?.invoke()
        created++
        sink = createdSink
        return createdSink != null
    }

    /**
     * 未安装时静默丢弃：日志不该在它没准备好时把调用方拖崩。
     * 返回是否写成功。
     */
    fun record(line: String): Boolean {
        val target = sink ?: return false
        synchronized(this) { target.append(line) }
        return true
    }
}
