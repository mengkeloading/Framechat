---
id: no_fluff
name: 去套话
description: 自动剪掉回复末尾的套话
version: 1.0.0
hooks: [output]
inject: none
capabilities: {}
entry: script/main.js
forbid:
  - 不要在回复末尾追加"希望有帮助""还有其他问题"这类套话
  - 不要改动正文，只删末尾客套
require:
  - 结尾停在最后一个实质句子上
---

钩子 B。删除末尾"希望有帮助/还有其他问题"一类套话。
