function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  var t = inp.text || '';
  if (!t.replace(/\s/g, '')) return null;

  // 只有「框架没给 shim」这一种情况才静默退出；
  // 其他异常直接抛，让 dispatcher 记进 trace —— 别再让 skill_hub 死得无声无息。
  if (typeof ModuleLoader === 'undefined') return null;

  var pkgs = ModuleLoader.listPackages();
  var parts = [];
  for (var i = 0; i < pkgs.length; i++) {
    var p = pkgs[i];
    if (p.id === 'skill_hub' || !p.hasSkill) continue;
    parts.push('- ' + p.name + '（' + p.id + '）：' + p.description);
  }
  if (!parts.length) return null;

  var extra = '【可用技能目录】\n'
    + '本机已安装下列技能。回答时用到哪个技能的规范，就按它的 id 在末尾声明。\n\n'
    + parts.join('\n');
  return ModuleKit.reply({ type: 'context', text: t, extra: extra });
}
