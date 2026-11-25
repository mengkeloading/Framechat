# 阶段 3：ViewModel 接线说明

## 已就绪

EngineBridge 把整条链路装配好：

    ChatViewModel -> EngineBridge.send(sessionId, prompt)
                        -> ChatEngine
                            -> RequestBuilder
                            -> OkHttpStreamSource
                            -> ModuleDispatcher
                            -> RoomMessageStore

## ViewModel 侧只需三段改动

### 1. 保存依赖

    private val engine = EngineBridge(
        db, registry, dispatcher, stream, tools, providers)

### 2. send 方法替换

    fun send(prompt: String) {
        viewModelScope.launch {
            runCatching { engine.send(currentId, prompt) }
        }
    }

### 3. stop 方法

    fun stop() = engine.cancel()

## 界面如何刷新

两种做法：

1. 观察 engine.state（idle/streaming）切换 loading 态
2. 消息列表改为观察 Room 的 Flow

推荐第 2 种：Room 已经是唯一真相源，
ChatEngine 直接写库，UI 自动刷新。

## 需要补的 DAO 查询

    @Query("SELECT * FROM my_messages WHERE sessionId = :sid " +
           "ORDER BY timestamp ASC")
    fun observe(sid: String): Flow<List<MessageEntity>>

一条 Flow 就够，Compose 里 collectAsState。

## 与原实现并存

旧 ChatViewModel 保留，用开关切换入口：

    object EngineSwitch { var useNew = true }

新路径通过全部断言后再删旧路径。
