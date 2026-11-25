(function (global) {
  'use strict';
  function markdown(db, sessionId) {
    var s = db.sessionDao.get(sessionId) || {};
    var rows = db.messageDao.getBySession(sessionId);
    var out = [];
    out.push('# ' + (s.title || '未命名'));
    out.push('');
    for (var i = 0; i < rows.length; i++) {
      var r = rows[i];
      if (r.role === 'system') continue;
      out.push(r.role === 'user' ? '## 用户' : '## 助手');
      out.push('');
      out.push(String(r.content || ''));
      out.push('');
    }
    return out.join('\n');
  }
  global.ExportSession = { markdown: markdown };
})(window);
