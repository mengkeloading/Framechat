function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  var t = inp.text || '';
  if (!t.replace(/\s/g, '')) return null;

  // 只有「框架没给 shim」这一种情况才静默退出；
  // 其他异常直接抛，让 dispatcher 记进 trace —— 别再让 skill_hub 死得无声无息。
  if (typeof ModuleLoader === 'undefined') return null;

  var pkgs = ModuleLoader.listPackages();
  var parts = [];
  var total = 0;
  var LIMIT = 6000;
  for (var i = 0; i < pkgs.length; i++) {
    var p = pkgs[i];
    if (p.id === 'skill_hub' || !p.hasSkill) continue;
    var doc = '';
    try {
      doc = host.requestLocalAction('skill.read', JSON.stringify({ id: p.id }));
    } catch (e) {
      continue;   // 单个模块读不到就跳过，不影响其他
    }
    if (!doc) continue;
    if (total + doc.length > LIMIT) break;
    total += doc.length;
    parts.push('## 技能：' + p.name + '\n' + doc);
  }
  if (!parts.length) return null;

  var extra = '【技能目录·自动装载】\n'
    + '下面每个技能是一份专业规范。判断用户最后一条消息属于哪个技能，'
    + '就严格按那份规范回答；都不相关就正常回答，不要硬套。\n\n'
    + parts.join('\n\n---\n\n');
  return ModuleKit.reply({ type: 'context', text: t, extra: extra });
}
