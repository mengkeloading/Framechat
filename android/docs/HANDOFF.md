# MyChat 交接单

> 读者：未来的自己 / 接手的 AI / 协作者。
> 原则：只写事实和约束，不写愿景。

## 0. 一分钟了解

做一个 Android 聊天 App，差异化是**模块（钩子）体系**——
在「用户输入」和「大模型」之间插一层可编程中间层。

两条并行的线：

| 线 | 状态 | 用途 |
|---|---|---|
| JS demo | ✅ 契约已验证 | 快速验证设计，手机上可跑 |
| Kotlin App | ❌ 未编译 | 最终产品，fork 自 Simple-Chat |

**demo 的使命已结束**，剩下只修 bug。新能力一律进 Kotlin。

## 1. 路径

手机上两个工程都在 `/storage/emulated/0/demo/` 下；下表的相对路径
以各自工程根为基准。

| 内容 | 路径 |
|---|---|
| JS demo | chat-demo-main/ |
| demo 产物 | chat-demo-main/dist/（新包换 vNN 文件名绕缓存） |
| Kotlin 工程 | android/ |
| 上游参考 | Simple-Chat-main/Simple-Chat-main/ |
| QPython 脚本目录 | /storage/emulated/0/Android/data/org.qpython.qpy/files/ |

注意：这两个工程目录**都不是 git 仓库**（没有 .git）。

## 2. 已验证的能力（demo 实测）

| 能力 | 证据 |
|---|---|
| 钩子 A：pass / local / context | 真接口 4 用例 |
| 钩子 B：pass / rewrite / invoke | no-fluff / formatter |
| fail-open | local-calc 语法错时对话未崩 |
| skill 装载（reference 内联） | SKILL_OK 验证通过 |
| 多技能判断交给模型 | 3 技能四路分流全对 |
| 本地短路 0 请求 | 算 12*37 秒回无流式 |
| 106 条断言 | 验收页 PASS（js/tests 7 个套件，106 次 check 调用） |
| 启动自检 modcheck | 通过 |

## 3. 契约（不可随意改）

| 项 | 内容 |
|---|---|
| 钩子点 | 只有 A（input）/ B（output） |
| 信封 | {text, phase, ctx} |
| ctx 字段 | sessionId, session, attachments, messages |
| messages 来源 | historyBase（组包用历史，不含本轮输入） |
| 能力 | resource.read / clock.now / data.session |
| 失败策略 | 异常一律 fail-open 当 pass |
| 包格式 | MODULE.md + script/main.js + reference/ |

## 4. 六条硬约束（违反会出事故）

1. HostBridge 必须同步。模块无法 await。
2. reasoning_content 不能回传。requestbuilder 只复制 role/content。
3. tools 只能来自 manifest。其他来源冒充会 422。
4. 有 tool_calls 的轮次禁止钩子 B 改写。
5. 模块必须先在面板启用，否则 dispatcher 不遍历。
6. JS 正则不能跨行，否则语法错误后 fail-open 静默失效。

## 5. 踩过的事故（不要重犯）

| 事故 | 根因 |
|---|---|
| 全屏遮罩吃掉所有点击 | 给 modMask 强写 pointer-events:auto |
| 弹窗飘到半空 + 双窗口 | 用 !important 改 ctxPop 的 bottom |
| 本地计算完全不生效 | 正则跨行导致 SyntaxError |
| 三轮排查 skill 不生效 | 模块没在面板启用 |
| messages 恒为空 | 探测正则 this.list 误匹配 this.listeners |

**共同教训：不知道原样就别用 !important 覆盖；改之前先读。**

## 6. UI 状态

**已冻结**（§21.8）。不要再在 demo 上做 UI 交互和像素对齐。

| 遗留 | 归属 |
|---|---|
| 抽屉动画不同步 | Compose 用 Column 天然解决 |
| 会话补充提示词无输入框 | Kotlin 侧 |
| 附件上传不可用 | Via 下 file:// 限制，Kotlin 侧 |

## 7. 工作流

改 demo 的标准动作：

```
① 拿到一次性 Python 补丁脚本（放 QPython 目录，跑 1.py）
② 脚本自动备份 .bak 并改文件
③ 脚本调 build.py 打包
④ Via 打开 dist/MyChat-vNN.html（换文件名绕缓存）
⑤ 发「诊断ctx」或点验收页确认
```

改代码时必须：

| 规则 | 原因 |
|---|---|
| 先读再改 | 盲改已翻车 3 次 |
| 一次只改一个点 | 便于定位 |
| 改框架必须更新 FRAMEWORK.md + CHANGELOG.md | 防止失控 |
| 每行代码 < 78 字符 | 手机粘贴会硬折行 |
| 正则一律单行 | 跨行 = SyntaxError |

## 8. Kotlin 侧现状

新增的钩子子系统（不含 fork 自带代码）：

| 项 | 数值 |
|---|---|
| 生产代码 | 22 个文件 / 1650 行（core/ 11 个 837 行，hook/ 10 个 743 行，app/EngineBridge 70 行） |
| 测试 | 13 个文件 / 1128 行 |
| JVM 可跑测试 | 80 条 @Test |
| @Ignore 骨架 | 0 条 |
| 编译次数 | 0 |

整个工程共 227 个 main 源文件、112 个测试文件（含 fork 自带）。

**1650 行是未验证资产。** 这是当前最大的风险。

## 9. 下一步（按顺序，不要跳）

| # | 动作 | 验收标准 |
|---|---|---|
| 1 | AiCode / Termux 里装 kotlinc，逐文件编译 core/ 和 hook/ | 纯逻辑文件全部编译通过 |
| 2 | 初始化 git 并 push 到 GitHub（当前无 .git） | 仓库能打开 |
| 3 | 补 .github/workflows/ 编译流程再触发 Actions | 拿到真实报错清单 |
| 4 | 同步契约：ctx.session / attachments / messages 进 HookSession | 与 demo 一致 |
| 5 | （保底）Capacitor 包装现有 HTML 出 APK | 1-2 天可上架 |

**第 1 步是重点**：它把编译反馈从 10 分钟降到 1 分钟，能拦掉约 80% 的错。

第 4 步注意：docs/CONTRACT-DIFF.md 里 `ctx.session` 标了 YES，那是关键词
扫描的误判（HookSession 只有 id 和 systemPrompt），实际三项都缺。

## 10. 文档索引

| 文件 | 用途 |
|---|---|
| chat-demo-main/docs/FRAMEWORK.md | 契约总览（唯一依据） |
| chat-demo-main/docs/CHANGELOG.md | 变更日志 + 事故表 |
| chat-demo-main/docs/FILEMAP.md | 改功能前先查这张表 |
| chat-demo-main/docs/REPORT.md | 定位、竞争力、决策记录 |
| chat-demo-main/docs/DEMO-FREEZE.md | demo 冻结说明 + 不要再做的事 |
| android/docs/CONTRACT-DIFF.md | demo 与 Kotlin 的契约差异（自动扫描，会误报） |
| chat-demo-main/docs/MODULE-COOKBOOK.md | 加模块的模板（自动生成） |
| android/docs/ROADMAP.md | 端侧模型方案 |
| chat-demo-main/ARCHITECTURE.md | 架构长文（900+ 行） |

## 11. 一句话

> demo 验证了**契约可行**；Kotlin 要证明**它能构建**。
> 在能构建之前，不要加任何新功能。
