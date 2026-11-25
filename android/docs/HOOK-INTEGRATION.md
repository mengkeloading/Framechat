# 钩子体系移植计划

基于 Simple-Chat 现有结构，改动点如下。

## 新增包

com/simplechat/app/hook/
  HookResult.kt      钩子返回值（pass/local/context/rewrite/invoke）
  ModuleManifest.kt  MODULE.md front matter 解析结果
  ModuleRegistry.kt  扫 assets/modules，解析 MODULE.md
  JsExecutor.kt      接口：execute(scriptRef, inputJson): String
  SandboxJsExecutor  JavaScriptSandbox 实现
  ModuleDispatcher   钩子调度 + 合并 + invoke 深度限制
  HostBridge.kt      能力闸门 + 配额 + 审计

## 改动点

| 文件 | 改动 |
| --- | --- |
| ui/chat/ChatViewModel.kt | send() 开头插 hookA；流结束后插 hookB |
| net/ChatApi.kt | buildChatRequest 接收 hookA 的 extra 与附件 |
| db/ChatDao.kt | 消息实体加 source / pending / rewritten 字段 |
| ui/chat/MessageList.kt | 气泡渲染加「本地 / 已接管」标记 |
| ui/settings/SettingsScreen.kt | 增加模块管理入口与权限标签 |

## 验收标准

以 chat-demo-main 的 104 条断言为准，逐条搬成 JUnit。

## 许可

保留 LICENSE 与 NOTICE.md，不得删除。
