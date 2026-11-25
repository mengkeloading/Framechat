/* sseparser.js — 对应 SseParser.kt。纯单行解析，不做分片合并。 */
(function (global) {
  'use strict';

  function payload(line) {
    if (typeof line !== 'string') return null;
    if (line.indexOf('data:') !== 0) return null;
    var s = line.slice(5).trim();
    if (!s || s === '[DONE]') return null;
    try { return JSON.parse(s); } catch (e) { return null; }
  }
  function deltaOf(o) {
    return (o && o.choices && o.choices[0] && o.choices[0].delta) || null;
  }
  function extractDelta(line) {
    var d = deltaOf(payload(line));
    return (d && typeof d.content === 'string') ? d.content : null;
  }
  function extractReasoning(line) {
    var d = deltaOf(payload(line));
    var r = d && d.reasoning_content;
    return (typeof r === 'string' && r) ? r : null;
  }
  function extractToolCalls(line) {
    var d = deltaOf(payload(line));
    var tc = d && d.tool_calls && d.tool_calls[0];
    if (!tc) return null;
    var f = tc['function'] || {};
    return {
      index: (typeof tc.index === 'number' ? tc.index : 0),
      id: tc.id || null,
      name: f.name || null,
      argumentsFragment: f.arguments || ''
    };
  }

  global.SseParser = { extractDelta: extractDelta, extractReasoning: extractReasoning, extractToolCalls: extractToolCalls };
})(window);
