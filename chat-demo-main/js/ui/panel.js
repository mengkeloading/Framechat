(function () {
  'use strict';

  var db = window.__db;
  if (!db) { console.warn('[panel] 缺少 db'); return; }

  /* ---------- 浮层全部搬到 body，脱离 footer 的 transform ---------- */
  function adopt(id) {
    var el = document.getElementById(id);
    if (!el) {
      el = document.createElement('div');
      el.id = id;
    }
    document.body.appendChild(el);
    el.style.cssText = '';
    return el;
  }

  var panel = adopt('modPanel');
  var ctxPop = adopt('ctxPop');
  var mask = adopt('modMask');

  function dbg(msg) {
    var s = document.getElementById('state');
    if (s) s.textContent = msg;
  }
  dbg('panel ready');
  window.__panelReady = true;

  /* ---------- 抽屉开关 ---------- */
  function closeSheets() {
    panel.classList.remove('on');
    ctxPop.classList.remove('on');
    mask.classList.remove('on');
    var f = document.querySelector('footer');
    if (f) f.style.transform = '';
    dbg('closed');
  }

  function openSheet(el) {
    panel.classList.remove('on');
    ctxPop.classList.remove('on');
    el.classList.add('on');
    mask.classList.add('on');
    var f = document.querySelector('footer');
    if (f) {
      var h = el.getBoundingClientRect().height || 0;
      f.style.transform = 'translateY(-' + (h + 8) + 'px)';
    }
    dbg('open');
  }

  mask.addEventListener('click', closeSheets);

  /* ---------- 工具 ---------- */
  var PRESETS = [
    { id: 'none', name: '默认', desc: '使用软模块提供的提示词' },
    { id: 'novel', name: '小说',
      desc: '你是一位有文学判断力的小说写作搭档，陪我把故事写下去。' },
    { id: 'essay', name: '散文',
      desc: '你是一位散文写作搭档，懂得记忆和沉默的重量。' },
    { id: 'poem', name: '诗',
      desc: '你是一位现代诗写作搭档，不把诗写成分行的大白话。' },
    { id: 'coach', name: '情感导师',
      desc: '你是理性的倾听者，先理解再给建议，不说教。' }
  ];

  function curId() { return window.__currentId; }
  function sess() {
    var id = curId();
    return id ? (db.sessionDao.get(id) || null) : null;
  }

  function toast(msg) {
    var el = document.getElementById('toast');
    if (!el) {
      el = document.createElement('div');
      el.id = 'toast';
      el.style.cssText =
        'position:fixed;left:50%;bottom:96px;transform:translateX(-50%);' +
        'background:rgba(20,20,20,.9);color:#fff;font-size:13px;' +
        'padding:8px 14px;border-radius:10px;opacity:0;' +
        'pointer-events:none;transition:opacity .2s;z-index:999';
      document.body.appendChild(el);
    }
    el.textContent = msg;
    el.style.opacity = '1';
    clearTimeout(toast.t);
    toast.t = setTimeout(function () { el.style.opacity = '0'; }, 1500);
  }

  function save(patch) {
    var s = sess();
    if (!s) { toast('先发一条消息创建会话'); return; }
    db.sessionDao.update(s.id, patch);
    if (window.Persist) window.Persist.save(db);
  }

  function block(title, right) {
    var d = document.createElement('div');
    d.style.cssText = 'padding:14px;border-top:1px solid #f5f5f5';
    var h = document.createElement('div');
    h.style.cssText = 'font-size:12px;color:#8e9094;margin-bottom:8px;' +
      'display:flex;justify-content:space-between';
    h.innerHTML = '<span>' + title + '</span><span>' +
      (right == null ? '' : right) + '</span>';
    d.appendChild(h);
    return d;
  }

  function secName() {
    var s = sess();
    var d = block('对话名称');
    var inp = document.createElement('input');
    inp.style.cssText = 'width:100%;box-sizing:border-box;' +
      'border:1px solid #e5e5e5;border-radius:10px;padding:9px 12px;' +
      'font-size:14px;font-family:inherit;outline:none;background:#f5f5f5';
    inp.value = (s && s.title) || '';
    inp.placeholder = '未命名';
    inp.oninput = function () {
      save({ title: inp.value.trim() });
      var t = document.getElementById('sessionTitle');
      if (t) t.textContent = inp.value.trim();
    };
    d.appendChild(inp);
    return d;
  }

  function secPrompt() {
    var s = sess();
    var p0 = (s && s.systemPrompt) || '';
    var n = PRESETS.filter(function (p) {
      return p.id !== 'none' && p.desc === p0;
    }).length;
    var d = block('系统提示词', n + ' 预设');

    PRESETS.forEach(function (p) {
      var on = (p.id === 'none' && !p0) || (p.desc === p0);
      var row = document.createElement('div');
      row.style.cssText = 'display:flex;gap:12px;padding:12px 0;' +
        'border-bottom:1px solid #f7f7f7;align-items:flex-start';
      var r = document.createElement('div');
      r.style.cssText = 'flex:0 0 20px;width:20px;height:20px;' +
        'border-radius:10px;box-sizing:border-box;margin-top:1px;' +
        'border:' + (on ? '6px solid #426efe' : '1.5px solid #d6d6d6');
      var main = document.createElement('div');
      main.style.cssText = 'flex:1;min-width:0';
      var nm = document.createElement('div');
      nm.style.cssText = 'font-size:15px;color:#0f0f0f';
      nm.textContent = p.name;
      var ds = document.createElement('div');
      ds.style.cssText = 'font-size:12.5px;color:#8e9094;line-height:1.55;' +
        'margin-top:2px';
      ds.textContent = p.desc;
      main.appendChild(nm);
      main.appendChild(ds);
      row.appendChild(r);
      row.appendChild(main);
      row.onclick = function () {
        save({ systemPrompt: p.id === 'none' ? null : p.desc });
        render();
        toast('已选：' + p.name);
      };
      d.appendChild(row);
    });

    var inp = document.createElement('input');
    inp.style.cssText = 'width:100%;box-sizing:border-box;margin-top:12px;' +
      'border:1px solid #e5e5e5;border-radius:10px;padding:9px 12px;' +
      'font-size:14px;font-family:inherit;outline:none';
    inp.placeholder = '自定义系统提示词';
    inp.value = p0;
    inp.onchange = function () {
      save({ systemPrompt: inp.value.trim() || null });
      render();
      toast('已保存');
    };
    d.appendChild(inp);
    return d;
  }

  function secModules() {
    var s = sess();
    var hard = ((s && s.activeHardModules) || []).slice();
    var soft = ((s && s.activeSoftModules) || []).slice();
    var d = block('模块（只影响本对话）',
      (hard.length + soft.length) + ' 启用');
    if (!window.ModuleLoader) return d;

    window.ModuleLoader.listPackages().forEach(function (p) {
      var isSoft = !p.entry;
      var on = isSoft ? soft.indexOf(p.id) >= 0 : hard.indexOf(p.id) >= 0;
      var row = document.createElement('div');
      row.style.cssText = 'display:flex;align-items:center;gap:10px;' +
        'padding:12px 0;border-bottom:1px solid #f7f7f7';
      var main = document.createElement('div');
      main.style.cssText = 'flex:1;min-width:0';
      var nm = document.createElement('div');
      nm.style.cssText = 'font-size:15px;color:#0f0f0f';
      nm.textContent = p.name || p.id;
      var ds = document.createElement('div');
      ds.style.cssText = 'font-size:12px;color:#8e9094;margin-top:2px;' +
        'white-space:nowrap;overflow:hidden;text-overflow:ellipsis';
      ds.textContent = p.description || '';
      main.appendChild(nm);
      main.appendChild(ds);
      var sw = document.createElement('div');
      sw.style.cssText = 'flex:0 0 auto;font-size:12px;border-radius:10px;' +
        'padding:3px 10px;border:1px solid ' +
        (on ? '#cfe0ff' : '#e5e5e5') + ';color:' +
        (on ? '#426efe' : '#8e9094') + ';background:' +
        (on ? '#eaf1ff' : '#fff');
      sw.textContent = on ? '开' : '关';
      row.appendChild(main);
      row.appendChild(sw);
      row.onclick = function () {
        if (isSoft) {
          var i = soft.indexOf(p.id);
          if (i >= 0) soft.splice(i, 1); else soft.push(p.id);
        } else {
          var j = hard.indexOf(p.id);
          if (j >= 0) hard.splice(j, 1); else hard.push(p.id);
        }
        save({
          activeHardModules: hard.slice(),
          activeSoftModules: soft.slice()
        });
        var now = isSoft ? soft.indexOf(p.id) >= 0
                         : hard.indexOf(p.id) >= 0;
        sw.textContent = now ? '开' : '关';
        sw.style.color = now ? '#426efe' : '#8e9094';
        sw.style.borderColor = now ? '#cfe0ff' : '#e5e5e5';
        sw.style.background = now ? '#eaf1ff' : '#fff';
        toast((now ? '已开启 ' : '已关闭 ') + (p.name || p.id));
      };
      d.appendChild(row);
    });
    return d;
  }

  function render() {
    panel.innerHTML = '';
    if (!sess()) {
      var d = block('还没有会话');
      var p = document.createElement('div');
      p.style.cssText = 'font-size:13px;color:#8e9094';
      p.textContent = '先发一条消息，这个对话才有配置。';
      d.appendChild(p);
      panel.appendChild(d);
      return;
    }
    panel.appendChild(secName());
    panel.appendChild(secPrompt());
    panel.appendChild(secModules());
  }

  /* ---------- 上下文抽屉 ---------- */
  function renderCtx() {
    var s = sess() || {};
    var rows = curId() ? db.messageDao.getBySession(curId()) : [];
    var hard = (s.activeHardModules || []).length;
    var soft = (s.activeSoftModules || []).length;
    var budget = (typeof s.contextBudget === 'number') ? s.contextBudget : 4000;
    var box = document.createElement('div');
    box.style.cssText = 'padding:16px;font-size:13.5px;' +
      'color:#545557;line-height:1.9';
    box.innerHTML =
      '<div style="font-size:12px;color:#8e9094;margin-bottom:8px">当前对话上下文</div>' +
      '供应商 <b style="color:#0f0f0f">' + (s.providerId || 'deepseek') +
      '</b> · 模型 <b style="color:#0f0f0f">' + (s.model || '默认') + '</b><br>' +
      '上下文预算 <b style="color:#0f0f0f">' + budget +
      '</b> · 工具轮数 <b style="color:#0f0f0f">' +
      (s.maxToolRounds || 3) + '</b><br>' +
      '硬模块 <b style="color:#0f0f0f">' + hard + '</b> · 软模块 <b style="color:#0f0f0f">' +
      soft + '</b><br>' +
      '消息 <b style="color:#0f0f0f">' + rows.length + '</b> 条';
    ctxPop.innerHTML = '';
    ctxPop.appendChild(box);
  }

  /* ---------- 事件委托 ---------- */
  document.addEventListener('click', function (e) {
    var t = e.target;
    while (t && t !== document.body) {
      if (t.id === 'modBtn') {
        if (panel.classList.contains('on')) closeSheets();
        else { render(); openSheet(panel); }
        return;
      }
      if (t.id === 'ctxBtn') {
        if (ctxPop.classList.contains('on')) closeSheets();
        else { renderCtx(); openSheet(ctxPop); }
        return;
      }
      t = t.parentNode;
    }
  }, true);

  /* ---------- 语音 ---------- */
  var SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  var recog = null;
  var listening = false;

  var MIC = '<svg viewBox="0 0 24 24" width="18" height="18" fill="none"'
    + ' stroke="#0f0f0f" stroke-width="1.6" stroke-linecap="round">'
    + '<circle cx="12" cy="12" r="9"/>'
    + '<circle cx="9.5" cy="12" r="1" fill="#0f0f0f"/>'
    + '<circle cx="12" cy="12" r="1" fill="#0f0f0f"/>'
    + '<circle cx="14.5" cy="12" r="1" fill="#0f0f0f"/></svg>';

  var KB = '<svg viewBox="0 0 24 24" width="18" height="18" fill="none"'
    + ' stroke="#0f0f0f" stroke-width="1.6" stroke-linecap="round">'
    + '<rect x="3" y="7" width="18" height="10" rx="2"/>'
    + '<path d="M7 11h.01M11 11h.01M15 11h.01M7 14h10"/></svg>';

  function bindVoice() {
    var v = document.getElementById('voiceBtn');
    if (!v) return;
    v.innerHTML = MIC;
    v.onclick = function (e) {
      e.stopPropagation();
      if (!SR) { toast('当前浏览器不支持语音输入'); return; }
      if (listening) { try { recog.stop(); } catch (err) {} return; }
      try {
        recog = new SR();
        recog.lang = 'zh-CN';
        recog.interimResults = false;
        recog.onresult = function (ev) {
          var txt = ev.results[0][0].transcript;
          var inp = document.getElementById('input');
          if (inp) {
            inp.value = (inp.value ? inp.value + ' ' : '') + txt;
            inp.dispatchEvent(new Event('input'));
          }
        };
        recog.onend = function () { listening = false; v.innerHTML = MIC; };
        recog.onerror = function () {
          listening = false; v.innerHTML = MIC; toast('语音识别失败');
        };
        recog.start();
        listening = true;
        v.innerHTML = KB;
        toast('请说话…');
      } catch (err) { toast('无法启动语音识别'); }
    };
  }

  bindVoice();
  setTimeout(bindVoice, 300);

  window.__panel = { open: render, close: closeSheets };
})();
