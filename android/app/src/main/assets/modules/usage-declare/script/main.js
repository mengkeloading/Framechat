function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  if (!inp.text) return null;
  return ModuleKit.reply({
    type: 'context',
    text: inp.text,
    extra: '【输出规范】\n'
      + '你必须在回答的最后一行单独写一行，声明你使用了哪些技能模块，例如：\n'
      + '[[USED:skill_code]]\n'
      + '多个模块用逗号分隔：[[USED:skill_code,skill_novel]]\n'
      + '如果你没有使用任何技能模块的规范，就不要写这一行。'
  });
}
