---
id: time_local
name: 本地时间
description: 输入含「几点」时本地回答，不发起网络请求
version: 1.0.0
hooks: [input]
inject: none
capabilities: {}
entry: script/main.js
---

钩子 A。返回 local 即短路，本轮不联网。
