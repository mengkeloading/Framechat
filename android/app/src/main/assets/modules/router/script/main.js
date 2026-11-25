function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  if (inp.text.indexOf('路由:') !== 0) return null;
  return ModuleKit.reply({
    type: 'invoke',
    moduleName: 'formatter',
    payload: inp.text.slice(3).trim()
  });
}
