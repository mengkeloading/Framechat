function (inputJson, host) {
  var P = [
    /^(希望|但愿)[^。]{0,40}(帮助|有用)/,
    /^(如果|如有|若有)[^。]{0,80}(问题|需要|疑问)/,
    /^(还有|任选|随时|欢迎)[^。]{0,40}(问|找|告诉)/,
    /随时(都在|问|找|告诉)我/,
    /我会尽力(提供)?(帮助|解答)/,
    /(还有其他|有任何)(问题|需要|疑问)[^。]{0,20}(问|告诉|找)/
  ];
  // 保守规则：段落里带着实质内容（定义/结构/因果）就绝对不删，
  // 宁可漏剪也不要剪掉正文。
  var KEEP = /(是|包括|分为|核心|本质|结论|所以|因此)/;
  function isFluff(p) {
    if (!p || p.length > 160) return false;
    if (KEEP.test(p)) return false;
    for (var i = 0; i < P.length; i++) {
      if (P[i].test(p)) return true;
    }
    return false;
  }
  var inp = ModuleKit.parse(inputJson);
  var t = inp.text || '';
  if (!t) return null;
  var parts = t.split(/\n\s*\n/);
  if (parts.length === 1) {
    // 模型常用单换行分段：没有空行时只看最后一行，且整行要是套话才包
    var lines = t.split('\n');
    if (lines.length < 2) return null;
    var tail = lines[lines.length - 1].trim();
    if (!tail || tail.length >= 100 || !isFluff(tail)) return null;
    var keptLine = lines.slice(0, lines.length - 1).join('\n').replace(/\s+$/, '');
    if (!keptLine) return null;
    return ModuleKit.reply({
      type: 'rewrite',
      text: keptLine + '\n[[HIDE]]' + tail + '[[/HIDE]]'
    });
  }
  var cut = parts.length;
  for (var k = parts.length - 1; k >= 0 && k >= parts.length - 2; k--) {
    var p = parts[k].trim();
    if (!p) { cut = k; continue; }
    if (isFluff(p)) { cut = k; continue; }
    break;
  }
  if (cut >= parts.length) return null;
  var keptParts = parts.slice(0, cut).join('\n\n').replace(/\s+$/, '');
  if (!keptParts) return null;
  var fluffParts = parts.slice(cut).filter(function (x) { return x.trim(); });
  if (!fluffParts.length) return null;
  return ModuleKit.reply({
    type: 'rewrite',
    text: keptParts + '\n[[HIDE]]' + fluffParts.join('\n\n') + '[[/HIDE]]'
  });
}
