/* moduleloader.js — 模块包注册表（skill 风格）
 * 包由打包期展开成 registerPackage(...)，字段与 MODULE.md front matter 一致。
 * Kotlin 侧：ModuleRegistry 运行期扫 assets/modules/<folder>/ 得到同样的结构。
 */
(function (global) {
  'use strict';

  var packages = {};
  var legacy = {};   /* 兼容 registerHard，测试里临时注册用 */

  function registerPackage(p) {
    if (!p || !p.id) throw new Error('registerPackage: 缺少 id');
    packages[p.id] = {
      id: p.id,
      name: p.name || p.id,
      description: p.description || '',
      version: p.version || '0.0.0',
      hooks: p.hooks || [],
      inject: p.inject || (p.entry ? 'none' : 'full'),
      capabilities: p.capabilities || {},
      entry: p.entry || (p.scripts && p.scripts['main.js'] ? 'main.js' : null),
      body: p.body || '',
      references: p.references || {},
      scripts: p.scripts || {}
    };
  }

  /* 兼容旧接口：把单个函数包成一个最小包 */
  function registerHard(name, fn) {
    legacy[name] = fn;
    packages[name] = {
      id: name, name: name, description: '', version: '0.0.0',
      hooks: ['input', 'output'], inject: 'none', capabilities: {},
      entry: 'main.js', body: '', references: {},
      scripts: { 'main.js': '(' + fn.toString() + ')' }
    };
  }

  function getPackage(id) { return packages[id] || null; }
  function listPackages() {
    return Object.keys(packages).map(function (k) { return packages[k]; });
  }
  function idsForPhase(chain, phase) {
    return (chain || []).filter(function (id) {
      var p = packages[id];
      return p && p.hooks.indexOf(phase) >= 0;
    });
  }
  function getReference(id, name) {
    var p = packages[id];
    if (!p) return null;
    if (name === 'MODULE.md') return p.body;
    return Object.prototype.hasOwnProperty.call(p.references, name) ? p.references[name] : null;
  }
  function getScript(id, file) {
    var p = packages[id];
    if (!p) return null;
    return Object.prototype.hasOwnProperty.call(p.scripts, file) ? p.scripts[file] : null;
  }

  global.ModuleLoader = {
    registerPackage: registerPackage,
    registerHard: registerHard,
    getPackage: getPackage,
    listPackages: listPackages,
    idsForPhase: idsForPhase,
    getReference: getReference,
    getScript: getScript
  };
})(window);
