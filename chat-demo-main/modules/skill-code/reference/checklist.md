# 代码审查清单

## 正确性
- `!!` / `lateinit` 能否换成 `?.` / `?:` / `requireNotNull`
- `catch (e: Exception) {}` 是否静默吞异常
- 集合遍历中是否有并发修改
- 协程作用域：`GlobalScope` 一律标记为必须改
- 挂起函数是否在 `withContext` 里做了阻塞 IO

## 可读性
- 函数是否超过 30 行（超过则建议拆）
- 参数是否超过 4 个（超过则建议用 data class）
- 布尔参数是否在调用处看不出含义
- 命名是否表达意图（`data`、`tmp`、`flag` 不合格）

## Compose 专项
- `mutableStateOf` 是否在 `remember {}` 里
- `LaunchedEffect` 的 key 是否齐全
- `Modifier` 顺序是否把 `padding` 放在 `background` 之前
- 是否在重组中做了重活（应移到 `remember` 或副作用）

## 性能
- 循环内是否重复创建对象
- 大列表是否缺 `key`
- 是否在频繁路径上用了反射或正则编译
