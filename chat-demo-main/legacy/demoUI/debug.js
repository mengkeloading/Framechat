(function(){
  window.__logs = [];
  var panel = document.createElement('div');
  panel.id = '__debug_panel';
  panel.style.cssText = 'position:fixed;top:0;left:0;right:0;max-height:40vh;overflow:auto;background:#111;color:#0f0;font:12px/1.5 monospace;padding:8px;z-index:999999;display:none;white-space:pre-wrap;word-break:break-all;';
  document.documentElement.appendChild(panel);

  var btn = document.createElement('div');
  btn.textContent = '🐞';
  btn.style.cssText = 'position:fixed;right:8px;bottom:80px;width:40px;height:40px;border-radius:50%;background:#e00;color:#fff;display:flex;align-items:center;justify-content:center;font-size:20px;z-index:999999;';
  btn.onclick = function(){
    panel.style.display = panel.style.display === 'none' ? 'block' : 'none';
  };
  document.documentElement.appendChild(btn);

  function push(msg, color){
    var line = (color === 'err' ? '❌ ' : color === 'ok' ? '✅ ' : '• ') + msg;
    window.__logs.push(line);
    panel.textContent = window.__logs.join('\n');
    if (color === 'err') panel.style.display = 'block';
  }
  window.log = push;

  window.addEventListener('error', function(e){
    push('JS错误: ' + e.message + ' @ ' + String(e.filename||'').split('/').pop() + ':' + e.lineno, 'err');
  });
  window.addEventListener('unhandledrejection', function(e){
    push('Promise错误: ' + ((e.reason && e.reason.message) || e.reason), 'err');
  });
  push('debug.js 已加载', 'ok');
})();
