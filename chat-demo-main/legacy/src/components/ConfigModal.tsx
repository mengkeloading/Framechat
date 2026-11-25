import React, { useState, useEffect } from 'react';

export function ConfigModal() {
  const [isOpen, setIsOpen] = useState(false);
  const [name, setName] = useState('默认助手');
  const [baseUrl, setBaseUrl] = useState('https://api.deepseek.com/v1');
  const [apiKey, setApiKey] = useState('');

  useEffect(() => {
    const saved = localStorage.getItem('mychat_config');
    if (saved) {
      const cfg = JSON.parse(saved);
      setName(cfg.name || '默认助手');
      setBaseUrl(cfg.baseUrl || 'https://api.deepseek.com/v1');
      setApiKey(cfg.apiKey || '');
    } else {
      setIsOpen(true); // 没有配置过，自动弹出
    }
  }, []);

  const handleSave = () => {
    if (!apiKey.trim()) {
      alert('API Key 不能为空');
      return;
    }
    localStorage.setItem('mychat_config', JSON.stringify({ name, baseUrl, apiKey }));
    setIsOpen(false);
    window.location.reload(); // 刷新页面以应用新配置
  };

  if (!isOpen) return null;

  return (
    <div style={{
      position: 'fixed', top: 0, left: 0, width: '100vw', height: '100vh',
      background: 'rgba(0,0,0,0.5)', display: 'flex', justifyContent: 'center',
      alignItems: 'center', zIndex: 9999, backdropFilter: 'blur(4px)'
    }}>
      <div style={{
        background: '#fff', padding: 24, borderRadius: 16, width: '90%', maxWidth: 400,
        boxShadow: '0 10px 25px rgba(0,0,0,0.1)', display: 'flex', flexDirection: 'column', gap: 16
      }}>
        <h2 style={{ textAlign: 'center', margin: 0, fontSize: 20, color: '#333' }}>模型 API 配置</h2>
        
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <label style={{ fontSize: 14, color: '#666', fontWeight: 500 }}>展示名称</label>
          <input type="text" value={name} onChange={e => setName(e.target.value)}
            style={{ padding: 10, border: '1px solid #ddd', borderRadius: 8, fontSize: 15 }} />
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <label style={{ fontSize: 14, color: '#666', fontWeight: 500 }}>API Base URL</label>
          <input type="text" value={baseUrl} onChange={e => setBaseUrl(e.target.value)}
            style={{ padding: 10, border: '1px solid #ddd', borderRadius: 8, fontSize: 15 }} />
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <label style={{ fontSize: 14, color: '#666', fontWeight: 500 }}>API Key (必填)</label>
          <input type="password" value={apiKey} onChange={e => setApiKey(e.target.value)}
            placeholder="sk-..." style={{ padding: 10, border: '1px solid #ddd', borderRadius: 8, fontSize: 15 }} />
        </div>

        <button onClick={handleSave} style={{
          padding: 12, background: '#2b5eff', color: '#fff', border: 'none',
          borderRadius: 20, fontSize: 16, fontWeight: 'bold', cursor: 'pointer', marginTop: 8
        }}>保存并开始聊天</button>
      </div>
    </div>
  );
}
