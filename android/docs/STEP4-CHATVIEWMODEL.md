# 第 4 步：ChatViewModel 接入对照说明

目标：把钩子 A / B 插进既有发送流程，尽量少改动。
依据：chat-demo-main 的 104 条断言。

## 0. 前置：会话增加模块字段

Simple-Chat 没有模块概念，需要补两列：

    activeHardModules: List<String>
    activeSoftModules: List<String>

放在 ConversationEntity 或 SettingsStore，默认给 11 个包：

    attach_context, time_local, task_rewrite, router

再写一个适配函数：

    private fun hookSessionOf(cid: String): HookSession

## 1. 插入点 A：send() 开头

定位：

    grep -n 'fun send(' ui/chat/ChatViewModel.kt

插入伪代码：

    fun send(prompt, attachments, onPersisted) {
        viewModelScope.launch {
            val s = hookSessionOf(conversationId)
            val a = dispatcher.dispatchInput(s, prompt)
            if (a is HookAResult.Local) {
                persistAssistant(a.text, source = LOCAL)
                onPersisted()
                return@launch
            }
            val effective = a.text
            val extra = (a as? HookAResult.Context)?.extra
            // 以下走原流程，但 user 内容用 effective
            sendAttempt(effective, extra, attachments)
        }
    }

关键点：local 分支必须在落库之后立刻 return，绝不发网络。

## 2. 插入点 B：组包

定位：

    grep -n 'buildChatRequest' net/ChatApi.kt

新增两个参数：

    extra: String?          // 来自 HookAResult.Context
    attachmentTexts: List<String>  // 文本附件正文

组包顺序（不可改）：

    system(软模块或 systemPrompt)
    system(压缩摘要，若有)
    history
    user(effective)
    system(extra)
    tool(本轮结果)

## 3. 插入点 C：流结束

定位：

    grep -n 'StreamEvent.Done' ui/chat/ChatViewModel.kt

插入伪代码：

    val b = dispatcher.dispatchOutput(s, rawText, effective)
    val hasTool = pendingToolCalls.isNotEmpty()
    val final = if (b is HookBResult.Rewrite && !hasTool)
        b.text else rawText
    persistAssistant(final, rewritten = b is HookBResult.Rewrite)

为什么有 hasTool 判断：改写会让 tool_call_id 与 assistant 断链，
真实接口会返回 422（已在 demo 里踩过）。

## 4. 插入点 D：气泡标记

UiMessage 增加 source 与 rewritten 两个字段，MessageList 里：

    if (msg.source != MODEL) Text(本地 / 反射, 11sp, 灰)
    if (msg.rewritten) Text(已接管, 11sp, 品牌蓝)

## 5. 校验清单

| 断言 | 检查位置 |
| --- | --- |
| 本地截留不联网 | 插入点 A 的 return |
| extra 进请求体 | 插入点 B 的 system 消息 |
| 改写只作用于最终气泡 | 插入点 C |
| 有 tool_calls 时不改写 | 插入点 C 的 hasTool |
| 会话隔离 | hookSessionOf 按 cid 取 |

## 6. 三个回归风险

1. stop() 取消时不应执行 hookB —— 在协程取消后跳过。
2. retryMessage 应重跑整轮（删消息后重发），
   而不是只重跑 hookB。
3. 流式中途异常不要落库半截文本，
   保持 Simple-Chat 原有错误处理。

## 7. 建议改动量

| 文件 | 新增/改动行数 |
| --- | --- |
| ChatViewModel.kt | 约 40 行 |
| ChatApi.kt | 约 25 行 |
| ChatDao.kt + 实体 | 约 30 行（含迁移） |
| MessageList.kt | 约 20 行 |
| 新增 hook/ | 7 个文件 |

合计约 120 行改动 + 7 个新文件。
