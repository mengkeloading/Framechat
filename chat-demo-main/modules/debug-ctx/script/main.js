function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  var t = String(inp.text || '').trim();
  if (t !== '诊断ctx') return null;
  var c = inp.ctx || {};
  var o = [];
  o.push('phase=' + inp.phase);
  var keys = [];
  try { keys = Object.keys(c); } catch (e) {}
  o.push('ctx keys: ' + keys.join(', '));
  var a = c.attachments || [];
  o.push('attachments: ' + (a.length
    ? JSON.stringify(a).slice(0, 160) : '空'));
  var m = c.messages || [];
  o.push('messages: ' + m.length + ' 条');
  if (m.length) {
    var f = m[0];
    var l = m[m.length - 1];
    o.push('  首条: ' + f.role + ' / '
      + String(f.content || '').slice(0, 30));
    o.push('  末条: ' + l.role + ' / '
      + String(l.content || '').slice(0, 30));
  }
  var s = c.session || null;
  if (s) {
    o.push('title=' + (s.title || ''));
    o.push('硬/软模块=' + (s.activeHardModules || []).length
      + '/' + (s.activeSoftModules || []).length);
    o.push('systemPrompt=' + (s.systemPrompt
      ? String(s.systemPrompt).slice(0, 30) : '空'));
  } else {
    o.push('session: 无');
  }
  var out = o.join('\n');
  return ModuleKit.reply({
    type: 'local', text: out, content: out, reply: out
  });
}
