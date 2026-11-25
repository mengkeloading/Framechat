function (inputJson, host) {
  var inp = ModuleKit.parse(inputJson);
  if (inp.text.indexOf('几点') < 0) return null;
  var d = new Date();
  var hh = ('0' + d.getHours()).slice(-2);
  var mm = ('0' + d.getMinutes()).slice(-2);
  return ModuleKit.reply({
    type: 'local',
    text: '现在是 ' + hh + ':' + mm + '（本地截留，未发起网络请求）'
  });
}
