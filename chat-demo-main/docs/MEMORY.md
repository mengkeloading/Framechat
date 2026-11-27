# 记忆系统设计

> 仿人脑的记忆：近期完整、远期抽象、常用清晰、久远模糊但可回溯。

## 一、总览

四层：

- 对话层：messages（最近 N 轮原文）
- 标签层：tags（强度 / 深度 / 关联）
- 压缩层：tag_summaries（L1-L4 + 种子）
- 画像层：user_profile（你是谁）

## 二、数据模型

### messages

加一列：

- tagId  TEXT（可空）——该消息所属标签

### tags

- id                TEXT PRIMARY KEY
- name              TEXT NOT NULL
- embedding         BLOB NOT NULL（质心向量）
- summary           TEXT（当前 L1 摘要，快速读）
- strength          REAL 默认 1.0
- readCount         INTEGER 默认 0
- negativeCount     INTEGER 默认 0
- currentLevel      INTEGER 默认 0（0=原文，1..4）
- lastReadAt        INTEGER
- lastDecayAt       INTEGER
- lastCompressedAt  INTEGER
- createdAt         INTEGER（v19 已有）

### tag_summaries（压缩链 + 种子）

- id          TEXT PRIMARY KEY
- tagId       TEXT NOT NULL
- level       INTEGER NOT NULL（1..4）
- text        TEXT NOT NULL
- sourceRefs  TEXT NOT NULL（JSON 数组）
- createdAt   INTEGER NOT NULL
- 唯一约束：(tagId, level)

sourceRefs 的两种形态：

- level=1 时：["msg:<id>", "msg:<id>", ...]   指向原文消息
- level>=2 时：["sum:<summaryId>"]            指向下一层摘要

### tag_links（横向关联）

- fromTag    TEXT NOT NULL
- toTag      TEXT NOT NULL
- coCount    INTEGER 默认 0
- updatedAt  INTEGER
- 主键：(fromTag, toTag)

### user_profile

- id         INTEGER PRIMARY KEY CHECK (id = 1)
- json       TEXT（完整画像）
- version    INTEGER
- updatedAt  INTEGER

### profile_history

- id, field, oldValue, newValue, evidence, reason, createdAt

## 三、一条必须记住的话

tag_summaries.tagId 刻意不加外键。

理由：加 FK 意味着删标签要连带删摘要，而未来"合并标签""导出标签"时
级联删会误伤。代价是可能留孤儿行。

所以：删标签前必须先调 TagSummaryDao.deleteAllOf(tagId)，再删 tags 行。
实施删除功能时别忘。

## 四、核心算法

### 4.1 衰减（艾宾浩斯，指数）

公式：

    strength(t) = strength * e^(-days / τ)

    τ = 7 + 10 * strength + 3 * readCount

其中 τ 的单位是天，表示"这件事能记住多久"。

为什么用指数而不是线性：

    线性：每天掉固定量 → 30 天后突然归零（不自然）
    指数：先快后慢，永远不到零 → 符合"不会彻底忘，只是难想起"

参照：艾宾浩斯遗忘曲线（1885），R = e^(-t/S)，
      所有"衰减速率正比于当前量"的过程都是这个形状。

### 4.2 层选择

displayLevel —— 决定"给哪一层"（按当前强度）

    strength >= 1.5  →  L1（200 字，保留结论和决策）
    strength >= 0.5  →  L2（100 字，只留要点）
    strength >= 0.2  →  L3（40 字，一句话）
    else             →  L4（12 字，等同标签名）

goalLevel —— 决定"何时压缩"（按强度和距上次压缩的时间）

    strength < 1.5 且 7  天未压  →  压到 L2
    strength < 0.5 且 14 天未压  →  压到 L3
    strength < 0.2 且 30 天未压  →  压到 L4

额外规则：

    新建 48 小时内不压缩（巩固窗口，神经科学：新记忆前 48 小时最脆弱）
    只往下压，不往上（压缩不可逆）
    到 L4 后不再压

### 4.3 强度事件

    召回命中        strength +0.3，readCount +1，negativeCount -1
    用户说否定词    strength -0.5，negativeCount +1
                     negativeCount >= 3 时强制把 strength 压到 0.5 以下
    用户删除消息    strength -0.3
    自然衰减        按 4.1 的指数公式
    用户手动恢复    strength 重置为 1.0

封顶：strength 最高 3.0
下限：strength 最低 0.0

### 4.4 召回

    if (strength < 0.25) return empty      // 强度闸门，低于此不主动召回
    if (currentLevel == archived) return empty
    score = cos(query, tag.embedding)
    if (score < 0.68) return empty          // 阈值比裸余弦
    按 score 降序，最多取 2 个

注意：阈值比的是"裸余弦"，强度只影响排序和后续选层，
      不参与"能否召回"的判断——除了 0.25 那道闸门。

## 五、参数表

