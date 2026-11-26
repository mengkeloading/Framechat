---
id: skill_code
name: 代码审查
description: 代码审查。用户贴出代码、问"这样写对不对""有问题吗""怎么改"时使用。按严重程度分级，每条给出可执行的修改。
version: 1.0.0
hooks: []
inject: none
capabilities: {}
entry: script/main.js
format_marks:
  - "[必须改]"
  - "[建议改]"
  - "[可选]"
forbid:
  - 不要开场白（"好的"、"我来看看"）
  - 不要结尾总结
  - 不要问"还需要什么"
require:
  - 每个问题必须写全三件事：在哪、为什么、改成什么
  - 必须给出最小修改版
---

纯知识包：不挂钩子，SKILL.md 由框架在 selected 命中时直接注入。

`forbid` / `require` 会渲染成注入块末尾的【禁止】/【要求】两节。
`format_marks` 声明输出必须含的分级标记，钩子 B 会检查并记 trace。
