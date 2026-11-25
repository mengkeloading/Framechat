/* md.js — 迷你 Markdown：代码块 / 行内代码 / 粗体 / 列表 / 标题 / 分隔线
 * 只做聊天场景够用的子集，不追求完整。
 */
(function (global) {
  'use strict';

  function esc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
                    .replace(/>/g, '&gt;');
  }

  function inline(s) {
    var out = esc(s);
    out = out.replace(/`([^`]+)`/g, '<code>$1</code>');
    out = out.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    return out;
  }

  function codeBlock(b) {
    var lang = b.lang || 'text';
    return '<div class="codeblock"><div class="banner"><span>' + esc(lang) +
           '</span><button class="copycode">复制</button></div><pre>' +
           esc(b.code) + '</pre></div>';
  }


  function parseTable(lines, start) {
    var rows = [];
    var i = start;
    while (i < lines.length && lines[i].trim().indexOf('|') === 0) {
      rows.push(lines[i].trim());
      i += 1;
    }
    if (rows.length < 2) return null;
    var sep = rows[1].replace(/\|/g, '').trim();
    if (!/^[-: ]+$/.test(sep)) return null;
    var cells = function (line) {
      return line.replace(/^\|/, '').replace(/\|$/, '')
        .split('|').map(function (s) { return s.trim(); });
    };
    var head = cells(rows[0]);
    var body = rows.slice(2).map(cells);
    var html = '<table class="mtable"><thead><tr>';
    head.forEach(function (h) { html += '<th>' + inline(h) + '</th>'; });
    html += '</tr></thead><tbody>';
    body.forEach(function (r) {
      html += '<tr>';
      r.forEach(function (c) { html += '<td>' + inline(c) + '</td>'; });
      html += '</tr>';
    });
    html += '</tbody></table>';
    return { html: html, next: i };
  }

  function render(src) {
    var text = String(src == null ? '' : src);
    var blocks = [];
    text = text.replace(/```([a-zA-Z0-9_+\-]*)\r?\n([\s\S]*?)```/g,
      function (m, lang, code) {
        blocks.push({ lang: lang, code: code });
        return '\u0000B' + (blocks.length - 1) + '\u0000';
      });

    var lines = text.split(/\r?\n/);
    var out = [];
    var inList = false;

    function closeList() { if (inList) { out.push('</ul>'); inList = false; } }

    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      if (line.trim().indexOf('|') === 0) {
        var tb = parseTable(lines, i);
        if (tb) {
          closeList();
          out.push(tb.html);
          i = tb.next - 1;
          continue;
        }
      }
      var m = line.match(/^\u0000B(\d+)\u0000$/);
      if (m) { closeList(); out.push(codeBlock(blocks[parseInt(m[1], 10)])); continue; }
      if (!line.trim()) { closeList(); continue; }
      m = line.match(/^(#{1,3})\s+(.*)$/);
      if (m) { closeList(); var n = m[1].length; out.push('<h' + n + '>' + inline(m[2]) + '</h' + n + '>'); continue; }
      m = line.match(/^[-*]\s+(.*)$/);
      if (m) {
        if (!inList) { out.push('<ul>'); inList = true; }
        out.push('<li>' + inline(m[1]) + '</li>');
        continue;
      }
      if (/^---+$/.test(line)) { closeList(); out.push('<hr>'); continue; }
      closeList();
      out.push('<p>' + inline(line) + '</p>');
    }
    closeList();
    return out.join('');
  }

  global.MiniMd = { render: render, esc: esc };
})(window);