| 参数 | 值 | 来源 / 理由 |
|---|---|---|
| 召回阈值 | 0.68 | 对话场景比文档 RAG（0.7-0.8）松 |
| 召回上限 | 2 | 标签数量少，多了反而干扰 |
| 强度闸门 | 0.25 | 低于此不主动召回，但目录里可见 |
| 归档阈值 | 0.1 | 低于此标记 archived |
| 强度上限 | 3.0 | 防止单个标签无限膨胀 |
| 初始强度 | 1.0 | — |
| boost | +0.3 | 一次召回约等于 1.7 次否定的抵消 |
| 负反馈 | -0.5 | 用户否定是强信号 |
| 删除回扣 | -0.3 | 比否定弱，因为删除可能只是清理 |
| 衰减 τ | 7 + 10×strength + 3×readCount | 艾宾浩斯 + 重要度加权 |
| 巩固窗口 | 48 小时 | 神经科学 |
| L1 / L2 / L3 / L4 | 200 / 100 / 40 / 12 字 | 金字塔比例 100/50/20/5% |
| 压缩间隔 | 7 / 14 / 30 天 | 越淡压得越慢（配合强度） |
| 整理间隔 | 12 小时 | 推荐系统常用批更新周期 |
| 画像提炼 | 每 50 轮 | — |
| 画像注入 | 40 字 | 每轮开销可忽略 |

## 六、注入格式

### 6.1 记忆目录（每次都给，成本极低）

模型需要知道"我有哪些记忆存在"，但不一定需要内容。

    【记忆目录】
    - MyChat 开发（12 条）
    - 记忆系统的设计（8 条）
    - 那个 bug 排查（4 条，正在淡化）

规则：

    按 strength 降序，最多 10 个
    strength < 0.5 的标"正在淡化"
    archived 的不出现在目录里（在设置页的归档区可见）
    每项成本约 15 字，10 项 = 150 字

### 6.2 召回命中时的注入

一次只给 1 个完整内容，其余只给名字。

    【记忆】MyChat 开发

    用户在做一个模块化聊天 App，技术栈 Kotlin，
    讨论了模块粒度设计，倾向按业务域拆分。
    未解决：记忆的冲突处理。

    相关内容：模块系统 / 记忆系统 / 那个 bug 排查

    （要看哪个，说它的名字）

规则：

    主标签 = 召回分数最高的那个，给 displayLevel 对应的内容
    其余标签只出现在"相关内容"里（只有名字，无内容）
    "相关内容"按 coCount（共现次数）降序，最多 5 个
    总长度控制在 300 字以内

### 6.3 为什么是"1 完整 + N 名字"

    如果给 2 个完整内容 → 400-600 字，挤占预算
    如果只给 1 个 → 模型可能觉得信息不够
    1 完整 + N 名字 = 模型知道"还有别的"，需要时可以要

人类的记忆也是这样：想起一件事，同时隐约知道"还有相关的"，
但那些相关的具体内容，要再想一下才出来。

## 七、逐层展开协议

### 7.1 纵向：往更细的层走

模型输出标记：

    [[!EXPAND:MyChat 开发]]
    或
    [[!EXPAND:tagId]]

框架处理：

    1. 找到该标签
    2. 当前层往上退一层（L3 → L2，L2 → L1，L1 → L0 原文）
    3. 下一轮注入时用更细的层

限制：

    单轮最多展开 2 次（防止模型"到处翻"）
    到 L0 后不能再展开（到顶）
    每次展开记日志：expand:tag=<id> from=<Lx> to=<Ly>

### 7.2 横向：跳到关联标签

模型输出：

    [[!EXPAND:记忆系统]]      （如果"记忆系统"在相关内容列表里）

框架处理：

    找到该标签 → 按 6.2 的格式注入它的内容

限制：

    和纵向共用"单轮 2 次"的限制
    横向跳转不改变原标签的层

### 7.3 用户也可以触发

用户说"展开记忆系统"或"看看那个 bug"。

A 钩子识别这类意图，等价于模型输出 [[!EXPAND]]。

### 7.4 一个完整的展开链

    第 1 轮：用户问"MyChat 现在做到哪了"
      注入：【记忆】MyChat 开发（L2 内容）
            相关内容：模块系统 / 记忆系统

    第 2 轮：模型觉得需要细节
      输出：[[!EXPAND:记忆系统]]
      注入：【记忆】记忆系统（L2 内容）
            相关内容：标签化 / 逐层回忆 / MyChat 开发

    第 3 轮：模型继续深入
      输出：[[!EXPAND:逐层回忆]]
      注入：【记忆】逐层回忆（这里是 L2，因为该标签没有更细的）
            （它没有关联标签）

    第 4 轮：模型决定往回跳
      输出：[[!EXPAND:MyChat 开发]]
      注入：回到 MyChat 开发，但这次可以给 L1（更细一层）

注意：每次 EXPAND 都让目标标签"再深一层"，
      所以反复展开同一个标签会逐渐逼近原文。
