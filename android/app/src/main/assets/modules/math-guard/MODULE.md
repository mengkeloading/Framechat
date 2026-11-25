---
id: math_guard
name: 计算守门
description: 输入含「算一下」时注入分步计算的附加要求
version: 1.0.0
hooks: [input]
inject: none
capabilities: {}
entry: script/main.js
---

## 它做什么
钩子 A 命中「算一下」时返回 context，extra 要求模型分步计算。

## 它不做什么
不自己算数，不拦截输入，不发网络。
