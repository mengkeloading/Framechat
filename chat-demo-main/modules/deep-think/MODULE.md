---
id: deep_think
name: 深度思考
description: 输入以「深想：」开头时注入先拆解再作答的思考框架
version: 1.0.0
hooks: [input]
inject: none
capabilities: {}
entry: script/main.js
---

钩子 A。命中「深想：」后返回 context，extra 作为本轮 system 指令。
