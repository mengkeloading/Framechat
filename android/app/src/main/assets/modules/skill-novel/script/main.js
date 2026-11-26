function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  if (!inp.ctx || !inp.ctx.forced) return null;   // 只被前缀强制时才注入

  var t = inp.text || '';
  if (!t.replace(/\s/g, '')) return null;

  var doc = '';
  try {
    doc = host.requestLocalAction('resource.read', JSON.stringify({ name: 'SKILL.md' }));
  } catch (e) {
    doc = '';
  }
  if (!doc) return null;

  return ModuleKit.reply({
    type: 'context',
    text: t,
    extra: '【写作规范】\n' + doc
  });
}
