#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""status.py — 只读检查：当前项目处于哪一步"""

from pathlib import Path
import time

ROOT = Path("/storage/emulated/0/demo/chat-demo-main")


def f(rel):
    return ROOT / rel


def mark(ok):
    return "OK  " if ok else "缺  "


print("项目根: " + str(ROOT))
print("")

# 1. 架构文档
arch = f("ARCHITECTURE.md")
print(mark(arch.exists()) + "ARCHITECTURE.md" +
      ("  (" + str(len(arch.read_text(encoding='utf-8').splitlines())) + " 行)" if arch.exists() else ""))

# 2. 旧版目录（移植前）
print(mark(f("js/vm/chatviewmodel.js").exists()) + "js/ 运行时目录")

# 3. 新版模块目录（移植后）
mods = f("modules")
pkgs = sorted([d.name for d in mods.iterdir() if d.is_dir()]) if mods.is_dir() else []
print(mark(bool(pkgs)) + "modules/ 模块包目录  共 " + str(len(pkgs)) + " 个")
for p in pkgs:
    has_md = (mods / p / "MODULE.md").exists()
    has_js = (mods / p / "script").is_dir()
    has_ref = (mods / p / "reference").is_dir()
    print("      - " + p + ("  MD" if has_md else "  !!缺MODULE.md")
          + (" +script" if has_js else "") + (" +reference" if has_ref else ""))

# 4. 运行时是否已换成新版
loader = f("js/module/moduleloader.js")
hb = f("js/bridge/hostbridge.js")
if loader.exists():
    t = loader.read_text(encoding="utf-8")
    print(mark("registerPackage" in t) + "moduleloader.js  " +
          ("已换新版" if "registerPackage" in t else "还是旧版（未移植）"))
if hb.exists():
    t = hb.read_text(encoding="utf-8")
    print(mark("HostBridge.create" in t or "audit" in t) + "hostbridge.js   " +
          ("已换新版(L1+审计)" if "audit" in t else "还是旧版（空实现）"))

# 5. 产物
print("")
out = Path("/storage/emulated/0/mychat-demo.html")
if out.exists():
    age = int(time.time() - out.stat().st_mtime)
    size = out.stat().st_size
    print("产物 /storage/emulated/0/mychat-demo.html")
    print("     大小 " + str(size) + " 字节，更新于 " + str(age) + " 秒前")
else:
    print("产物 不存在（还没打包过）")

print("")
if not pkgs:
    print(">>> 结论：移植未做。跑 port_skill.py（输入 W），再跑 bundle.py")
elif not out.exists():
    print(">>> 结论：模块目录已就绪，但还没打包。跑 bundle.py")
else:
    print(">>> 结论：移植+打包都做过。直接打开 /storage/emulated/0/mychat-demo.html")