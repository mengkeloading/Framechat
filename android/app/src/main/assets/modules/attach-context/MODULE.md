---
id: attach_context
name: 附加要求
description: 输入以「附加：」开头时，把内容作为 system 附加指令拼入本轮请求
version: 1.0.0
hooks: [input]
inject: none
capabilities: {}
entry: script/main.js
---

钩子 A。命中后返回 context，extra 是字符串，会被当成一条 system 消息。
