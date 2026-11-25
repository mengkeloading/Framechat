/* features_test.js — 上下文压缩 / 单对话设置 / 附件 / TTS */
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
    title('基础功能：压缩 / 设置 / 附件 / TTS');

    var rb = new RequestBuilder(ModuleLoader);

    /* ---------- 上下文压缩 ---------- */
    var hist = [];
    for (var i = 0; i < 8; i++) {
      hist.push({ role: 'user', content: '第' + i + '个问题：请介绍一下主题' + i });
      hist.push({ role: 'assistant', content: '答'.repeat(50) });
    }
    var b = await rb.build(
      { activeSoftModules: [], activeHardModules: [], contextBudget: 120 },
      hist, '最新一问', null, [], []);

    var sysRows = b.messages.filter(function (m) { return m.role === 'system'; });
    check('超预算时生成压缩摘要',
          sysRows.length === 1 && sysRows[0].content.indexOf('已压缩') >= 0,
          JSON.stringify(sysRows[0] && sysRows[0].content));
    check('摘要里保留了早期提问的关键词',
          sysRows.length === 1 && sysRows[0].content.indexOf('主题0') >= 0,
          sysRows[0] && sysRows[0].content);
    check('压缩后首条非 system 消息是 user',
          b.messages.filter(function (m) { return m.role !== 'system'; })[0].role === 'user',
          JSON.stringify(b.messages.map(function (m) { return m.role; })));
    check('压缩有记录', (rb.lastTrim || {}).compressed === true,
          JSON.stringify(rb.lastTrim));

    var short = [{ role: 'user', content: '你好' }];
    var b2 = await rb.build(
      { activeSoftModules: [], activeHardModules: [], contextBudget: 4000 },
      short, '再问', null, [], []);
    check('未超预算时不产生摘要',
          b2.messages.filter(function (m) { return m.role === 'system'; }).length === 0,
          JSON.stringify(b2.messages));

    /* ---------- 单对话设置 ---------- */
    var db = new AppDatabase();
    var sid = db.sessionDao.insert({ title: 'S', contextBudget: 300 });
    db.sessionDao.update(sid, { systemPrompt: '你是一个只回答一个字的助手', bogus: 'x' });
    var s = db.sessionDao.get(sid);
    check('SessionDao.update 白名单：已知字段生效',
          s.systemPrompt === '你是一个只回答一个字的助手', JSON.stringify(s));
    check('SessionDao.update 白名单：未知字段被忽略',
          !('bogus' in s), JSON.stringify(s));

    var b3 = await rb.build(s, [], '你好', null, [], []);
    check('对话级 systemPrompt 覆盖软模块',
          b3.messages[0].role === 'system' &&
          b3.messages[0].content.indexOf('只回答一个字') >= 0,
          JSON.stringify(b3.messages[0]));

    /* ---------- 附件 ---------- */
    var att = [{ name: 'notes.txt', text: '附件正文：项目代号 ALPHA' }];
    var b4 = await rb.build(
      { activeSoftModules: [], activeHardModules: [] }, [], '看看附件', null, [], att);
    var attRows = b4.messages.filter(function (m) {
      return m.role === 'system' && m.content.indexOf('[附件 notes.txt]') >= 0;
    });
    check('文本附件进入请求体',
          attRows.length === 1 && attRows[0].content.indexOf('ALPHA') >= 0,
          JSON.stringify(attRows));

    var b5 = await rb.build(
      { activeSoftModules: [], activeHardModules: [] }, [], '没有附件', null, [], []);
    check('无附件时请求体不含附件消息',
          b5.messages.filter(function (m) {
            return m.role === 'system' && m.content.indexOf('[附件') >= 0;
          }).length === 0, JSON.stringify(b5.messages));

    /* ---------- TTS ---------- */
    var screen = new ChatScreen(
      { subscribe: function () {}, messages: function () { return []; },
        state: { type: 'idle' }, pendingAttachments: [] },
      'x',
      { list: document.createElement('div'), empty: document.createElement('div'),
        input: document.createElement('textarea'), send: document.createElement('button'),
        state: document.createElement('span'), trace: document.createElement('pre') });
    check('ChatScreen 暴露 speak / stopSpeak',
          typeof screen.speak === 'function' && typeof screen.stopSpeak === 'function');

    /* ---------- 多供应商 ---------- */
    var plist = Providers.list().map(function (p) { return p.id; });
    check('供应商注册表至少有 3 家',
          plist.indexOf('deepseek') >= 0 && plist.indexOf('openai') >= 0 &&
          plist.indexOf('ollama') >= 0, plist.join(','));

    var db2 = new AppDatabase();
    var sid2 = db2.sessionDao.insert({ title: 'P', providerId: 'ollama', model: 'qwen2.5' });
    var s2 = db2.sessionDao.get(sid2);
    var b6 = await rb.build(s2, [], '你好', null, [], []);
    check('会话级 model 进入请求体', b6.model === 'qwen2.5', b6.model);

    var resolved = Providers.resolve(s2);
    check('供应商解析出对应 baseUrl',
          resolved.id === 'ollama' && resolved.baseUrl.indexOf('11434') >= 0,
          JSON.stringify(resolved));

    db2.sessionDao.update(sid2, { providerId: 'deepseek', model: null });
    check('切换供应商后回落到默认 model',
          Providers.resolve(db2.sessionDao.get(sid2)).model === 'deepseek-chat',
          JSON.stringify(Providers.resolve(db2.sessionDao.get(sid2))));

    var u1 = Providers.join('https://a.com/v1', '/chat/completions');
    check('URL 由 baseUrl 与 path 拼接',
          u1 === 'https://a.com/v1/chat/completions', u1);
    var u2 = Providers.resolve({ providerId: 'deepseek' });
    check('默认 DeepSeek 的完整 URL 正确',
          u2.url === 'https://api.deepseek.com/v1/chat/completions',
          u2.url);
    var u3 = Providers.resolve({ baseUrl: 'https://x/v1',
          path: '/v2/chat' });
    check('自定义 path 生效',
          u3.url === 'https://x/v1/v2/chat', u3.url);

    /* 模块开关影响调度链 */
    var exq = new BrowserSandboxExecutor(ModuleLoader);
    var dpq = new ModuleDispatcher(ModuleLoader, exq);
    var sOn = { id: 'q', activeHardModules: ['attach_context'],
                activeSoftModules: [] };
    var sOff = { id: 'q', activeHardModules: [],
                 activeSoftModules: [] };
    var rOn = await dpq.dispatchInput(sOn, '附加：正式');
    var rOff = await dpq.dispatchInput(sOff, '附加：正式');
    check('开启模块后输入被接管', rOn.type === 'context',
          JSON.stringify(rOn));
    check('关闭模块后输入放行', rOff.type === 'pass',
          JSON.stringify(rOff));

    var bad = results.filter(function (x) { return !x.ok; });
    var total = document.querySelectorAll('#out .row').length;
    var sum = document.getElementById('sum');
    if (bad.length) {
      sum.className = 'fail';
      sum.textContent = bad.length + ' 条失败（共 ' + total + '）';
      document.title = 'FAIL ' + bad.length;
    } else {
      sum.className = 'pass';
      sum.textContent = total + '/' + total + ' 全部通过（含基础功能）';
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
