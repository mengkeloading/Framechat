# 组件清单

对照官方 DeepSeek Chat，标注 demo 现状。

| 组件 | 官方表现 | demo |
| --- | --- | --- |
| TopBar | ☰ / 居中标题 / 🔊 / ＋，52dp | 有 |
| MessageList | LazyColumn，左右 16dp | 有 |
| EmptyState | 鲸鱼图 + 你好，请等会儿再来吧 | 有 |
| UserBubble | 灰底 #F5F5F5，圆角 16 | 有 |
| AssistantBlock | 通栏无气泡，行高 26 | 有 |
| MdParagraph | 段距 16，首尾归零 | 有 |
| MdHeading | h1-h3，32/16 间距 | 有 |
| MdList | 缩进 18，条目间距 6 | 有 |
| MdBlockquote | 左侧留 14 | 有 |
| MdTable | th/td 10×16 | 缺 |
| InlineCode | .875em，圆角 6 | 有 |
| CodeBlock | 圆角 12，banner 上两角，pre 16 | 有 |
| CodeCopyBtn | 28 高，圆角 8，13sp | 有 |
| ThinkingBlock | 折叠 + shimmer + 左侧竖线 | 缺 |
| MessageActions | 复制 / 朗读 / 重新生成 | 部分 |
| CiteChip | 搜索引用角标 | 缺 |
| NoticeBar | 灰底 ⓘ，输入框上方 | 有 |
| JumpToBottom | 右下浮动圆钮 | 缺 |
| Composer | 24 圆角整卡 | 有 |
| TextArea | padding 12/12/0/16，minH 42 | 有 |
| CapsuleToggle | 深度思考 / 智能搜索，32 高 | 有 |
| AttachmentStrip | 64×64，圆角 16，删除角标 | 部分 |
| VoiceButton | 空输入时显示，描边圆 | 有 |
| SendButton | 30 圆，黑底实心箭头 | 有 |
| StopButton | 流式中替换发送键 | 有 |
| ErrorBar | 灰底 + 重试 | 有 |
| Disclaimer | 回答由 AI 生成，仅供参考 | 有 |
| DrawerSearch | 搜索对话内容… | 有 |
| SessionGroup | 置顶 / 今天 / 7 天内 | 部分 |
| SessionItem | 标题 + 选中态 | 有 |
| NewChatButton | 开启新对话 | 有 |
| AccountRow | 头像 + 邮箱 + ⋯ | 有 |
| SettingsGroup | 分组卡片 | 有 |
| ProviderConfig | 名称/Key/BaseUrl/Path/模型 | 部分 |
| ModuleList | 模块 + 权限标签 | 缺 |
| ConfirmDialog | 删除确认 | 用系统 confirm |
| Toast | 轻提示 | 缺 |
| ShareExport | 导出 Markdown | 有 |

小计：38 项 / 已有 27 / 部分 4 / 缺 6

## 优先补的 5 项

1. ThinkingBlock  深度思考折叠区
2. MdTable        表格渲染
3. MessageActions 补「重新生成」
4. JumpToBottom   回到底部
5. ModuleList     设置页模块权限列表
