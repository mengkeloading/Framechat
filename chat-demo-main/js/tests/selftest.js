/* selftest.js — 29 条断言，覆盖文档 7.7 全部易错点 */
(function (global) {
  'use strict';

  var results = [];
  var out = document.getElementById('out');

  function title(t) {
    var d = document.createElement('div');
    d.className = 'case'; d.textContent = t; out.appendChild(d);
  }
  function check(name, cond, detail) {
    results.push({ name: name, ok: !!cond });
    var row = document.createElement('div');
    row.className = 'row';
    row.innerHTML = '<span class="tag ' + (cond ? 'ok' : 'fail') + '">' +
                    (cond ? 'PASS' : 'FAIL') + '</span>';
    var sp = document.createElement('span');
    sp.textContent = name;
    row.appendChild(sp);
    out.appendChild(row);
    if (!cond && detail) {
      var dd = document.createElement('div');
      dd.className = 'detail'; dd.textContent = String(detail);
      out.appendChild(dd);
    }
  }

  function mkEnv(opts) {
    opts = opts || {};
    var db = new AppDatabase();
    var sessionId = db.sessionDao.insert({
      title: 't',
      activeSoftModules: opts.soft || ['simp-module'],
      activeHardModules: opts.hard || ['attach_context', 'time_local', 'task_rewrite', 'router'],
      maxToolRounds: (typeof opts.maxToolRounds === 'number' ? opts.maxToolRounds : 3)
    });
    var executor = new BrowserSandboxExecutor(ModuleLoader);
    var dispatcher = new ModuleDispatcher(ModuleLoader, executor);
    var builder = new RequestBuilder(ModuleLoader);
    var api = new ReplayApiClient(
      opts.rounds || [[SseFixtures.delta('你好，我在。'), SseFixtures.done()]]);
    var vm = new ChatViewModel({
      db: db, dispatcher: dispatcher, requestBuilder: builder,
      apiClient: api, jsExecutor: executor
    });
    return { db: db, sid: sessionId, dispatcher: dispatcher, api: api, vm: vm };
  }

  async function run() {
    var e;

    title('用例 1：普通消息 / tempId 先 insert 后 update');
    e = mkEnv();
    await e.vm.send(e.sid, '你好');
    var rows1 = e.db.messageDao.getBySession(e.sid);
    check('只有 1 次请求', e.api.calls === 1, 'calls=' + e.api.calls);
    check('气泡数=2（user+assistant），流式未产生重复行',
          rows1.length === 2, 'rows=' + rows1.length);
    check('assistant 落库内容 = 模型文本',
          rows1[1].content === '你好，我在。', rows1[1].content);
    check('state 回到 idle', e.vm.state.type === 'idle', e.vm.state.type);

    title('用例 2：附加：xxx -> extra 进请求体');
    e = mkEnv();
    await e.vm.send(e.sid, '附加：把语气改正式');
    var body2 = e.api.bodies[0];
    var extras = body2.messages.filter(function (m) {
      return m.role === 'system' && m.content.indexOf('附加要求') >= 0;
    });
    check('extra 进入 messages', extras.length === 1, JSON.stringify(body2.messages));
    var users2 = body2.messages.filter(function (m) { return m.role === 'user'; });
    check('user 消息未重复', users2.length === 1, JSON.stringify(users2));

    title('用例 3：执行任务 -> 钩子B 改写');
    e = mkEnv();
    await e.vm.send(e.sid, '执行任务 写个周报');
    var rows3 = e.db.messageDao.getBySession(e.sid);
    check('最终气泡 = 验收文案',
          rows3[rows3.length - 1].content === '【排版模块已接管】我收到了任务：写个周报',
          rows3[rows3.length - 1].content);

    title('用例 4：本地截留 -> 一次网络都不发');
    e = mkEnv();
    await e.vm.send(e.sid, '现在几点了');
    check('requests == 0', e.api.calls === 0, 'calls=' + e.api.calls);
    var loc = e.db.messageDao.getBySession(e.sid);
    check('local 气泡落库且标记 local',
          loc.length === 1 && loc[0].local === true, JSON.stringify(loc));

    title('用例 5：INVOKE 转调 formatter');
    e = mkEnv({ rounds: [[SseFixtures.delta('路由:你好'), SseFixtures.done()]] });
    await e.vm.send(e.sid, '试试路由');
    var rows5 = e.db.messageDao.getBySession(e.sid);
    check('invoke 链生效', rows5[rows5.length - 1].content === '【路由模块已接管】你好',
          rows5[rows5.length - 1].content);

    title('用例 6：模块抛异常 fail-open');
    ModuleLoader.registerHard('boom', function () { throw new Error('boom'); });
    e = mkEnv({ hard: ['boom', 'attach_context'] });
    await e.vm.send(e.sid, '你好');
    check('异常未阻断，仍发出请求', e.api.calls === 1, 'calls=' + e.api.calls);
    check('trace 记录了 fail-open',
          e.dispatcher.traces.some(function (t) { return t.msg.indexOf('fail-open') >= 0; }),
          JSON.stringify(e.dispatcher.traces));

    title('用例 7：软模块只注入一条 system，且在首位');
    e = mkEnv();
    await e.vm.send(e.sid, '你好');
    var b7 = e.api.bodies[0];
    var sysMsgs = b7.messages.filter(function (m) { return m.role === 'system'; });
    check('首位是 system 且只有一条软模块消息',
          b7.messages[0].role === 'system' && sysMsgs.length === 1,
          JSON.stringify(b7.messages.slice(0, 2)));

    title('用例 8：tool_calls 跨事件累加 + 记录 + 第二轮形状');
    e = mkEnv({
      rounds: [
        [SseFixtures.tool(0, 'call_1', 'analyze_chat', '{"a"'),
         SseFixtures.tool(0, null, null, ':1}'),
         SseFixtures.done()],
        [SseFixtures.delta('已经分析完了。'), SseFixtures.done()]
      ]
    });
    await e.vm.send(e.sid, '帮我分析');
    var recs = e.db.toolCallDao.rows;
    check('tool 记录已 insert 并 update 成功',
          recs.length === 1 && recs[0].success === true, JSON.stringify(recs));
    check('分片参数被正确拼接', recs[0].rawRequestJson === '{"a":1}', recs[0].rawRequestJson);
    check('进入第二轮（calls=2）', e.api.calls === 2, 'calls=' + e.api.calls);
    var b8 = e.api.bodies[1];
    var toolRows8 = b8.messages.filter(function (m) { return m.role === 'tool'; });
    check('第二轮请求里 tool 消息只出现一次', toolRows8.length === 1,
          JSON.stringify(toolRows8));
    var asstRows8 = b8.messages.filter(function (m) { return m.role === 'assistant'; });
    check('tool 轮次的 assistant 消息留在上下文里', asstRows8.length === 1,
          JSON.stringify(b8.messages.map(function (m) { return m.role; })));
    var users8 = b8.messages.filter(function (m) { return m.role === 'user'; });
    check('第二轮 user 消息没有重复追加', users8.length === 1,
          JSON.stringify(users8));

    title('用例 9：空参兜底 {}');
    e = mkEnv({
      rounds: [
        [SseFixtures.tool(0, 'call_x', 'analyze_chat', ''), SseFixtures.done()],
        [SseFixtures.delta('ok'), SseFixtures.done()]
      ]
    });
    await e.vm.send(e.sid, '空参');
    check('rawRequestJson 兜底为 {}',
          e.db.toolCallDao.rows[0].rawRequestJson === '{}',
          e.db.toolCallDao.rows[0].rawRequestJson);

    title('用例 10：maxToolRounds 超限 + truncated 系统消息');
    var toolRound = [SseFixtures.tool(0, 'c1', 'analyze_chat', '{}'), SseFixtures.done()];
    e = mkEnv({ maxToolRounds: 3, rounds: [toolRound, toolRound, toolRound, toolRound] });
    await e.vm.send(e.sid, '一直调工具');
    check('恰好执行 3 轮', e.api.calls === 3, 'calls=' + e.api.calls);
    var sysRows = e.db.messageDao.getBySession(e.sid).filter(function (r) {
      return r.role === 'system';
    });
    check('插入了一条超限系统消息', sysRows.length === 1, JSON.stringify(sysRows));
    check('超限后 state 回到 idle', e.vm.state.type === 'idle', e.vm.state.type);

    title('用例 11：请求异常 -> ERROR 不被 finally 覆盖');
    function BoomClient() { this.calls = 0; this.bodies = []; }
    BoomClient.prototype.streamChat = async function* () {
      this.calls += 1;
      throw new Error('http_401 Unauthorized');
    };
    e = mkEnv();
    e.vm.apiClient = new BoomClient();
    await e.vm.send(e.sid, '你好');
    check('state 停在 error', e.vm.state.type === 'error', e.vm.state.type);
    check('错误文案被 ErrorMapper 分类',
          String(e.vm.state.message).indexOf('401') >= 0, e.vm.state.message);

    title('用例 12：SseParser 单行解析与坏行容错');
    check('extractDelta 正常', SseParser.extractDelta(SseFixtures.delta('abc')) === 'abc');
    check('坏 JSON 返回 null 不抛', SseParser.extractDelta('data: {"choi') === null);
    check('[DONE] 返回 null', SseParser.extractDelta('data: [DONE]') === null);

    title('用例 13：关掉信封，钩子B 改写必然失效（坑1 证据）');
    ModuleDispatcher.LEGACY(true);
    e = mkEnv();
    await e.vm.send(e.sid, '执行任务 写个周报');
    var rows13 = e.db.messageDao.getBySession(e.sid);
    check('拿不到 ctx.input 时改写失效',
          rows13[rows13.length - 1].content.indexOf('排版模块') < 0,
          rows13[rows13.length - 1].content);
    ModuleDispatcher.LEGACY(false);

    title('用例 14：INVOKE 自调用被深度限制掐断');
    ModuleLoader.registerHard('loop', function (inputJson) {
      var inp = ModuleKit.parse(inputJson);
      if (inp.phase !== 'b') return null;
      return ModuleKit.reply({ type: 'invoke', moduleName: 'loop', payload: inp.text });
    });
    e = mkEnv({ hard: ['loop'], rounds: [[SseFixtures.delta('循环'), SseFixtures.done()]] });
    var t0 = Date.now();
    await e.vm.send(e.sid, '触发循环');
    check('未死循环、按 pass 返回',
          (Date.now() - t0) < 1000 && e.vm.state.type === 'idle');

    var failed = results.filter(function (x) { return !x.ok; });
    var sum = document.getElementById('sum');
    if (failed.length) {
      sum.className = 'fail';
      sum.textContent = failed.length + '/' + results.length + ' 未通过：' +
                        failed.map(function (x) { return x.name; }).join('；');
      document.title = 'FAIL ' + failed.length;
    } else {
      sum.className = 'pass';
      sum.textContent = results.length + '/' + results.length +
                        ' 全部通过 —— 可逐文件转 Kotlin';
      document.title = 'PASS ' + results.length;
    }
    global.__RESULTS__ = results;
  }

  function __start() {
    var p = run();
    if (p && p.catch) {
      p.catch(function (e) {
        var d = document.createElement('div');
        d.className = 'detail';
        d.textContent = '!! 测试中断: ' + (e && e.message ? e.message : e);
        document.getElementById('out').appendChild(d);
        document.title = 'ERROR';
      });
    }
  }
  if (document.readyState === 'complete') { __start(); }
  else { window.addEventListener('load', function () { __start(); }); }
})(window);
