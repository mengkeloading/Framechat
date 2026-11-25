window.log && window.log('app.js loaded');

// 入口：所有模块已按顺序加载完毕，这里只做启动
(function() {
  try {
    // 主题
    const savedTheme = localStorage.getItem('ds_theme') || 'dark';
    document.body.setAttribute('data-theme', savedTheme);

    // 回填 API Key
    const keyInput = document.getElementById('apiKeyInput');
    if (keyInput) keyInput.value = AppState.config.apiKey;

    // 启动会话
    if (AppState.chats.length > 0) loadChat(AppState.chats[0].id);
    else newChat();
  } catch (e) {
    console.error('[启动失败]', e);
    document.body.innerHTML = '<div style="padding:40px;color:#e00;font-family:monospace;">启动出错：'
      + e.message + '<br><br>请按 F12 查看控制台。</div>';
  }
})();
