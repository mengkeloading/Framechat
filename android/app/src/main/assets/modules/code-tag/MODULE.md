---
id: code_tag
name: 代码块标注
description: 回复里的代码块缺少语言标签时自动补 text
version: 1.0.0
hooks: [output]
inject: summary
capabilities: {}
entry: script/main.js
---

## 它做什么
钩子 B 检查回复里的 ``` 代码围栏；若围栏后没有语言标签，补成 ```text。

## 它不做什么
不判断语言种类，不改代码内容，不碰网络。
