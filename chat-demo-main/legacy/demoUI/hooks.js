window.log && window.log('hooks.js loaded');

// 钩子 A：发送前拦截/分流；钩子 B：回复后改写
window.hookA = async function(text) {
  if (text.includes('本地')) return { type: 'local', text: '【本地拦截】这句话不需要请求 AI。' };
  return { type: 'pass', text };
};
window.hookB = async function(text) {
  if (text.includes('调用test')) return { type: 'rewrite', text: '【已处理：test 任务】' };
  return { type: 'pass', text };
};
