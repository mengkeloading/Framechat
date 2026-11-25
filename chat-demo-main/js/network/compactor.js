/* compactor.js — 上下文压缩：超预算时把早期历史压成一句摘要
 * 规则：保留最近 KEEP_TAIL 条；被丢弃的 user 提问抽前 N 字做成 system 摘要。
 * 真实实现可以改成调用模型做摘要（走 host.llm.call），但规则版本可测、零成本。
 */
(function (global) {
  'use strict';

  var KEEP_TAIL = 4;
  var SNIPPET = 24;

  function estimate(t) { return Math.ceil(String(t || '').length / 1.6); }

  function compact(history, allowance) {
    var list = history || [];
    if (!list.length) return { messages: [], dropped: 0, summary: null };

    var used = 0, tail = [];
    for (var i = list.length - 1; i >= 0; i--) {
      var c = estimate(list[i].content) + 4;
      if (tail.length >= KEEP_TAIL || used + c > allowance) break;
      used += c;
      tail.unshift(list[i]);
    }
    /* 配对优先：首条是 assistant 时把它前面那条 user 带上 */
    var cut = list.length - tail.length;
    if (cut > 0 && tail.length && tail[0].role !== 'user') {
      var prev = list[cut - 1];
      if (prev && prev.role === 'user') { tail.unshift(prev); cut -= 1; }
    }
    if (cut <= 0) return { messages: tail, dropped: 0, summary: null };

    var bits = [];
    for (var k = 0; k < cut; k++) {
      if (list[k].role !== 'user') continue;
      var s = String(list[k].content || '').replace(/\s+/g, ' ').slice(0, SNIPPET);
      if (s) bits.push(s + (String(list[k].content).length > SNIPPET ? '…' : ''));
    }
    var summary = null;
    if (bits.length) {
      summary = {
        role: 'system',
        content: '［已压缩 ' + cut + ' 条历史］早前问过：' + bits.join('；')
      };
    }
    return {
      messages: summary ? [summary].concat(tail) : tail,
      dropped: cut,
      summary: summary
    };
  }

  global.ContextCompactor = { compact: compact, KEEP_TAIL: KEEP_TAIL };
})(window);
