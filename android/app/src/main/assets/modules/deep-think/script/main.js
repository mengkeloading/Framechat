function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  var t = inp.text || '';
  if (t.indexOf('深想：') !== 0) return null;
  var body = t.slice(3).trim();
  if (!body) return null;
  var guide = '本轮请先深度思考再作答。'
    + '第一步拆解关键要素；'
    + '第二步列出不确定处与必要假设；'
    + '第三步给出结论与理由。';
  return ModuleKit.reply({
    type: 'context',
    text: inp.text,
    extra: guide
  });
}
