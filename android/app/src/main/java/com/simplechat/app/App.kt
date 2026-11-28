package com.simplechat.app

import android.app.Application
import android.content.Context
import android.content.ComponentCallbacks2
import com.simplechat.app.ui.theme.WallpaperImageCache
import com.simplechat.app.data.ChatRepository
import com.simplechat.app.data.LocaleHelper
import com.simplechat.app.data.Res
import com.simplechat.app.data.SettingsStore
import com.simplechat.app.hook.AssetModuleTree
import com.simplechat.app.hook.HookRouter
import com.simplechat.app.hook.OnnxTextEmbedder
import mychat.framework.sandbox.NoopExecutor
import mychat.framework.sandbox.PythonExecutor
import mychat.framework.butler.ButlerManager
import mychat.framework.butler.KeywordButler
import mychat.framework.butler.NoButler
import mychat.framework.butler.SemanticButler
import mychat.framework.hook.HookRunner
import mychat.framework.hook.ModuleRegistry
import mychat.framework.sandbox.RhinoJsExecutor
import com.simplechat.app.db.AppDatabase
import com.simplechat.app.net.ChatApi
import com.simplechat.app.net.SseClient
import com.simplechat.app.net.anthropic.AnthropicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class App : Application() {

    /** 调试包才会播种的模块：验链路用，release 不碰。 */
    private val DEBUG_SEED_MODULES =
        setOf("debug_ctx", "local_calc", "no_fluff", "skill_novel", "skill_code")

    lateinit var container: AppContainer
        private set

    override fun attachBaseContext(newBase: Context) {
        // 先按语言设置包一层：数据层 / ViewModel 取词（Res）都走这条路。
        // wrapAppBase 会把**未包装**的 base 记给 LocaleHelper —— 换语言后 Res 要从它现算。
        super.attachBaseContext(LocaleHelper.wrapAppBase(newBase))
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) WallpaperImageCache.clear()
    }

    override fun onCreate() {
        super.onCreate()
        com.simplechat.app.data.CrashDiagnostics.install(this)
        Res.init(this)
        container = AppContainer(this)
        com.simplechat.app.hook.UsageLog.install(this)

        /*
         * 模块表要在主线程扫一次（19 个目录 / 51 个文件，毫秒级）。
         * debug 包里把结果 Toast 出来：这是「assets 到底扫到了没有」的唯一外部证据。
         */
        val modules = container.moduleRegistry
        if (BuildConfig.DEBUG) {
            android.widget.Toast.makeText(
                this,
                "模块 ${modules.registry.all().size} 个 / 问题 ${modules.problems.size} 条",
                android.widget.Toast.LENGTH_LONG,
            ).show()

            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            com.simplechat.app.hook.UsageLog.banner("App 启动 " + stamp)
            com.simplechat.app.hook.UsageLog.record(
                "version=" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
            )
            com.simplechat.app.hook.UsageLog.record(
                "modules=" + modules.registry.all().size +
                    " problems=" + modules.problems.size
            )
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    val stored = container.settingsStore.flow.first()
                    com.simplechat.app.hook.UsageLog.record(
                        "model=" + stored.modelId + " butler=" + stored.butlerId +
                            " memory=" + stored.memoryEnabled
                    )
                }
            }

            // 启动时把各模块声明的格式标记写进日志：没有 marks= 行就是没解析到
            modules.registry.all()
                .filter { it.manifest.formatMarks.isNotEmpty() }
                .forEach {
                    com.simplechat.app.hook.UsageLog.record(
                        "marks=" + it.id + ":" + it.manifest.formatMarks.joinToString("|")
                    )
                }

            // 扫描问题（含重复前缀）逐条写日志
            modules.problems.forEach { com.simplechat.app.hook.UsageLog.record("problem=" + it) }
            modules.infos.forEach { com.simplechat.app.hook.UsageLog.record("info=" + it) }

            // 标签强度一览（DEBUG）：衰减是否在跑，看这几行就够
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { container.tagMemory.fixMissingAnchors() }
                runCatching { container.tagMemory.logTopTags() }
                runCatching { container.consolidator.maybeRun() }
                    .onFailure {
                        com.simplechat.app.hook.UsageLog.record(
                            "consolidate:error " + it.javaClass.simpleName
                        )
                    }
            }

            // 描述撞车提醒：需要向量，放后台算，不拖启动
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    val lines = mychat.framework.hook.DescriptionCollision.check(
                        modules.registry.all()
                    ) { a, b -> container.butlerManager.current("semantic").similarity(a, b) }
                    lines.forEach { com.simplechat.app.hook.UsageLog.record("problem=" + it) }
                }
            }

            /*
             * 仅调试包：把验链路要用的模块补进启用集合（并集，不会覆盖用户已有选择）。
             * 新增模块不必清 App 数据。
             */
            CoroutineScope(Dispatchers.Main).launch {
                val seeded = withContext(Dispatchers.IO) {
                    container.settingsStore.ensureDebugModules(DEBUG_SEED_MODULES)
                }
                if (seeded) {
                    android.widget.Toast.makeText(
                        this@App,
                        "已启用 " + DEBUG_SEED_MODULES.joinToString(", "),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }

            // 向量模型探针：不参与业务，结果写 Download/mychat-embed.log
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { com.simplechat.app.hook.EmbedProbe.run(this@App) }
                    .onFailure { android.util.Log.w("MyChat/Embed", "probe failed", it) }
            }
        }
        if (BuildConfig.DEBUG) {
            com.simplechat.app.hook.UsageLog.banner("启动完成")
        }
        // 「后台继续生成」的前台服务需要 application context（见 BackgroundGeneration）
        com.simplechat.app.data.BackgroundGeneration.install(this)

        // 把 v1.7 之前留在 DataStore 里的**明文** API Key 就地加密。
        // 一次性动作，放在进程启动时做，不与任何界面竞争。
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                container.settingsStore.migrateLegacyApiKey()
                container.settingsStore.migrateApiProfiles()
            }.onFailure { android.util.Log.w("SimpleChat/Settings", "Configuration migration failed: ${it.javaClass.simpleName}") }
        }
    }
}

