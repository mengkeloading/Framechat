function (inputJson, host) {
  var args;
  try { args = JSON.parse(inputJson); } catch (e) { args = {}; }
  return JSON.stringify({
    ok: true,
    summary: '收到参数：' + JSON.stringify(args)
  });
}
