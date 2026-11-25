function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  if (!inp.text) return null;
  return ModuleKit.reply({ type: 'rewrite', text: '【路由模块已接管】' + inp.text });
}
