# 模块手册（自动生成）

> 加模块 = 新建目录 + MODULE.md + script/main.js。
> build 自动扫描，不用改任何配置。

## front matter 字段

| 字段 | 取值 | 说明 |
|---|---|---|
| `id` | 下划线 | 唯一标识 |
| `hooks` | `[input]` / `[output]` | 挂哪一端 |
| `inject` | `none`/`summary`/`full` | 能看到多少上下文 |
| `capabilities` | `{}` 或 `resource.read: N` | 能力配额 |
| `entry` | `script/main.js` | 入口 |

## 钩子 A 返回值

- `null` → pass（不干预）
- `{type:"context", text, extra}` → 注入一条 system
- `{type:"local", ...}` → 不联网直接回

## 钩子 B 返回值

- `null` → pass
- `{type:"rewrite", text}` → 改写回复
- `{type:"invoke", ...}` → 触发工具

## analyze-chat  (`analyze-chat`)

- hooks: ``  ·  inject: `none`
- 返回类型: `pass only`
- 供 tool_calls 调用的具名工具

```js
function (inputJson, host) {
  var args;
  try { args = JSON.parse(inputJson); } catch (e) { args = {}; }
  return JSON.stringify({
    ok: true,
    summary: '收到参数：' + JSON.stringify(args)
  });
}
```

## attach-context  (`attach-context`)

- hooks: `input`  ·  inject: `none`
- 返回类型: `context`
- 输入以「附加：」开头时，把内容作为 system 附加指令拼入本轮请求

```js
function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  if (inp.text.indexOf('附加：') !== 0) return null;
  var body = inp.text.slice(3).trim();
  if (!body) return null;
  return ModuleKit.reply({
    type: 'context',
    text: inp.text,
    extra: '本轮附加要求：' + body
  });
}
```

## code-tag  (`code-tag`)

- hooks: `output`  ·  inject: `summary`
- 返回类型: `rewrite`
- 回复里的代码块缺少语言标签时自动补 text

```js
function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  if (inp.phase !== 'b') return null;
  var t = String(inp.text || '');
  if (t.indexOf('```') < 0) return null;
  var changed = t.replace(/```[ \t]*\n/g, '```text\n');
  if (changed === t) return null;
  return ModuleKit.reply({ type: 'rewrite', text: changed });
}
```

## deep-think  (`deep-think`)

- hooks: `input`  ·  inject: `none`
- 返回类型: `context`
- 输入以「深想：」开头时注入先拆解再作答的思考框架

```js
function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  var t = inp.text || '';
  if (t.indexOf('深想：') !== 0) return null;
  var body = t.slice(3).trim();
  if (!body) return null;
  var guide = '本轮请先深度思考再作答。'
    + '第一步拆解关键要素；'
    + '第二步列出不确定处与必要假设；'
    + '第三步给出结论与理由。';
  return ModuleKit.reply({
    type: 'context',
    text: inp.text,
    extra: guide
  });
}
```

## formatter  (`formatter`)

- hooks: `output`  ·  inject: `none`
- 返回类型: `rewrite`
- 只能被 invoke 转调，给文本加接管前缀

```js
function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  if (!inp.text) return null;
  return ModuleKit.reply({ type: 'rewrite', text: '【路由模块已接管】' + inp.text });
}
```

## long-fold  (`long-fold`)

- hooks: `output`  ·  inject: `none`
- 返回类型: `rewrite`
- 回复超过 800 字时在末尾追加折叠提示

```js
function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  if (inp.phase !== 'b') return null;
  var t = String(inp.text || '');
  if (t.length <= 800) return null;
  return ModuleKit.reply({
    type: 'rewrite',
    text: t + '\n\n（共 ' + t.length + ' 字，已折叠显示）'
  });
}
```

## math-guard  (`math-guard`)

- hooks: `input`  ·  inject: `none`
- 返回类型: `context`
- 输入含「算一下」时注入分步计算的附加要求

```js
function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  if (inp.phase !== 'a') return null;
  if (String(inp.text).indexOf('算一下') < 0) return null;
  return ModuleKit.reply({
    type: 'context',
    text: inp.text,
    extra: '要求：分步计算，每一步写清算式与结果。'
  });
}
```

## router  (`router`)

- hooks: `output`  ·  inject: `none`
- 返回类型: `invoke`
- 回复以「路由:」开头时转调 formatter 模块

```js
function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  if (inp.text.indexOf('路由:') !== 0) return null;
  return ModuleKit.reply({
    type: 'invoke',
    moduleName: 'formatter',
    payload: inp.text.slice(3).trim()
  });
}
```

## simp-module  (`simp-module`)

- hooks: ``  ·  inject: `full`
- 返回类型: `pass only`
- 回答使用简体中文，列表用「- 」开头，避免多余寒暄

```js

```

## task-rewrite  (`task-rewrite`)

- hooks: `output`  ·  inject: `none`
- 返回类型: `rewrite`
- 用户输入以「执行任务」开头时，把最终气泡改写成接管格式

```js
function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  var src = (inp.ctx && inp.ctx.input) || '';
  if (src.indexOf('执行任务') !== 0) return null;
  var task = src.slice(4).replace(/^[\s\u3000:：,，]+/, '').trim();
  if (!task) return null;
  return ModuleKit.reply({
    type: 'rewrite',
    text: '【排版模块已接管】我收到了任务：' + task
  });
}
```

## time-local  (`time-local`)

- hooks: `input`  ·  inject: `none`
- 返回类型: `local`
- 输入含「几点」时本地回答，不发起网络请求

```js
function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  if (inp.text.indexOf('几点') < 0) return null;
  var d = new Date();
  var hh = ('0' + d.getHours()).slice(-2);
  var mm = ('0' + d.getMinutes()).slice(-2);
  return ModuleKit.reply({
    type: 'local',
    text: '现在是 ' + hh + ':' + mm + '（本地截留，未发起网络请求）'
  });
}
```

## weekly-reporter  (`weekly-reporter`)

- hooks: `output`  ·  inject: `summary`
- 返回类型: `rewrite`
- 把任务清单整理成周报，格式规则放在 reference/style.md，按需加载

```js
function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  var src = (inp.ctx && inp.ctx.input) || '';
  var text = String(inp.text || '');
  if (src.indexOf('周报') < 0 && text.indexOf('周报') < 0) return null;

  var style = host.requestLocalAction('resource.read', JSON.stringify({ name: 'style.md' }));
  var raw = src.replace(/^[^:：]*[:：]/, '');
  var tasks = raw.split(/[、,，]/).filter(function (t) { return t.trim(); })
    .map(function (t) { return '- ' + t.trim(); });
  return ModuleKit.reply({
    type: 'rewrite',
    text: '【周报排版】\n' + style.trim() + '\n' + tasks.join('\n')
  });
}
```
