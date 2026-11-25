---
id: weekly_reporter
name: 周报排版
description: 周报排版。用户说"写个周报""整理成周报""这周做了什么"时使用。把已有清单按格式排好，不负责规划任务、不负责拆需求。
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
