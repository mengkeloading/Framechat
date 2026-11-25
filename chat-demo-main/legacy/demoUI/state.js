window.log && window.log('state.js loaded');
function safeGetItem(k, def) {
  try { var v = localStorage.getItem(k); return v === null ? def : v; }
  catch(e) { return def; }
}
function safeSetItem(k, v) {
  try { localStorage.setItem(k, v); } catch(e) {}
}


// 全局状态集中管理，避免各模块各自为政
window.AppState = {
  config: { apiKey: safeGetItem('ds_key', '') },
  chats: JSON.parse(safeGetItem('ds_chats', '[]')),
  currentChatId: null,
  isTTSEnabled: false,

  saveChats() { safeSetItem('ds_chats', JSON.stringify(this.chats)); },
  saveKey(key) { safeSetItem('ds_key', key); this.config.apiKey = key; },
  getCurrentChat() { return this.chats.find(c => c.id === this.currentChatId); }
};
