"""一次性脚本：把 modules.tar.gz 解到 Download/assets-modules，并打印每个模块的 hooks。

不按文件名找压缩包（聊天软件保存时可能改名字），而是按**内容**判断：
看里面有没有 modules/ 目录。
"""

import os
import shutil
import tarfile
import zipfile

DL = '/storage/emulated/0/Download'
OUT = os.path.join(DL, 'assets-modules')


def candidates():
    out = []
    for root, dirs, files in os.walk(DL):
        for f in files:
            low = f.lower()
            if '.tar' in low or low.endswith(('.gz', '.tgz', '.zip')):
                out.append(os.path.join(root, f))
    return out


def has_modules(path):
    try:
        if path.lower().endswith('.zip'):
            with zipfile.ZipFile(path) as z:
                return any(n.startswith('modules/') for n in z.namelist())
        with tarfile.open(path, 'r:*') as t:
            return any(n.startswith('modules/') for n in t.getnames())
    except Exception:
        return False


def main():
    all_files = candidates()
    print('Download 下候选压缩包：')
    for p in all_files:
        print('  ', os.path.basename(p))

    found = [p for p in all_files if has_modules(p)]
    if not found:
        print('!! 没有一个包里有 modules/ 目录')
        print('   请把 modules.tar.gz 保存到 Download 再跑一次')
        return

    src = found[0]
    print('使用：', src)
    os.makedirs(OUT, exist_ok=True)

    if src.lower().endswith('.zip'):
        with zipfile.ZipFile(src) as z:
            z.extractall(DL)
    else:
        with tarfile.open(src, 'r:*') as t:
            t.extractall(DL)

    src_dir = os.path.join(DL, 'modules')
    moved = 0
    if os.path.isdir(src_dir):
        for name in sorted(os.listdir(src_dir)):
            s = os.path.join(src_dir, name)
            d = os.path.join(OUT, name)
            if os.path.exists(d):
                shutil.rmtree(d)
            os.rename(s, d)
            moved += 1
        try:
            os.rmdir(src_dir)
        except OSError:
            pass

    print('已解压 %d 个模块 -> %s' % (moved, OUT))

    for name in sorted(os.listdir(OUT)):
        md = os.path.join(OUT, name, 'MODULE.md')
        hooks = '无 MODULE.md'
        if os.path.exists(md):
            with open(md, encoding='utf-8') as fh:
                for line in fh:
                    if line.startswith('hooks:'):
                        hooks = line.strip()[6:].strip()
                        break
        print('  %-16s hooks=%s' % (name, hooks))


main()
