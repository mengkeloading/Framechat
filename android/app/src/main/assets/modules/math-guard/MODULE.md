---
id: math_guard
name: 计算守门
description: 数学错误检查。当回答里出现计算过程、单位、数量对比时，用于核对是否有算错的地方。它不负责计算，只负责发现错误。
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
