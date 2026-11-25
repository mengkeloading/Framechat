const MODULE_BASE = '/modules/';

export async function runModule(scriptName, inputJson) {
    // 支持中文路径的 fetch
    const url = MODULE_BASE + encodeURIComponent(scriptName) + '/index.js';
    const res = await fetch(url);
    if (!res.ok) throw new Error('[executor] 加载模块失败: ' + scriptName);
    const source = await res.text();
    
    const fn = new Function('input', `"use strict";\n${source}\nif (typeof handle !== 'function') throw new Error('no handle');\nreturn handle(input);`);
    const result = fn(inputJson);
    
    return typeof result === 'string' ? result : JSON.stringify(result);
}
