# MyChat 架构（Kotlin 落地版）

> **地位**：本文件是《主框架技术文档》的修订版。原文档中 5 个未定的契约空洞，
> 已由 JS demo（mychat-demo.html，39 条断言全绿）实测确定。Cursor 开工前先读这份。
> 两者冲突时**以本文档为准**。

---

## 0. 怎么读这份文档

| 你要做什么 | 读哪几节 |
| --- | --- |
| 写 Kotlin | §5 实现顺序 + §13 实现定稿 + §24 实测结论 |
| 加一个模块 | §13.1 目录形态 + §13.2 front matter |
| 了解能力模型 | §11 能力阶梯 + §16 安全边界 |
| 了解能扩到哪 | §15 反射弧 + §22 Python/skill 互通 |
| 画 UI | §21 设计规范 + §21.5 定稿 + §21.6 官方数值 |
| 确认某处验没验过 | §24 真接口验证记录 |
| 交付前检查 | §25 当前状态与已知偏离 |

---

## 1. 已验证的钩子契约

### 1.1 输入信封（向后兼容扩展）

原文档的 JsExecutor.execute(scriptName, inputJson) 只有两个参数。inputJson 的实际内容是：

    {"text":"用户输入或模型回传文字",
     "phase":"a",
     "ctx":{"sessionId":"...","input":"原始用户输入"}}

- phase：a = 钩子 A，b = 钩子 B。**模块靠它区分自己被谁调用。**
- ctx.input：仅钩子 B 有意义，是本轮原始用户输入。
- 向后兼容：模块收到裸字符串（非 JSON）时，视为 phase=a、ctx 为空。
- 此扩展由原文档 §3.3 允许，不改变钩子结构。

### 1.2 钩子 A 返回（phase=a）

| type | 字段 | 语义 |
| --- | --- | --- |
| pass | text | 放行，text 是放行文本（可改写用户输入） |
| local | text | 本地截留，**不发网络请求**，直接出气泡，本轮结束 |
| context | text, extra（字符串） | 放行，extra 作为独立 system 消息拼入请求 |

合并规则：按 session.activeHardModules 顺序执行；local 命中立即短路；
多个 context 的 extra 用换行连接；text 以后者覆盖前者。

### 1.3 钩子 B 返回（phase=b）

| type | 字段 | 语义 |
| --- | --- | --- |
| pass | — | 展示原始回传文本 |
| rewrite | text | 覆盖最终气泡内容 |
| invoke | moduleName, payload | 转调另一个模块，按它的返回继续流转 |

invoke 规则：深度上限 3，带 visited 去重，超限按 pass 处理并记 trace。

### 1.4 fail-open（铁律）

模块出现以下任一情况，一律当 pass 处理、记 trace、不阻断主流程：

- 抛异常
- 返回非字符串
- 返回非法 JSON
- phase=a 返回 rewrite/invoke，或 phase=b 返回 local/context

### 1.5 模块签名（JS 侧）

    handle(inputJson, host) -> string | null

- 入参全字符串、出参 JSON 字符串，**不传对象引用**（Kotlin 侧要跨沙箱边界）。
- host 是 HostBridge，L0 阶段白名单为空，任何调用都抛错。
- 返回 null 表示「本模块不处理」。

---

## 2. 请求体构造规则（顺序不可改）

    messages = [
      ① soft system     : 所有启用的软模块内容 join 换行，合并成一条 system
      ② history         : 上下文原文（排除规则见 3.1）
      ③ user            : 本轮用户输入，仅第一轮追加（见 3.2）
      ④ hookAExtra      : 有则作为一条 system（D1 未决，见 §4）
      ⑤ pendingToolMsgs : 本轮新产生的 tool 消息
    ]

有硬模块时注入 tools 定义。注意 D9（见 §4）。

---

## 3. 消息与状态规则

### 3.1 getContextForRequest 必须排除三类

| 排除条件 | 原因 |
| --- | --- |
| role == system | 超限提示等系统消息不进上下文 |
| role == tool | 历史 tool 明细不进上下文（原文档 §7.5 明文），本轮 tool 由 ⑤ 单独带 |
| pending == true | 流式占位行不算上下文 |

> **不要用「content 为空」判断占位。** tool 轮次的 assistant 内容本来就是空的，
> 用内容判断会误删它，导致 tool 消息失去前驱 assistant 消息，真接口直接报错。
> 必须用 pending 标志。

### 3.2 userInput 只在第一轮传

循环第 2 轮起，请求体里不能再追加 user 消息，它已经在 history 里了。
RequestBuilder.build 的 userInput 参数可空，为空时不追加。

### 3.3 tempId 时序

    insert(id=tempId, role=assistant, content=空, pending=true)
      -> 每个 delta: update(tempId, content=累积文本)     // pending 仍为 true
      -> 定型:       update(tempId, content=最终文本, pending=false)

### 3.4 tool 循环

    if (round >= session.maxToolRounds) { truncated = true; break }   // 循环体内、执行前
    round++
    ...
    for (tc in toolCalls) {
      recordId = toolCallDao.insert(rawRequestJson = tc.args, success = null)  // 执行前
      result   = jsExecutor.execute(tc.name, tc.args)                         // 抛则 mapJsError
      toolCallDao.update(recordId, rawResultJson, success, errorMessage)      // 执行后
      messageDao.insert(role=tool, content=result, toolCallId=tc.id)
    }

- 空参兜底：args 为空字符串时用 {}
- 超过轮数上限：插入一条 role=system 的提示消息并落库

### 3.5 finally 条件重置

    finally { if (state !is Error) state = Idle }

ERROR 态不被 finally 覆盖，否则用户看不到错误。

---

## 4. 五个空洞的结论

| 编号 | 问题 | 结论 | 证据 |
| --- | --- | --- | --- |
| D1 | extra 拼在哪 | **未定**。当前是独立 system 消息，位于 user 之后，改一个常量可切成 user | 用例 2 |
| D2 | 钩子 B 拿不到用户输入 | **已定**：dispatchOutput(session, rawText, ctx) 带第三参，ctx.input = 本轮用户输入 | 用例 3 / 13 |
| D5 | user 消息重复追加 | **已定**：userInput 只在第一轮传 | 用例 2 / 8 |
| D7 | tool 消息在请求里出现两次 | **已定**：getContextForRequest 排除 role=tool | 用例 8 |
| D8 | 占位误判 | **已定**：用 pending 标志，不看内容是否为空 | 用例 8 |

