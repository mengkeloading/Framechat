import { runModule } from './executor.js';

// 启用名单（这里直接填中文名）
const ACTIVE_MODULES = ['分析聊天'];

export async function hookA(rawInput) {
    let cur = rawInput;
    const extras = [];
    for (const name of ACTIVE_MODULES) {
        try {
            const res = JSON.parse(await runModule(name, JSON.stringify({ text: cur, phase: 'A' })));
            if (res.type === 'local') return res;
            if (res.type === 'context') {
                if (res.extra) extras.push(res.extra);
                if (res.text) cur = res.text;
            } else if (res.text) cur = res.text;
        } catch (e) { console.warn(`[hookA] 模块 ${name} 异常`, e); }
    }
    return extras.length ? { type: 'context', text: cur, extra: extras.join('\n') } : { type: 'pass', text: cur };
}

export async function hookB(rawText, depth = 0) {
    if (depth > 3) return { type: 'pass', text: rawText };
    let cur = rawText;
    let rew = false;
    for (const name of ACTIVE_MODULES) {
        try {
            const res = JSON.parse(await runModule(name, JSON.stringify({ text: cur, phase: 'B' })));
            if (res.type === 'rewrite') { cur = res.text || cur; rew = true; }
            else if (res.type === 'invoke') {
                const ir = JSON.parse(await runModule(res.moduleName, res.payload || '{}'));
                if (ir.type === 'rewrite' && ir.text) { cur = ir.text; rew = true; }
                return await hookB(cur, depth + 1);
            }
        } catch (e) { console.warn(`[hookB] 模块 ${name} 异常`, e); }
    }
    return { type: rew ? 'rewrite' : 'pass', text: cur };
}
