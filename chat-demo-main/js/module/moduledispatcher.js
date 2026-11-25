/* moduledispatcher.js — 按 manifest 的 hooks 与 session 的启用清单调度
 * 契约不变：pass / local / context ; pass / rewrite / invoke
 */
(function (global) {
  'use strict';

  var LEGACY_PLAIN_TEXT = false;
  var MAX_INVOKE_DEPTH = 3;

  function ModuleDispatcher(loader, executor) {
    this.loader = loader;
    this.executor = executor;
    this.traces = [];
  }

  ModuleDispatcher.prototype._trace = function (tag, name, msg) {
    this.traces.push({ tag: tag, name: name, msg: msg });
  };

  ModuleDispatcher.prototype._envelope = function (text, phase, ctx) {
    if (LEGACY_PLAIN_TEXT) return text;
    return JSON.stringify({ text: text, phase: phase, ctx: ctx || {} });
  };

  ModuleDispatcher.prototype._call = async function (id, inputJson, phase) {
    var pkg = this.loader.getPackage(id);
    if (!pkg) return null;
    var ref = id + '/' + pkg.entry;
    var raw;
    try {
      raw = await this.executor.execute(ref, inputJson);
    } catch (e) {
      this._trace('hook', id, '执行异常 fail-open: ' + e.message);
      return null;
    }
    if (raw === null || raw === undefined || raw === '') return null;
    if (typeof raw !== 'string') {
      this._trace('hook', id, '返回值非字符串 fail-open');
      return null;
    }
    var obj;
    try { obj = JSON.parse(raw); } catch (e) {
      this._trace('hook', id, '非法 JSON fail-open');
      return null;
    }
    if (!ModuleKit.isLegal(obj.type, phase)) {
      this._trace('hook', id, 'phase=' + phase + ' 非法类型 ' + obj.type + ' fail-open');
      return null;
    }
    return obj;
  };

  ModuleDispatcher.prototype.dispatchInput = async function (session, rawInput, attachments, history) {
    var chain = this.loader.idsForPhase(session.activeHardModules, 'input');
    if (!chain.length) return { type: 'pass', text: rawInput };

    var extras = [], text = rawInput;
    for (var i = 0; i < chain.length; i++) {
      var env = this._envelope(rawInput, 'a', {
        sessionId: session.id,
        session: session,
        messages: (history || []).slice(-20),
        attachments: attachments || []
      });
      var r = await this._call(chain[i], env, 'a');
      if (!r) continue;
      this._trace('hookA', chain[i], 'type=' + r.type);
      if (r.type === 'local') return { type: 'local', text: r.text || '' };
      if (r.type === 'context') {
        if (r.extra) extras.push(String(r.extra));
        if (r.text) text = r.text;
      }
    }
    if (extras.length) return { type: 'context', text: text, extra: extras.join('\n') };
    return { type: 'pass', text: text };
  };

  ModuleDispatcher.prototype.dispatchOutput = async function (session, rawText, ctx) {
    var chain = this.loader.idsForPhase(session.activeHardModules, 'output');
    var hit = null, hitName = null;

    for (var i = 0; i < chain.length; i++) {
      var env = this._envelope(rawText, 'b', ctx || {});
      var r = await this._call(chain[i], env, 'b');
      if (r && r.type !== 'pass') { hit = r; hitName = chain[i]; break; }
    }
    if (!hit) return { type: 'pass', text: rawText };

    var depth = 0, visited = [], name = hitName;
    while (hit) {
      if (hit.type === 'rewrite') {
        this._trace('hookB', name, 'rewrite');
        return { type: 'rewrite', text: hit.text || rawText };
      }
      if (hit.type === 'invoke') {
        var target = hit.moduleName;
        depth += 1;
        if (depth > MAX_INVOKE_DEPTH || !target || !this.loader.getPackage(target) ||
            visited.indexOf(target) >= 0) {
          this._trace('hookB', name, 'invoke 中止 depth=' + depth + ' -> pass');
          return { type: 'pass', text: rawText };
        }
        visited.push(target);
        name = target;
        var env2 = this._envelope(String(hit.payload || rawText), 'b', ctx || {});
        hit = await this._call(target, env2, 'b');
        continue;
      }
      return { type: 'pass', text: rawText };
    }
    return { type: 'pass', text: rawText };
  };

  ModuleDispatcher.LEGACY = function (v) { LEGACY_PLAIN_TEXT = !!v; };
  global.ModuleDispatcher = ModuleDispatcher;
})(window);
