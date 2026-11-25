function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  if (inp.text.indexOf('附加：') !== 0) return null;
  var body = inp.text.slice(3).trim();
  if (!body) return null;
  return ModuleKit.reply({
    type: 'context',
    text: inp.text,
    extra: '本轮附加要求：' + body
  });
}