/**
 * 手写依赖容器。不引入 Hilt / Koin —— 依赖少到这程度，框架是负担。
 * 各 Store 与 Repository 随里程碑逐步填充。
 */
class AppContainer(private val app: Application) {

    val json: Json by lazy { ChatApi.defaultJson() }

    val httpClient: OkHttpClient by lazy { ChatApi.defaultClient() }

    private val sseClient: SseClient by lazy { SseClient(httpClient, json) }

    private val anthropicApi: AnthropicApi by lazy { AnthropicApi(httpClient, json) }

    val chatApi: ChatApi by lazy { ChatApi(httpClient, sseClient, anthropicApi, json) }

    val settingsStore: SettingsStore by lazy { SettingsStore(app) }

    /**
     * 记忆压缩：取数走 Room，摘要走聊天通道。
     *
     * 摘要用**当前选中的模型**：换模型时行为会跟着变，这是有意的 ——
     * 用便宜的模型压出来的摘要质量差，用户自己会换回来。
     */
    val compressor: mychat.framework.memory.compress.Compressor by lazy {
        mychat.framework.memory.compress.DefaultCompressor(
            source = com.simplechat.app.hook.RoomCompressionSource(database),
            summarizer = com.simplechat.app.hook.ChatApiSummarizer(stream = { prompt ->
                val stored = settingsStore.flow.first()
                chatApi.streamChat(
                    baseUrl = stored.baseUrl,
                    apiKey = stored.apiKey,
                    model = com.simplechat.app.net.ModelInfo(id = stored.modelId),
                    messages = listOf(
                        com.simplechat.app.net.OutMessageDto(
                            role = "user",
                            content = com.simplechat.app.net.MessageContent.text(prompt),
                        )
                    ),
                    thinkingEnabled = false,
                )
            }),
            log = { line ->
                if (BuildConfig.DEBUG) com.simplechat.app.hook.UsageLog.record(line)
            },
        )
    }

