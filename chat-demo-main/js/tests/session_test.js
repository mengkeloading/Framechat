/* session_test.js — 多会话隔离 */
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

  async function run() {
    title('多会话隔离');

    var db = new AppDatabase();
    var ex = new BrowserSandboxExecutor(ModuleLoader);
    var dp = new ModuleDispatcher(ModuleLoader, ex);
    var rb = new RequestBuilder(ModuleLoader);
    var rounds = [];
    for (var i = 0; i < 8; i++) {
      rounds.push([SseFixtures.delta('答复' + (i + 1)), SseFixtures.done()]);
    }
    var api = new ReplayApiClient(rounds);
    var vm = new ChatViewModel({
      db: db, dispatcher: dp, requestBuilder: rb, apiClient: api, jsExecutor: ex
    });

    var A = db.sessionDao.insert({
      title: 'A', activeSoftModules: [], activeHardModules: [],
      maxToolRounds: 3, contextBudget: 180
    });
    var B = db.sessionDao.insert({
      title: 'B', activeSoftModules: [], activeHardModules: [],
      maxToolRounds: 3
    });

    await vm.send(A, '甲一');
    await vm.send(B, '乙一');
    await vm.send(A, '甲二');

    var ra = db.messageDao.getBySession(A);
    var rb2 = db.messageDao.getBySession(B);

    var aTexts = ra.map(function (m) { return m.content; });
    var bTexts = rb2.map(function (m) { return m.content; });
    check('A 只有自己的消息',
          ra.length === 4 && ra[0].content === '甲一' && ra[2].content === '甲二' &&
          aTexts.every(function (t) { return t.indexOf('乙') < 0; }),
          aTexts.join('|'));
    check('B 只有自己的消息',
          rb2.length === 2 && rb2[0].content === '乙一' &&
          bTexts.every(function (t) { return t.indexOf('甲') < 0; }),
          bTexts.join('|'));

    check('A 上下文不含 B 的内容',
          db.messageDao.getContextForRequest(A).every(function (m) {
            return m.content.indexOf('乙') < 0;
          }),
          JSON.stringify(db.messageDao.getContextForRequest(A)));
    check('B 上下文不含 A 的内容',
          db.messageDao.getContextForRequest(B).every(function (m) {
            return m.content.indexOf('甲') < 0;
          }),
          JSON.stringify(db.messageDao.getContextForRequest(B)));

    var bodyA = api.bodies[2].messages.map(function (m) { return m.content; }).join('|');
    check('A 第三次请求只带 A 的历史',
          bodyA.indexOf('甲一') >= 0 && bodyA.indexOf('乙一') < 0, bodyA);

    check('预算按会话独立',
          (rb.lastTrim || {}).budget === 180, JSON.stringify(rb.lastTrim));

    check('vm.messages 按会话取',
          vm.messages(A).length === 4 && vm.messages(B).length === 2,
          vm.messages(A).length + '/' + vm.messages(B).length);

    /* 错误态：A 失败后切到 B 仍能发 */
    function Boom() { this.calls = 0; this.bodies = []; }
    Boom.prototype.streamChat = async function* () { throw new Error('http_503'); };
    vm.apiClient = new Boom();
    await vm.send(A, '这句会失败');
    check('失败后状态是 error', vm.state.type === 'error', vm.state.type);
    vm.setState({ type: 'idle' });
    vm.apiClient = new ReplayApiClient([[SseFixtures.delta('B 还能用'), SseFixtures.done()]]);
    await vm.send(B, '切过来继续');
    check('切走错误态后 B 能正常发送',
          vm.state.type === 'idle' && vm.messages(B).length === 4,
          vm.state.type + '/' + vm.messages(B).length);

    var bad = results.filter(function (x) { return !x.ok; });
    var total = document.querySelectorAll('#out .row').length;
    var sum = document.getElementById('sum');
    if (bad.length) {
      sum.className = 'fail';
      sum.textContent = bad.length + ' 条失败（共 ' + total + '）';
      document.title = 'FAIL ' + bad.length;
    } else {
      sum.className = 'pass';
      sum.textContent = total + '/' + total + ' 全部通过（含多会话隔离）';
      document.title = 'PASS ' + total;
    }
  }

  function start() {
    var p = run();
    if (p && p.catch) p.catch(function (e) {
      var d = document.createElement('div');
      d.className = 'detail';
      d.textContent = '!! 中断: ' + (e && e.message ? e.message : e);
      document.getElementById('out').appendChild(d);
      document.title = 'ERROR';
    });
  }
  if (document.readyState === 'complete') { start(); }
  else { window.addEventListener('load', function () { start(); }); }
})(window);
