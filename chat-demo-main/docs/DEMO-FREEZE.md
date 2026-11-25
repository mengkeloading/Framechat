# DEMO 冻结说明

> 本 demo 的使命是**验证框架契约**，已完成。
> 后续只修 bug，不加功能。新能力一律进 Kotlin。

## 已冻结的契约

| 项 | 内容 |
|---|---|
| 钩子点 | A（input）/ B（output），只有这两个 |
| 返回值 | A: pass/local/context；B: pass/rewrite/invoke |
| 信封 | {text, phase, ctx} |
| ctx 字段 | sessionId, session, attachments, messages |
| messages 来源 | historyBase（组包用历史，不含本轮输入） |
| 能力 | resource.read / clock.now / data.session |
| 失败策略 | fail-open，异常一律当 pass |
| 包格式 | MODULE.md + script/main.js + reference/ |

## 已验证的核心能力

1. skill 文件夹直接变模块（SKILL_OK 验证）
2. 多技能判断交给模型（3 技能四路分流全对）
3. 本地短路 0 请求（local-calc 五类能力）
4. 钩子 B 改写输出（no-fluff / formatter）
5. 会话配置可读（session.systemPrompt 链路通）
6. 历史可读（ctx.messages 实测 6 条）

## 模块清单（自动扫描）

| id | 名称 | hooks | reference 文件数 |
|---|---|---|---|
| `analyze_chat` | 对话分析 | [] | 0 |
| `attach_context` | 附加要求 | [input] | 0 |
| `code_tag` | 代码块标注 | [output] | 0 |
| `debug_ctx` | 上下文诊断 | [input] | 0 |
| `deep_think` | 深度思考 | [input] | 0 |
| `formatter` | 格式化 | [output] | 0 |
| `local_calc` | 本地计算 | [input] | 0 |
| `long_fold` | 长回复折叠 | [output] | 0 |
| `math_guard` | 计算守门 | [input] | 0 |
| `no_fluff` | 去套话 | [output] | 0 |
| `router` | 路由 | [output] | 0 |
| `simp-module` | 排版说明 | [] | 0 |
| `skill_code` | 代码审查 | [] | 2 |
| `skill_hub` | 技能中心（自动判断） | [input] | 0 |
| `skill_novel` | 小说写作（skill 装载） | [input] | 2 |
| `skill_product` | 需求拆解 | [] | 2 |
| `task_rewrite` | 任务改写 | [output] | 0 |
| `time_local` | 本地时间 | [input] | 0 |
| `weekly_reporter` | 周报排版 | [output] | 1 |

## 技能清单（有 reference 的模块）

- **代码审查**（skill_code）
- **小说写作（skill 装载）**（skill_novel）
- **需求拆解**（skill_product）
- **周报排版**（weekly_reporter）

## 已知限制（不是 bug，是环境/设计边界）

| 限制 | 原因 | 归属 |
|---|---|---|
| 附件上传不可用 | Via 下 file:// 限制 | Kotlin 侧实现 |
| `data.session`（走 db）不可用 | db 全局名未知 | Kotlin 侧用 DAO |
| 技能是"全量注入"而非按需加载 | 浏览器里跑不了 embedding | P2 端侧模型 |
| 深度思考只看不判 | 无差异化，已砍 | 不做 |
| UI 抽屉动画不同步 | 两套定位机制 | Compose 里用 Column 天然解决 |
| 会话补充提示词无输入框 | UI 工作 | Kotlin 侧 |

## 不要在 demo 上再做的事

- 加 UI 交互（都要在 Compose 重写）
- 加新的钩子点（改框架，Kotlin 要同步）
- 追像素级对齐（已冻结在 §21.8）

## 下一步

阶段一：GitHub Actions 首次编译。
目标只有一个：**确认这个 fork 能构建**。不在这期间加任何功能。
