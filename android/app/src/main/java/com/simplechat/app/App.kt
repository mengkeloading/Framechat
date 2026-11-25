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
import mychat.framework.hook.HookRunner
import mychat.framework.hook.ModuleRegistry
import mychat.framework.sandbox.RhinoJsExecutor
import com.simplechat.app.db.AppDatabase
import com.simplechat.app.net.ChatApi
import com.simplechat.app.net.SseClient
import com.simplechat.app.net.anthropic.AnthropicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class App : Application() {

    /** 调试包才会播种的模块：验链路用，release 不碰。 */
    private val DEBUG_SEED_MODULES = setOf("debug_ctx", "local_calc", "no_fluff", "usage_declare")

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
     * 模块表。assets 不是 File，走 [AssetModuleTree]。
     * 扫描失败不拦启动 —— 模块是增强，不是主流程。
     */
    val moduleRegistry by lazy { ModuleRegistry.scan(AssetModuleTree(app.assets, "modules")) }

    /** 钩子入口：每轮新建 dispatcher，traces 由它累积。 */
    val hookRunner by lazy {
        HookRunner(moduleRegistry.registry, RhinoJsExecutor(moduleRegistry.registry))
    }

    val database: AppDatabase by lazy { AppDatabase.build(app) }

    val chatRepository: ChatRepository by lazy { ChatRepository(database, app) }

    /**
     * OpenCode Go 的路由标识（`x-opencode-session`），安装级稳定即可。
     * 后续会持久化到 DataStore，避免每次启动变化。
     */
    val openCodeSessionId: String by lazy { java.util.UUID.randomUUID().toString() }
}
