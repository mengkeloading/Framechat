window.log && window.log('settings.js loaded');

// 设置 / 主题 / API 配置
window.openSettings = function() {
  document.getElementById('settings-page').classList.add('show');
  if (document.getElementById('drawer').classList.contains('open')) toggleDrawer();
};
window.closeSettings = function() { document.getElementById('settings-page').classList.remove('show'); };
window.openSubPage = function(id) { document.getElementById(id).classList.add('show'); };
window.closeSubPage = function(id) { document.getElementById(id).classList.remove('show'); };

window.toggleTheme = function() {
  const current = document.body.getAttribute('data-theme');
  document.body.setAttribute('data-theme', current === 'dark' ? 'light' : 'dark');
  localStorage.setItem('ds_theme', document.body.getAttribute('data-theme'));
};

window.saveApiKey = function() {
  const key = document.getElementById('apiKeyInput').value.trim();
  if (key) { AppState.saveKey(key); alert('已保存'); closeSubPage('api-page'); }
};
