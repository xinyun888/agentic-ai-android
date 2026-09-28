#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""下载 LinuxVM 完整离线运行时：
- Alpine QEMU 依赖包 -> app/src/main/assets/qemu/
- Alpine virt ISO    -> app/src/main/assets/vm/alpine-virt.iso
"""
import os
import sys
import tarfile
import tempfile
import time
import urllib.request
from pathlib import Path

ALPINE_VERSION = "v3.24"
ISO_VERSION = "3.24.2"
ISO_URL = (
    f"https://dl-cdn.alpinelinux.org/alpine/{ALPINE_VERSION}/releases/aarch64/"
    f"alpine-virt-{ISO_VERSION}-aarch64.iso"
)
BASE = f"https://dl-cdn.alpinelinux.org/alpine/{ALPINE_VERSION}"
ROOT = Path(__file__).resolve().parent
QEMU_DIR = ROOT / "app/src/main/assets/qemu"
VM_DIR = ROOT / "app/src/main/assets/vm"


def fetch(url: str, out: Path, retries: int = 3) -> None:
    if out.exists() and out.stat().st_size > 0:
        print(f"skip {out.name}")
        return
    out.parent.mkdir(parents=True, exist_ok=True)
    print(f"download {url}")
    last = None
    for i in range(retries):
        try:
            with urllib.request.urlopen(url, timeout=300) as r, open(out, "wb") as f:
                while True:
                    chunk = r.read(262144)
                    if not chunk:
                        break
                    f.write(chunk)
            print(f"  -> {out.stat().st_size} bytes")
            return
        except Exception as e:  # noqa
            last = e
            print(f"  retry {i + 1}/{retries}: {e}")
            time.sleep(2)
    raise RuntimeError(f"download failed: {url}: {last}")


def load_index(repo: str):
    tmp = Path(tempfile.gettempdir()) / f"apkindex-{repo}-{os.getpid()}.tar.gz"
    fetch(f"{BASE}/{repo}/aarch64/APKINDEX.tar.gz", tmp)
    with tarfile.open(tmp, "r:gz") as t:
        data = t.extractfile("APKINDEX").read().decode("utf-8", "replace")
    tmp.unlink(missing_ok=True)
    return data


def main() -> int:
    QEMU_DIR.mkdir(parents=True, exist_ok=True)
    VM_DIR.mkdir(parents=True, exist_ok=True)

    pkgs = {}
    provides = {}

    def parse(repo: str, data: str):
        for block in data.split("\n\n"):
            lines = block.splitlines()
            name = next((l[2:] for l in lines if l.startswith("P:")), None)
            if not name:
                continue
            ver = next((l[2:] for l in lines if l.startswith("V:")), "")
            deps = next((l[2:] for l in lines if l.startswith("D:")), "").split()
            prov = next((l[2:] for l in lines if l.startswith("p:")), "").split()
            pkgs[name] = {"repo": repo, "version": ver, "deps": deps}
            for token in prov:
                provides.setdefault(token.split("=")[0], []).append(name)

    for repo in ("main", "community"):
        parse(repo, load_index(repo))

    def resolve(token: str):
        if token.startswith("!"):
            return None
        for op in (">=", "<=", "=", ">", "<", "~"):
            if op in token:
                token = token.split(op)[0]
                break
        if token in pkgs:
            return token
        return (provides.get(token) or [None])[0]

    closure = set()
    queue = ["qemu-system-aarch64", "qemu-img"]
    while queue:
        name = queue.pop()
        if name in closure or name not in pkgs:
            continue
        closure.add(name)
        for dep in pkgs[name]["deps"]:
            resolved = resolve(dep)
            if resolved:
                queue.append(resolved)

    manifest = []
    total = 0
    for name in sorted(closure):
        info = pkgs[name]
        fn = f"{name}-{info['version']}.apk"
        url = f"{BASE}/{info['repo']}/aarch64/{fn}"
        out = QEMU_DIR / fn
        fetch(url, out)
        manifest.append((fn, out.stat().st_size, url))
        total += out.stat().st_size

    fetch(ISO_URL, VM_DIR / "alpine-virt.iso")
    manifest.append(("alpine-virt.iso", (VM_DIR / "alpine-virt.iso").stat().st_size, ISO_URL))
    total += (VM_DIR / "alpine-virt.iso").stat().st_size

    (QEMU_DIR / "MANIFEST.txt").write_text(
        "".join(f"{fn}\t{size}\t{url}\n" for fn, size, url in manifest),
        encoding="utf-8",
    )
    print(f"\n完成：{len(manifest)} 个文件，共 {total / 1024 / 1024:.1f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())