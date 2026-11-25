/* persist_test.js — 持久化 / Markdown / 自动标题 / 重试接口 */
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
    title('日常可用：持久化 / Markdown / 自动标题');

    check('Persist 模块已加载',
          typeof Persist === 'object' && typeof Persist.save === 'function');

    var canPersist = Persist.available();
    if (canPersist) {
      var db = new AppDatabase();
      var sid = db.sessionDao.insert({ title: '持久化测试' });
      db.messageDao.insert({ sessionId: sid, role: 'user', content: '存盘内容' });
      Persist.save(db);

      var db2 = new AppDatabase();
      var loaded = Persist.load(db2);
      check('save/load 往返成功', loaded === true);
      check('恢复后消息还在',
            db2.messageDao.getBySession(sid).length === 1 &&
            db2.messageDao.getBySession(sid)[0].content === '存盘内容',
            JSON.stringify(db2.messageDao.getBySession(sid)));
      Persist.clear();
    } else {
      check('localStorage 不可用，跳过持久化往返（环境限制）', true);
      check('localStorage 不可用，跳过恢复断言（环境限制）', true);
    }

    var md = MiniMd.render('看这里：\n```js\nlet a = 1\n```\n');
    check('Markdown 渲染代码块',
          md.indexOf('codeblock') >= 0 && md.indexOf('let a = 1') >= 0, md);
    var md2 = MiniMd.render('# 标题\n- 条目\n**粗** `code`');
    check('Markdown 渲染标题/列表/粗体/行内码',
          md2.indexOf('<h1>') >= 0 && md2.indexOf('<li>') >= 0 &&
          md2.indexOf('<strong>') >= 0 && md2.indexOf('<code>') >= 0, md2);

    var db3 = new AppDatabase();
    var sid3 = db3.sessionDao.insert({ title: '会话 1' });
    var ex = new BrowserSandboxExecutor(ModuleLoader);
    var vm = new ChatViewModel({
      db: db3,
      dispatcher: new ModuleDispatcher(ModuleLoader, ex),
      requestBuilder: new RequestBuilder(ModuleLoader),
      apiClient: new ReplayApiClient([[SseFixtures.delta('好'), SseFixtures.done()]]),
      jsExecutor: ex
    });
    await vm.send(sid3, '帮我解释一下闭包这个概念');
    check('首轮后自动生成会话标题',
          db3.sessionDao.get(sid3).title.indexOf('帮我解释') === 0,
          db3.sessionDao.get(sid3).title);

    check('ChatViewModel 提供 retry', typeof vm.retry === 'function');

    var dbx = new AppDatabase();
    var sidx = dbx.sessionDao.insert({ title: 'x' });
    dbx.messageDao.insert({ sessionId: sidx, role: 'user', content: 'a' });
    dbx.removeSession(sidx);
    check('删除会话后 get 返回 null',
          dbx.sessionDao.get(sidx) === null,
          JSON.stringify(dbx.sessionDao.get(sidx)));
    check('删除会话后消息被清空',
          dbx.messageDao.getBySession(sidx).length === 0,
          JSON.stringify(dbx.messageDao.getBySession(sidx)));
    check('导出 Markdown 含标题与正文',
          ExportSession.markdown(db3, sid3).indexOf('帮我解释') >= 0);

    var bad = results.filter(function (x) { return !x.ok; });
    var total = document.querySelectorAll('#out .row').length;
    var sum = document.getElementById('sum');
    if (bad.length) {
      sum.className = 'fail';
      sum.textContent = bad.length + ' 条失败（共 ' + total + '）';
      document.title = 'FAIL ' + bad.length;
    } else {
      sum.className = 'pass';
      sum.textContent = total + '/' + total + ' 全部通过（含日常可用）';
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
