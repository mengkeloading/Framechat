/* executor.js — 对应 JsExecutor.kt。签名保持 execute(scriptRef, inputJson)
 * scriptRef 两种写法：
 *   "weekly_reporter/script/main.js"  -> 指定包 + 指定文件
 *   "analyze_chat"                    -> 只给包名，取它的 entry
 * 模块在包装函数里执行，宿主能力全部遮蔽；能力只能经 host 拿。
 */
(function (global) {
  'use strict';

  var SHADOW = 'var fetch=undefined,XMLHttpRequest=undefined,localStorage=undefined,' +
               'document=undefined,window=undefined,require=undefined,importScripts=undefined;';

  function resolve(ref) {
    var pkgId, file;
    if (ref.indexOf('/') >= 0) {
      pkgId = ref.slice(0, ref.indexOf('/'));
      file = ref.slice(ref.indexOf('/') + 1);
    } else {
      pkgId = ref;
      var p = ModuleLoader.getPackage(ref);
      file = p ? p.entry : null;
    }
    return { pkgId: pkgId, file: file };
  }

  function BrowserSandboxExecutor(loader, opts) {
    opts = opts || {};
    this.loader = loader;
    this.timeoutMs = opts.timeoutMs || 3000;
    this.calls = [];
  }

  BrowserSandboxExecutor.prototype.execute = async function (scriptRef, inputJson) {
    var r = resolve(scriptRef);
    var pkg = ModuleLoader.getPackage(r.pkgId);
    if (!pkg) throw new Error('module_not_found: ' + r.pkgId);
    var src = ModuleLoader.getScript(r.pkgId, r.file);
    if (!src) throw new Error('script_not_found: ' + scriptRef);

    var host = HostBridge.create(r.pkgId);
    var t0 = Date.now();
    try {
      var fn = new Function('inputJson', 'host',
        SHADOW + '\nreturn (' + src + ')(inputJson, host);');
      var out = fn(inputJson, host);
      this.calls.push({ ref: scriptRef, ms: Date.now() - t0, ok: true });
      return out;
    } catch (e) {
      this.calls.push({ ref: scriptRef, ms: Date.now() - t0, ok: false });
      throw e;
    }
  };

  global.BrowserSandboxExecutor = BrowserSandboxExecutor;
})(window);
