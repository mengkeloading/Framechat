# 阶段 1：db 层替换说明

## 结构

    com/simplechat/app/storage/
      Entities.kt          三张表
      Converters.kt        List<String> 转换
      Daos.kt              三个 DAO
      MyChatDatabase.kt    独立数据库 mychat.db
      SessionRepository.kt 级联删除与会话操作

他们的 `db/` 包完全不动。

## 为什么用独立数据库文件

1. 不需要处理两套 schema 的迁移对齐
2. 切换失败可以无损退回（删掉 mychat.db 即可）
3. 替换完成后一次性移除旧库

## 字段对照（我们的 vs 他们的）

| 我们的 | 用途 | 他们没有的 |
| --- | --- | --- |
| source | model / local / reflex | 需要新增 |
| pending | 流式占位标记 | 需要新增 |
| rewritten | 是否被钩子改写 | 需要新增 |
| stopped | 用户中止 | 需要新增 |
| contextBudget | 会话级预算 | 他们有全局设置 |
| activeHardModules | 钩子链 | 需要新增 |

注意：不要用 content 是否为空判断占位 ——
tool 轮次的 assistant 内容本来就是空的，必须用 pending。

## 三条查询排除（缺一不可）

    role NOT IN ('system', 'tool') AND pending = 0

system 是超限提示；tool 是本轮单独带；pending 是流式占位。

## 下一步

1. 写 ChatEngine 与 Room 的适配器（MessageStore 实现）
2. 加开关：useNewStorage = true/false
3. 双跑对比：同一轮对话两边落库结果一致
4. 断言全绿后删除旧 db 包

## 测试

Room 需要 Robolectric 或 instrumented test：

    testImplementation("org.robolectric:robolectric:4.13")
    android { testOptions { unitTests.isIncludeAndroidResources = true } }

待接入后覆盖：插入 / 更新 / 三条件上下文 / 级联删除。

## 适配器：RoomMessageStore

ChatEngine 只认识 MessageStore 接口，Room 通过适配器接入：

    MyChatDatabase -> RoomMessageStore -> ChatEngine

装配示例：

    val db = MyChatDatabase.get(context)
    val store = RoomMessageStore(db)
    val engine = ChatEngine(
        store = store,
        builder = RequestBuilder(moduleRegistry),
        stream = OkHttpStreamSource(client),
        hooks = ModuleDispatcherBridge(dispatcher),
        tools = SandboxToolRunner(executor),
        session = SessionMapper.toHookSession(entity)
    )

## 切换开关（建议）

    object StorageSwitch {
        var useNew = true
    }

两边都写库，对比一轮对话后的一致性；确认无误再删旧实现。

