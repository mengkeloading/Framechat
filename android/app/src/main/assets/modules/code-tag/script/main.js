function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  if (inp.phase !== 'b') return null;
  var t = String(inp.text || '');
  if (t.indexOf('```') < 0) return null;
  var changed = t.replace(/```[ \t]*\n/g, '```text\n');
  if (changed === t) return null;
  return ModuleKit.reply({ type: 'rewrite', text: changed });
}
