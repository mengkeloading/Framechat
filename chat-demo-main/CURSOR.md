# Cursor 分批下发清单

## 怎么用

一次只让它写一批。粘贴下面第 1 批的提示词，跑通再下一批。
每批都必须按 ARCHITECTURE.md 的对应小节，**不要扩展职责**。

## 分批顺序

| 批 | 文件 | 依赖 | 验收 |
| --- | --- | --- | --- |
| 1 | data/ 三 Entity + Daos + AppDatabase | — | 建库成功，getContextForRequest 三种排除正确 |
| 2 | error/ErrorMapper.kt | — | 401/429/超时分类 |
| 3 | module/ModuleLoader.kt | assets | 扫 8 个包，front matter 类型兜底 |
| 4 | js/JsExecutor.kt + bridge/HostBridge.kt | 3 | execute(scriptRef,json) 签名不变 |
| 5 | network/SseParser.kt | — | 单行解析、坏行返回 null |
| 6 | network/ApiClient.kt | 2,5 | 产出原始 SSE 行的 Flow |
| 7 | network/RequestBuilder.kt | 1,3 | 顺序：软模块→history→user→extra→tool |
| 8 | module/HookResults + ModuleDispatcher | 3,4 | pass/local/context/pass/rewrite/invoke + fail-open + invoke 深度 3 |
| 9 | vm/ChatViewModel.kt | 1-8 | tempId、tool 循环、truncated、finally 条件重置 |
| 10 | ui/ChatUiState + ChatScreen | 9 | 六个状态 |
| 11 | MainActivity | 10 | edge-to-edge |

## 第 1 批提示词（复制即用）

    这是 MyChat 项目。先读 ARCHITECTURE.md（权威）和《主框架技术文档》（背景）。
    现在只实现 data/ 下的 5 个文件：
      ChatSession.kt  ChatMessage.kt  ToolCallRecord.kt  Daos.kt  AppDatabase.kt
    严格按 ARCHITECTURE.md §4 和 §13.1，以及原文档 §4.1 的字段定义。

    硬性要求：
    1. ChatMessage 必须有 source: String 字段（"model" / "local" / "reflex"）—— 见 §18。
    2. ChatMessage 必须有 pending: Boolean —— 用于区分流式占位与已定型消息，见 §3.3。
       **不要用 content 是否为空来判断占位**，那会误删 tool 轮次的 assistant 消息。
    3. getContextForRequest 必须排除三类：role="system"、role="tool"、pending=true —— 见 §3.1。
    4. ChatSession 的两个 List<String> 字段（activeSoftModules / activeHardModules）
       必须配 TypeConverter，Room 不能直接存 List。
    5. ToolCallRecord 的 success 用 Boolean?（null = 执行中）—— 见 §3.4。

    不要扩展职责：不要写网络、不要写 JS 执行、不要在 Dao 里做业务判断。
    给我完整可运行代码，不要省略。

## 每批完成后的自查清单

- tempId：先 insert(pending=true)，逐 delta update(content)，定型 update(content, pending=false)
- getContextForRequest 的三种排除都在
- RequestBuilder 的 userInput 只在第一轮传（可空）
- 工具调用空参兜底成 {}
- 超出 maxToolRounds 用 truncated 标志，并插 system 消息
- finally 里只在本轮不是 Error 时才回 Idle
- JsExecutor.execute(scriptRef, inputJson) 签名没变
