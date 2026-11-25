# 路线图

## P0 端侧智能体（判断能力）

**目标**：内嵌 embedding 模型，50ms 内判断"这句话该用哪个技能"。离线、0 token。

| 层 | 方案 | 体积 | 状态 |
|---|---|---|---|
| L1 关键词打分 | 纯 Kotlin | 0 | 已实现（KeywordSkillRouter） |
| L2 端侧 embedding | bge-small-zh + ONNX | ~24MB | 骨架就绪，待接引擎 |
| L3 端侧小 LLM | Qwen2.5-0.5B | 300MB+ | 不做（收益 < 成本） |

**为什么不做 L3**：为"选技能"加载 500MB 模型，延迟 1-3s，耗电，收益低于 L2。

**L2 落地步骤**：
1. 下载 bge-small-zh-v1.5 int8 量化版 → app/src/main/assets/models/
2. 加依赖 com.microsoft.onnxruntime:onnxruntime-android:1.17.0
3. 实现 TextEmbedder（WordPiece 分词 + OnnxSession）
4. 技能向量预计算后存 Room，启动时加载
5. 阈值 0.55，低于则回退 KeywordSkillRouter

## 复用经验

- 模块必须先在面板启用，否则 dispatcher 不遍历它
- 模块不能联网，路由必须在框架层做
- tool 声明只能来自 manifest，用其他来源冒充会 422
