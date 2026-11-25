---
id: skill_hub
name: 技能中心（自动判断）
description: 把所有技能规范注入本轮，由模型自己判断该用哪个
version: 1.0.0
hooks: [input]
inject: none
capabilities:
  resource.read: 40
entry: script/main.js
---

钩子 A。枚举带 SKILL.md 的模块，全量注入，判断权交给模型。
