# Third-Party Notices

This project builds an Android application that can optionally download and
bundle third-party runtime components. Their licenses are listed here for
compliance when distributing source code or APKs.

## PRoot

- Component: PRoot (static binaries placed in `app/src/main/jniLibs/`)
- License: GPL-3.0
- Source: https://github.com/termux/proot
- Prebuilt source used by `fetch-linux-runtime.ps1`:
  https://github.com/ahmed-alnassif/proot
- GPL obligation: if you distribute an APK containing these binaries, you
  must provide the corresponding source code or a written offer to provide it,
  and keep the license notices.

## QEMU

- Component: QEMU system emulator packages under `app/src/main/assets/qemu*/`
- License: GPL-2.0 (QEMU)
- Source: https://www.qemu.org/
- The Alpine Linux packages are downloaded by `fetch-vm-assets.py` and
  contain their own package metadata and license files.
- GPL obligation: if you distribute an APK containing QEMU binaries, provide
  the corresponding source code or a written offer to provide it.

## Alpine Linux

- Component: rootfs tarballs and virtual ISO under `app/src/main/assets/`
- License: each Alpine package carries its own license (mostly MIT/BSD/GPL)
- Alpine package license database: https://gitlab.alpinelinux.org/alpine/aports
- Keep the licenses included in the rootfs/ISO when redistributing.

## Chaquopy

- Component: Chaquopy Python runtime for Android
- License and terms: https://chaquo.com/chaquopy/license/
- Review the license before distributing the APK commercially.

## Other libraries

Kotlin/AndroidX/OkHttp/Gson/Material Components and Python packages used by the
app keep their respective upstream licenses. See Gradle dependencies and the
installed Python package metadata.

This notice is for informational purposes only and is not legal advice.
