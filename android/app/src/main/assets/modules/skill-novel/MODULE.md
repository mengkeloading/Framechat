---
id: skill_novel
name: 小说写作（skill 装载）
description: 输入以「小说：」开头时装载 reference/SKILL.md 作为本轮规范
version: 1.0.0
hooks: [input]
inject: none
capabilities:
  resource.read: 3
entry: script/main.js
---

钩子 A。命中「小说：」后读取 reference/SKILL.md，作为 system 注入。
