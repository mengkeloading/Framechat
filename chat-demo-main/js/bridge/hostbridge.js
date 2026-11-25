/* hostbridge.js — 单点代理（对应 Magisk 的 magiskd）
 * 模块只拿到 host，拿不到文件系统、拿不到 registry、拿不到 API Key。
 * 审计是全局单例（相当于 magisk.log）。
 */
(function (global) {
  'use strict';

  var AUDIT = [];          /* 全局审计，跨实例共享 */
  var CAPS = { clock: false };

  function create(pkgId) {
    var pkg = ModuleLoader.getPackage(pkgId);
    if (!pkg) throw new Error('host: 未知模块 ' + pkgId);
    return new Bridge(pkg);
  }

  function Bridge(pkg) {
    this.pkgId = pkg.id;
    this.pkg = pkg;
    this.counters = {};
  }

  Bridge.prototype._allow = function (action) {
    var quota = this.pkg.capabilities[action];
    if (typeof quota !== 'number') {
      AUDIT.push({ pkg: this.pkgId, action: action, ok: false, err: 'not_declared' });
      throw new Error('capability_not_declared: ' + action);
    }
    this.counters[action] = (this.counters[action] || 0) + 1;
    if (this.counters[action] > quota) {
      AUDIT.push({ pkg: this.pkgId, action: action, ok: false, err: 'quota_exceeded' });
      throw new Error('quota_exceeded: ' + action + ' (' + quota + ')');
    }
  };

  Bridge.prototype.requestLocalAction = function (action, payload) {   /* 同步：模块无法 await */
    this._allow(action);

    if (action === 'resource.read') {
      var name = JSON.parse(payload || '{}').name;
      var content = ModuleLoader.getReference(this.pkgId, name);
      if (typeof content !== 'string') {
        AUDIT.push({ pkg: this.pkgId, action: action, ok: false, err: 'not_found' });
        throw new Error('resource_not_found: ' + name);
      }
      AUDIT.push({ pkg: this.pkgId, action: action, ok: true, arg: name });
      return content;
    }
    if (action === 'data.session') {
      var sid = global.__currentId || null;
      if (!sid) throw new Error('no_session');
      var names = ['__db', 'db', 'Db', 'AppDb', 'MyChatDb'];
      var D = null;
      for (var ni = 0; ni < names.length; ni++) {
        if (global[names[ni]]) { D = global[names[ni]]; break; }
      }
      if (!D && global.Dao) D = global.Dao;
      if (!D) throw new Error('db_not_ready: none of ' + names);
      if (!D.sessionDao) {
        throw new Error('db_no_dao: ' + Object.keys(D).join(','));
      }
      var ss = D.sessionDao.get(sid) || {};
      var rr = D.messageDao ? D.messageDao.getBySession(sid) : [];
      return JSON.stringify({
        session: ss,
        messages: (rr || []).slice(-20)
      });
    }
    if (action === 'clock.now') {
      AUDIT.push({ pkg: this.pkgId, action: action, ok: true });
      return new Date().toISOString();
    }
    AUDIT.push({ pkg: this.pkgId, action: action, ok: false, err: 'not_implemented' });
    throw new Error('capability_not_implemented: ' + action);
  };

  global.HostBridge = {
    create: create,
    audit: function () { return AUDIT.slice(); },
    clearAudit: function () { AUDIT.length = 0; },
    capabilities: CAPS
  };
})(window);
