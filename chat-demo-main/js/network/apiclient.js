/* apiclient.js — 对应 ApiClient.kt。产出原始 SSE 行。 */
(function (global) {
  'use strict';

  function ReplayApiClient(rounds) {
    this.rounds = rounds || [[]];
    this.calls = 0;
    this.bodies = [];
  }
  ReplayApiClient.prototype.streamChat = async function* (provider, body) {
    this.bodies.push(body);
    var idx = Math.min(this.calls, this.rounds.length - 1);
    var lines = this.rounds[idx] || [];
    this.calls += 1;
    for (var i = 0; i < lines.length; i++) yield lines[i];
  };

  function HttpApiClient(opts) {
    opts = opts || {};
    var p = (typeof Providers !== 'undefined')
      ? Providers.resolve(opts.session || {}) : null;
    this.url = opts.url || (p ? p.url : 'https://api.deepseek.com/chat/completions');
    this.apiKey = opts.apiKey || '';
    this.calls = 0;
    this.bodies = [];
  }
  HttpApiClient.prototype.streamChat = async function* (provider, body) {
    this.calls += 1;
    this.bodies.push(body);
    var resp = await fetch(this.url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream',
        'Authorization': 'Bearer ' + this.apiKey
      },
      body: JSON.stringify(body)
    });
    if (!resp.ok) {
      var t = '';
      try { t = (await resp.text()).slice(0, 200); } catch (e) {}
      throw new Error('http_' + resp.status + ': ' + t);
    }
    var reader = resp.body.getReader();
    var decoder = new TextDecoder('utf-8');
    var buf = '';
    while (true) {
      var chunk = await reader.read();
      if (chunk.done) break;
      buf += decoder.decode(chunk.value, { stream: true });
      var idx;
      while ((idx = buf.indexOf('\n')) >= 0) {
        var line = buf.slice(0, idx).replace(/\r$/, '');
        buf = buf.slice(idx + 1);
        if (line) yield line;
      }
    }
  };

  var Fixtures = {
    delta: function (text) {
      return 'data: ' + JSON.stringify({ choices: [{ delta: { content: text } }] });
    },
    tool: function (index, id, name, args) {
      var fn = {};
      if (name) fn.name = name;
      fn.arguments = args || '';
      var tc = { index: index, 'function': fn };
      if (id) tc.id = id;
      return 'data: ' + JSON.stringify({ choices: [{ delta: { tool_calls: [tc] } }] });
    },
    done: function () { return 'data: [DONE]'; }
  };

  global.ReplayApiClient = ReplayApiClient;
  global.HttpApiClient = HttpApiClient;
  global.SseFixtures = Fixtures;
})(window);
