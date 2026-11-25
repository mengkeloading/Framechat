---
id: formatter
name: 格式化
description: 只能被 invoke 转调，给文本加接管前缀
version: 1.0.0
hooks: [output]
inject: none
capabilities: {}
entry: script/main.js
---

不在会话的启用清单里，所以首轮链扫不到它，只能被 invoke 找到。
