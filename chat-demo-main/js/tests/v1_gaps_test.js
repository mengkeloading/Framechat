/* v1_gaps_test.js — 上下文预算 / 取消生成 / 重试 */
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
    sp.textContent = name; row.appendChild(sp);
    out.appendChild(row);
    if (!cond && detail) {
      var dd = document.createElement('div');
      dd.className = 'detail'; dd.textContent = String(detail);
      out.appendChild(dd);
    }
  }
  function roles(msgs) {
    return msgs.map(function (m) { return m.role; }).join(',');
  }

  async function run() {
    title('框架缺口：上下文预算 / 取消生成 / 重试');

    /* 1 预算内不裁剪 */
    var rb = new RequestBuilder(ModuleLoader);
    var short = [{ role: 'user', content: '你好' },
                 { role: 'assistant', content: '你好呀' }];
    var b1 = await rb.build({ activeSoftModules: [], activeHardModules: [] },
                            short, '再问一句', null, []);
    check('预算内不裁剪', b1.messages.length === 3, roles(b1.messages));
    check('未声明 tools 时请求体不含 tools', !b1.tools, JSON.stringify(b1.tools));
    check('未声明 tools 时请求体不含 tools', !b1.tools, JSON.stringify(b1.tools));

    /* 2 超预算裁剪 */
    var long = [];
    for (var i = 0; i < 60; i++) long.push({ role: 'user', content: '问'.repeat(100) });
    for (var j = 0; j < 60; j++) long.push({ role: 'assistant', content: '答'.repeat(100) });
    var b2 = await rb.build({ activeSoftModules: [], activeHardModules: [], contextBudget: 1200 },
                            long, '最后一句', null, []);
    check('超预算被裁剪', b2.messages.length < 40, 'msgs=' + b2.messages.length);
    var tail = b2.messages[b2.messages.length - 1];
    check('最新 user 一定保留', tail.role === 'user' && tail.content === '最后一句',
          JSON.stringify(tail));
    check('裁剪量可查', rb.lastTrim && rb.lastTrim.dropped > 0, JSON.stringify(rb.lastTrim));
    var short6 = [];
    for (var q = 0; q < 3; q++) {
      short6.push({ role: 'user', content: '一二三四五六七八九十' });
      short6.push({ role: 'assistant', content: '一二三四五六七八九十' });
    }
    await rb.build({ activeSoftModules: [], activeHardModules: [], contextBudget: 60 },
                   short6, '最后一问', null, []);
    var dbx = new AppDatabase();
    var sidx = dbx.sessionDao.insert({ title: 't', contextBudget: 120 });
    var alt = [];
    for (var q2 = 0; q2 < 12; q2++) {
      alt.push({ role: 'user', content: '问'.repeat(10) });
      alt.push({ role: 'assistant', content: '答'.repeat(60) });
    }
    var b3 = await rb.build({ activeSoftModules: [], activeHardModules: [],
                              contextBudget: 72 }, alt, '收尾', null, []);
    check('裁剪后历史首条是 user（不留下孤立的助手回复）',
          b3.messages.filter(function (m) { return m.role !== 'system'; })
            [0].role === 'user',
          JSON.stringify(b3.messages.map(function (m) { return m.role; })));
    check('contextBudget 能通过 DAO 保留（多轮真实路径）',
          dbx.sessionDao.get(sidx).contextBudget === 120,
          JSON.stringify(dbx.sessionDao.get(sidx)));
    check('极小预算也能触发裁剪（保底值不再吞掉配置）',
          (rb.lastTrim || {}).dropped > 0, JSON.stringify(rb.lastTrim));

    /* 3 取消生成 */
    var db3 = new AppDatabase();
    var sid3 = db3.sessionDao.insert({ activeSoftModules: [], activeHardModules: [], maxToolRounds: 3 });
    var ex3 = new BrowserSandboxExecutor(ModuleLoader);
    var vm3 = new ChatViewModel({
      db: db3, dispatcher: new ModuleDispatcher(ModuleLoader, ex3),
      requestBuilder: new RequestBuilder(ModuleLoader), apiClient: null, jsExecutor: ex3
    });
    function CancelClient() { this.calls = 0; this.bodies = []; }
    CancelClient.prototype.streamChat = async function* () {
      this.calls += 1;
      yield SseFixtures.delta('第一段');
      yield SseFixtures.delta('第二段');
      vm3.cancel();
      yield SseFixtures.delta('不该出现');
    };
    vm3.apiClient = new CancelClient();
    await vm3.send(sid3, '开始');
    var rows3 = db3.messageDao.getBySession(sid3);
    var a3 = rows3.filter(function (r) { return r.role === 'assistant'; })[0];
    check('取消后保留已生成文本', a3 && a3.content === '第一段第二段',
          a3 && a3.content);
    check('取消后标记 stopped', a3 && a3.stopped === true, JSON.stringify(a3));
    check('取消后不再收后续分块', a3 && a3.content.indexOf('不该出现') < 0,
          a3 && a3.content);
    check('取消后 state 回 idle', vm3.state.type === 'idle', vm3.state.type);

    /* 4 重试 */
    var db4 = new AppDatabase();
    var sid4 = db4.sessionDao.insert({ activeSoftModules: [], activeHardModules: [], maxToolRounds: 3 });
    var ex4 = new BrowserSandboxExecutor(ModuleLoader);
    var vm4 = new ChatViewModel({
      db: db4, dispatcher: new ModuleDispatcher(ModuleLoader, ex4),
      requestBuilder: new RequestBuilder(ModuleLoader), apiClient: null, jsExecutor: ex4
    });
    function FlakyClient() { this.calls = 0; this.bodies = []; }
    FlakyClient.prototype.streamChat = async function* () {
      this.calls += 1;
      if (this.calls === 1) throw new Error('http_503');
      yield SseFixtures.delta('重试成功');
      yield SseFixtures.done();
    };
    vm4.apiClient = new FlakyClient();
    await vm4.send(sid4, '你好');
    check('首次失败进入 error', vm4.state.type === 'error', vm4.state.type);
    await vm4.retry(sid4);
    var rows4 = db4.messageDao.getBySession(sid4);
    var users4 = rows4.filter(function (r) { return r.role === 'user'; });
    check('重试后成功且回到 idle',
          vm4.state.type === 'idle' &&
          rows4.some(function (r) { return r.content === '重试成功'; }),
          JSON.stringify(rows4.map(function (r) { return r.role + ':' + r.content.slice(0, 6); })));
    check('重试不产生重复 user', users4.length === 1, 'users=' + users4.length);

    var bad = results.filter(function (x) { return !x.ok; });
    var total = document.querySelectorAll('#out .row').length;
    var sum = document.getElementById('sum');
    if (bad.length) {
      sum.className = 'fail';
      sum.textContent = bad.length + ' 条失败（总 ' + total + ' 条）';
      document.title = 'FAIL ' + bad.length;
    } else {
      sum.className = 'pass';
      sum.textContent = total + '/' + total + ' 全部通过';
      document.title = 'PASS ' + total;
    }
    global.__RESULTS__ = (global.__RESULTS__ || []).concat(results);
  }

  function start() {
    var p = run();
    if (p && p.catch) p.catch(function (e) {
      var d = document.createElement('div');
      d.className = 'detail';
      d.textContent = '!! 测试中断: ' + (e && e.message ? e.message : e);
      document.getElementById('out').appendChild(d);
      document.title = 'ERROR';
    });
  }
  if (document.readyState === 'complete') { start(); }
  else { window.addEventListener('load', function () { start(); }); }
})(window);
