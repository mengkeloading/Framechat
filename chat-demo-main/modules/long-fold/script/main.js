function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  if (inp.phase !== 'b') return null;
  var t = String(inp.text || '');
  if (t.length <= 800) return null;
  return ModuleKit.reply({
    type: 'rewrite',
    text: t + '\n\n（共 ' + t.length + ' 字，已折叠显示）'
  });
}
