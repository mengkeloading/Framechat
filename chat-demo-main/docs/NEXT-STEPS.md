# 下一步工作指令

> 可直接交给 AiCode / Cursor 执行。每条有验收标准和禁止事项。

## 当前状态

| 资产 | 状态 |
|---|---|
| framework | 1345 行 / 131 断言 / 58 秒跑通 |
| android | 2100 行 / 0 次编译 |
| demo | 契约已冻结 |

**唯一真相是 framework。**

## 进度（2026-11-25）

| 工单 | 状态 |
|---|---|
| B1 Rhino 执行器 | ✅ 完成，23 条断言通过 |
| B2 替换 core/ + hook/ | 🟡 结构已改，Kotlin 编译未验证 |
| B3 App 侧三样 | ⬜ 未开始 |
| B4 Actions 首次编译 | ⬜ 未开始 |

B1 落地在 `framework/src/sandbox/kotlin/mychat/framework/sandbox/RhinoJsExecutor.kt`，
断言在 `src/sandbox/test/.../RhinoSelfTest.kt`：null 返回、经 host 取 `clock.now`、
`Packages`/`getClass`/`document` 均不可见、死循环 300ms 被打断、
与 dispatcher 联调（local 短路 / reference 读取 / 抛异常 fail-open）。

B2 删除清单已留档：`~/workspace/android_removed-2026-11-25.tar.gz`
（core 11 + hook 10 + 测试 13 + 三份引用不存在 API 的适配器）。
App 改为直接编译 `framework` 的源码，见 `app/build.gradle.kts`。

**环境限制（重要）**：这台 aarch64 + musl 容器无法 `assembleDebug`。
aapt2 只存在 x86_64（Google Maven / build-tools）和 bionic-arm64（Termux）
两种构建，本机都跑不了。B2 的“编译通过”与 B4 的 APK 必须放
GitHub Actions 或 PC 上验证。Gradle 配置阶段已验证通过（`gradlew help`）。

## B1 Rhino 执行器

- 目标：SandboxJsExecutor : JsExecutor，同步执行
- 依赖：org.mozilla:rhino:1.7.15
- 验收：能执行 `function(i,h){return null}` 返回 null；
  能经 host 调 `clock.now` 拿到时间串
- 禁止：用 WebView；用 coroutines 包装接口
- 要点：optimizationLevel=-1；Context.javaToJS 注入 host；
  try/finally 里 Context.exit()
- 预估：1 天

## B2 替换 core/ + hook/

- 目标：删掉 android 的 11+9 个文件，改为依赖 framework
- 验收：编译通过（跑不起来正常）
- 禁止：两份实现并存；边删边改逻辑
- 顺序：先删 → 立刻编译 → 报错清单就是待补清单
- 预估：0.5 天

## B3 App 侧补三样

| 补什么 | 接口 |
|---|---|
| 持久化 | MessageStore（Room，tempId -> pending -> 落定） |
| UI 状态机 | ChatEngine.onState -> StateFlow |
| 网络流 | StreamSource（OkHttp，30s/60s，不设首字节超时） |

- 验收：真机发消息 -> 流式回复 -> 落库 -> 重启还在
- 禁止：在这三个里塞业务逻辑
- 预估：2-3 天

## B4 Actions 首次编译

- 验收：assembleDebug 绿，APK 能装能开
- 禁止：期间加任何新功能
- 预估：0.5-1 天

## 阶段 C（B4 绿之后）

| 工单 | 内容 |
|---|---|
| C1 | embedding 路由（24MB） |
| C2 | 输入归一化（先规则，后小 LLM） |
| C3 | 本地/云端调度 |

## 体积优化（暂缓）

- 换 MNN 引擎可省 13MB（引擎 16MB → 3MB），模型不动
- 触发条件：有真实用户抱怨体积，或上架对体积有硬要求
