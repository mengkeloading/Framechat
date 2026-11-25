function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  if (!inp.text) return null;
  return ModuleKit.reply({
    type: 'context',
    text: inp.text,
    extra: '【输出规范】\n'
      + '如果你使用了某个技能模块的规范，在回答的最后单独一行写：\n'
      + '[[USED:模块id]]\n'
      + '多个模块用逗号分隔，如 [[USED:skill_code,skill_novel]]。\n'
      + '没有使用任何模块就不要写这一行。'
  });
}
