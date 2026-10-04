#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""下载 aarch64 Alpine 的 node/npm/git/bash 离线仓库（供 guest 免联网安装），
并合并 main+community 的 APKINDEX，输出到 app/src/main/assets/guest-apks/aarch64/。"""
import os, sys, tarfile, gzip, io, time, urllib.request
from pathlib import Path

BASE = "https://dl-cdn.alpinelinux.org/alpine/v3.24"
ARCH = "aarch64"
ROOT = Path(r"C:\Users\Lenovo\WorkBuddy\2026-06-29-04-47-43\chat-app")
DEST = ROOT / "app/src/main/assets/guest-apks" / ARCH
DEST.mkdir(parents=True, exist_ok=True)
TARGETS = ["nodejs", "npm", "git", "bash", "pnpm"]

def fetch(url, out, retries=3):
    if out.exists() and out.stat().st_size > 0:
        return
    last = None
    for i in range(retries):
        try:
            with urllib.request.urlopen(url, timeout=120) as r, open(out, "wb") as f:
                while True:
                    chunk = r.read(262144)
                    if not chunk: break
                    f.write(chunk)
            return
        except Exception as e:
            last = e; time.sleep(2)
    raise RuntimeError(f"download failed {url}: {last}")

def load_index(repo):
    tmp = Path(os.environ.get("TEMP", ".")) / f"idx-{repo}-{ARCH}.tar.gz"
    fetch(f"{BASE}/{repo}/{ARCH}/APKINDEX.tar.gz", tmp)
    with tarfile.open(tmp, "r:gz") as t:
        raw = t.extractfile("APKINDEX").read()
    return raw

pkgs, provides = {}, {}
for repo in ("main", "community"):
    data = load_index(repo).decode("utf-8", "replace")
    for block in data.split("\n\n"):
        lines = block.splitlines()
        name = next((l[2:] for l in lines if l.startswith("P:")), None)
        if not name: continue
        ver = next((l[2:] for l in lines if l.startswith("V:")), "")
        deps = next((l[2:] for l in lines if l.startswith("D:")), "").split()
        prov = next((l[2:] for l in lines if l.startswith("p:")), "").split()
        pkgs[name] = {"repo": repo, "version": ver, "deps": deps}
        for tok in prov:
            provides.setdefault(tok.split("=")[0], []).append(name)

def resolve(tok):
    if tok.startswith("!"): return None
    for op in (">=", "<=", "=", ">", "<", "~"):
        if op in tok: tok = tok.split(op)[0]; break
    if tok in pkgs: return tok
    return (provides.get(tok) or [None])[0]

closure, queue = set(), list(TARGETS)
while queue:
    n = queue.pop()
    if n in closure or n not in pkgs: continue
    closure.add(n)
    for d in pkgs[n]["deps"]:
        r = resolve(d)
        if r: queue.append(r)

print("packages:", len(closure))
total = 0
for name in sorted(closure):
    info = pkgs[name]
    fn = f"{name}-{info['version']}.apk"
    out = DEST / fn
    fetch(f"{BASE}/{info['repo']}/{ARCH}/{fn}", out)
    total += out.stat().st_size
    print(" ", fn, out.stat().st_size)
print(f"total {total/1024/1024:.1f} MB")

# 合并 main+community 索引，写成 APKINDEX.tar.gz（guest 只有一个仓库地址）
merged = load_index("main") + b"\n" + load_index("community")
inner = io.BytesIO()
with tarfile.open(fileobj=inner, mode="w") as t:
    info = tarfile.TarInfo("APKINDEX"); info.size = len(merged); info.mtime = int(time.time()); info.mode = 0o644
    t.addfile(info, io.BytesIO(merged))
raw = inner.getvalue()
with open(DEST / "APKINDEX.bin", "wb") as f:
    f.write(gzip.compress(raw, 9))
print("index written:", (DEST/'APKINDEX.bin').stat().st_size)
