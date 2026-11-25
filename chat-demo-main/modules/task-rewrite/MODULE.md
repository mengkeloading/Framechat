---
id: task_rewrite
name: 任务改写
description: 用户输入以「执行任务」开头时，把最终气泡改写成接管格式
version: 1.0.0
hooks: [output]
inject: none
capabilities: {}
entry: script/main.js
---

钩子 B。任务原文来自信封里的 ctx.input —— 不赌模型服从。
