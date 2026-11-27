---
id: skill_code
name: 代码审查
description: 代码审查。用户贴出代码、问"这样写对不对""有问题吗""怎么改"时使用。按严重程度分级，每条给出可执行的修改。
version: 1.0.0
hooks: []
inject: none
sample_queries:
  - "这段代码有问题吗"
  - "帮我看看这个函数"
  - "这样写对不对"
  - "能优化一下吗"
  - "这里为什么报错"
capabilities: {}
entry: script/main.js
format_marks:
  - "[必须改]"
  - "[建议改]"
  - "[可选]"
  - "[无问题]"
constraints:
  - no_preamble
  - no_summary
  - no_followup_question
  - no_heading
  - each_item_three_parts
  - minimal_patch
  - no_hallucination
  - no_vague
---

纯知识包：不挂钩子，SKILL.md 由框架在 selected 命中时直接注入。

`constraints` 是封闭枚举，展开成注入块末尾的【禁止】/【要求】；未知枚举会在启动时报 problem。
`format_marks` 声明输出使用的分级标记（任一即算用了格式），钩子 B 会检查并记 trace。
