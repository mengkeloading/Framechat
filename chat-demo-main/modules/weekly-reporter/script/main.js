function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  var src = (inp.ctx && inp.ctx.input) || '';
  var text = String(inp.text || '');
  if (src.indexOf('周报') < 0 && text.indexOf('周报') < 0) return null;

  var style = host.requestLocalAction('resource.read', JSON.stringify({ name: 'style.md' }));
  var raw = src.replace(/^[^:：]*[:：]/, '');
  var tasks = raw.split(/[、,，]/).filter(function (t) { return t.trim(); })
    .map(function (t) { return '- ' + t.trim(); });
  return ModuleKit.reply({
    type: 'rewrite',
    text: '【周报排版】\n' + style.trim() + '\n' + tasks.join('\n')
  });
}
