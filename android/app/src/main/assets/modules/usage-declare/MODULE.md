---
id: usage_declare
name: 使用声明
description: 输出规范。要求模型在回答末尾声明使用了哪些模块。
version: 1.0.0
hooks: [input]
inject: none
capabilities: {}
entry: script/main.js
---

钩子 A。命中任意输入后返回 context，extra 是一段输出规范，被当成一条 system 消息。
它不改写用户输入，也不短路，只负责让模型在结尾多写一行 `[[USED:…]]`。
