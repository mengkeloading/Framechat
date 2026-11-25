// ModuleKit：模块脚本可用的最小工具集
// 与 chat-demo-main/js/module/modulekit.js 保持一致
var ModuleKit = (function () {
  function parse(inputJson) {
    if (typeof inputJson !== 'string') {
      return { text: '', phase: 'a', ctx: {} };
    }
    var t = inputJson.replace(/^\s+/, '');
    if (t.charAt(0) === '{') {
      try {
        var o = JSON.parse(t);
        if (o && typeof o.text === 'string') {
          return {
            text: o.text,
            phase: o.phase || 'a',
            ctx: o.ctx || {}
          };
        }
      } catch (e) {}
    }
    return { text: inputJson, phase: 'a', ctx: {} };
  }
  function reply(o) { return JSON.stringify(o); }
  var A = ['pass', 'local', 'context'];
  var B = ['pass', 'rewrite', 'invoke'];
  function isLegal(type, phase) {
    return (phase === 'a' ? A : B).indexOf(type) >= 0;
  }
  return { parse: parse, reply: reply, isLegal: isLegal };
})();
