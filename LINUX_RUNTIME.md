# Linux 环境（PRoot + Alpine）

这个模块给 AI 助手提供本地 Linux shell，并通过 `phone` 命令桥接 Android 无障碍控制手机。

## 1. 下载运行时

当前 APK 默认不包含 proot 和 Alpine rootfs，需要先在有网络的机器上运行：

```powershell
cd 项目根目录
powershell -ExecutionPolicy Bypass -File .\fetch-linux-runtime.ps1
```

脚本会下载：

- `app/src/main/jniLibs/arm64-v8a/libproot_exec.so`
- `app/src/main/jniLibs/x86_64/libproot_exec.so`
- `app/src/main/assets/linux/alpine-aarch64.tar.gz`
- `app/src/main/assets/linux/alpine-x86_64.tar.gz`

如果 GitHub 访问困难，可以传自定义 URL：

```powershell
.\fetch-linux-runtime.ps1 -ProotArm64Url "https://..." -ProotX86_64Url "https://..."
```

## 2. 重新构建 APK

普通构建（targetSdk=35，Linux 可能在 Android 10+ 受 W^X 影响）：

```powershell
gradle :app:assembleDebug
gradle :app:assembleRelease
```

侧载建议直接跑（targetSdk=28，PRoot 兼容性最好）：

```powershell
.\build-linux.bat
```

`packaging { jniLibs { useLegacyPackaging = true } }` 会把 `libproot_exec.so` 解压到 `nativeLibraryDir`，这样 Android 10+ 也能以可执行文件方式启动 proot。

## 3. App 内使用

1. 打开 App  点右上角终端图标进入Linux 环境；
2. 点击安装 Alpine rootfs；
3. 输入命令测试：

```sh
uname -a
apk add python3
phone dump
phone find 设置
phone tap 540 1200
phone text 你好
```

## 4. 桥接协议

`LinuxRuntimeManager` 会把 App 的 `files/phone_bridge` 挂载为 guest 的 `/phone`。
`phone` 脚本往 `request_<id>` 写入命令行，宿主 `PhoneBridgeManager` 执行无障碍操作，再写回 `response_<id>`。

支持命令：

```text
dump
find <text>
tap <x> <y>
swipe <x1> <y1> <x2> <y2> [durationMs]
text <content>
back
home
available
```

## 5. AI 工具

`linux_exec` 已注册到 `ToolRegistry`，默认角色可以直接调用，例如：

```json
{"command": "phone dump", "timeout": "30"}
```

工作区映射为 `/workspace`，按对话隔离。
## 6. Android 10+ W^X 兼容提示

PRoot 在 targetSdk >= 29 时可能无法执行 rootfs 内的二进制（表现：`Permission denied`）。
如果需要在本机离线运行完整 Alpine 用户空间，可以侧载构建一个 targetSdk=28 的 APK：

```powershell
gradle :app:assembleDebug -PtargetSdk=28
gradle :app:assembleRelease -PtargetSdk=28
```

默认构建仍使用 targetSdk=35。targetSdk=28 的 APK 不建议上架 Google Play，只适合自己侧载使用。

如果你需要真完整 Linux / systemd / Docker / 强隔离，则不应该走 PRoot，而应该做 QEMU 全系统虚拟机。PRoot 只是阶段性方案。
## 7. 许可证说明（重要）

- PRoot 本身是 GPL 软件。本项目的 `fetch-linux-runtime.ps1` 从第三方构建仓库 `ahmed-alnassif/proot` 下载预编译二进制，该仓库声明为 GPLv3，上游为 Termux PRoot 项目。
- 如果你要分发包含这些二进制的 APK，需要遵守 GPL：提供对应源代码或源码获取方式，并保留许可证声明。
- Alpine rootfs 内的各个软件包有各自的许可证，分发前也需要确认。
- 如果不想引入 GPL 依赖，可以：
  1. 只发布不含 PRoot/rootfs 的 APK，让用户自行下载；
  2. 或者改用 QEMU 方案并自行处理所有许可证。

本仓库默认不提交 `jniLibs/*.so` 和 `assets/linux/*.tar.gz`，只提供下载脚本。
## 8. 直接可用的 APK（当前构建机产物）

如果已经运行过 `fetch-linux-runtime.ps1`，可以直接安装：

- Linux 侧载版（targetSdk=28，PRoot 兼容性最好）：
  `dist/app-release-linux-target28.apk`
- 普通现代版（targetSdk=35，Google Play / 新系统友好）：
  `dist/app-release-target35.apk`

Debug 版本对应：

- `dist/app-debug-linux-target28.apk`
- `dist/app-debug-target35.apk`

在 App 里点右上角终端图标 -> 安装 Alpine rootfs -> 输入 `uname -a` 测试。