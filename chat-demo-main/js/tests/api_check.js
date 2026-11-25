/* api_check.js — 真接口诊断 + 多轮压力测试 */
(function (global) {
  'use strict';

  var CASES = [
    { label: '1 普通', text: '你好' },
    { label: '2 附加', text: '附加：把语气改正式' },
    { label: '3 改任务', text: '执行任务 写个周报' },
    { label: '4 本地', text: '现在几点了' }
  ];

  var TURNS = [
    '你好，介绍一下你自己',
    '再说一个成语',
    '解释这个成语的意思',
    '举一个使用它的例句',
    '把刚才三句话压缩成一句',
    '谢谢，最后一句话总结'
  ];

  function Probe(inner) { this.inner = inner; this.stats = null; }
  Probe.prototype.streamChat = async function* (provider, body) {
    var t0 = Date.now();
    var st = { body: body, first: null, lines: 0, chars: 0, error: null };
    this.stats = st;
    try {
      for await (var line of this.inner.streamChat(provider, body)) {
        st.lines += 1;
        st.chars += line.length;
        if (st.first === null) st.first = Date.now() - t0;
        yield line;
      }
    } catch (e) {
      st.error = (e && e.message ? e.message : String(e));
      throw e;
    } finally {
      st.ms = Date.now() - t0;
    }
  };

  function el(tag, css, text) {
    var d = document.createElement(tag);
    if (css) d.style.cssText = css;
    if (text) d.textContent = text;
    return d;
  }

  function build() {
    var btn = el('button',
      'position:fixed;right:10px;bottom:56px;z-index:9999;height:32px;padding:0 12px;' +
      'border-radius:16px;border:0;background:#426efe;color:#fff;font-size:12px;' +
      'font-weight:500', '真接口诊断');
    var panel = el('div',
      'position:fixed;left:0;right:0;bottom:0;z-index:9998;max-height:82%;' +
      'background:#fff;border-top:1px solid #e5e5e5;display:none;flex-direction:column;' +
      'padding:10px 12px 16px;font-size:13px;color:#0f0f0f;' +
      'font-family:-apple-system,"PingFang SC",sans-serif');

    var head = el('div', 'display:flex;align-items:center;gap:8px;padding-bottom:8px');
    var key = el('input', 'flex:1;min-width:0;border:1px solid #e5e5e5;border-radius:8px;' +
      'padding:6px 8px;font-size:12px');
    key.type = 'password';
    key.placeholder = 'API Key（仅内存）';
    var close = el('button', 'border:0;background:none;font-size:18px;color:#8e9094', '×');
    head.appendChild(key); head.appendChild(close);

    var row = el('div', 'display:flex;flex-wrap:wrap;gap:6px;padding-bottom:8px');
    var out = el('pre', 'flex:1;overflow:auto;margin:0;padding:10px;background:#fafafa;' +
      'border:1px solid #eee;border-radius:8px;font:11px/1.6 ui-monospace,monospace;' +
      'white-space:pre-wrap;color:#333;min-height:120px');
    out.textContent = '输入 key，点用例，或点「多轮 x6」。';

    var copy = el('button', 'margin-top:8px;height:32px;border-radius:8px;' +
      'border:1px solid #e5e5e5;background:#fff;font-size:12px', '复制全部');
    copy.onclick = function () {
      var ta = document.createElement('textarea');
      ta.value = out.textContent;
      document.body.appendChild(ta); ta.select();
      try { document.execCommand('copy'); copy.textContent = '已复制'; }
      catch (e) { copy.textContent = '手动长按复制'; }
      setTimeout(function () { copy.textContent = '复制全部'; }, 1500);
      document.body.removeChild(ta);
    };

    panel.appendChild(head); panel.appendChild(row);
    panel.appendChild(out); panel.appendChild(copy);
    var host = document.getElementById('goApiCheck');
    if (host) {
      btn.style.display = 'none';
      host.onclick = function () {
        if (window.__closeDrawer) window.__closeDrawer();
        panel.style.display = 'flex';
      };
    }
    document.body.appendChild(btn); document.body.appendChild(panel);

    btn.onclick = function () {
      panel.style.display = panel.style.display === 'flex' ? 'none' : 'flex';
    };
    close.onclick = function () { panel.style.display = 'none'; };

    function mkEnv() {
      var db = new AppDatabase();
      var sid = db.sessionDao.insert({
        title: 'probe',
        activeSoftModules: ['simp-module'],
        activeHardModules: ['attach_context', 'time_local', 'task_rewrite', 'router'],
        maxToolRounds: 3
      });
      var ex = new BrowserSandboxExecutor(ModuleLoader);
      var dp = new ModuleDispatcher(ModuleLoader, ex);
      var builder = new RequestBuilder(ModuleLoader);
      var probe = new Probe(new HttpApiClient({ apiKey: key.value.trim() }));
      var vm = new ChatViewModel({
        db: db, dispatcher: dp, requestBuilder: builder, apiClient: probe, jsExecutor: ex
      });
      return { db: db, sid: sid, dp: dp, probe: probe, vm: vm, builder: builder };
    }

    CASES.forEach(function (c) {
      var b = el('button', 'height:28px;padding:0 10px;border-radius:14px;' +
        'border:1px solid #e5e5e5;background:#fff;font-size:12px', c.label);
      b.onclick = function () { runOne(c.text, b); };
      row.appendChild(b);
    });

    var mb = el('button', 'height:28px;padding:0 10px;border-radius:14px;border:0;' +
      'background:#0f0f0f;color:#fff;font-size:12px', '多轮 x6');
    mb.onclick = function () { runMulti(mb); };
    row.appendChild(mb);

    function runOne(text, btnEl) {
      if (!key.value.trim()) { out.textContent = '先填 API Key'; return; }
      btnEl.disabled = true;
      out.textContent = '请求中…';
      var e = mkEnv();
      var lines = ['用例   : ' + text, 'key    : 已设置(长度 ' + key.value.trim().length + ')', ''];
      e.vm.send(e.sid, text).then(function () {
        var rows = e.db.messageDao.getBySession(e.sid);
        var last = rows[rows.length - 1];
        var st = e.probe.stats || {};
        lines.push('请求体 :');
        lines.push(JSON.stringify(st.body, null, 2));
        lines.push('');
        lines.push('首字节 : ' + (st.first == null ? '(无)' : st.first + 'ms'));
        lines.push('SSE 行 : ' + (st.lines || 0) + '   字符 ' + (st.chars || 0) +
                   '   总耗时 ' + (st.ms || 0) + 'ms');
        lines.push('状态   : ' + e.vm.state.type);
        lines.push('钩子   : ' + (e.dp.traces.length
          ? e.dp.traces.map(function (t) { return t.tag + ':' + t.name; }).join(' ')
          : '(全 pass)'));
        lines.push('气泡   : ' + (last ? last.content : '(空)'));
        if (last && last.rewritten) lines.push('标记   : 已接管（模型原文被钩子改写）');
        if (st.error) lines.push('!! 网络错误: ' + st.error);
        out.textContent = lines.join('\n');
        btnEl.disabled = false;
      }).catch(function (err) {
        lines.push('!! 异常: ' + (err && err.message ? err.message : err));
        if (String(err).indexOf('Failed to fetch') >= 0) {
          lines.push('提示：CORS —— file:// 不能跨域请求，需要本地服务器或代理');
        }
        out.textContent = lines.join('\n');
        btnEl.disabled = false;
      });
    }

    function runMulti(btnEl) {
      if (!key.value.trim()) { out.textContent = '先填 API Key'; return; }
      btnEl.disabled = true;
      out.textContent = '多轮测试中…（6 轮，会产生 token 消耗）';

      var db = new AppDatabase();
      var sid = db.sessionDao.insert({
        title: 'multi',
        activeSoftModules: ['simp-module'],
        activeHardModules: ['attach_context', 'time_local', 'task_rewrite', 'router'],
        maxToolRounds: 3,
        contextBudget: 180          /* 故意设极小，逼出裁剪 */
      });
      var ex = new BrowserSandboxExecutor(ModuleLoader);
      var dp = new ModuleDispatcher(ModuleLoader, ex);
      var builder = new RequestBuilder(ModuleLoader);
      var probe = new Probe(new HttpApiClient({ apiKey: key.value.trim() }));
      var vm = new ChatViewModel({
        db: db, dispatcher: dp, requestBuilder: builder, apiClient: probe, jsExecutor: ex
      });

      var lines = ['=== 多轮压力测试（6 轮 / 上下文预算 180）==='];
      var t0 = Date.now();
      var idx = 0;
      var totalDropped = 0;

      function step() {
        if (idx >= TURNS.length) {
          var rows = db.messageDao.getBySession(sid);
          lines.push('');
          lines.push('会话结束：消息 ' + rows.length + ' 条，总耗时 ' +
                     (Date.now() - t0) + 'ms');
          var users = rows.filter(function (r) { return r.role === 'user'; });
          var dup = users.length !== TURNS.length;
          lines.push(dup ? '!! user 条数异常：' + users.length + '（应为 ' + TURNS.length + '）'
                         : 'ok  user 条数 = ' + users.length + '，无重复');
          lines.push(totalDropped > 0
            ? 'ok  预算生效：共裁剪 ' + totalDropped + ' 条历史'
            : '!! 预算没触发裁剪，把 contextBudget 再调小');
          out.textContent = lines.join('\n');
          btnEl.disabled = false;
          return;
        }

        var text = TURNS[idx];
        var beforeCount = db.messageDao.getBySession(sid).length;
        vm.send(sid, text).then(function () {
          var st = probe.stats || {};
          var msgs = (st.body && st.body.messages) || [];
          var uCount = msgs.filter(function (m) { return m.role === 'user'; }).length;
          var tr = builder.lastTrim || {};
          totalDropped += (tr.dropped || 0);
          var verdict = (uCount > idx + 1) ? '!!重复' :
                        (uCount === idx + 1 ? 'ok全量' : '裁剪生效');
          lines.push(
            '轮 ' + (idx + 1) +
            ' ｜请求消息 ' + msgs.length + ' 条' +
            ' ｜会话已有 ' + beforeCount + ' 条' +
            ' ｜裁剪 ' + (tr.dropped || 0) + ' ｜预算 ' + (tr.budget || '?') +
            ' ｜请求内 user×' + uCount + ' ' + verdict +
            ' ｜首字节 ' + (st.first == null ? '(无)' : st.first + 'ms') +
            ' ｜' + vm.state.type);
          out.textContent = lines.join('\n');
          idx += 1;
          setTimeout(step, 200);
        }).catch(function (err) {
          lines.push('轮 ' + (idx + 1) + ' 失败: ' +
                     (err && err.message ? err.message : err));
          out.textContent = lines.join('\n');
          btnEl.disabled = false;
        });
      }

      step();
    }
  }

  if (document.readyState === 'complete') build();
  else window.addEventListener('load', build);
})(window);
