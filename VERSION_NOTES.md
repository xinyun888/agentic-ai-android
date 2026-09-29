## v2.58.4（真机模拟器修复）

- 修复 rootfs 绝对符号链接相对化错误：
  - `File.canonicalFile` 与非 canonical 路径混用，导致 `bin/sh` 等链接指向 `alpine.tmp`
  - 现在统一 canonicalize，已验证 `bin/sh -> busybox`
- 修复离线安装 QEMU 被误判失败：
  - PRoot 下 apk post-install/trigger 脚本会 `fork: Function not implemented`
  - 但 QEMU 二进制已安装，现在按二进制存在判定成功
- 新增 x86_64 QEMU 离线包，支持 x86_64 模拟器/设备
- QEMU 默认改用 1 个 vCPU，提高 PRoot 下稳定性
- Android 14 x86_64 模拟器实测：
  - App 启动正常
  - rootfs 解压正常
  - 离线安装 QEMU 正常
  - ISO 释放、qcow2 创建正常
  - QEMU UEFI + GRUB + Alpine ISO 能启动
- 注意：模拟器里 QEMU 套 QEMU，guest kernel 会 soft lockup；需要真机 arm64 最终验证

## v2.58.3（离线启动修正）

- 发现并修正：netboot 的 `modloop=/vm/modloop-virt` 是宿主路径，guest initramfs 实际访问不到
- 改为使用 APK 内置的 **Alpine virt ISO**：
  - 内核 / initramfs / modloop / APK 仓库都在 ISO 内
  - QEMU 通过 UEFI pflash + `-cdrom` + `-boot d` 启动
  - 完全离线，首次启动直接进入 Alpine live 环境
- 已在 Windows QEMU 上用同一组参数验证：能进入 `localhost login:`
- APK 会变大（ISO 约 89MB），release 约 188MB

# 版本记录

## v2.58.1（合体修复）

- 修复长命令输出超过 200k 后可能卡死的问题
- QEMU 与 chat-app 共用同一个 LinuxRuntimeManager，避免双 manager 作用域冲突
- 离线 QEMU 安装前先修复 rootfs 绝对符号链接，安装后再修复一次
- 预修正 busybox-binsh 包内的 `/bin/sh -> /bin/busybox` 为相对链接，防止 apk 安装断开 guest shell
- VM 页面增加 arm64 ABI 检查；非 arm64 设备禁用 VM 功能
- 创建磁盘前强制检查 QEMU 已安装
- QEMU 启动异常时清理 session，避免残留进程
- Boot 参数改用 HTTP Alpine repo，避免 initramfs 无证书时引导失败

## v2.58（合体版）

在 chat-app 内合并 Linux VM 能力：

- 新增 `vm/QemuManager`、`vm/QemuSession`
- 新增 `ui/VmScreen`
- APK 内置完整离线 QEMU 包与 Alpine netboot（`assets/qemu`、`assets/vm`）
- 主界面新增 QEMU 虚拟机入口
- 保留原有全部功能：AI 聊天、Agent 工具、无障碍手机控制、PhoneBridge、主动模式、命理师等
- 支持 targetSdk=35 构建，也支持 `build-linux.bat` 的 targetSdk=28 侧载构建
- 推荐侧载使用 `AI-Chat-v2.58-combined-linux-target28-release.apk`，避免 Android 10+ W^X 限制

## v2.57（当前版本）

完成内容：

- PRoot + Alpine Linux 子系统
  - `fetch-linux-runtime.ps1` 下载 PRoot 二进制与 Alpine rootfs
  - `LinuxRuntimeManager` 解压 rootfs、启动 proot、挂载 `/workspace`
  - 处理 Alpine 绝对符号链接、loader、DNS、超时与输出截断
- Android 无障碍手机桥
  - `PhoneBridgeManager` 监听 `phone_bridge` 目录
  - guest 内自动生成 `/usr/local/bin/phone`
  - 支持 dump / find / tap / swipe / text / back / home / available
- AI 工具
  - `ToolRegistry.linux_exec`
  - 默认角色与命理师角色均可用
- UI
  - 主界面右上角终端图标进入 `LinuxScreen`
  - 一键安装 rootfs、命令终端、状态与 W^X 提示
- 构建
  - 默认 targetSdk=35
  - `build-linux.bat` 构建 targetSdk=28 的侧载 Linux 版
  - `dist/` 下已有 4 个 APK（release/debug  target28/target35）

已知限制：

- PRoot 不是完整虚拟机，不支持 systemd、Docker、自定义内核
- targetSdk=28 是侧载兼容方案；targetSdk>=29 可能触发 Android 10+ W^X 限制
- PRoot / Alpine 有 GPL 等许可证要求，见 `LINUX_RUNTIME.md`
- phone 桥会让 Linux 拥有控制手机的能力，不要运行不可信脚本

## 下一版规划

以下内容不再加进当前 `chat-app`，单独做一个新 App：

- QEMU system mode 完整 Linux 虚拟机
- 独立内核、systemd、Docker、强隔离
- 独立 App 负责 VM 生命周期、镜像下载、终端/VNC、快照管理
- 通过明确 IPC 与当前 chat-app / PhoneBridge 协作，避免主 App 膨胀