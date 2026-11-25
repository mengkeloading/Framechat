---
id: weekly_reporter
name: 周报排版
description: 把任务清单整理成周报，格式规则放在 reference/style.md，按需加载
version: 1.0.0
hooks: [output]
inject: summary
capabilities:
  resource.read: 5
entry: script/main.js
---

## 它做什么
命中「周报」时，读取 `reference/style.md` 的格式规则，把任务清单排版成周报。

## 为什么用资源而不是拼提示词
规则文件只在命中时才读，平时不进上下文，不烧 token。这就是 skill 的渐进披露。

## 它不做什么
不发网络、不写库。
