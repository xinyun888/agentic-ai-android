#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""下载 LinuxVM 完整离线运行时（arm64 + x86_64 QEMU 包 + Alpine virt ISO）。"""
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
ASSETS = ROOT / "app/src/main/assets"


def is_valid_apk(path: Path) -> bool:
    """Alpine APK v2 is a concatenation of signature + control + data gzip streams."""
    if path.suffix != ".apk":
        return True
    try:
        data = path.read_bytes()
    except Exception:
        return False
    return data[:2] == b"\x1f\x8b" and data.count(b"\x1f\x8b\x08") >= 3


def fetch(url: str, out: Path, retries: int = 3) -> None:
    if out.exists() and out.stat().st_size > 0 and is_valid_apk(out):
        print(f"skip {out.name}")
        return
    if out.exists() and out.stat().st_size > 0:
        print(f"invalid APK, re-download {out.name}")
        out.unlink()
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


def load_index(arch: str, repo: str) -> str:
    tmp = Path(tempfile.gettempdir()) / f"apkindex-{arch}-{repo}-{os.getpid()}.tar.gz"
    fetch(f"{BASE}/{repo}/{arch}/APKINDEX.tar.gz", tmp)
    with tarfile.open(tmp, "r:gz") as t:
        data = t.extractfile("APKINDEX").read().decode("utf-8", "replace")
    tmp.unlink(missing_ok=True)
    return data


def fetch_qemu(arch: str, dest: Path) -> None:
    dest.mkdir(parents=True, exist_ok=True)
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
        parse(repo, load_index(arch, repo))

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

    total = 0
    for name in sorted(closure):
        info = pkgs[name]
        fn = f"{name}-{info['version']}.apk"
        url = f"{BASE}/{info['repo']}/{arch}/{fn}"
        out = dest / fn
        fetch(url, out)
        total += out.stat().st_size
    print(f"[{arch}] QEMU packages: {len(closure)} files, {total / 1024 / 1024:.1f} MB")


def main() -> int:
    fetch_qemu("aarch64", ASSETS / "qemu")
    fetch_qemu("x86_64", ASSETS / "qemu-x86_64")
    fetch(ISO_URL, ASSETS / "vm/alpine-virt.iso")
    netboot = f"{BASE}/releases/aarch64/netboot"
    fetch(f"{netboot}/vmlinuz-virt", ASSETS / "vm/vmlinuz-virt")
    fetch(f"{netboot}/initramfs-virt", ASSETS / "vm/initramfs-virt")
    print("\n完成")
    return 0


if __name__ == "__main__":
    sys.exit(main())