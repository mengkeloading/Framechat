/* requestbuilder.js — 软模块按 inject 规则注入（渐进披露） */
(function (global) {
  'use strict';

  var EXTRA_ROLE = 'system';
  var MODEL = 'deepseek-chat';
  var DEFAULT_BUDGET = 4000;          /* 估算 token 上限，CJK 按字符/1.6 粗估 */

  function estimate(t) { return Math.ceil(String(t || '').length / 1.6); }
  function msgCost(m) { return estimate(m.content) + 4; }

  /* 从最新往回取，直到超预算；无论如何至少保留最后一条 */
  function trimHistory(list, budget) {
    if (!list.length) return [];
    var kept = [], used = 0;
    for (var i = list.length - 1; i >= 0; i--) {
      var c = msgCost(list[i]);
      if (used + c > budget && kept.length) break;
      used += c;
      kept.unshift(list[i]);
    }
    /* 配对优先：若首条是 assistant，把它前一条 user 也带上，
       避免历史以「孤立的助手回复」开头 */
    if (kept.length && kept[0].role !== 'user') {
      var pos = -1;
      for (var k = 0; k < list.length; k++) {
        if (list[k] === kept[0]) { pos = k; break; }
      }
      if (pos > 0 && list[pos - 1].role === 'user') {
        kept.unshift(list[pos - 1]);
      }
    }
    return kept;
  }

  function RequestBuilder(loader) { this.loader = loader; }

  RequestBuilder.prototype.build = async function (session, history, userInput,
                                                  hookAExtra, pendingToolMessages,
                                                  attachments) {
    var messages = [];
    var blocks = [];

    (session.activeSoftModules || []).forEach(function (id) {
      var p = this.loader.getPackage(id);
      if (!p) return;
      if (p.inject === 'full' && p.body) blocks.push(p.body);
      else if (p.inject === 'summary') blocks.push('- ' + p.name + '：' + p.description);
    }, this);
    /* 硬模块也可以声明 inject: summary，让模型知道它存在 */
    (session.activeHardModules || []).forEach(function (id) {
      var p = this.loader.getPackage(id);
      if (p && p.inject === 'summary') {
        blocks.push('- ' + p.name + '：' + p.description);
      }
    }, this);
    /* 对话级覆盖：systemPrompt 存在时替换软模块注入 */
    if (session.systemPrompt) blocks = [session.systemPrompt];
    if (blocks.length) messages.push({ role: 'system', content: blocks.join('\n\n') });

    var budget = (typeof session.contextBudget === 'number')
      ? session.contextBudget : DEFAULT_BUDGET;
    var reserved = estimate(blocks.join('\n\n')) + estimate(userInput) +
                   estimate(hookAExtra) + 24;
    var allowance = Math.max(48, budget - reserved);
    var comp = ContextCompactor.compact(history || [], allowance);
    var picked = comp.messages;
    this.lastTrim = { budget: budget, allowance: allowance,
                      kept: picked.length, dropped: comp.dropped,
                      compressed: !!comp.summary };
    picked.forEach(function (m) {
      messages.push({ role: m.role, content: m.content });
    });

    if (userInput) {
      var last = messages.length ? messages[messages.length - 1] : null;
      if (!(last && last.role === 'user' && last.content === userInput)) {
        messages.push({ role: 'user', content: userInput });
      }
    }

    /* 附件：文本内容作为 system 消息插在本轮 user 之前 */
    (attachments || []).forEach(function (a) {
      if (!a || !a.text) return;
      messages.push({
        role: 'system',
        content: '[附件 ' + a.name + ']\n' + a.text
      });
    });

    if (hookAExtra) messages.push({ role: EXTRA_ROLE, content: hookAExtra });

    (pendingToolMessages || []).forEach(function (m) {
      messages.push({ role: 'tool', content: m.content, tool_call_id: m.toolCallId });
    });

    var body = { model: (session.model || MODEL), stream: true, messages: messages };

    /* D9：tools 只来自模块 manifest 的 tools 声明。
       绝不能用 activeHardModules 冒充 —— 那会让模型去调用钩子模块。 */
    var tools = [];
    (session.activeHardModules || []).forEach(function (id) {
      var p = this.loader.getPackage(id);
      if (p && p.tools && p.tools.length) {
        p.tools.forEach(function (t) { tools.push(t); });
      }
    }, this);
    if (tools.length) body.tools = tools;

    return body;
  };

  global.RequestBuilder = RequestBuilder;
})(window);
