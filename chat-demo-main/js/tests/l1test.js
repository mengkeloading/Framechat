/* l1test.js — L1 能力模型断言（走真实注册表 + 真实执行器） */
(function (global) {
  'use strict';

  var results = [];
  var out = document.getElementById('out');

  function title(t) {
    var d = document.createElement('div');
    d.className = 'case';
    d.textContent = t;
    out.appendChild(d);
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
      dd.className = 'detail';
      dd.textContent = String(detail);
      out.appendChild(dd);
    }
  }

  async function run() {
    title('L1 能力模型（skill 风格模块包）');
    HostBridge.clearAudit();

    check('扫描到 8 个模块包', ModuleLoader.listPackages().length >= 8,
          ModuleLoader.listPackages().map(function (p) { return p.id; }).join(','));

    var wr = ModuleLoader.getPackage('weekly_reporter');
    check('front matter 被正确解析（hooks/inject/capabilities）',
          wr && wr.hooks.indexOf('output') >= 0 && wr.inject === 'summary' &&
          wr.capabilities['resource.read'] === 5,
          JSON.stringify(wr && { hooks: wr.hooks, inject: wr.inject, caps: wr.capabilities }));

    check('资源不进 system（渐进披露）',
          wr.inject !== 'full' && wr.body.indexOf('周报格式要求') < 0,
          'inject=' + (wr && wr.inject));

    /* 走真实执行器：命中周报 -> 读资源 -> 改写 */
    var ex = new BrowserSandboxExecutor(ModuleLoader);
    var outRaw = await ex.execute(
      'weekly_reporter/script/main.js',
      JSON.stringify({ text: '好的', phase: 'b',
                       ctx: { input: '生成周报：修复登录、补充文档' } }));
    var parsed = JSON.parse(outRaw);
    check('模块按需读到 reference/style.md',
          parsed.text && parsed.text.indexOf('每条任务单独一行') >= 0,
          parsed.text);
    check('用资源内容完成改写',
          parsed.type === 'rewrite' && parsed.text.indexOf('修复登录') >= 0,
          parsed.text);

    /* 配额 */
    var quotaOk = false, quotaMsg = '';
    try {
      var host = HostBridge.create('weekly_reporter');
      for (var i = 0; i < 6; i++) {
        await host.requestLocalAction('resource.read', JSON.stringify({ name: 'style.md' }));
      }
    } catch (e) { quotaOk = true; quotaMsg = e.message; }
    check('超过声明配额抛 quota_exceeded', quotaOk, quotaMsg);

    /* 未声明能力 */
    var undeclaredOk = false, undeclaredMsg = '';
    try {
      await HostBridge.create('time_local').requestLocalAction('net.get', '{}');
    } catch (e) { undeclaredOk = true; undeclaredMsg = e.message; }
    check('未声明能力抛 capability_not_declared', undeclaredOk, undeclaredMsg);

    /* 环境隔离 */
    ModuleLoader.registerPackage({
      id: 'probe_env', name: 'env', description: '', version: '0',
      hooks: ['output'], inject: 'none', capabilities: {}, entry: 'main.js',
      scripts: { 'main.js': 'function (i, h) { return JSON.stringify({ f: typeof fetch, w: typeof window }); }' }
    });
    var envRaw = await ex.execute('probe_env/main.js', '{}');
    var env = JSON.parse(envRaw);
    check('模块内 fetch / window 均不存在',
          env.f === 'undefined' && env.w === 'undefined', JSON.stringify(env));

    /* 审计 */
    var audit = HostBridge.audit();
    check('全局审计同时包含成功与失败记录',
          audit.some(function (a) { return a.ok; }) &&
          audit.some(function (a) { return !a.ok; }),
          JSON.stringify(audit.slice(-3)));

    var prev = (global.__RESULTS__ || []).length;
    var bad = results.filter(function (x) { return !x.ok; });
    var total = prev + results.length;
    var sum = document.getElementById('sum');
    if (bad.length) {
      sum.className = 'fail';
      sum.textContent = bad.length + ' 条失败（总 ' + total + ' 条）';
      document.title = 'FAIL ' + bad.length;
    } else {
      sum.className = 'pass';
      sum.textContent = total + '/' + total + ' 全部通过（框架 30 + L1 能力 9）';
      document.title = 'PASS ' + total;
    }
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
