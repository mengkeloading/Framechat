/* modulekit.js — 对应 HookResults.kt
 * 模块入参信封（文档 §3.3 允许的向后兼容扩展）：
 *   {"text":"...", "phase":"a"|"b", "ctx":{...}}
 * 裸字符串视为 phase="a"、无 ctx —— 旧模块不改也能跑。
 */
(function (global) {
  'use strict';

  function parse(inputJson) {
    if (typeof inputJson !== 'string') return { text: '', phase: 'a', ctx: {} };
    var t = inputJson.replace(/^\s+/, '');
    if (t.charAt(0) === '{') {
      try {
        var o = JSON.parse(t);
        if (o && typeof o === 'object' && typeof o.text === 'string') {
          return { text: o.text, phase: o.phase || 'a', ctx: o.ctx || {} };
        }
      } catch (e) { /* 落到裸字符串分支 */ }
    }
    return { text: inputJson, phase: 'a', ctx: {} };
  }

  function reply(o) { return JSON.stringify(o); }

  var A_TYPES = ['pass', 'local', 'context'];
  var B_TYPES = ['pass', 'rewrite', 'invoke'];

  function isLegal(type, phase) {
    return (phase === 'a' ? A_TYPES : B_TYPES).indexOf(type) >= 0;
  }

  global.ModuleKit = { parse: parse, reply: reply, isLegal: isLegal };
})(window);
