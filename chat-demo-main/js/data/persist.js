/* persist.js — 会话与消息持久化（localStorage 快照）
 * Kotlin 侧对应 Room；这里用整库快照，够用且好回滚。
 */
(function (global) {
  'use strict';

  var KEY = 'mychat.v1';
  var PREFS = 'mychat.prefs';

  function available() {
    try {
      localStorage.setItem('__t', '1');
      localStorage.removeItem('__t');
      return true;
    } catch (e) { return false; }
  }

  function save(db) {
    if (!available()) return false;
    try {
      localStorage.setItem(KEY, JSON.stringify({
        v: 1,
        sessions: db.sessionDao.rows,
        messages: db.messageDao.rows,
        tools: db.toolCallDao.rows
      }));
      return true;
    } catch (e) { return false; }
  }

  function load(db) {
    if (!available()) return false;
    var raw = null;
    try { raw = localStorage.getItem(KEY); } catch (e) { return false; }
    if (!raw) return false;
    try {
      var o = JSON.parse(raw);
      if (!o || o.v !== 1) return false;
      if (Array.isArray(o.sessions)) db.sessionDao.rows = o.sessions;
      if (Array.isArray(o.messages)) db.messageDao.rows = o.messages;
      if (Array.isArray(o.tools)) db.toolCallDao.rows = o.tools;
      return db.sessionDao.rows.length > 0;
    } catch (e) { return false; }
  }

  function clear() {
    try { localStorage.removeItem(KEY); } catch (e) {}
  }

  function savePrefs(obj) {
    if (!available()) return false;
    try { localStorage.setItem(PREFS, JSON.stringify(obj || {})); return true; }
    catch (e) { return false; }
  }
  function loadPrefs() {
    if (!available()) return {};
    try { return JSON.parse(localStorage.getItem(PREFS) || '{}') || {}; }
    catch (e) { return {}; }
  }

  global.Persist = {
    save: save, load: load, clear: clear, available: available,
    savePrefs: savePrefs, loadPrefs: loadPrefs, KEY: KEY
  };
})(window);
