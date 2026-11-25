/* daos.js — 对应三个 Entity + Dao + AppDatabase
 * 已修：占位判定改用 pending；上下文排除 system / tool / pending
 */
(function (global) {
  'use strict';

  var seq = 0;
  function now() { seq += 1; return Date.now() * 1000 + seq; }
  function uuid() { return 'id-' + (++seq) + '-' + Math.random().toString(36).slice(2, 8); }

  function MessageDao() { this.rows = []; }
  MessageDao.prototype.insert = function (msg) {
    var row = {
      id: msg.id || uuid(),
      sessionId: msg.sessionId,
      role: msg.role,
      content: msg.content || '',
      toolCallId: msg.toolCallId || null,
      local: !!msg.local,
      pending: !!msg.pending,
      timestamp: msg.timestamp || now()
    };
    this.rows.push(row);
    return row.id;
  };
  MessageDao.prototype.update = function (id, patch) {
    for (var i = 0; i < this.rows.length; i++) {
      if (this.rows[i].id !== id) continue;
      for (var k in patch) if (Object.prototype.hasOwnProperty.call(patch, k)) {
        this.rows[i][k] = patch[k];
      }
      return true;
    }
    return false;
  };
  MessageDao.prototype.removeBySession = function (s) {
    var n = 0;
    this.rows = this.rows.filter(function (r) {
      if (r.sessionId === s) { n++; return false; }
      return true;
    });
    return n;
  };
          MessageDao.prototype.getBySession = function (sessionId) {
    return this.rows.filter(function (r) { return r.sessionId === sessionId; });
  };
  /* 上下文原文：不含 system、不含历史 tool 明细、不含流式占位 */
  MessageDao.prototype.get = function (id) {
    for (var i = 0; i < this.rows.length; i++) if (this.rows[i].id === id) return this.rows[i];
    return null;
  };
  MessageDao.prototype.remove = function (id) {
    var n = 0;
    this.rows = this.rows.filter(function (r) { if (r.id === id) { n++; return false; } return true; });
    return n;
  };
  MessageDao.prototype.deleteAfter = function (sessionId, id) {
    var anchor = null;
    for (var i = 0; i < this.rows.length; i++) if (this.rows[i].id === id) anchor = this.rows[i];
    if (!anchor) return 0;
    var before = this.rows.length;
    this.rows = this.rows.filter(function (r) {
      return !(r.sessionId === sessionId && r.timestamp > anchor.timestamp);
    });
    return before - this.rows.length;
  };
  MessageDao.prototype.getContextForRequest = function (sessionId) {
    return this.rows.filter(function (r) {
      if (r.sessionId !== sessionId) return false;
      if (r.role === 'system') return false;
      if (r.role === 'tool') return false;
      if (r.pending === true) return false;
      return true;
    }).map(function (r) {
      return { role: r.role, content: r.content, toolCallId: r.toolCallId };
    });
  };

  function ToolCallDao() { this.rows = []; }
  ToolCallDao.prototype.insert = function (rec) {
    var row = {
      id: rec.id || uuid(), messageId: rec.messageId,
      rawRequestJson: rec.rawRequestJson, rawResultJson: null,
      success: null, errorMessage: null, timestamp: now()
    };
    this.rows.push(row);
    return row.id;
  };
  ToolCallDao.prototype.update = function (id, patch) {
    for (var i = 0; i < this.rows.length; i++) {
      if (this.rows[i].id !== id) continue;
      for (var k in patch) if (Object.prototype.hasOwnProperty.call(patch, k)) {
        this.rows[i][k] = patch[k];
      }
      return true;
    }
    return false;
  };

  function SessionDao() { this.rows = []; }
            /* 会话字段清单：加新字段只改这里，insert / update 自动跟随 */
          var SESSION_FIELDS = [
            'title', 'providerId', 'model', 'baseUrl',
            'activeSoftModules', 'activeHardModules',
            'maxToolRounds', 'contextBudget', 'systemPrompt', 'ttsAuto',
            'path', 'apiStyle', 'echoReasoning'
          ];

          SessionDao.prototype.insert = function (s) {
            var row = { id: s.id || uuid(), createdAt: now() };
            SESSION_FIELDS.forEach(function (k) {
              if (Object.prototype.hasOwnProperty.call(s, k) && s[k] !== undefined) {
                row[k] = s[k];
              }
            });
            if (row.title == null) row.title = '新对话';
            if (row.providerId == null) row.providerId = 'deepseek';
            if (!Array.isArray(row.activeSoftModules)) row.activeSoftModules = [];
            if (!Array.isArray(row.activeHardModules)) row.activeHardModules = [];
            if (typeof row.maxToolRounds !== 'number') row.maxToolRounds = 3;
            if (typeof row.contextBudget !== 'number') row.contextBudget = null;
            row.systemPrompt = row.systemPrompt || null;
            row.ttsAuto = !!row.ttsAuto;
            this.rows.push(row);
            return row.id;
          };
            SessionDao.prototype.update = function (id, patch) {
            var s = this.get(id);
            if (!s || !patch) return false;
            SESSION_FIELDS.forEach(function (k) {
              if (Object.prototype.hasOwnProperty.call(patch, k)) s[k] = patch[k];
            });
            return true;
          };

  SessionDao.prototype.remove = function (id) {
    var n = 0;
    this.rows = this.rows.filter(function (s) {
      if (s.id === id) { n++; return false; }
      return true;
    });
    return n;
  };
          SessionDao.prototype.get = function (id) {
    for (var i = 0; i < this.rows.length; i++) if (this.rows[i].id === id) return this.rows[i];
    return null;
  };

  function AppDatabase() {
    this.sessionDao = new SessionDao();
    this.messageDao = new MessageDao();
    this.toolCallDao = new ToolCallDao();
  }

    /* fallback remove */
  SessionDao.prototype.remove = function (id) {
    var before = this.rows.length;
    this.rows = this.rows.filter(function (s) {
      return s.id !== id;
    });
    return before - this.rows.length;
  };
  MessageDao.prototype.removeBySession = function (sid) {
    var before = this.rows.length;
    this.rows = this.rows.filter(function (r) {
      return r.sessionId !== sid;
    });
    return before - this.rows.length;
  };
  global.AppDatabase = AppDatabase;
    AppDatabase.prototype.removeSession = function (id) {
    var ids = {};
    this.messageDao.rows.forEach(function (m) {
      if (m.sessionId === id) ids[m.id] = 1;
    });
    this.messageDao.removeBySession(id);
    this.toolCallDao.rows = this.toolCallDao.rows.filter(function (r) {
      return !ids[r.messageId];
    });
    return this.sessionDao.remove(id);
  };
  global.uuid = uuid;
})(window);
