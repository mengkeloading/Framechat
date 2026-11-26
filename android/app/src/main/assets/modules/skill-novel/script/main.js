function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  var t = inp.text || '';
  if (!t.replace(/\s/g, '')) return null;

  // 前缀由框架剥离（prefix: 小说），这里拿到的是已被强制路由过来的正文
  var rules = '';
  try {
    rules = host.requestLocalAction(
      'resource.read', JSON.stringify({ name: 'SKILL.md' }));
  } catch (e) {
    rules = '';
  }
  if (!rules) return null;

  return ModuleKit.reply({
    type: 'context',
    text: t,
    extra: '【写作规范】\n' + rules
  });
}
