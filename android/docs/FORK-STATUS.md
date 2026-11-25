# FORK 状态

## 现状

本目录是 Simple-Chat 的本地 fork，用于个人学习与原型验证。
**尚未获得作者明确回应**（MIT 已授权，此处仅出于礼貌）。

- 来源：Simple-Chat (c) 2026 Pokanshu, MIT
- 保留文件：LICENSE 原样未改
- 我们的改动：见 NOTICE.md

## 两种结局

### 作者同意或未回应

继续在此基础上开发：

1. 新增 com.simplechat.app.hook/ 七个文件
2. 改 ChatViewModel / ChatApi / ChatDao / MessageList 四处集成点
3. 以 chat-demo-main 的 104 条断言作为验收

### 作者表示不希望被 fork

删除本目录，按 chat-demo-main/ARCHITECTURE.md 从零构建。

可复用的资产：

- ARCHITECTURE.md：25 节规范，可直接指导开发
- modules/：11 个模块包，原样进 assets
- 104 条断言：JUnit 测试的现成清单
- docs/COMPONENTS.md：38 项组件清单，逐项实现

需要新写的部分与估计：

| 模块 | 内容 | 估计 |
| --- | --- | --- |
| data | Room 三实体 + DAO | 1 天 |
| net | SseParser + ApiClient + RequestBuilder | 1 天 |
| hook | 七个文件 | 2 天 |
| vm | ChatViewModel | 3 天 |
| ui | ChatScreen + Markdown 渲染 | 5-7 天 |

合计约 2 周。
