# 契约差异（demo -> Kotlin）

> 自动扫描生成。YES = Kotlin 侧已有对应字段/分支。

| 契约项 | Kotlin 侧 | 命中关键词 |
|---|---|---|
| ctx.sessionId | **YES** | sessionId |
| ctx.session | **YES** | session: |
| ctx.attachments | **NO** | - |
| ctx.messages | **NO** | - |
| resource.read | **YES** | resource.read, reference |
| local 短路 | **YES** | "local", Local |
| rewrite | **YES** | "rewrite", Rewrite |
| fail-open | **YES** | catch |

## 同步顺序（重要）

1. **先编译基线** —— 确认当前 2100 行能构建，拿到锚点。
2. **再逐项同步** —— 每同步一项就编译一次，避免错误混杂。
3. **最后对齐模块包** —— assets/modules 与 demo 的 modules/ 保持一致。

## 禁止事项

- 不要在同步期间修改已有 API 签名（会连锁破坏 66-69 条测试）。
- 不要一次同步多项（编译错误无法定位）。
