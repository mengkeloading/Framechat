window.log && window.log('drawer.js loaded');

// 抽屉 + 历史对话
window.toggleDrawer = function() {
  const d = document.getElementById('drawer'), o = document.getElementById('drawer-overlay');
  if (d.classList.contains('open')) {
    d.classList.remove('open'); o.classList.remove('show');
    setTimeout(() => o.style.display = 'none', 300);
  } else {
    o.style.display = 'block'; setTimeout(() => o.classList.add('show'), 10);
    d.classList.add('open');
    renderDrawerList();
  }
};

window.renderDrawerList = function() {
  const list = document.getElementById('drawer-chat-list');
  list.innerHTML = '';
  AppState.chats.forEach(c => {
    const div = document.createElement('div');
    div.className = 'chat-item' + (c.id === AppState.currentChatId ? ' active' : '');
    div.innerHTML = `<span>${c.title || '新对话'}</span><span class="del" onclick="event.stopPropagation();deleteChat('${c.id}')">×</span>`;
    div.onclick = () => { AppState.currentChatId = c.id; loadChat(c.id); toggleDrawer(); };
    list.appendChild(div);
  });
};

window.deleteChat = function(id) {
  AppState.chats = AppState.chats.filter(c => c.id !== id);
  if (AppState.currentChatId === id) newChat();
  else { AppState.saveChats(); renderDrawerList(); }
};

window.newChat = function() {
  const id = Date.now().toString();
  AppState.chats.unshift({ id, title: '新对话', messages: [] });
  AppState.currentChatId = id;
  AppState.saveChats();
  loadChat(id);
  if (document.getElementById('drawer').classList.contains('open')) toggleDrawer();
};
