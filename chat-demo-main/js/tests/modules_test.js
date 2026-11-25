/* modules_test.js — 模块库扩充验证（只加目录，不改运行时） */
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
    title('模块库扩充（3 个新包，零运行时改动）');

    var all = ModuleLoader.listPackages().map(function (p) { return p.id; });

    check('扫描到 11 个模块包（8 原有 + 3 新增）', all.length >= 11,
          all.join(','));
    check('三个新包都在注册表里',
          all.indexOf('code_tag') >= 0 && all.indexOf('long_fold') >= 0 &&
          all.indexOf('math_guard') >= 0, all.join(','));

    var ct = ModuleLoader.getPackage('code_tag');
    check('code_tag 的 front matter 正确',
          ct && ct.hooks.indexOf('output') >= 0 && ct.inject === 'summary',
          JSON.stringify(ct && { hooks: ct.hooks, inject: ct.inject }));

    var ex = new BrowserSandboxExecutor(ModuleLoader);

    /* code_tag：无语言标签 -> 补 text */
    var r1 = await ex.execute('code_tag/script/main.js',
      JSON.stringify({ text: '看这里：\n```\nlet a = 1\n```\n', phase: 'b' }));
    check('code_tag 给无标签代码块补 text',
          r1 && JSON.parse(r1).text.indexOf('```text') >= 0, r1);

    /* code_tag：已有标签 -> 不动 */
    var r2 = await ex.execute('code_tag/script/main.js',
      JSON.stringify({ text: '```js\nlet a = 1\n```', phase: 'b' }));
    check('code_tag 对已有标签不动作', r2 === null || r2 === '', r2);

    /* long_fold：超过 800 字 */
    var longText = new Array(900).join('字');
    var r3 = await ex.execute('long_fold/script/main.js',
      JSON.stringify({ text: longText, phase: 'b' }));
    check('long_fold 超长回复追加折叠提示',
          r3 && JSON.parse(r3).text.indexOf('已折叠显示') >= 0,
          r3 && r3.slice(-40));

    /* long_fold：短文本 -> 不动 */
    var r4 = await ex.execute('long_fold/script/main.js',
      JSON.stringify({ text: '短回复', phase: 'b' }));
    check('long_fold 对短回复不动作', r4 === null || r4 === '', r4);

    /* math_guard：走真实 dispatcher，验证 extra */
    var dp = new ModuleDispatcher(ModuleLoader, ex);
    var session = { id: 's1', activeHardModules: ['math_guard'] };
    var a1 = await dp.dispatchInput(session, '帮我算一下 3 的平方');
    check('math_guard 注入 extra',
          a1.type === 'context' && a1.extra.indexOf('分步计算') >= 0,
          JSON.stringify(a1));

    var a2 = await dp.dispatchInput(session, '今天天气不错');
    check('math_guard 未命中时 pass', a2.type === 'pass', JSON.stringify(a2));

    /* 硬模块 inject: summary 进 system */
    var rb = new RequestBuilder(ModuleLoader);
    var body = await rb.build(
      { activeSoftModules: [], activeHardModules: ['code_tag'] },
      [], '你好', null, []);
    var sys = (body.messages || []).filter(function (m) { return m.role === 'system'; });
    check('硬模块 inject: summary 进入 system',
          sys.length === 1 && sys[0].content.indexOf('代码块标注') >= 0,
          JSON.stringify(sys));

    var bad = results.filter(function (x) { return !x.ok; });
    var total = document.querySelectorAll('#out .row').length;
    var sum = document.getElementById('sum');
    if (bad.length) {
      sum.className = 'fail';
      sum.textContent = bad.length + ' 条失败（共 ' + total + '）';
      document.title = 'FAIL ' + bad.length;
    } else {
      sum.className = 'pass';
      sum.textContent = total + '/' + total + ' 全部通过（含模块库扩充）';
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
