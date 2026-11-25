# MyChat Demo 文件地图

> **改功能前先查这张表。一个功能只对应一个文件。**
> 第三节的自动清单由 `ui_filemap.py` 生成。

## 一、我要改 X，去哪个文件

| 需求 | 主文件 | 关键位置 |
|---|---|---|
| 消息列表 / 输入框 / 滚动 | `js/ui/chatscreen.js` | render / scroll |
| 气泡与通栏样式 | `ui/shell.html` 的 CSS | - |
| Markdown 渲染 | `js/ui/md.js` | render() |
| 面板 / 上下文 / 蒙版 / 抽屉 | `js/ui/panel.js` **唯一入口** | openSheet / renderCtx |
| 模块包扫描与加载 | `js/module/moduleloader.js` | - |
| 钩子调度 A/B、fail-open | `js/module/moduledispatcher.js` | dispatchInput / dispatchOutput |
| 模块可用 API | `js/module/modulekit.js` | - |
| 模块沙箱执行 | `js/executor.js` | resolve() |
| HostBridge / 审计 | `js/bridge/hostbridge.js` | - |
| 请求组包 / 预算裁剪 | `js/network/requestbuilder.js` | - |
| 上下文压缩 | `js/network/compactor.js` | KEEP_TAIL |
| SSE 解析 | `js/network/sseparser.js` | - |
| 供应商 / URL | `js/network/providers.js` | - |
| HTTP 调用 | `js/network/apiclient.js` | - |
| 错误提示文案 | `js/error/errormapper.js` | - |
| 数据表 / DAO | `js/data/daos.js` | - |
| 偏好与持久化 | `js/data/persist.js` | - |
| 会话状态机 | `js/vm/chatviewmodel.js` | - |
| 启动装配 / 全局事件 | `js/boot.js` | - |
| 导出 / 朗读 | `js/ui/export.js` `js/ui/tts.js` | - |
| 跳到最新按钮 | `js/ui/scrollfab.js` | mount() / refresh() |
| 模块健康检查 | `js/module/modcheck.js` | run() / checkOne() |
| 断言 | `js/tests/*.js` | 一文件一域 |

## 二、已知重复（待合并，合并前别在这两处改同一功能）

| 函数 | 位置 | 计划 |
|---|---|---|
| `renderCtx()` | extra.js L477 / panel.js L272 | 删 extra.js 那份 |
| `openSheet()` | panel.js L40 / boot.js | 保留 panel.js |
| `openDrawer()` | extra.js / boot.js | 保留 boot.js |
| `toast()` | extra.js / panel.js | 只留一个 |

## 三、新增功能的三条规矩

1. 新功能 = **新文件**，不往 `extra.js` 里堆。
2. 新文件要在 `build.py` 的加载列表加一行（位置决定依赖顺序）。
3. 在本表「一」里加一行，否则下一个人（或下次的你）找不到。

## 四、自动清单

| 文件 | 行数 | 导出 |
|---|---|---|
| `js/boot.js` | 246 | `__closeDrawer`, `__currentId`, `__db`, `__newSession`, `__persist`, `__refreshSessions`, `__screen`, `__switchTo`, `__vm` |
| `js/bridge/hostbridge.js` | 63 | - |
| `js/data/daos.js` | 183 | - |
| `js/data/persist.js` | 65 | - |
| `js/error/errormapper.js` | 23 | - |
| `js/js/executor.js` | 55 | - |
| `js/module/moduledispatcher.js` | 111 | - |
| `js/module/modulekit.js` | 33 | - |
| `js/module/moduleloader.js` | 70 | - |
| `js/network/apiclient.js` | 78 | - |
| `js/network/compactor.js` | 53 | - |
| `js/network/providers.js` | 67 | - |
| `js/network/requestbuilder.js` | 114 | - |
| `js/network/sseparser.js` | 33 | - |
| `js/tests/api_check.js` | 246 | - |
| `js/tests/features_test.js` | 182 | - |
| `js/tests/l1test.js` | 127 | - |
| `js/tests/modules_test.js` | 116 | - |
| `js/tests/persist_test.js` | 117 | - |
| `js/tests/selftest.js` | 235 | - |
| `js/tests/session_test.js` | 131 | - |
| `js/tests/v1_gaps_test.js` | 161 | - |
| `js/ui/chatscreen.js` | 153 | - |
| `js/ui/export.js` | 20 | - |
| `js/ui/extra.js` | 505 | - |
| `js/ui/md.js` | 101 | - |
| `js/ui/panel.js` | 352 | `__panel`, `__panelReady` |
| `js/ui/tts.js` | 39 | - |
| `js/vm/chatviewmodel.js` | 218 | - |