    /** `[[!EXPAND:名字]]` 的落地：把标签展开成更细一层。 */
    val expandResolver: com.simplechat.app.hook.ExpandResolver by lazy {
        com.simplechat.app.hook.ExpandResolver(
            tags = database.tagDao(),
            summaries = database.tagSummaryDao(),
            log = { line -> if (BuildConfig.DEBUG) com.simplechat.app.hook.UsageLog.record(line) },
        )
    }

    /** 注入用的记忆目录。 */
    val memoryDirectory: mychat.framework.memory.MemoryDirectory by lazy {
        com.simplechat.app.hook.RoomMemoryDirectory(database.tagDao())
    }

    /** `debug:memory` 用：把每个标签的当前状态渲染成一段文字。 */
    val memoryDump: suspend () -> String = {
        val now = System.currentTimeMillis()
        val entries = database.tagDao().all().map { tag ->
            mychat.framework.memory.MemoryDump.Entry(
                name = tag.name,
                level = tag.currentLevel,
                strength = mychat.framework.memory.TagStrength.currentOf(
                    tag.strength, tag.lastDecayAt, tag.readCount, now,
                ),
                summary = tag.summary,
            )
        }
        mychat.framework.memory.MemoryDump.render(entries)
    }

    /** 12 小时一次的批量整理；不阻塞启动，失败只记日志。 */
    val consolidator: mychat.framework.memory.consolidate.Consolidator by lazy {
        mychat.framework.memory.consolidate.DefaultConsolidator(
            source = com.simplechat.app.hook.RoomConsolidationSource(database.tagDao()),
            compressor = compressor,
            settings = com.simplechat.app.hook.DataStoreConsolidateSettings(settingsStore),
            log = { line ->
                if (BuildConfig.DEBUG) com.simplechat.app.hook.UsageLog.record(line)
            },
        )
    }

    /**
     * 模块表。assets 不是 File，走 [AssetModuleTree]。
     * 扫描失败不拦启动 —— 模块是增强，不是主流程。
     */
    val moduleRegistry by lazy { ModuleRegistry.scan(AssetModuleTree(app.assets, "modules")) }

    /** 钩子入口：每轮新建 dispatcher，traces 由它累积。 */
    val hookRunner by lazy {
        HookRunner(
            source = moduleRegistry.registry,
            executors = mychat.framework.hook.ExecutorRegistry(
                listOf(
                    RhinoJsExecutor(moduleRegistry.registry),
                    PythonExecutor(),
                    NoopExecutor,
                )
            ),
            onFormatCheck = { result ->
                if (BuildConfig.DEBUG) {
                    com.simplechat.app.hook.UsageLog.record(
                        "format_check=" + result.moduleId + ":" + result.logValue()
                    )
                }
            },
            onDebug = { line ->
                if (BuildConfig.DEBUG) com.simplechat.app.hook.UsageLog.record(line)
            }
        )
    }

    /** 向量模型：懒加载，首次路由时才把 23MB 模型读进内存。 */
    val embedder by lazy { OnnxTextEmbedder(app.assets) }

    /** 管家工厂：semantic 共享 embedder，切来切去不会重载模型。 */
    val butlerManager by lazy {
        ButlerManager(
            mapOf(
                SemanticButler.ID to { SemanticButler(embedder) },
                KeywordButler.ID to { KeywordButler() },
                NoButler.ID to { NoButler() },
            )
        )
    }

    val hookRouter by lazy { HookRouter(moduleRegistry.registry, butlerManager) }

    val database: AppDatabase by lazy { AppDatabase.build(app) }

    val chatRepository: ChatRepository by lazy { ChatRepository(database, app) }

    /** 标签记忆：开关在调用点判断（SettingsStore.memoryEnabled）。 */
    val tagMemory: com.simplechat.app.hook.TagMemoryService by lazy {
        com.simplechat.app.hook.TagMemoryService(
            embedder = embedder,
            tags = database.tagDao(),
            enabled = { true },
            links = database.tagLinkDao(),
        )
    }

    /**
     * OpenCode Go 的路由标识（`x-opencode-session`），安装级稳定即可。
     * 后续会持久化到 DataStore，避免每次启动变化。
     */
    val openCodeSessionId: String by lazy { java.util.UUID.randomUUID().toString() }
}
