---
id: long_fold
name: 长回复折叠
description: 回复超过 800 字时在末尾追加折叠提示
version: 1.0.0
hooks: [output]
inject: none
capabilities: {}
entry: script/main.js
---

## 它做什么
钩子 B 统计回复字符数，超过 800 就在末尾追加「已折叠」提示。

## 它不做什么
不真的折叠内容（折叠是 UI 的事），不改正文。
