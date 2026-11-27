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
