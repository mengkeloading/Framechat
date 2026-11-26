function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  if (!inp.ctx || !inp.ctx.forced) return null;   // 只被「附加：」强制时才注入

  var t = (inp.text || '').trim();
  if (!t) return null;

  return ModuleKit.reply({
    type: 'context',
    text: inp.text,
    extra: '本轮附加要求：' + t
  });
}
