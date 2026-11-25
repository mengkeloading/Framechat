function (inputJson, host) {
  function rep(s) {
    return ModuleKit.reply({
      type: 'local', text: s, content: s, reply: s });
  }
  function fmt(x) {
    return String(Math.round(x * 1e6) / 1e6);
  }
  var inp = ModuleKit.parse(inputJson);
  var t = String(inp.text || '').trim();
  if (!t || t.length > 60) return null;

  var m = t.match(
    /^(?:算一?下?|计算|calc)?\s*([-+*/%().\d\s]+?)\s*=?$/);
  if (m) {
    var e = m[1].replace(/\s/g, '');
    var ok = e.length >= 3 && e.length <= 40;
    ok = ok && /\d/.test(e) && /^[-+*/%().\d]+$/.test(e);
    if (ok) {
      var v = null;
      try {
        v = Function('"use strict";return (' + e + ')')();
      } catch (err) { v = null; }
      if (typeof v === 'number' && isFinite(v)) {
        return rep(e + ' = ' + fmt(v));
      }
    }
  }

  m = t.match(
    /^(0x[0-9a-fA-F]+|\d+)\s*(?:转|换成)\s*(\d+)\s*进制$/);
  if (m) {
    var n = m[1].indexOf('0x') === 0
      ? parseInt(m[1], 16) : parseInt(m[1], 10);
    var b = parseInt(m[2], 10);
    if (b >= 2 && b <= 36) {
      return rep(n + ' → ' + n.toString(b) + ' 进制');
    }
  }

  m = t.match(/^([\d.]+)\s*%\s*(?:的|of)\s*([\d.]+)$/);
  if (m) {
    var p = parseFloat(m[1]) * parseFloat(m[2]) / 100;
    return rep(m[1] + '% x ' + m[2] + ' = ' + fmt(p));
  }

  var P = t.split(/\s*(?:转|换成|to|1>)\s*/i);
  if (P.length === 2) {
    var L = P[0].replace(/\s/g, '');
    var R = P[1].replace(/\s/g, '');
    var lm = L.match(/^([\d.]+)(.+)$/);
    if (lm) {
      var x = parseFloat(lm[1]);
      var u = lm[2];
      var C = /^(度|℃|c|摄氏)$/i;
      var F = /^(度|℉|f|华氏)$/i;
      if (C.test(u) && F.test(R)) {
        return rep(lm[1] + 'C = ' + fmt(x * 9 / 5 + 32) + 'F');
      }
      if (F.test(u) && C.test(R)) {
        return rep(lm[1] + 'F = ' + fmt((x - 32) * 5 / 9) + 'C');
      }
      var U = {
        '公里': 1000, '千米': 1000, '米': 1, '厘米': 0.01,
        '毫米': 0.001, '英里': 1609.344, '英尺': 0.3048,
        '千克': 1000, '克': 1, '斤': 500, '磅': 453.592
      };
      var U2 = { km: 1000, m: 1, cm: 0.01, mm: 0.001,
        mile: 1609.344, ft: 0.3048, kg: 1000, g: 1,
        lb: 453.592 };
      var a = U[u] || U2[u];
      var c = U[R] || U2[R];
      if (a && c) {
        return rep(lm[1] + ' ' + u + ' = '
          + fmt(x * a / c) + ' ' + R);
      }
    }
  }

  return null;
}
