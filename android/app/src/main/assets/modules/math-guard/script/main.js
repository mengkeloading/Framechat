function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  if (inp.phase !== 'a') return null;
  if (String(inp.text).indexOf('算一下') < 0) return null;
  return ModuleKit.reply({
    type: 'context',
    text: inp.text,
    extra: '要求：分步计算，每一步写清算式与结果。'
  });
}
