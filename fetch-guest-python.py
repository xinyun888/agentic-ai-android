#!/usr/bin/env python3
"""下载 guest aarch64 Python3 运行时 APK 仓库，供 QEMU guest 离线安装 harness。"""
import urllib.request, tarfile, io
from pathlib import Path

BASE = "https://dl-cdn.alpinelinux.org/alpine/v3.24/main/aarch64"
OUT = Path(__file__).resolve().parent / "app/src/main/assets/guest-apks/aarch64"
NAMES = [
    "libexpat", "libbz2", "libffi", "gdbm", "xz-libs", "libgcc", "libstdc++",
    "mpdecimal", "ncurses-terminfo-base", "libncursesw", "libpanelw", "readline",
    "sqlite-libs", "python3", "python3-pycache-pyc0", "pyc", "python3-pyc",
]

def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    print("download APKINDEX ...")
    index = urllib.request.urlopen(BASE + "/APKINDEX.tar.gz", timeout=60).read()
    (OUT / "APKINDEX.tar.gz").write_bytes(index)
    tar = tarfile.open(fileobj=io.BytesIO(index), mode="r:gz")
    text = tar.extractfile("APKINDEX").read().decode("utf-8", "replace")
    versions = {}
    for record in text.split("\n\n"):
        name = version = None
        for line in record.splitlines():
            if line.startswith("P:"):
                name = line[2:]
            elif line.startswith("V:"):
                version = line[2:]
        if name in NAMES and name not in versions:
            versions[name] = version
    missing = [n for n in NAMES if n not in versions]
    if missing:
        print("missing:", missing)
        return 1
    for name in NAMES:
        filename = name + "-" + versions[name] + ".apk"
        data = urllib.request.urlopen(BASE + "/" + filename, timeout=120).read()
        (OUT / filename).write_bytes(data)
        print(filename, len(data))
    print("done ->", OUT)
    return 0

if __name__ == "__main__":
    raise SystemExit(main())