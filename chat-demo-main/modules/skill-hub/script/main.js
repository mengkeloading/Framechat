function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  var t = inp.text || '';
  if (!t.replace(/\s/g, "")) return null;
  var pkgs = [];
  try {
    pkgs = ModuleLoader.listPackages() || [];
  } catch (e) {
    return null;
  }
  var parts = [];
  var total = 0;
  var LIMIT = 6000;
  for (var i = 0; i < pkgs.length; i++) {
    var p = pkgs[i];
    if (p.id === 'skill_hub') continue;
    var refs = p.references || {};
    var doc = refs['SKILL.md'] || '';
    if (!doc) continue;
    if (total + doc.length > LIMIT) break;
    total += doc.length;
    parts.push('## 技能：' + (p.name || p.id) + '\n' + doc);
  }
  if (!parts.length) return null;
  var head = '【技能目录·自动装载】\n'
    + '下面每个技能是一份专业规范。判断用户最后一条消息属于哪个技能，'
    + '就严格按那份规范回答；都不相关就正常回答，不要硬套。\n\n';
  var extra = head + parts.join('\n\n---\n\n');
  return ModuleKit.reply({
    type: 'context', text: t, extra: extra
  });
}
