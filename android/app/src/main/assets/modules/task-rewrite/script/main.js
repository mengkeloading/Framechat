function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  var src = (inp.ctx && inp.ctx.input) || '';
  if (src.indexOf('执行任务') !== 0) return null;
  var task = src.slice(4).replace(/^[\s\u3000:：,，]+/, '').trim();
  if (!task) return null;
  return ModuleKit.reply({
    type: 'rewrite',
    text: '【排版模块已接管】我收到了任务：' + task
  });
}
