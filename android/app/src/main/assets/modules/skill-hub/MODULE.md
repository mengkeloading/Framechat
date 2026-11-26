---
id: skill_hub
name: 技能中心（自动判断）
description: 把所有技能规范注入本轮，由模型自己判断该用哪个
version: 1.0.0
hooks: [input]
inject: none
capabilities:
  skill.read: 40
entry: script/main.js
---

钩子 A。枚举带 SKILL.md 的模块，全量注入，判断权交给模型。

> 暂时停用：兜底逻辑已移到 App 的 ModuleSelector（selected = 所有 hasSkill）。
> 它进不了 selected，保留脚本供将来需要「一次注入全部正文」时复用。
