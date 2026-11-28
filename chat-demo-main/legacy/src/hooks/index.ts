import analyzeChatSource from '../hard_modules/analyze_chat/index.js?raw';
import analyzeChatMd from '../hard_modules/analyze_chat/README.md?raw';
import formatReplySource from '../hard_modules/format_reply/index.js?raw';
import formatReplyMd from '../hard_modules/format_reply/README.md?raw';

// 注册所有硬模块
const MODULE_SOURCES: Record<string, string> = {
  analyze_chat: analyzeChatSource,
  format_reply: formatReplySource,
};

const ACTIVE_MODULES = ['analyze_chat'];

// 执行器：用 new Function 执行纯 JS 代码
function runModule(name: string, inputJson: string): string {
  const source = MODULE_SOURCES[name];
  if (!source) throw new Error(`模块 ${name} 不存在`);
  const fn = new Function('input', `"use strict";\n${source}\nif (typeof handle !== 'function') throw new Error('no handle');\nreturn handle(input);`);
  return fn(inputJson);
}

export async function hookA(rawInput: string) {
  let cur = rawInput;
  const extras: string[] = [];
  for (const name of ACTIVE_MODULES) {
    try {
      const res = JSON.parse(runModule(name, JSON.stringify({ text: cur, phase: 'A' })));
      if (res.type === 'local') return { type: 'local', text: res.text };
      if (res.type === 'context') {
        if (res.extra) extras.push(res.extra);
        if (res.text) cur = res.text;
      } else if (res.text) cur = res.text;
    } catch (e) { console.warn(`[hookA] 模块 ${name} 异常`, e); }
  }
  return { type: extras.length ? 'context' : 'pass', text: cur, extra: extras.join('\n') };
}

export async function hookB(rawText: string, depth = 0) {
  if (depth > 3) return { type: 'pass', text: rawText };
  let cur = rawText;
  let rew = false;
  for (const name of ACTIVE_MODULES) {
    try {
      const res = JSON.parse(runModule(name, JSON.stringify({ text: cur, phase: 'B' })));
      if (res.type === 'rewrite') { cur = res.text || cur; rew = true; }
      else if (res.type === 'invoke') {
        const ir = JSON.parse(runModule(res.moduleName, res.payload || '{}'));
        if (ir.type === 'rewrite' && ir.text) { cur = ir.text; rew = true; }
        return await hookB(cur, depth + 1);
      }
    } catch (e) { console.warn(`[hookB] 模块 ${name} 异常`, e); }
  }
  return { type: rew ? 'rewrite' : 'pass', text: cur };
}
