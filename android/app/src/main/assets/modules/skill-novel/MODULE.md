---
id: skill_novel
name: 小说写作（skill 装载）
description: 小说写作。用户说"帮我写个故事""写一段描写""续写""这个场景怎么写"时使用。擅长人物对白、节奏控制、意境营造。
version: 1.0.0
hooks: []
inject: none
capabilities:
  resource.read: 3
prefix: 小说
entry: script/main.js
---

钩子 A。命中「小说：」后读取 reference/SKILL.md，作为 system 注入。
