/* errormapper.js — 对应 ErrorMapper.kt。JS 只管抛，分类都在这里。 */
(function (global) {
  'use strict';

  function map(e) {
    var m = (e && e.message) || String(e);
    if (/^http_401/.test(m)) return '鉴权失败（401），请检查 API Key';
    if (/^http_429/.test(m)) return '请求过于频繁（429），请稍后重试';
    if (/^http_5/.test(m)) return '服务端错误，请稍后重试';
    if (/timeout|network|Failed to fetch/i.test(m)) return '网络异常，请检查连接';
    if (/module_not_found/.test(m)) return '模块不存在：' + m;
    return '发生错误：' + m;
  }
  function mapJsError(e) {
    return {
      json: JSON.stringify({ ok: false, error: String((e && e.message) || e) }),
      isError: true,
      err: String((e && e.message) || e)
    };
  }

  global.ErrorMapper = { map: map, mapJsError: mapJsError };
})(window);