### 还需你决定

| 编号 | 问题 | 现状 |
| --- | --- | --- |
| D1 | extra 用 system 还是 user 角色 | demo 用 system，一个常量可切 |
| D9 | tools 定义来自哪里 | demo 错误地用了 activeHardModules 全量，**应改成单独的 tool 模块清单** |
| D10 | 本地截留气泡如何标记 | 原文档注释说要标注，但 ChatMessage 实体没字段。建议加 source（model / local） |

---

## 5. 文件清单与实现顺序

依赖顺序，严格按此推进，一次只做一行：

| 序 | 文件 | 依赖 |
| --- | --- | --- |
| 1 | data/ChatSession.kt ChatMessage.kt ToolCallRecord.kt Daos.kt AppDatabase.kt | — |
| 2 | error/ErrorMapper.kt | — |
| 3 | module/ModuleLoader.kt | assets |
| 4 | js/JsExecutor.kt SandboxJsExecutor.kt | 3 + bridge/HostBridge.kt |
| 5 | network/SseParser.kt | — |
| 6 | network/ApiClient.kt | 2, 5 |
| 7 | network/RequestBuilder.kt | 1, 3 |
| 8 | module/HookResults.kt ModuleDispatcher.kt | 3, 4 |
| 9 | vm/ChatViewModel.kt | 1–8 全部 |
| 10 | ui/ChatUiState.kt ChatScreen.kt | 9 |
| 11 | MainActivity.kt | 10 |

---

## 6. JS 到 Kotlin 映射

