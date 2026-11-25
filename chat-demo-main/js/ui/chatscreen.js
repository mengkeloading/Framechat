/* chatscreen.js — 渲染（Markdown）+ TTS + 附件条 + 错误重试 */
(function (global) {
  'use strict';

  var CAN_TTS = (typeof speechSynthesis !== 'undefined');
  var speaking = false;

  function ChatScreen(vm, sessionId, els) {
    this.vm = vm;
    this.sessionId = sessionId;
    this.els = els;
    var self = this;
    vm.subscribe(function () { self.render(); });
  }

  ChatScreen.prototype.speak = function (text) {
    if (!CAN_TTS) return false;
    try {
      speechSynthesis.cancel();
      var u = new SpeechSynthesisUtterance(String(text || ''));
      u.lang = 'zh-CN';
      u.onend = function () { speaking = false; };
      u.onerror = function () { speaking = false; };
      speaking = true;
      speechSynthesis.speak(u);
      return true;
    } catch (e) { return false; }
  };
  ChatScreen.prototype.stopSpeak = function () {
    if (!CAN_TTS) return;
    try { speechSynthesis.cancel(); } catch (e) {}
    speaking = false;
  };
  ChatScreen.prototype.isSpeaking = function () { return speaking; };

  ChatScreen.prototype.render = function () {
    var vm = this.vm;
    var rows = vm.messages(this.sessionId);
    var html = '';

    for (var i = 0; i < rows.length; i++) {
      var r = rows[i];
      if (r.role === 'system') continue;
      var streaming = (r.pending === true);
      var chips = '';
      if (r.local) chips += '<span class="taglocal">本地</span>';
      if (r.rewritten) chips += '<span class="tagrw">已接管</span>';
      if (r.stopped) chips += '<span class="tagstop">已停止</span>';

      var body = (r.role === 'assistant')
        ? MiniMd.render(r.content)
        : MiniMd.esc(r.content);

      var acts = '';
      if (r.role === 'assistant' && r.content && !streaming) {
        acts = '<div class="acts">' +
               '<button data-speak="' + i + '">朗读</button>' +
               '<button data-stop="1">停止</button>' +
               '<button data-copy="' + i + '">复制</button></div>';
      }

      html += '<div class="bubble ' + r.role + (streaming ? ' pending' : '') + '">' +
              body + chips + acts + '</div>';
    }

    this.els.list.innerHTML = html;
    this.els.list.scrollTop = this.els.list.scrollHeight;
    if (this.els.empty) this.els.empty.style.display = rows.length ? 'none' : 'block';
    if (this.els.state) {
      this.els.state.textContent = (vm.state.type === 'error')
        ? 'error' : vm.state.type;
    }

    /* 错误条 */
    if (this.els.errbar) {
      if (vm.state.type === 'error') {
        this.els.errbar.style.display = 'block';
        this.els.errbar.innerHTML =
          '<span>' + MiniMd.esc(vm.state.message || '出错了') + '</span>' +
          '<button id="retryBtn">重试</button>';
        var self0 = this;
        var rb = document.getElementById('retryBtn');
        if (rb) rb.onclick = function () { self0.vm.retry(self0.sessionId); };
      } else {
        this.els.errbar.style.display = 'none';
        this.els.errbar.innerHTML = '';
      }
    }

    /* 附件条 */
    if (this.els.files) {
      var at = vm.pendingAttachments || [];
      if (at.length) {
        var fh = '';
        for (var j = 0; j < at.length; j++) {
          fh += '<div class="chipfile">' + MiniMd.esc(at[j].name) +
                (at[j].text ? '' : '（仅记录）') + '</div>';
        }
        this.els.files.innerHTML = fh;
        this.els.files.style.display = 'flex';
      } else {
        this.els.files.innerHTML = '';
        this.els.files.style.display = 'none';
      }
    }

    var self = this;
    this.els.list.querySelectorAll('[data-speak]').forEach(function (b) {
      b.addEventListener('click', function () {
        var row = rows[parseInt(b.getAttribute('data-speak'), 10)];
        if (row) self.speak(row.content);
      });
    });
    this.els.list.querySelectorAll('[data-stop]').forEach(function (b) {
      b.addEventListener('click', function () { self.stopSpeak(); });
    });
    this.els.list.querySelectorAll('[data-copy]').forEach(function (b) {
      b.addEventListener('click', function () {
        var row = rows[parseInt(b.getAttribute('data-copy'), 10)];
        if (!row) return;
        copyText(row.content, b);
      });
    });
    this.els.list.querySelectorAll('.copycode').forEach(function (b) {
      b.addEventListener('click', function () {
        var pre = b.closest('.codeblock').querySelector('pre');
        copyText(pre ? pre.textContent : '', b);
      });
    });
  };

  function copyText(text, btn) {
    var ta = document.createElement('textarea');
    ta.value = text;
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand('copy'); btn.textContent = '已复制'; }
    catch (e) { btn.textContent = '复制失败'; }
    setTimeout(function () { btn.textContent = '复制'; }, 1200);
    document.body.removeChild(ta);
  }

  ChatScreen.prototype.sendCurrent = async function () {
    var text = this.els.input.value.trim();
    if (!text) return;
    this.els.input.value = '';
    this.els.send.disabled = true;
    try { await this.vm.send(this.sessionId, text); }
    finally { this.els.send.disabled = false; this.render(); }
  };

  global.ChatScreen = ChatScreen;
})(window);
