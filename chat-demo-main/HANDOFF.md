# MyChat 交接说明

> 一页纸讲清：这是什么、验过什么、怎么继续。

---

## 1. 这是什么

一个 Android 原生聊天 App 的**已验证框架 + 可运行 JS 原型**。

- **真实的 App 还没写** —— Kotlin 需要 PC（JDK 17 + Android SDK）
- 但**框架的每一条规则都被 74 条断言 + 真实 API 验证过**
- Kotlin 阶段的工作是"照抄已验证的行为"，不是重新设计

---

## 2. 箱子里有什么

| 文件 / 目录 | 作用 |
| --- | --- |
| `ARCHITECTURE.md` | **权威文档**，25 节约 900 行。CURSOR 第一句就要读它 |
| `CURSOR.md` | 11 批下发清单 + 第 1 批完整提示词 |
| `modules/` | 11 个模块包（skill 风格：MODULE.md + script/ + reference/） |
| `js/` | 运行时 13 个文件 + 5 个测试文件 |
| `mychat-demo.html` | 单文件产物，含 74 条断言 + 真接口诊断面板 |
| `ui-preview-v2.html` | UI 对照物（10 个场景，含官方数值） |
| `build.py` | 打包器：扫 modules/ + 拼单文件 + 自检 |
| `status.py` | 只读体检 |

手机侧唯一入口（在 QPython 目录）：`mychat.py` → 选 1 打包 / 选 2 体检。

---

## 3. 已经验证过什么

### 离线断言 74 条

| 组 | 条数 | 覆盖 |
| --- | --- | --- |
| 框架核心 | 30 | 钩子 A/B、tempId、tool 循环、SSE 解析、invoke 深度、fail-open |
| L1 能力模型 | 9 | 模块包扫描、front matter、渐进披露、配额、审计、沙箱隔离 |
| 框架缺口 | 12 | 预算裁剪、取消、重试、DAO 保留字段、配对裁剪 |
| 多会话隔离 | 9 | 消息/上下文/预算/状态按会话隔离 |
| 模块库扩充 | 10 | 3 个新包零改动接入 |

### 真实 API 验证

| 用例 | 结果 |
| --- | --- |
| 普通消息 | 首字节 240–470ms，流式正常 |
| 附加：xxx | 请求体多一条 system，模型接受 |
| 执行任务 xxx | 钩子 B 改写，气泡 = 验收文案 |
| 现在几点 | 0 次请求，本地截留 |
| 6 轮多轮 | 请求消息收敛到 4 条，预算生效 |

### 真接口发现的 6 个 bug（离线测不到）

假 tools 导致 422 / tool content 为 null / tool 轮次被改写断链 /
预算保底值吞配置 / DAO 白名单丢字段 / 裁剪切断问答对 —— 全部已修。

---

## 4. 怎么在手机上继续

1. QPython 跑 `mychat.py` → 选 1 打包
2. 浏览器打开 `/storage/emulated/0/mychat-demo.html`
3. 【验收】页看断言；右下「真接口诊断」测真 API

**注意**：
- 改完 `js/` 或 `modules/` **必须重新打包**
- Via 会缓存 `file://`，长按刷新 → 忽略缓存
- 多文件版（tests.html / index.html）在此环境**永远打不开**，只能跑单文件产物

---

## 5. 怎么搬到 PC 继续

1. 把整个 `chat-demo-main` 复制到 PC（或推 GitHub 再 clone）
2. Cursor 打开，第一句：

   > 读 ARCHITECTURE.md，然后只实现 data/ 下的 5 个文件（§5 序号 1），
   > 严格按 §13.1 的模块包结构和 §13.7 的测试清单，不要扩展职责。

3. 按 `CURSOR.md` 的 11 批顺序推进，**一次一批**
4. 每批完成后跑 `ARCHITECTURE` §7 / §13.7 对应的单测

---

## 6. 移植必读：7 条已知偏离

demo 与文档不一致的地方，**以文档为准**：

| # | demo | Kotlin |
| --- | --- | --- |
| 1 | `local: Boolean` | `source: String`（model/local/reflex） |
| 2 | ChatUiState 全局单例 | `Map<sessionId, ChatUiState>` |
| 3 | 脚本以函数字符串注册 | assets 读 .js 文本交沙箱 |
| 4 | 变量遮蔽模拟沙箱 | JavaScriptSandbox / QuickJS |
| 5 | `<script>` 加载模块 | assets/modules/<folder>/script/*.js |
| 6 | 软模块内容是 js 常量 | assets/modules/soft/*.md |
| 7 | DAO 内存数组 | Room，白名单字段须跟随实体扩展 |

---

## 7. 下一步优先级

| 优先级 | 内容 |
| --- | --- |
| 1 | Kotlin 骨架：data → ErrorMapper → ModuleLoader → JsExecutor → SseParser → ApiClient → RequestBuilder → ModuleDispatcher → ChatViewModel |
| 2 | UI 落地：照 §21.5 冻结 tokens + §21.6 官方数值 |
| 3 | v1.1：反射弧（§15）、模块启停 UI、消息操作行、深度思考区 |
| 4 | v1.3：Python 运行时 / skill 互通（§22） |

---

## 8. 一句话总结

**框架已被 74 条断言和真实 API 双重验证；剩下的全部工作在 PC 上。**