| JS（已跑通） | Kotlin |
| --- | --- |
| js/vm/chatviewmodel.js | ChatViewModel.kt（分支顺序、注释编号不动） |
| js/module/moduledispatcher.js | ModuleDispatcher.kt |
| js/module/moduleloader.js | ModuleLoader.kt |
| js/module/modulekit.js | HookResults.kt + 信封解析 |
| js/js/executor.js | JsExecutor.kt + SandboxJsExecutor.kt |
| js/network/apiclient.js | ApiClient.kt（产出原始 SSE 行的 Flow） |
| js/network/sseparser.js | SseParser.kt（纯单行解析） |
| js/network/requestbuilder.js | RequestBuilder.kt |
| js/bridge/hostbridge.js | HostBridge.kt |
| js/error/errormapper.js | ErrorMapper.kt |
| js/data/daos.js | 三 Entity + Dao + AppDatabase |
| js/ui/chatscreen.js | ChatScreen.kt |
| assets/modules/hard/*.js | 原样进 app/src/main/assets/modules/hard/ |
| assets/modules/soft/*.js | 内容是 md 原文，落成 modules/soft/*.md |

JS 侧 execute 的 await 语义对应 suspend fun；async generator + for await 对应 Flow + collect。

---

## 7. 39 条断言对应 Kotlin 测试

把这 30 条搬成 JUnit + runTest，就是「避免二次开发」的验收线：

| 断言组 | Kotlin 测试 |
| --- | --- |
| 用例 1 | ChatViewModelTest.plainMessage |
| 用例 2 | RequestBuilderTest.contextExtra |
| 用例 3 | HookBTest.rewriteByTurnCtx |
| 用例 4 | HookATest.localShortCircuit |
| 用例 5 | ModuleDispatcherTest.invokeChain |
| 用例 6 | ModuleDispatcherTest.moduleThrowsFailOpen |
| 用例 7 | RequestBuilderTest.softModuleSingleSystem |
| 用例 8 | ToolLoopTest.*（分片拼接 / 记录 / 第二轮形状 / 三种排除） |
| 用例 9 | ToolCallAccumulatorTest.emptyArgs |
| 用例 10 | ToolLoopTest.maxRoundsTruncated |
| 用例 11 | ChatViewModelTest.errorNotResetByFinally |
| 用例 12 | SseParserTest.* |
| 用例 13 | HookBTest.rewriteNeedsTurnCtx |
| 用例 14 | ModuleDispatcherTest.invokeDepthLimit |

---

## 8. Cursor 分批下发

不要一次让它写整个 App。按 §5 的顺序，一次一个文件。

提示词模板：

> 这是 MyChat 项目。先读 ARCHITECTURE.md（权威）和《主框架技术文档》（背景）。
> 现在只实现 Xxx.kt。
> 严格按 ARCHITECTURE.md 的对应小节和原文档 §7.x 中这个文件的
> 「负责什么 / 输入输出 / 内部逻辑 / 不负责什么」来写，不要扩展职责，
> 不要碰文档写明「不负责」的部分。
> 行为必须与 js/ 下对应的实现完全一致（该实现已通过 30 条断言），
> 分支顺序、兜底逻辑、注释编号都不要改。
> 给我完整可运行代码，不要省略。

每批完成后对照检查：tempId 先 insert 后 update、pending 标志、
getContextForRequest 的三种排除、userInput 只在第一轮、空参兜底、
truncated 标志、finally 条件重置。漏一条就让它补。

---

## 9. 环境注意

- **JS demo 阶段**：file:// 下浏览器禁止跨文件加载脚本（相对路径与绝对路径均已实测失败）。
  所以源码树与运行产物分离：改任何模块后**必须重跑 bundle.py**，产物是 mychat-demo.html。
- **Kotlin 阶段**：Cursor 是桌面应用，需要 PC。Gradle 打包 APK，不存在上述限制。
- 硬模块在浏览器里以共享作用域加载（demo 的偏离）。Kotlin 侧是
  JavaScriptSandbox + evaluateJavaScriptAsync，用 withTimeout(3000) 防死循环。
---

## 10. 模块包规范（早期草案，已被 §13 取代 —— 以 §13 为准）

### 10.1 目录形态

    assets/modules/pkgs/<moduleId>/
    ├── manifest.json          必需
    ├── main.js                必需，导出 function handle(inputJson, host)
    ├── tools/                可选，被 tool_calls 调用的具名脚本
    │   └── build_report.js
    └── resources/            可选，按需加载，不预先进入上下文
        └── style.md

### 10.2 manifest 字段

| 字段 | 必需 | 说明 |
| --- | --- | --- |
| id | 是 | 包标识，与目录名一致 |
| name | 是 | 显示名 |
| summary | 是 | 一句话说明，拼进 system，**正文靠 resource 按需取** |
| version | 是 | 语义化版本 |
| author | 否 | |
| phases | 是 | input / output 的子集，决定参与哪个钩子 |
| permissions | 是 | 能力名 -> 配额整数。未声明的能力调用即抛错 |
| entry | 是 | 默认 main.js |
| tools | 否 | 具名工具列表，每项含 name / description / entry |
| minAppVersion | 否 | 兼容性下限 |

### 10.3 与 Magisk 的对应

| Magisk | 本框架 |
| --- | --- |
| module.prop | manifest.json |
| 模块目录 | pkgs/<id>/ |
| magiskd 持权 | HostBridge 单点代理 |
| disable 文件开关 | permissions 逐项授权 + 模块启用位 |
| 作用域挂载 | 能力白名单 |
| magisk.log | 审计轨迹 |
| Zygisk 隔离 | JavaScriptSandbox / QuickJS |

### 10.4 启停不删包

模块启用状态与权限授权状态独立于包文件，存于会话或全局配置。禁用只改状态位，不要求删除目录。

---

## 11. 能力阶梯与授权

| 级别 | 能力 | 接口（统一入口） | 默认配额 | 风险 |
| --- | --- | --- | --- | --- |
| L0 | 无 | — | — | 无 |
| L1 | 读资源 / 读时钟 / 会话 KV | host.requestLocalAction(能力名, JSON) | resource.read 5，clock.now 2 | 低 |
| L2 | 白名单网络 | 同上 | net.get 3 | 中，**必须逐模块用户授权** |
| L3 | 二次模型调用 / 界面提示 | 同上 | llm.call 0 | 高，默认关闭 |

### 11.1 三条不可让步的约束

1. **单点代理**：模块只拿到 host 对象，拿不到文件系统、拿不到 registry、拿不到 API Key。
   网络请求由 Kotlin 签名发出，模块永远看不到凭据。
2. **声明即上限**：manifest 里没声明的能力，调用直接抛 capability_not_declared。
   声明了但超过配额，抛 quota_exceeded。两种都按 fail-open 处理，不影响主流程。
3. **全程审计**：每次能力调用记录 pkg / action / 参数摘要 / 结果 / 耗时。
   审计是"扩权之后还能测"的唯一办法。

### 11.2 与 Xposed 的分界

不学 Xposed 的三件事：不在宿主进程内注入、不给模块进程级权限、不提供无作用域的钩子。
需要"更强"的时候，加的是**具名能力**，不是"把整个环境交出去"。

---

## 12. 待定项

| 编号 | 问题 | 建议 |
| --- | --- | --- |
| D11 | 是否开放 L2 网络 | 开放，但逐模块授权 + 域白名单 + Kotlin 代签 |
| D12 | 是否开放 L3 llm.call | v1 关闭，等 L1/L2 稳定 |
| D13 | 模块间是否可互相调用 | 允许 host.module.call，但深度与越权同样受限 |
---

## 13. 实现定稿（覆盖 §10 的目录描述）

> §10 里的 `pkgs/<id>/manifest.json` 是早期草案。**实际实现如下，以本节为准。**

### 13.1 目录形态（8 个包，已验证）

    modules/
    ├── simp-module/
    │   └── MODULE.md                 纯提示词模块（inject: full）
    ├── attach-context/               id: attach_context
    │   ├── MODULE.md
    │   └── script/main.js
    ├── time-local/                   id: time_local
    ├── task-rewrite/                 id: task_rewrite
    ├── router/                       id: router
    ├── formatter/                    id: formatter（只被 invoke 调用）
    ├── analyze-chat/                 id: analyze_chat（tool 目标）
    └── weekly-reporter/              id: weekly_reporter
        ├── MODULE.md
        ├── reference/style.md        按需读取，不进 system
        └── script/main.js

- **目录名用连字符**（skill 惯例），**id 由 front matter 决定**，两者可以不同。
- 没有 `MODULE.md` 的目录在扫描时被跳过并告警。

### 13.2 MODULE.md front matter 字段（实现版）

| 字段 | 必需 | 说明 |
| --- | --- | --- |
| id | 是 | 模块标识，与目录名可不同 |
| name | 是 | 显示名 |
| description | 是 | 一句话说明，`inject: summary` 时进 system |
| version | 是 | 语义化版本 |
| hooks | 是 | `input` / `output` 的子集，决定参与哪个钩子 |
| inject | 否 | `none` / `summary` / `full`，有 entry 时默认 none |
| capabilities | 是 | 能力名 -> 配额整数；必须写 `{}` 或缩进映射 |
| entry | 有脚本时必需 | 相对包目录，如 `script/main.js` |

### 13.3 三种 inject 的语义

| inject | system 里放什么 | 用例 |
| --- | --- | --- |
| none | 什么都不放 | 硬模块，逻辑自理 |
| summary | `- name：description` | 需要模型知道存在，但正文按需读 |
| full | MODULE.md 正文 | 纯提示词模块 |

### 13.4 脚本文件形态

`script/*.js` 的**整个文件内容就是一个函数表达式**：

    function (inputJson, host) {
      var inp = ModuleKit.parse(inputJson);
      ...
      return ModuleKit.reply({ type: 'rewrite', text: '...' });
    }

执行器把它包成 `new Function('inputJson','host', SHADOW + 'return (' + src + ')(inputJson, host)')`，
其中 SHADOW 遮蔽 `fetch / XMLHttpRequest / localStorage / document / window / require`。
Kotlin 侧由 JavaScriptSandbox 提供真隔离。

### 13.5 JsExecutor 的 scriptRef 两种写法

| 写法 | 解析 |
| --- | --- |
| `weekly_reporter/script/main.js` | 指定包 + 指定文件 |
| `analyze_chat` | 只给包名，取该包的 entry |

**`execute(scriptRef, inputJson): String` 签名不变** —— 能力扩张没有动这个接口。

### 13.6 D15：HostBridge 对 JS 必须是同步的

模块里不能 await（QuickJS 无协程桥接），所以：

| 级别 | 对 JS 的形态 | Kotlin 实现 |
| --- | --- | --- |
| L1（资源/时钟/会话） | **同步返回字符串** | 在 IO 线程上阻塞等待，微秒级可接受 |
| L2（网络） | 同步返回 | 必须设超时并把失败变成异常，不得挂死 |
| L3（llm.call） | 待定 | 建议改为异步契约（模块返回 Promise） |

**不要给 L1 写成 suspend 后直接暴露给 JS** —— 模块拿到的会是 Promise，
`.trim()` 立刻 TypeError（这个坑已经踩过一次）。

### 13.7 39 条断言 -> Kotlin 测试（补 §7）

| 断言 | Kotlin |
| --- | --- |
| L1: 扫描到 8 个模块包 | ModuleRegistryTest.scanAll |
| L1: front matter 解析 | ModuleRegistryTest.parseFrontMatter |
| L1: 资源不进 system | RequestBuilderTest.injectRules |
| L1: 按需读到 style.md | HostBridgeTest.resourceRead |
| L1: 用资源完成改写 | WeeklyReporterTest.rewriteWithStyle |
| L1: 配额超限抛错 | HostBridgeTest.quotaExceeded |
| L1: 未声明能力抛错 | HostBridgeTest.undeclaredCapability |
| L1: 模块内无 fetch/window | SandboxExecutorTest.noAmbientAuthority |
| L1: 全局审计含失败项 | HostBridgeTest.auditIsGlobal |

### 13.8 两条环境铁律

1. **注册先于测试**：模块包的 `registerPackage(...)` 必须在任何测试或 UI 启动代码之前完成。
   单文件打包时由打包器保证顺序；将来若异步加载，必须用显式的 "registry ready" 信号。
2. **front matter 类型兜底**：`{}` / `[]` / 多行映射三种写法都要处理，
   拿到值后强校验类型（`hooks` 必须是数组，`capabilities` 必须是对象）。
   Kotlin 侧用 snakeyaml 后同样要做这一步，不能假设作者写对。

---

## 14. 待拍板清单（D1/D2/D10 已定，见 §17；其余仍待定）

| 编号 | 问题 | 现在 demo 的行为 | 影响 |
| --- | --- | --- | --- |
| D1 | hookAExtra 用 system 还是 user 角色 | system | RequestBuilder 的 EXTRA_ROLE 常量 |
| D9 | tools 定义来源 | 错用了 activeHardModules 全量 | 应从模块 manifest 的 tools 声明生成 |
| D10 | 本地截留气泡如何标记 | 消息上有 local 布尔 | ChatMessage 实体需要新增字段 |
| D11 | 是否开放 L2 网络 | 未开放 | HostBridge 能力表 |
| D12 | 是否开放 L3 llm.call | 未开放 | 同上 |
---

## 15. 反射弧（Reflex Arc）：第三入口

### 15.1 定位

| 层 | 职责 | 延迟 | 成本 |
| --- | --- | --- | --- |
| 大脑（模型） | 推理、创作 | 1–3 秒 | token |
| 脊柱（Kotlin） | 网络、存储、事件总线、动作执行 | 毫秒 | 无 |
| 反射弧（模块 onEvent） | 本地条件反射，不经模型 | 亚毫秒 | 无 token |

反射弧**不生成对话内容**，它只产生"动作意图"。

### 15.2 第三入口

    execute(scriptRef, inputJson) 的 inputJson 变为事件信封：

    {"name":"clipboard", "payload":{...}}

模块侧仍是一个函数表达式：

    function (eventJson, host) { ... return ModuleKit.reply({type:'action', ops:[...]}); }

`hooks` 之外新增 `events` 声明：

    events: [clipboard, network]

### 15.3 新增返回类型 action

    {"type":"action", "ops":[{"op":"insert_system_message","text":"..."}]}

op 白名单（白名单外的直接丢弃并记审计）：

| op | 效果 |
| --- | --- |
| insert_system_message | 插入一条系统提示气泡 |
| prefill_input | 预填输入框（用户仍需手动发送） |
| toast | 轻提示 |
| mark_previous | 给上一条 assistant 消息加标记（如「已过滤」） |

**模块不执行动作，只声明意图。执行权在 Kotlin。**

### 15.4 限流（全部在原生侧）

- 同类型事件 500ms 内只派发一次（debounce）
- 单模块每分钟动作数上限（默认 10）
- 单轮事件派发总预算上限（默认 20 次模块调用）

JS 侧无法限流，因为事件已经进入沙箱。

---

## 16. 反射弧的安全边界

### 16.1 两条硬规则

1. **剪贴板 ∩ 网络 = 空**：`events` 含 clipboard 的模块，`capabilities` 不得含任何 `net.*`。
   注册期校验，冲突直接拒绝加载（`config_conflict`）。
   理由：这是"读剪贴板 → 外发"的唯一硬阻断，用户看不懂权限组合。
2. **审计不记原文**：事件 payload 只记 `name / byteLength / sha256[:8]`，绝不落原文。
   剪贴板里可能有密码、验证码、私聊。

### 16.2 三条软规则

3. **改写模型输出必须标记**：截断或替换模型原文时，气泡上必须显示「已过滤」并可展开原文。
   静默改写等于冒充模型发言。
4. **不给硬件控制权**：亮度、音量、震动强度等不进 op 白名单。
5. **所有反射弧效果可全局关闭**：设置里一个开关，关掉后 `dispatchEvent` 直接返回。

### 16.3 版本归属

| 能力 | 版本 | 在哪验证 |
| --- | --- | --- |
| dispatchEvent / action 白名单 / 互斥校验 / 限流 | v1.1 | Kotlin 单元测试（纯逻辑） |
| 剪贴板、网络、亮屏真实事件 | v1.1 | Android 真机 |
| onChunk 流式反射（截断/过滤） | v1.2 | 需先定义每轮调用预算 |

---

## 17. 已拍板（对应 §14）

| 编号 | 决定 |
| --- | --- |
| D1 | hookAExtra 用 `system` 角色，位于 user 之后 |
| D2 | dispatchOutput 带 `ctx`（含 input），接受 |
| D10 | ChatMessage 新增 `source: String`（model / local / reflex）；气泡右下角灰字标签 + 淡灰底 |
---

## 18. D10 定案：本地气泡的双标记

| 层 | 做法 |
| --- | --- |
| 数据 | ChatMessage 新增 source: String = model / local / reflex |
| 主标记 | 气泡右下角 11sp 灰字：本地 / 反射 |
| 辅标记 | model 纯白+#F0F0F0 边框；local/reflex 用 #F7F7F8 淡灰、无边框 |

**两个都上，且主标记不可省。** 纯颜色标记在深色模式、色弱、截屏分享时会失效，
而这个标记的语义是「这句不是模型说的」，不能靠用户猜。

---

## 19. 模块巧思库（想法留存，实现分版本）

> 这一节记的是"值得做但还没做"的点子。接口已定，实现推后。

### 19.1 反射弧（第三入口，v1.1）

接口草图（已定，不实现）：

    事件信封 : {"name":"clipboard", "payload":{...}}
    模块签名 : function (eventJson, host) { return ModuleKit.reply({type:'action', ops:[...]}) }
    声明字段 : events: [clipboard, network]

| 编号 | 场景 | 触发 | 动作 | 版本 |
| --- | --- | --- | --- | --- |
| R1 | 复制了链接 | clipboard | insert_system_message「发现链接，要总结吗」 | v1.1 |
| R2 | 复制了长英文 | clipboard | insert_system_message「要翻译吗」 | v1.1 |
| R3 | 复制的是代码片段 | clipboard | prefill_input「解释这段代码：」 | v1.1 |
| R4 | 网络断开 | network | insert_system_message「已离线」 | v1.1 |
| R5 | 429 限流 | api_error | insert_system_message「稍后重试」+ 退避提示 | v1.1 |
| R6 | 长回复超 800 字 | post_render | mark 折叠「展开全文」 | v1.1 |
| R7 | 代码块缺语言标签 | output | 自动补标注 | v1.1 |
| R8 | 流式吐出「作为一个人工智能」 | onChunk | 截断 + mark_previous「已过滤」 | v1.2 |
| R9 | 深夜 2 点发消息 | clock | prefill_input 安慰语（需开关） | v1.2 |

R8/R9 两条附加约束：
- R8 必须**标记**且原文可展开，静默改写等于冒充模型发言
- R9 只允许 prefill（用户按发送才算数），**不给亮度/音量等硬件控制权**

### 19.2 值得保留的四个设计判断

1. **动作即意图**：模块返回 action，宿主执行。模块不调 UI API —— 只产出数据，天然可测可审计。
2. **能力声明即权限**：manifest 写什么就能用什么，没写的调用直接抛错。
3. **渐进披露**：description 进 system，正文按需 resource.read。长期省 token。
4. **审计全局单例**：对应 magisk.log，跨实例共享，可追责。

---

## 20. 框架缺口与优先级

| 优先级 | 缺口 | 说明 |
| --- | --- | --- |
| v1.0 必须 | 上下文预算管理 | 现在全量 history 原样发出，§7.5 写明不自行截断。长对话必然超限。需要 token 估算 + 截断/摘要策略 |
| v1.0 必须 | 取消生成 | 用户停止时协程断流，保留已生成文本 |
| v1.0 必须 | 重试 | Error(retryable) 已定义，缺实现 |
| v1.1 | 会话列表 / 自动标题 / 消息复制重发 | 体验层 |
| v1.1 | 模块管理 UI | 启用、授权、配额可视化。没有它用户不知道在授什么权 |
| v1.2 | 多 provider / onChunk 反射 / 模块导入 | |
| 不做 | 虚拟 DOM、模块控制硬件、模块任意网络 | 会把架构拉回 Xposed |

---

## 21. UI 设计规范

### 21.1 Tokens

| 类别 | 值 |
| --- | --- |
| 主背景 | #FFFFFF |
| 输入区底 | #F4F4F5 |
| 描边 | #E5E5E5（1dp）、分割线 #F0F0F0 |
| 正文 | #1A1A1A |
| 次要文字 | #666666 |
| Placeholder | #888888 |
| 本地/反射气泡底 | #F7F7F8 |

| 尺寸 | 值 |
| --- | --- |
| 顶栏高 | 56dp |
| 图标 | 24dp，**requiredSize 强制 1:1**，描边 1.8–2dp |
| 输入区圆角 | 24dp |
| 输入框最小高 | 48dp |
| 胶囊 | 高 32dp / 圆角 16dp |
| 发送按钮 | 30dp 圆形 |
| 抽屉宽 | 280dp |

| 字号 | 值 |
| --- | --- |
| 顶栏标题 | 17sp / 500 |
| 空态欢迎 | 20sp / 600 |
| 气泡正文 | 16sp / 400 |
| 胶囊文字 | 13sp / 500 |
| 来源标记 | 11sp / 400 |

### 21.2 组件清单

TopBar(mindicator: menu / title / volume / plus) · MessageList(LazyColumn) ·
Bubble(user / assistant / local / reflex / system) · InputCard(TextField + 胶囊 + 加号 + 发送) ·
Drawer(新对话 / 历史 / 设置入口) · SettingsCard(账户 / 应用 / 关于) ·
EmptyState · ErrorBar · SourceChip · ThinkingIndicator

### 21.3 六个状态的 UI 表现

| 状态 | 顶栏 | 气泡区 | 输入区 |
| --- | --- | --- | --- |
| idle | 正常 | 空态欢迎语 或 历史 | 可输入，发送按钮灰/黑按内容切换 |
| sending | 正常 | 底部出现一个无内容的 pending 气泡 | 发送按钮禁用 |
| streaming | 正常 | 最后一个气泡逐字增长 + 末尾光标 | 发送按钮变「停止」 |
| executing_tool | 正常 | 气泡下方显示「正在执行 analyze_chat」 | 禁用 |
| executing_local | 正常 | 直接出现 local 气泡（带标记） | 禁用 |
| error | 正常 | 底部一条可重试的 ErrorBar，气泡保留 | 恢复可用 |

### 21.4 UI 层三条硬约束

1. 图标一律 `requiredSize(24.dp)`，禁止用 `Modifier.size` 放在 Row 的 weight 里 —— 那正是首页图标被拉扁的原因。
2. TextField 必须去掉默认下划线与背景，`containerColor = Transparent`。
3. `enableEdgeToEdge` + 各屏幕内部用 `systemBarsPadding()`，背景色要能铺到状态栏下面，否则顶部会留白条。
---

## 22. Python 运行时与 skill 互通（v1.3 规划）

### 22.1 三个前提

| 环节 | 需要改的 |
| --- | --- |
| 目录结构 | 允许 SKILL.md 作为 MODULE.md 的别名；front matter 增加 runtime 字段 |
| 执行器 | JsExecutor 抽成 ScriptExecutor 接口，execute(scriptRef, inputJson) 签名不变 |
| 隔离 | 按运行时分级授权（见 22.3） |

### 22.2 运行时是插拔的

    interface ScriptExecutor {                     // v1.0 就要是这个形状
        suspend fun execute(scriptRef: String, inputJson: String): String
    }
    class JsExecutor : ScriptExecutor              // QuickJS / JavaScriptSandbox
    class PythonExecutor : ScriptExecutor          // Chaquopy（v1.3）

front matter: `runtime: js | python`，缺省 js。
上层（ModuleDispatcher / ChatViewModel）对运行时无感知。

### 22.3 隔离等级不同，授权必须分级

| 运行时 | 隔离强度 | 允许能力 | UI 标注 |
| --- | --- | --- | --- |
| js（QuickJS） | 强：无 DOM、无 import、可中断 | 全部声明能力 | 无 |
| python（进程内） | 弱：可 import java.*、可读文件 | 仅 L1，禁止 net.*、file.* | 「此模块可访问设备资源」 |
| python（独立进程 + 管道） | 强 | 全部 | 无 |

**进程内 Python 不得声明网络能力** —— 它的沙箱挡不住 `import java.net`，
这与 §16.1「剪贴板 ∩ 网络 = 空」是同一类硬约束。

### 22.4 skill 互通适配表

| skill 侧 | 本框架 | 适配方式 |
| --- | --- | --- |
| SKILL.md | MODULE.md | 文件名二者其一即可 |
| front matter name / description | 同名 | 直接映射 |
| scripts/*.py | script/*.js | runtime: python |
| references/ | reference/ | 目录名别名 |
| 入口函数 | handle(inputJson, host) | 适配器包一层，**不改 skill 本体** |

目标：导入一个 skill 只增加一个覆盖段，不修改它的任何文件。

### 22.5 版本归属

v1.0：只要 ScriptExecutor 是接口（5 分钟的额外成本）
v1.3：Python 进程内运行时 + runtime 字段 + 分级授权
v1.4：独立进程沙箱（如果需要跑第三方模块）
---

## 21.5 UI 定稿（预览已逐项验证）

### 最终 tokens

| 项 | 定稿值 | 备注 |
| --- | --- | --- |
| 主背景 | #FFFFFF | |
| 输入区底 | #F4F4F5 | |
| 描边 / 分割线 | #E5E5E5 / #F0F0F0 | |
| 正文 / 次要 / 占位 | #1A1A1A / #666666 / #888888 | |
| **本地·反射气泡底** | **#EFF0F2** | 改过：原 #F7F7F8 与白底区分不出来 |
| **来源标签文字** | **#8A8A8A** 11sp | 改过：原 #9A9A9A 偏淡 |
| 用户气泡底 | #F4F4F5，圆角 16dp | |
| 助手气泡 | 白底 + #F0F0F0 1dp 边框，圆角 16dp | |
| 顶栏 | 56dp，标题 17sp/500 居中 | |
| 图标 | 24dp 固定，描边 1.8–2dp | requiredSize，禁止拉伸 |
| 输入卡片 | 圆角 24dp，`padding 6dp 10dp 8dp` | 改过：原 8/10 偏高 |
| 输入框 | `min-height 44dp`，透明底，无下划线 | 改过：原 48dp |
| 胶囊 | 32dp 高 / 16dp 圆角 / 13sp/500 | |
| 发送按钮 | 30dp 圆，黑底白箭头；禁用 #E5E5E5 | |
| 设置页底部 | `padding-bottom = 28dp + safe-area` | 改过：原被导航栏遮住 |
| 错误条 | #FDECEB 底 / #C0392B 字；重试 = 胶囊 `padding 4dp 10dp` `rounded 8dp` `bg #F8D7D4` | 改过：原只是粗体文字 |

### 只能真机验证的三项

| 现象 | 实现要点 |
| --- | --- |
| 键盘弹起时输入区上顶 | `windowSoftInputMode=adjustResize` + `Modifier.imePadding()` |
| 状态栏/导航栏沉浸 | `enableEdgeToEdge()` + 各屏内部 `systemBarsPadding()`，背景铺到栏下 |
| 列表滚到最新 | `LazyColumn` + `LaunchedEffect(messages.size) { listState.animateScrollToItem(last) }` |

对照物：`ui-preview.html`（七个状态可切换）。
---

## 21.6 官方 Android 数值（参照 deepseek-app-replica 的 data-android 分支）

来源：`src/components/**/[data-android]` 覆盖规则，是官方 Android 客户端的实测值。

### 关键结论：助手消息没有气泡框

官方 Android 版的助手回复是**通栏纯文本块**（`padding: 11.38dp 0 0 4dp`，
`max-width: 100%`），只有**用户消息**是灰底圆角气泡。

我们的旧设计给助手加了白底 + 1px 边框 + 16dp 圆角 —— 这是"看起来像仿"的最大来源，
必须去掉。

### 数值表

| 项 | 官方值 | 旧值 | 采用 |
| --- | --- | --- | --- |
| 顶栏高 | 52dp | 56dp | 52dp |
| 标题 | 15sp / 500 | 17sp / 500 | 15sp |
| 菜单图标 | 24×24 | 24×24 | 保持 |
| 喇叭图标 | 20×20 | 24×24 | 20dp |
| 新建图标 | 22×22 | 24×24 | 22dp |
| 正文 / 行高 | 16 / 26.31 | 16 / 24 | 26sp |
| 助手消息 | 无背景无边框，padding 11/0/0/4 | 白底+边框+16 圆角 | 改为通栏 |
| 用户气泡 | 灰底 #F5F5F5 圆角 16 | 同 | 保持 |
| 输入区外框圆角 | 24dp | 24dp | 保持 |
| 输入框内边距 | 12 / 12 / 0 / 16 | 6 / 4 | 采用 |
| 输入框最小高 | 42dp（窄屏） | 44dp | 42dp |
| 工具行 | padding 12dp、gap 8dp、margin-top 4dp | 无 padding | 采用 |
| 过渡曲线 | 220ms cubic-bezier(.2,0,0,1) | .22s ease | 采用 |
| 操作按钮色 | #8E9094 | — | 采用 |

### 颜色（官方 token）

| 用途 | 值 |
| --- | --- |
| 正文 | #0F0F0F |
| 次要 | #545557 |
| 三级 / 占位 | #8E9094 |
| 边框 | #E5E5E5 |
| 浅底 / 用户气泡 | #F5F5F5 |
| 品牌蓝 | #426EFE |
| 深色模式背景 | #151517 |

### 尚未采集

| 项 | 参照文件 |
| --- | --- |
| 用户气泡精确 padding / 圆角 | UserMessage.module.css |
| 消息底部操作行（复制 / 重新生成） | 同 AssistantMessage 的 [data-android] .bottom |
| 代码块与 markdown 排版 | markdown 相关样式 |
| 深度思考区（shimmer + 左侧竖线） | ReplyArea.module.css |
---

## 21.7 官方排版定稿（MessageList / markdown）

### 消息列表

| 项 | 官方 [data-android] | 旧值 | 定稿 |
| --- | --- | --- | --- |
| 列左右留白 | padding: 0 **20dp** 24dp | 12dp | 20dp |
| 列最大宽 | 808dp | 无 | 808dp |
| 消息间距（视觉定稿） | 21.75（10.37+11.38） | 26 | **22**（row 10 + 助手 pt 12） |
| 首条消息上边距 | 16.62dp | 0 | 16dp |

### markdown 正文

| 元素 | 官方值 |
| --- | --- |
| 段落 | margin: 16dp 0；**首尾归零** |
| h3 | margin: 32dp 0 16dp |
| 有序/无序列表 | margin 16dp 0；padding-left 18dp；li+li 6dp |
| 行内 code | font-size .875em；border-radius 6dp；padding 0 5dp |
| 分隔线 hr | height 1dp；margin 32dp 0 |
| 引用 blockquote | margin 16dp 0 0；padding-left 14dp |
| 表格 th/td | padding 10dp 16dp；首列去左内边距、末列去右内边距 |

### 代码块

| 项 | 官方值 |
| --- | --- |
| 外框 | border-radius **12dp**；margin 16dp 0；相邻块之间 11dp |
| 顶栏 | 只有上方两角 12dp 圆角 |
| 内容 pre | padding **16dp**；margin 0 |
| 语言名 | 12sp / 18sp，等宽字体 |
| 复制按钮 | 高 28dp、圆角 8dp、字号 13sp、左右 10dp、图标 14dp |

### 深度思考区（v1.1）

`thinking.markdown.css` 用**更紧的间距**：段落 12dp、分隔线 28dp、
列表条目 2dp。整块文字用 `label-secondary`（#545557）而非正文色。
---

## 21.8 UI 冻结基线（v1 定稿，停止继续对齐）

**决定**：UI 打磨到此为止。像素级对齐推迟到 Compose + 真机阶段，
原因是 WebView 与 Compose 的字体度量、行高、抗锯齿都不同，
在 HTML 上逐像素调会在移植时重做一遍。

### Token（最终值，写死）

| 类别 | 值 |
| --- | --- |
| 主背景 | #FFFFFF |
| 输入区底 / 用户气泡底 | #F5F5F5 |
| 本地·反射气泡底 | #EFF0F2 |
| 边框 | #E5E5E5 |
| 分割线 | #F0F0F0 |
| 正文 | #0F0F0F |
| 次要文字 | #545557 |
| 三级 / 占位 | #8E9094 |
| 品牌蓝 | #426EFE |
| 深色模式背景（未来） | #151517 |

| 尺寸 | 值 |
| --- | --- |
| 顶栏高 | 52dp |
| 标题 | 15sp / 500 |
| 菜单 / 喇叭 / 新建图标 | 24 / 20 / 22 |
| 消息区左右 | 16dp |
| 消息间距 | 22dp（row 10 + 助手 padding-top 12） |
| 助手消息 | 无背景无边框，padding-top 12 |
| 用户气泡 | 圆角 16dp，padding 10×14，最大宽 86% |
| 输入卡 | 圆角 24dp，border 1dp |
| 输入框 | min-height 42dp，padding 12/12/0/16 |
| 工具行 | padding 12dp，gap 8dp |
| 胶囊 | 高 32dp，圆角 16dp，13sp/500 |
| 发送 / 语音按钮 | 30dp 圆 |
| 抽屉宽 | 280dp |
| 列表项高 | 40dp |
| 搜索框 | 高 38dp，圆角 19dp |
| 过渡 | 220ms cubic-bezier(.2,0,0,1) |

### 停止线（5 条，已达成）

1. 助手消息没有气泡框（通栏）
2. 输入卡 24 圆角 + 内部 12/12/0/16
3. 顶栏 52dp、图标不变形
4. 五色 token 到位（正文/次要/占位/边框/品牌）
5. 用户气泡灰底圆角、本地与反射有标记

### Backlog（不在手机上改，留到 Compose 阶段）

| 项 | 原因 |
| --- | --- |
| 侧栏搜索框 / 分组 / 账号行的精确尺寸 | 目测值，需真机比对 |
| 胶囊图标造型 | 官方图标非公开资源 |
| 语音按钮与输入法的联动 | 需真实 InputMethod |
| 服务器提示条的可关闭交互 | 状态机相关 |
| 消息底部操作行的悬停/长按态 | 触屏手势，WebView 里失真 |
---

## 23. 真接口实测（2026-xx-xx，DeepSeek Chat）

### 23.1 四用例结果

| 用例 | 首字节 | SSE 行 | 总耗时 | 状态 | 钩子 |
| --- | --- | --- | --- | --- | --- |
| 你好 | 417ms | 9 | 852ms | idle | 全 pass |
| 附加：把语气改正式 | 260ms | 31 | 870ms | idle | hookA context |
| 执行任务 写个周报 | 349ms | 100 | 1423ms | idle | hookB rewrite |
| 现在几点了 | 无 | 0 | 0ms | idle | hookA local |

### 23.2 真接口暴露并已修复的三个问题

| 问题 | 现象 | 修复 |
| --- | --- | --- |
| D9：tools 冒充 | 用 activeHardModules 生成 tools，模型真去调用钩子模块，返回 422 | tools 只来自模块 manifest 的 tools 声明；未声明则不发送 |
| tool content 为空 | `"content": null` → `content should be a string or a list` | 非字符串一律兜底成 `{"ok":false,"error":"module_returned_empty"}` |
| tool 轮次被改写 | 钩子 B 改写带 tool_calls 的 assistant 内容，导致 tool_call_id 断链 | 有 tool_calls 时禁止改写，内容保持原文 |

### 23.3 参考值

- 首字节：260–420ms
- 整段：850–1400ms
- 因此 Kotlin 侧：连接超时 ≥ 60s，首字节预期 < 1s，不设首字节超时（模型可能先思考）

### 23.4 结论

钩子 A/B 在真实数据下的行为与离线断言一致。
Streaming 解析、extra 注入、本地截留三项均已实测通过。
---

## 24. 真接口验证记录（框架 v1.0 收工）

### 24.1 三个必须项的状态

| 项 | 验证方式 | 结论 |
| --- | --- | --- |
| 上下文预算 | 6 轮真实对话，预算 180 | 请求消息稳定在 4 条，裁剪单调增长 |
| 取消生成 | 流式中途 cancel | 保留已生成文本、标记 stopped、不再收后续分块、回 idle |
| 重试 | 首轮 503，retry | 成功、无重复 user、状态回 idle |

多轮实测数据（预算 180）：

| 轮 | 请求消息 | 裁剪 | user×N |
| --- | --- | --- | --- |
| 1 | 2 | 0 | 1 |
| 2 | 4 | 0 | 2 |
| 3 | 6 | 0 | 3 |
| 4 | 4 | 4 | 2 |
| 5 | 4 | 6 | 2 |
| 6 | 4 | 8 | 2 |

**判据**：请求消息数收敛、裁剪数单调增长、user×N 不再增长、会话内无重复 user。

### 24.2 真接口暴露的六个问题（全部离线测不到）

| # | 问题 | 现象 | 修复 |
| --- | --- | --- | --- |
| 1 | 假 tools | 用 activeHardModules 生成 tools，模型真去调钩子模块，422 | tools 只来自模块 manifest 的 tools 声明 |
| 2 | tool content 为空 | `content: null` → 422 | 非字符串兜底成 `{"ok":false,...}` |
| 3 | tool 轮次被改写 | 钩子 B 改带 tool_calls 的 assistant，tool_call_id 断链 | 有 tool_calls 时禁止改写 |
| 4 | 保底值吞配置 | `Math.max(256, budget-reserved)` 让小预算失效 | 保底降到 48 |
| 5 | DAO 白名单丢字段 | SessionDao 只复制已知字段，contextBudget 丢失，永远用默认 4000 | DAO 补字段 + 加断言 |
| 6 | 裁剪切断问答对 | 历史以孤立的 assistant 回复开头 | 配对优先：首条是 assistant 时把前一条 user 一并带上 |

**共同点**：都是"代码写了、看着对、但从未真正执行过"分支。
这六条全部由真实 API + 真实多轮触发，离线回放无法覆盖。

### 24.3 新增规则（并入正文约束）

**裁剪以问答对为单位**（补充 §3.1）

trimHistory 从最新往回取；若保留下来的首条是 assistant，必须把它前一条 user 一并保留。
不允许历史以孤立的助手回复开头。代价是最多多带一条消息的额度。

**DAO 白名单必须跟随实体扩展**

SessionDao / MessageDao 都是白名单式复制字段。任何新增字段（contextBudget / source /
rewritten / stopped）都必须同步加进 DAO，否则会静默丢失。
每次新增都要补一条"经由 DAO 的断言"，不能只测组件。

**错误态必须清理占位**

一轮失败时，tempId 占位行必须删除。否则会话里留下一条空的 assistant 消息，
下次作为上下文时虽然被 pending 排除，但 UI 上会显示一个空气泡。

**API Key 输入校验前置**（并入 §21 设置页规范）

- 只允许 ASCII 可打印字符
- 长度区间校验，`sk-` 前缀提示
- 校验失败在输入框层报错，不要拖到网络层
- HTTP 头只能放 ISO-8859-1，含中文会直接抛 `non ISO-8859-1 code point`

### 24.4 实测参考值

| 指标 | 值 |
| --- | --- |
| 首字节 | 240–470ms |
| 单轮整段 | 850–1400ms |
| 6 轮总耗时 | 约 8s |
| 单轮 SSE 行数 | 9–100 |

Kotlin 侧：connectTimeout 30s、readTimeout 60s、**不设首字节超时**（深度思考时可能 3–5s）。
### 24.5 多会话隔离（已验证）

| 断言 | 结论 |
| --- | --- |
| 各会话消息互不串台 | ✅ |
| getContextForRequest 按 sessionId 隔离 | ✅ |
| 请求体只带本会话历史 | ✅ |
| 上下文预算按会话独立 | ✅ |
| 切换会话时清掉 error 态 | ✅ |

**规则**：`ChatUiState` 目前是全局单例。切换会话时若处于 `Error`，
必须显式重置为 `Idle`，否则新会话会被上一个会话的错误态挡住（`send()` 有守卫）。
Kotlin 侧建议把 state 做成 `Map<sessionId, ChatUiState>`，从根上避免串台。
---

## 25. 当前状态与已知偏离（交接前必读）

### 25.1 已验证

| 项 | 证据 |
| --- | --- |
| 64 条离线断言 | 验收页全绿 |
| 真接口 4 用例 | 普通 / 附加 / 改写 / 本地截留 |
| 真接口 6 轮多轮 | 预算 180，请求消息收敛到 4 条 |
| 多会话隔离 | 9 条断言 |
| 能力模型 | 配额、未声明拒绝、全局审计 |

### 25.2 已知偏离（demo 与文档不一致，**移植时以文档为准**）

| # | demo 实现 | Kotlin 必须 |
| --- | --- | --- |
| 1 | 消息字段 `local: Boolean` | `source: String`（model / local / reflex），见 §18 |
| 2 | `ChatUiState` 全局单例 | `Map<sessionId, ChatUiState>`，否则切会话会串错误态 |
| 3 | 模块脚本以 `'function (inputJson, host){...}'` 字符串注册 | 从 assets 读 .js 文本，交 JavaScriptSandbox 执行 |
| 4 | 共享作用域 + 变量遮蔽（`var fetch=undefined`）模拟沙箱 | 真隔离：JavaScriptSandbox / QuickJS |
| 5 | 硬模块用 `<script>` 加载 | `assets/modules/<folder>/script/*.js` |
| 6 | 软模块内容是 js 常量 | `assets/modules/soft/*.md` 原文 |
| 7 | DAO 内存数组 | Room，且白名单字段必须跟随实体扩展 |

### 25.3 v1.1 backlog（不在 v1.0 范围）

| 项 | 依据 |
| --- | --- |
| 反射弧 onEvent / action ops | §15、§19.1 |
| 深度思考折叠区 | §21.6 未采集部分 |
| 消息操作行（复制 / 朗读 / 重新生成） | §21.6 |
| 模块启停 UI | §20 |
| Python 运行时 / skill 互通 | §22 |
| 视觉重设计（去"复刻"痕迹） | §21.8 冻结说明 |

### 25.4 一个判断

框架侧的能力边界、契约、错误处理已经压到位；
继续在手机上调 UI 的收益已经很低（WebView 与 Compose 渲染差异会让微调失效）。
**下一步的瓶颈是"能不能上 PC 跑 Kotlin"，不是"demo 还能改什么"。**
