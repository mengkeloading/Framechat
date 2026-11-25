window.log && window.log('templates.js loaded');

(function() {
  const I = window.ICONS;
  const appHTML = `
    <div id="drawer-overlay" onclick="toggleDrawer()"></div>
    <div id="drawer">
      <div class="drawer-header"><span>历史对话</span><span onclick="toggleDrawer()" style="cursor:pointer;font-size:24px;line-height:1;">×</span></div>
      <div class="new-chat-btn" onclick="newChat()">＋ 开启新对话</div>
      <div class="chat-list" id="drawer-chat-list"></div>
      <div class="drawer-footer" onclick="openSettings()">⚙️ 设置</div>
    </div>
    <div class="header" style="display:flex;justify-content:space-between;padding:12px 16px;align-items:center;">
      <div style="display:flex;align-items:center;gap:12px;">
        <div class="icon-btn" onclick="toggleDrawer()">${I.menu}</div>
        <span style="font-weight:600;font-size:17px;">新对话</span>
      </div>
      <div style="display:flex;gap:16px;">
        <div class="icon-btn" id="tts-btn" onclick="toggleTTS()">${I.tts}</div>
        <div class="icon-btn" onclick="newChat()">${I.newChat}</div>
      </div>
    </div>
    <div class="chat-box" id="chat-box"></div>
    <div class="bottom-area">
      <div class="input-card">
        <textarea id="userInput" placeholder="发消息或按住说话" rows="1"></textarea>
        <div class="input-tools">
          <div style="display:flex;gap:8px;">
            <div class="tool-icon" onclick="openSettings()">${I.settings}</div>
            <div class="tool-icon" style="width:auto;padding:0 12px;border-radius:16px;font-size:14px;">✨ 模型 ∨</div>
          </div>
          <div style="display:flex;gap:8px;align-items:center;">
            <div class="tool-icon">${I.plus}</div>
            <button class="send-btn" onclick="sendMessage()">${I.send}</button>
          </div>
        </div>
      </div>
    </div>
    <div class="overlay-page" id="settings-page">
      <div class="settings-header">
        <div class="back-btn" onclick="closeSettings()">${I.back}</div>
        <div>设置</div>
      </div>
      <div class="settings-content">
        <div class="group-title">账户</div>
        <div class="settings-group">
          <div class="settings-item" onclick="openSubPage('data-page')"><div>数据管理</div><div class="right">导入 / 导出 / 清空 ></div></div>
          <div class="settings-item" onclick="openSubPage('api-page')"><div>API 配置</div><div class="right">DeepSeek 官方 ></div></div>
          <div class="settings-item"><div>账户余额</div><div class="right">¥2.94 ></div></div>
        </div>
        <div class="group-title" style="margin-top:24px;">个性化</div>
        <div class="settings-group">
          <div class="settings-item" onclick="toggleTheme()"><div>外观</div><div class="right">跟随系统 · 蓝色 ></div></div>
          <div class="settings-item"><div>语言</div><div class="right">简体中文 ></div></div>
        </div>
      </div>
    </div>
    <div class="overlay-page" id="api-page">
      <div class="settings-header">
        <div class="back-btn" onclick="closeSubPage('api-page')">${I.back}</div>
        <div>API 配置</div>
      </div>
      <div class="settings-content">
        <div style="background:var(--bg-card);border-radius:16px;padding:16px;">
          <div style="color:var(--primary);font-size:16px;font-weight:600;margin-bottom:4px;">DeepSeek 官方</div>
          <div style="font-size:13px;color:var(--text-sub);margin-bottom:16px;">修改后自动保存，仅保存在本机。</div>
          <label>API Key</label>
          <input type="password" class="setting-input" id="apiKeyInput" placeholder="sk-...">
          <button onclick="saveApiKey()" style="margin-top:16px;width:100%;padding:12px;background:var(--primary);color:white;border:none;border-radius:8px;font-weight:bold;">保存</button>
        </div>
      </div>
    </div>
  `;
  document.body.innerHTML = appHTML;
})();
