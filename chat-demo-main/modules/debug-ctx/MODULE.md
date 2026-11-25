---
id: debug_ctx
name: 上下文诊断
description: 输入「诊断ctx」后回报 ctx 的字段
version: 1.0.0
hooks: [input]
inject: none
capabilities:
  data.session: 2
entry: script/main.js
---

钩子 A。诊断用，确认信封里有什么。
