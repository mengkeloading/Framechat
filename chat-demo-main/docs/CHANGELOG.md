# 变更日志

> 每条记录：改了什么 / 为什么 / 影响面。事故单列。

## 事故（不要重犯）

| 事故 | 根因 | 教训 |
|---|---|---|
| 全屏遮罩吃掉所有点击 | 给 `#modMask` 强写 `pointer-events:auto` | 不知道原样式就别用 !important 覆盖 |
| 上下文弹窗飘到半空+双窗口 | 用 `!important` 改 `#ctxPop` 的 `bottom` | 覆盖定位属性前先读原规则 |
| 本地计算完全不生效 | 正则字面量跨行 → SyntaxError → fail-open | 正则必须单行；加 modcheck |
| 三轮排查"skill 不生效" | 模块没在面板启用 | 先确认启用，再怀疑代码 |

## 框架级改动

| 版本 | 改动 | 影响 |
|---|---|---|
| v1 | 钩子 A/B + fail-open 契约 | 106 条断言 |
| v2 | 模块包 skill 风格（MODULE.md + reference） | 加模块不再改框架 |
| v3 | HostBridge 能力模型 + 配额 + 审计 | 模块越权被拦 |
| v4 | `resource.read` 打通（build 内联 reference） | skill 装载成为可能 |
| v5 | dispatcher 支持 local 短路 | 0 请求路径 |
| v6 | modcheck 启动自检 | 模块错误不再静默 |
| v12 | Kotlin 侧：模块树抽象（文件/assets）+ 注入 ModuleKit | 19 个模块可在 App 内运行 |
| v12 | 硬约束 7：`ctx.messages` 与 `data.session` 是两个数据源 | 接 App 时两者都要传 |
| v13 | 定位文档确立：docs/POSITIONING.md 为功能取舍唯一依据 | A/B 两侧精修层的边界写明 |
| v13 | 模块只维护 assets 一份，demo 冻结 | 同步断言改为 assets 自身健康度，demo 侧不再参与断言 |
| v14 | 硬约束 9：R8 后必须独立验证模块是否生效 | 反射方法被改名 → fail-open 静默离线，界面无提示 |
| v16 | USED 白名单校验 + 提示措辞加固 | 模型复述系统提示里的占位符 `[[USED:模块id]]` 会被误解析为声明；修法：只采信「已加载模块 id 列表」内的值，提示从占位符改为真实例子 |

## 模块层新增

| 模块 | 类型 | 说明 |
|---|---|---|
| deep-think | 钩子A | 「深想：」注入思考框架 |
| skill-novel | 钩子A + resource.read | skill 装载样板 |
| skill-code | 纯知识包 | 代码审查规范 |
| skill-hub | 钩子A | 全量注入，判断交给模型 |
| local-calc | 钩子A/local | 算数/进制/百分比/温度/单位，0 请求 |
| no-fluff | 钩子B/rewrite | 剪掉末尾套话 |
| debug-ctx | 钩子A/local | 「诊断ctx」回报信封字段 |
| scroll-fab | UI | 跳到底部按钮 |

## 待办（有依赖顺序）

1. `ctx.attachments` 透传 —— 改 dispatcher
2. `data.session` 能力 —— 模块按 sessionId 读会话数据
3. URL 预取 —— 框架先抓，模块读 `ctx.fetched`
4. `model.embed` 能力 —— 端侧 embedding 路由
5. Kotlin 编译验证 —— 最高优先级
