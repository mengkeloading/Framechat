# 框架契约（唯一依据）

> 本文件是模块与框架之间的契约。改框架必须同步改这里。

## 1. 钩子点

| 钩子 | 时机 | 可用返回 |
|---|---|---|
| A (input) | 用户输入后、组包前 | `pass` / `local` / `context` |
| B (output) | 收到完整回复后、落库前 | `pass` / `rewrite` / `invoke` |

**没有第三个钩子点。** 要新增必须同时改 dispatcher + 补断言 + 更新本表。

## 2. 信封格式

```json
{"text": "用户输入", "phase": "a" | "b", "ctx": {...}}
```

裸字符串视为 `phase=a`、`ctx={}`。

**当前 ctx 字段**：`sessionId`、`session`、`attachments`、`messages`（组包用的历史，来源 historyBase，最多 20 条）。
- `ctx.input` 只在钩子 B 出现
- `ctx.attachments` **不存在** —— 模块看不到附件内容

## 3. 返回值

| 类型 | 阶段 | 字段 | 效果 |
|---|---|---|---|
| `local` | A | `text` | 短路，不联网 |
| `context` | A | `text`, `extra` | `extra` 变成一条 system |
| `rewrite` | B | `text` | 替换模型输出 |
| `invoke` | B | - | 触发工具 |

返回 `null` = pass。

## 4. HostBridge 能力清单（自动扫描）

| 能力 | 说明 |
|---|---|
| `clock.now` | 当前时间 |
| `data.session` | 当前会话元数据 + 最近 20 条消息（依赖 `window.__currentId`） |
| `resource.read` | 读 reference 下的文件 |

声明方式：

```yaml
capabilities:
  resource.read: 3
```

未声明就调用 → 抛异常 → fail-open 当 pass。

## 5. 硬约束（踩过的坑）

1. **HostBridge 必须同步**。模块无法 await，写成 async 会报 `.trim is not a function`。
2. **`reasoning_content` 不能回传**。requestbuilder 只复制 `{role, content}`，天然安全。
3. **tools 只能来自 manifest**。用其他来源冒充会 422。
4. **有 tool_calls 的轮次禁止钩子 B 改写**，否则断链。
5. **模块必须先启用**，否则 dispatcher 不遍历。
6. **JS 正则不能跨行**，否则 `new Function` 语法错误 → fail-open 静默失效。
7. **`ctx.messages` 来自 `dispatchInput(session, text, history)` 的第三参数**，不是 `SessionData` 快照。两处数据源分离是有意的：`ctx.messages` 是这一轮组包用的历史，`data.session` 能力给的是宿主快照。接入 App 时**两个都要传**，否则模块看到的历史为空且不报错。

## 6. 模块包格式

```
modules/<目录名>/
  MODULE.md          front matter + 说明
  script/main.js     一个函数表达式 function (inputJson, host)
  reference/*        知识文件，经 resource.read 读取
```

## 7. 现有模块（自动扫描）

| id | 名称 | hooks | inject | capabilities |
|---|---|---|---|---|
| `analyze_chat` | 对话分析 | [] | none | - |
| `attach_context` | 附加要求 | [input] | none | - |
| `code_tag` | 代码块标注 | [output] | summary | - |
| `debug_ctx` | 上下文诊断 | [input] | none | - |
| `deep_think` | 深度思考 | [input] | none | - |
| `formatter` | 格式化 | [output] | none | - |
| `local_calc` | 本地计算 | [input] | none | - |
| `long_fold` | 长回复折叠 | [output] | none | - |
| `math_guard` | 计算守门 | [input] | none | - |
| `no_fluff` | 去套话 | [output] | none | - |
| `router` | 路由 | [output] | none | - |
| `simp-module` | 排版说明 | [] | full | - |
| `skill_code` | 代码审查 | [] | none | - |
| `skill_hub` | 技能中心（自动判断） | [input] | none | resource.read:40 |
| `skill_novel` | 小说写作（skill 装载） | [input] | none | resource.read:3 |
| `task_rewrite` | 任务改写 | [output] | none | - |
| `time_local` | 本地时间 | [input] | none | - |
| `weekly_reporter` | 周报排版 | [output] | summary | resource.read:5 |
