# 架构 V3：库的插件化接入

> 目标：让第三方 Python 库像 skill 一样可装、可查、可卸载。
> 本文只定契约与边界，实现细节另写。

## 1. 核心原则

| 类别 | 例子 | 策略 |
|---|---|---|
| 纯 Python 库 | `requests`、`python-dateutil`、`pyyaml` | **可下载**，装到应用私有目录 |
| 含 native 扩展的库 | `numpy`、`pillow`、`lxml` | **只能打包**进 APK（要么不提供） |
| 系统级 / 重型运行时 | `pandas`、`matplotlib`、`playwright`、`ffmpeg` | **永不做** |

判据只有一条：**能不能只在用户装它的时候付一次下载成本，且不炸包体**。

## 2. 库包格式

```
libs/
  <pkg-id>/
    manifest.json
    <pkg-id>/            ← 真正的 Python 包目录（进 sys.path 的那一层）
      __init__.py
      ...
```

`manifest.json` 字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 唯一标识，与目录名一致 |
| `name` | string | 展示名 |
| `version` | string | 语义化版本 |
| `size` | number | 解压后字节数，用于体积统计与配额 |
| `sha256` | string | 安装包校验值，装前必验 |
| `requires` | string[] | 依赖的其他库 id（lib → lib） |
| `entry` | string | 包目录名（默认与 `id` 相同） |
| `provides` | string[] | 提供的 import 名，用于 skill → lib 解析 |

## 3. 存放与优先级

| 位置 | 用途 | 可写 |
|---|---|---|
| `assets/libs/` | 随 APK 内置的库 | 否 |
| `filesDir/libs/` | 用户下载 / 导入的库 | 是 |

同名库同时存在时，**`filesDir/libs/` 优先**（允许用户覆盖内置版本）。

## 4. 加载机制

只把**已安装**的库目录注入 `sys.path`，不预加载、不 import 任何东西：

```python
for pkg_dir in installed_lib_dirs():      # filesDir 优先，其次 assets 解出的副本
    sys.path.insert(0, str(pkg_dir))
```

装载是惰性的：模块被 import 时才真正生效。

## 5. 依赖解析（两级）

| 级 | 方向 | 方法 |
|---|---|---|
| ① | skill → lib | 扫描 skill 的 `scripts/*.py` 里所有 `import X` / `from X import`，与已装库的 `provides` 并集比对 |
| ② | lib → lib | 按 `requires` 递归展开，遇到环或缺失即标记为不可用 |

解析结果只用于**提示与拦截**，不在运行时动态装库。

## 6. 安装流程

| 来源 | 动作 |
|---|---|
| 内置 | 启动时把 `assets/libs/` 清单读进索引，包体本身已在 APK 里 |
| URL | 下载 → 校验 `sha256` → 解压到 `filesDir/libs/<id>/` → 刷新索引 |
| 文件导入 | 系统文件选择器拿 `content://` URI → 拷到私有目录 → 同上流程 |

⚠️ Android 分区存储：**不能"把文件夹拖进 app 目录"**。所有进入方式都必须走应用自己的写入路径（下载 / 文件选择器），目录不由用户直接编辑。

## 7. 体积策略

| 项 | 体积 | 取舍 |
|---|---|---|
| Python 运行时 + 标准库 | ~11 MB | **必装** |
| `numpy` | +15 MB | 可选打包；不做就明确告知 |
| 纯 Python 库 | 按需 | 下载，不计入基础包 |
| 重型库（pandas / matplotlib / playwright / ffmpeg） | — | **永不做**，UI 层直接不展示 |

## 8. 用户可见状态

| 标记 | 含义 | 用户可做什么 |
|---|---|---|
| ✅ 可用 | 依赖齐全、已安装 | 直接使用 |
| 🟡 缺依赖可下载 | 缺的库是纯 Python | 一键下载 |
| 🔴 依赖不支持 | 缺的是 native / 已排除的库 | 只能看说明，不能装 |

状态由第 5 节的静态解析算出，展示在库列表与 skill 详情页。

## 9. 实现顺序

| # | 阶段 | 产出 |
|---|---|---|
| 1 | 库包格式 + 目录扫描 | 能列出"装了哪些库、各自 provides 什么" |
| 2 | `sys.path` 注入 | 已装库能被 skill 里的 `import` 用上 |
| 3 | 依赖静态检查 | 能判 ①②两级，输出 ✅/🟡/🔴 |
| 4 | URL 安装 + sha256 校验 | 能真正装一个纯 Python 库 |
| 5 | UI（列表、状态、安装按钮） | 用户可见 |

前四步都能在无 UI 的情况下验证。

## 10. 与 skill 的一致性

| 维度 | skill（模块） | 库（lib） |
|---|---|---|
| 元信息 | `MODULE.md` front matter | `manifest.json` |
| 内容 | 脚本 + reference | Python 包目录 |
| 分发 | 打包进 assets | 内置或下载 |
| 依赖 | `needs`（能力型） | `requires` + `provides`（包型） |
| 加载 | Rhino 沙箱按需执行 | `sys.path` 惰性注入 |
| 用户操作 | 启用 / 停用 | 安装 / 卸载 |

两者共用同一套心智：**声明式元信息 + 按需加载 + 明确的可用性状态**。
