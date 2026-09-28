# 版本记录

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