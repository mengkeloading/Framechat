
import { hookA, hookB } from '../hooksystem/index.js';
(window as any).__hookA = hookA;
(window as any).__hookB = hookB;
import { createRoot } from 'react-dom/client'
import { App } from './app/App.js'
import { setupTheme } from './app/theme.js'
import { ensureKatex } from './services/markdown/katex.js'
import './app/theme.css'
import './app/global.css'

ensureKatex()
setupTheme()

createRoot(document.getElementById('root') as HTMLElement).render(<App />)

import { createRoot } from 'react-dom/client';
import { ConfigModal } from './components/ConfigModal';

// 动态插入一个 DOM 节点用来挂载弹窗
const modalRoot = document.createElement('div');
modalRoot.id = 'config-modal-root';
document.body.appendChild(modalRoot);
createRoot(modalRoot).render(<ConfigModal />);
