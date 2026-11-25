
## 阶段 2：net 层

| 文件 | 行数 | 性质 |
| --- | --- | --- |
| core/Providers.kt | ~70 | 纯逻辑，可单测 |
| core/RequestBodyJson.kt | ~35 | 纯逻辑，可单测 |
| net/OkHttpStreamSource.kt | ~75 | 唯一碰网络 |

### 为什么把请求体序列化单独拿出来

打网络的代码没法单测，但请求体形状必须验。
拆成 RequestBodyJson 之后，4 条断言可以直接跑。

### 与旧实现的边界

他们的 net/ 有 27 个文件 4581 行，包含多协议适配。
我们只实现 chat completions 一种，先跑通再说。

### 接入方式

    val source = OkHttpStreamSource(resolver)
    val engine = ChatEngine(
        store = RoomMessageStore(db),
        builder = RequestBuilder(registry),
        stream = source,
        ...
    )

resolver 的实现放在 storage/ 或 settings/，
从 SessionEntity + SettingsStore 拼出 ProviderConfig。
