/* boot.js — 装配 + 抽屉 + 持久化 + 附件 + 设置 */
(function () {
  'use strict';

  var db = new AppDatabase();
  var restored = Persist.available() && Persist.load(db);

  var executor = new BrowserSandboxExecutor(ModuleLoader, { timeoutMs: 3000 });
  var dispatcher = new ModuleDispatcher(ModuleLoader, executor);
  var builder = new RequestBuilder(ModuleLoader);
  var replay = new ReplayApiClient([
    [SseFixtures.delta('这是离线回放：文字分块到达、逐字拼接。'), SseFixtures.done()]
  ]);
  var vm = new ChatViewModel({
    db: db, dispatcher: dispatcher, requestBuilder: builder,
    apiClient: replay, jsExecutor: executor
  });

  var prefs = Persist.loadPrefs();
  var useLive = !!prefs.useLive;
  var liveKey = prefs.key || '';

  function newSession(title) {
    return db.sessionDao.insert({
      title: title,
      activeSoftModules: ['simp-module'],
      activeHardModules: ['attach_context', 'time_local', 'task_rewrite', 'router'],
      maxToolRounds: 3
    });
  }

  if (!restored || db.sessionDao.rows.length === 0) {
    newSession('新对话');
  }
  var current = db.sessionDao.rows.length
    ? db.sessionDao.rows[0].id : null;
  window.__currentId = current;

  var $ = function (id) { return document.getElementById(id); };

  var els = {
    list: $('list'), empty: $('empty'), input: $('input'), send: $('send'),
    state: { textContent: '' }, traces: $('traces'), files: $('files'),
    errbar: $('errbar')
  };

  var screen = new ChatScreen(vm, current, els);

  /* ---------- 抽屉 ---------- */
  var drawer = $('drawer'), scrim = $('scrim');
  function openDrawer() { drawer.classList.add('on'); scrim.classList.add('on'); refreshSessions(); }
  function closeDrawer() { drawer.classList.remove('on'); scrim.classList.remove('on'); }

  $('menuBtn').onclick = openDrawer;
  scrim.onclick = closeDrawer;

  /* ---------- 会话列表 ---------- */
  function refreshSessions() {
    var box = $('sessionList');
    box.innerHTML = '';
    db.sessionDao.rows.forEach(function (s) {
      var d = document.createElement('div');
      d.className = 'ditem' + (s.id === current ? ' on' : '');
      d.textContent = s.title || '(未命名)';
      d.onclick = function () { switchTo(s.id); closeDrawer(); };
      box.appendChild(d);
    });
    var cur = db.sessionDao.get(current);
    $('sessionTitle').textContent = (cur && cur.title) || '';
  }

  function switchTo(id) {
    current = id;
    window.__currentId = id;
    screen.sessionId = id;
    if (vm.state.type === 'error') vm.setState({ type: 'idle' });
    vm.clearAttachments();
    applyClient();
    refreshSessions();
    screen.render();
  }

  $('newSessionBtn').onclick = function () {
    var id = newSession('新对话');
    switchTo(id);
    Persist.save(db);
    closeDrawer();
  };
  $('newBtn').onclick = function () { $('newSessionBtn').click(); };

  /* ---------- 面板切换 ---------- */
  function showPane(name) {
    $('pane-chat').style.display = (name === 'chat') ? 'flex' : 'none';
    $('pane-tests').style.display = (name === 'tests') ? 'flex' : 'none';
  }
  $('goTests').onclick = function () { closeDrawer(); showPane('tests'); };
  $('testsBack').onclick = function () { showPane('chat'); };

  /* ---------- 附件 ---------- */
  var fileInput = $('fileInput');
  $('attachBtn').onclick = function () { fileInput.click(); };
  fileInput.onchange = function () {
    Array.prototype.forEach.call(fileInput.files, function (f) {
      var isText = (f.type && f.type.indexOf('text') === 0) ||
                   /\.(txt|md|json|js|ts|py|csv|log)$/i.test(f.name);
      if (isText) {
        var r = new FileReader();
        r.onload = function () { vm.attach(f.name, String(r.result || '')); };
        r.readAsText(f);
      } else {
        vm.attach(f.name, null);
      }
    });
    fileInput.value = '';
  };

  /* ---------- 输入区：空文本显示语音，有文本显示发送 ---------- */
  var voiceBtn = $('voiceBtn'), sendBtn = $('send'), inputEl = $('input');
  function syncComposer() {
    if (!inputEl || !voiceBtn || !sendBtn) return;
    var has = inputEl.value.trim().length > 0;
    voiceBtn.style.display = has ? 'none' : 'flex';
    sendBtn.style.display = has ? 'flex' : 'none';
  }
  inputEl.addEventListener('input', syncComposer);
  inputEl.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); screen.sendCurrent(); }
  });
  sendBtn.onclick = function () { screen.sendCurrent(); };
  voiceBtn.onclick = function () { alert('语音输入属于宿主能力，Kotlin 侧用 SpeechRecognizer 实现'); };
  syncComposer();

  $('thinkBtn').onclick = function () { this.classList.toggle('on'); };
  $('searchBtn').onclick = function () { this.classList.toggle('on'); };
  $('traceBtn').onclick = function () {
    closeDrawer();
    $('traces').classList.toggle('on');
    screen.render();
  };
  $('soundBtn').onclick = function () {
    if (screen.isSpeaking()) screen.stopSpeak();
    else {
      var rows = vm.messages(current);
      for (var i = rows.length - 1; i >= 0; i--) {
        if (rows[i].role === 'assistant' && rows[i].content) { screen.speak(rows[i].content); return; }
      }
    }
  };

  /* ---------- 设置 ---------- */
  var sheet = $('sheet'), mask = $('sheetMask'), provSel = $('setProvider');

  function openSheet() {
    var s = db.sessionDao.get(current) || {};
    provSel.innerHTML = '';
    Providers.list().forEach(function (p) {
      var o = document.createElement('option');
      o.value = p.id; o.textContent = p.name;
      provSel.appendChild(o);
    });
    provSel.value = s.providerId || 'deepseek';
    $('provHint').textContent = Providers.get(s.providerId || 'deepseek').note || '';
    $('setModel').value = s.model || '';
    $('setBaseUrl').value = s.baseUrl || '';
    $('setPath').value = (s.path == null) ? '' : s.path;
    $('setModelX').value = s.model || '';
    $('setEcho').checked = !!s.echoReasoning;
    $('setResp').checked = (s.apiStyle === 'responses');
    $('setBudget').value = (typeof s.contextBudget === 'number') ? s.contextBudget : '';
    $('setRounds').value = (typeof s.maxToolRounds === 'number') ? s.maxToolRounds : 3;
    $('setPrompt').value = s.systemPrompt || '';
    $('setTts').checked = !!s.ttsAuto;
    $('setLive').checked = useLive;
    $('setKey').value = liveKey;
    closeDrawer();
    sheet.classList.add('on'); mask.classList.add('on');
  }
  function closeSheet() { sheet.classList.remove('on'); mask.classList.remove('on'); }

  $('setBtn').onclick = openSheet;
  $('sheetCancel').onclick = closeSheet;
  mask.onclick = closeSheet;
  provSel.onchange = function () {
    $('provHint').textContent = Providers.get(provSel.value).note || '';
  };
  $('sheetSave').onclick = function () {
    var b = $('setBudget').value.trim();
    var r = $('setRounds').value.trim();
    db.sessionDao.update(current, {
      providerId: provSel.value,
      model: $('setModel').value.trim() || null,
      contextBudget: b ? parseInt(b, 10) : null,
      maxToolRounds: r ? parseInt(r, 10) : 3,
      systemPrompt: $('setPrompt').value.trim() || null,
      ttsAuto: $('setTts').checked
    });
    useLive = $('setLive').checked;
    liveKey = $('setKey').value.trim();
    Persist.savePrefs({ useLive: useLive, key: liveKey });
    applyClient();
    closeSheet();
    refreshSessions();
    screen.render();
  };

  function applyClient() {
    var s = db.sessionDao.get(current);
    vm.apiClient = useLive ? new HttpApiClient({ apiKey: liveKey, session: s }) : replay;
  }

  /* ---------- 自动保存 ---------- */
  var timer = null;
  vm.subscribe(function () {
    syncComposer();
    clearTimeout(timer);
    timer = setTimeout(function () { Persist.save(db); }, 400);
  });

  /* ---------- 自动朗读 ---------- */
  var lastSpoken = null;
  vm.subscribe(function () {
    var s = db.sessionDao.get(current);
    if (!s || !s.ttsAuto || vm.state.type !== 'idle') return;
    var rows = vm.messages(current);
    for (var i = rows.length - 1; i >= 0; i--) {
      if (rows[i].role !== 'assistant' || !rows[i].content) continue;
      if (rows[i].id === lastSpoken) return;
      lastSpoken = rows[i].id;
      screen.speak(rows[i].content);
      return;
    }
  });

  applyClient();
  refreshSessions();
  screen.render();

  window.__vm = vm;
  window.__db = db;
  window.__screen = screen;
  window.__switchTo = switchTo;
  window.__refreshSessions = refreshSessions;
  window.__newSession = newSession;
  window.__closeDrawer = closeDrawer;
  window.__persist = Persist;
})();
