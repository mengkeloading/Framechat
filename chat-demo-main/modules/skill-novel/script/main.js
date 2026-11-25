function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  var t = inp.text || '';
  if (t.indexOf('小说：') !== 0) return null;
  var body = t.slice(3).trim();
  if (!body) return null;
  var rules = '';
  try {
    rules = host.requestLocalAction(
      'resource.read', JSON.stringify({ name: 'SKILL.md' }));
  } catch (e) { rules = ""; }
  var extra = '【写作规范】\n' + rules
    + '\n\n【本题材】\n' + body;
  return ModuleKit.reply({
    type: 'context', text: t, extra: extra
  });
}
