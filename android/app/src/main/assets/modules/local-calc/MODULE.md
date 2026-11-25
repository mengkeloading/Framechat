---
id: local_calc
name: 本地计算
description: 纯算式直接本地求值，不联网、不花 token
version: 1.0.0
hooks: [input]
inject: none
capabilities: {}
entry: script/main.js
---

钩子 A。匹配纯算式时返回 local，短路整条网络链路。
