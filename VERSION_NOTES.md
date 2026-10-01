## v2.61.2（Guest Harness 离线可用）

- 修复部分设备/网络下 Guest Harness 仍然未连接：
  - guest 的 Python3 之前需要访问外网 `dl-cdn.alpinelinux.org`
  - 现在 APK 内置 aarch64 Alpine Python3 APK 仓库：
    ```text
    assets/guest-apks/aarch64/
      APKINDEX.tar.gz
      libexpat / libbz2 / libffi / gdbm / xz-libs
      libgcc / libstdc++ / mpdecimal
      ncurses-terminfo-base / libncursesw / libpanelw
      readline / sqlite-libs / python3
      python3-pycache-pyc0 / pyc / python3-pyc
    ```
  - 宿主 `PhoneBridgeHttpServer` 增加 `/guest-apks/*`，guest 通过 `10.0.2.2` 本地仓库安装 python3，完全不用外网
  - guest 自动配置改为：
    ```sh
    echo "http://10.0.2.2:PORT/guest-apks" > /etc/apk/repositories
    apk update --allow-untrusted
    apk add --no-cache --allow-untrusted python3
    ```
- 新增 `fetch-guest-python.py`，干净 clone 时可重新下载 guest Python 仓库
- VM 实测：
  - 使用内置 APK 仓库，guest 离线安装 python3 成功
  - 自动配置输出 `AICHAT_HARNESS_OK`
  - hostfwd 后宿主访问：
    ```text
    http://127.0.0.1:18000/v1/models
    {"object": "list", "data": [{"id": "ds-harness", "object": "model"}]}
    ```
- 保留 v2.61.1 全部修复：lo、import json、FastHTTPServer、单例会话、孤儿 QEMU 清理、原子资源写入
- versionCode 278 / versionName 2.61.2

## v2.61.1（修复 Harness 不通 + 退出加载页损坏 VM）

- 修复 Guest Harness 不通：
  - 根因 1：guest 的 `lo` 回环网卡未启用，harness 健康检查连 `127.0.0.1:8000` 失败
    - guest 自动配置现在先 `ip link set lo up`
  - 根因 2：harness Python 脚本首行注释和 `import json` 被写成同一行，`json` 未导入，请求处理器启动后报 NameError
    - 已改成独立行，并编译校验通过
  - 根因 3：`HTTPServer.server_bind()` 会做反向 DNS，guest 网络下可能卡住
    - 内置 harness 改用 `FastHTTPServer`，跳过 `getfqdn`
  - guest 自动配置改为下载执行 `/guest-setup.sh`：
    - 等待 DHCP / 宿主 ping
    - 自动补 main/community 仓库
    - 安装 python3
    - 安装 phone 桥和 harness
    - 最多 30 秒重试健康检查，输出 `AICHAT_HARNESS_OK` / `AICHAT_HARNESS_FAIL`
  - HarnessScreen 连接状态改为每 4 秒自动重试
- 修复等待 VM 加载时退出会搞坏 VM、之后能启动但没有输出：
  - `QemuManager` 改为进程级单例，Activity/ViewModel 重建后复用同一会话
  - `ChatViewModel.onCleared()` 不再停止 QEMU；VM 由 `QemuKeepAliveService` 保持
  - 启动/停止加锁；启动前 `pkill` 残留 `qemu-system-*`，避免孤儿 QEMU 锁住 qcow2 和 18000 端口
  - 停止时等待 QEMU 进程退出，再清理残留
  - VmScreen 重新进入时轮询 `currentSession()`，自动接管正在运行的会话和输出
- 加固半截文件损坏：
  - ISO / vmlinuz / initramfs 改为 `.part` 写入后重命名
  - qcow2 改为 `alpine.qcow2.part` 创建后重命名
  - `imagesReady()` 增加最小文件大小校验，半截文件不再被当成可用
- VM 实测：
  - guest 执行自动配置脚本：phone-ok / AICHAT_HARNESS_OK
  - `http://127.0.0.1:8000/v1/models` 返回 JSON
- versionCode 277 / versionName 2.61.1

## v2.61.0（最终整合版）

- 功能目标：集大成者，不砍任何能力
  - 全 ABI QEMU（arm64 + x86_64）
  - 全 Python 包（Chaquopy）
  - PRoot Alpine Linux
  - QEMU 全系统 VM
  - 内置 Harness + QEMU Guest Harness
  - 手机无障碍控制 / PhoneBridge / 截屏
  - 主动陪伴模式
  - 多模态图片 / 记忆 / 计划 / 工具循环
  - 模型代理：API Key 不进入 guest
  - 通知 / 文件 / 剪贴板
- QEMU 直接内核启动：
  ```text
  -kernel /vm/vmlinuz-virt
  -initrd /vm/initramfs-virt
  -append "console=ttyAMA0 ip=dhcp nowatchdog"
  -cdrom /vm/alpine-virt.iso
  ```
- 安全模式自动降级：
  - 180 秒未进 login 自动用 512MB / cortex-a53 / tcg single 重试一次
- 安装到磁盘 + 持久启动（VM 实测通过）：
  - `setup-disk -m sys -k virt /dev/vda`
  - 磁盘布局：vda1 boot / vda2 swap / vda3 ext4 root
  - 磁盘启动：
    ```text
    root=/dev/vda3 rw rootfstype=ext4
    modules=virtio_blk,virtio_pci,ext4
    rootwait console=ttyAMA0 nowatchdog
    ```
  - VM 实测重新进入 `localhost login:`
- 引擎优化：
  - QemuSession 串口输出 150ms 节流
  - QemuSession 输入 48 字节分块，防串口丢字符
  - PhoneBridge 文件轮询 350ms
  - Python 懒加载
  - PARTIAL_WAKE_LOCK 保活
- Guest 自动安装：
  - phone 桥
  - python3
  - 内置 OpenAI 兼容 Harness（监听 0.0.0.0:8000）
- 版本：versionCode 276 / versionName 2.61.0

## v2.60.6（磁盘安装 VM 实测通过）

- 在 Windows QEMU aarch64 里完整跑通：
  1. 直接内核启动进入 `localhost login:`
  2. 执行新版 `/disk-install.sh`
  3. `setup-disk -m sys -k virt` 成功：
     ```text
     Installation is complete. Please reboot.
     AICHAT_DISK_EXIT_0
     ```
  4. 磁盘布局：
     ```text
     /dev/vda1  300MB  boot
     /dev/vda2    1GB  swap
     /dev/vda3 2771MB  ext4 root
     ```
  5. 从磁盘启动：
     ```text
     Kernel command line:
       root=/dev/vda3 rw rootfstype=ext4
       modules=virtio_blk,virtio_pci,ext4
       rootwait console=ttyAMA0 nowatchdog
     ```
     再次进入：
     ```text
     Welcome to Alpine Linux 3.24
     localhost login:
     ```
- 修复磁盘启动参数：
  - 增加 `rootfstype=ext4`
  - 增加 `modules=virtio_blk,virtio_pci,ext4`
  - 增加 `rootwait`
- v2.60.5 的 `/disk-install.sh` 修复已被 VM 证明有效：
  - 移除 `u-boot` world 依赖
  - 自动补 main/community 仓库
  - `apk update`
  - 再执行 `setup-disk -m sys -k virt /dev/vda`
- versionCode 275 / versionName 2.60.6

## v2.60.5（真机/VM 磁盘安装修复）

- 在 Windows QEMU aarch64 里实际启动 guest，并执行了 /disk-install.sh
- 发现真问题：
  - `setup-disk -m sys -k virt /dev/vda` 失败：
    ```text
    ERROR: unable to select packages: u-boot (no such package): required by world[u-boot]
    ```
  - `setup-disk -m data` 能成功，但生成的是 vda1 swap + vda2 ext4 root，
    不是完整可 switch_root 的系统，直接内核启动会 init panic
- v2.60.5 修复：
  - 安装前从 `/etc/apk/world` 移除 `u-boot`
  - 自动补 main/community Alpine 仓库
  - 执行 `apk update`
  - 再执行 `setup-disk -m sys -k virt /dev/vda`
  - 保留 vda3 作为 sys 模式 root 的默认假设
- 说明：磁盘安装目前属于实验性，Live 启动仍是稳定默认
- versionCode 274 / versionName 2.60.5

## v2.60.4（安装到磁盘 + 持久启动）

- QEMU 新增安装到磁盘流程：
  - VM 页面新增安装到磁盘按钮
  - 宿主提供 `/disk-install.sh`
  - guest 通过 `setup-disk -m sys -k virt /dev/vda` 安装到 qcow2
  - 安装完成后自动切换为磁盘启动
- 新增当前: Live / 当前: 磁盘切换按钮，持久化到 SharedPreferences
- 磁盘启动参数：
  ```text
  -kernel /vm/vmlinuz-virt
  -initrd /vm/initramfs-virt
  -append "root=/dev/vda3 rw console=ttyAMA0 nowatchdog"
  ```
  不再需要 `-cdrom`
- QemuSession 写入改为分块发送（48 字节 + 25ms），避免 guest 串口长命令丢字符
- 磁盘安装脚本已通过 `sh -n`
- 说明：root 分区按 Alpine `setup-disk -m sys -k virt` 默认布局 /dev/vda3；
  如果真机安装后发现分区不同，把 `/tmp/aichat-disk.log` 发出来再调
- versionCode 273 / versionName 2.60.4

## v2.60.3（引擎优化，不砍功能）

- QemuSession 输出节流：
  - 串口输出 150ms 合并刷新一次，减少 Compose 重绘和 O(n) 字符串复制
  - 进程退出仍会刷新最终内容
- PhoneBridge 文件轮询从 120ms 降到 350ms，降低电量/CPU
- PythonSessionManager 改为懒加载，冷启动不初始化 Python
- QEMU 安全模式自动降级：
  - 默认参数 180 秒未进入 login
  - 自动用 `-m 512 -smp 1 -cpu cortex-a53 -accel tcg,thread=single` 重启一次
- Guest Harness 自动安装：
  - 宿主新增 `/harness.py`
  - guest 自动装 `python3`、下载并后台启动内置 OpenAI 兼容 Harness
  - Harness 的模型请求走宿主 `/model/chat`，API Key 不离开宿主
  - Android HarnessScreen QEMU 模式可直接连接 `127.0.0.1:18000`
- 保留：
  - 全 ABI QEMU 资源
  - 全 Python 包
  - 全 Linux/QEMU/ISO/内核
  - Universal 完整包
- versionCode 272 / versionName 2.60.3

## v2.60.2（发布前 bug 修复）

- 修复 QemuKeepAliveService 缺少 `android.permission.WAKE_LOCK`：
  - 之前 `newWakeLock` 会抛 SecurityException，被 catch 后 `stopSelf()`
  - 结果 VM 前台保活服务可能直接退出
- 修复内置 Harness 模式可能把消息发到旧对话：
  - HarnessScreen 现在接收专用 conversationId
  - 发送前若 currentConversationId 不匹配，先 `loadConversation`
- 内置 Harness 现在显示 ChatViewModel.errorMessage，API 报错不再静默
- `ensureHarnessConversation()` 保证内置 Harness 有独立对话
- versionCode 271 / versionName 2.60.2

## v2.60.1（直接内核启动 + 内置 Harness）

- QEMU 改为直接内核启动，绕开 UEFI/pflash/GRUB：
  - `-kernel /vm/vmlinuz-virt`
  - `-initrd /vm/initramfs-virt`
  - `-append "console=ttyAMA0 ip=dhcp nowatchdog"`
  - `-cdrom /vm/alpine-virt.iso` 提供 modloop 和本地软件仓库
  - 去掉 `-drive if=pflash...` 和 `-boot d`
- 效果：
  - Windows QEMU 同参数实测约 5 秒进入：
    ```text
    localhost login:
    ```
  - `nowatchdog` 关闭 guest watchdog，避免 TCG 下 soft lockup 误报刷屏
  - 不再依赖 edk2-aarch64-code.fd / efi-vars.fd
- APK 新增内置：
  - `assets/vm/vmlinuz-virt`
  - `assets/vm/initramfs-virt`
  - `fetch-vm-assets.py` 自动下载
  - 构建检查 `checkBundledLinuxAssets` 一并检查
- VM 保活服务增加 `PARTIAL_WAKE_LOCK`，降低真机后台被 CPU 休眠拖死的概率
- HarnessScreen 增加内置模式：
  - 直接复用 App 现有 Agent 循环（工具/手机控制/Python/Linux/记忆）
  - QEMU模式继续使用 guest 里 OpenAI 兼容服务
  - 两种模式切换，不需要 WebView/浏览器
- versionCode 270 / versionName 2.60.1

## v2.60.0（原生 DS Harness 界面）

- 新增 Campose 原生 HarnessScreen：
  - 不再依赖 WebView / 外部浏览器
  - LazyColumn 聊天界面、流式输出、停止、清空
  - 设置 Base URL / API Path / Model / Token
  - 自动健康检查，未连接/已连接状态显示
- 支持任何 OpenAI 兼容的 harness：
  - `POST /v1/chat/completions`，`stream=true` SSE
  - 兼容整包 JSON 返回
  - 解析 `choices[0].delta.content` / `choices[0].message.content`
- QEMU 增加 hostfwd：
  ```text
  user,id=n0,hostfwd=tcp:127.0.0.1:18000-:8000
  ```
  guest 里 harness 监听 `0.0.0.0:8000`，Android 通过 `127.0.0.1:18000` 直接访问
- 主界面新增 DS Harness 入口（机器人图标）
- 模型 Key 可留空由 guest 自己处理；也可以走 guest 里的 harness 自己的配置
- versionCode 269 / versionName 2.60.0

## v2.59.9（交付前加固）

- guest 自动安装命令更稳：
  - eth0 up / udhcpc 任一失败都不再中断后续 wget
  - 网络已配置时不会因为 DHCP 返回非 0 导致桥安装失败
- `/model/chat` 返回 `application/json; charset=utf-8`
- 用 Git Bash `sh -n` 验证 guest `phone` 脚本语法通过
- 重新跑 lint + release/debug 构建
- versionCode 268 / versionName 2.59.9

## v2.59.8（截屏能力声明修复）

- 修复无障碍配置缺少 `android:canTakeScreenshot="true"` 导致 `/phone/screenshot` 一直 503
- 真机/模拟器验证：
  - 打开无障碍后 `GET /phone/screenshot?token=...` 返回 200
  - `Content-Type: image/jpeg`，约 160KB
- 同时验证模型代理：
  - guest 发送 `model=guest-tries-pro`
  - 宿主注入 `Authorization: Bearer <host key>`，并强制替换为当前 Profile 的 `deepseek-flash`
  - 请求体 `stream=false`
  - 返回正常，guest 全程看不到 API Key
- versionCode 267 / versionName 2.59.8

## v2.59.7（QEMU 桥限制全部补齐）

- guest 自动安装手机桥，不再每次冷启动手动粘贴命令：
  - QemuManager 监听串口输出
  - 检测 `login:` 自动输入 `root`
  - 检测 shell 提示符后自动执行 `PhoneBridgeHttpServer.guestSetupCommand()`
  - 自动配置 eth0/DHCP、下载 `/usr/local/bin/phone`、执行 `phone available`
- 新增截屏桥：
  - `ScreenControlService.captureScreenshotJpeg()` 基于无障碍 `takeScreenshot`（Android 11+）
  - HTTP 端点 `GET /phone/screenshot` 返回 JPEG
  - guest 命令 `phone screenshot [文件名]` 可直接保存截图
  - 供 guest 里的视觉 harness 使用
- 新增模型代理，API Key 不下放 guest：
  - HTTP 端点 `POST /model/chat`
  - 使用宿主当前激活 Profile 的 baseUrl/apiKey/model
  - guest 只发 OpenAI Chat Completions JSON，宿主负责注入 Key、强制 active model、`stream=false`
  - guest 被攻破也拿不到 Key；模型选择仍由宿主控制
- 之前版本已有的 `phone dump/tap/swipe/text/find/back/home` 保持
- 新增 `phone screenshot` 用法提示
- versionCode 266 / versionName 2.59.7

## v2.59.6（QEMU 手机桥 + VM 页面退出不中断）

- QEMU guest 手机控制桥：
  - 新增 `PhoneBridgeHttpServer`，只监听 `127.0.0.1:48879`
  - 随机 token 鉴权，错误 token 返回 403
  - QEMU user networking 下 guest 通过 `10.0.2.2:48879` 访问
  - 接口：`/phone/ping`、`/phone.sh`、`/phone/exec`
  - `/phone/exec` body 每行一个参数，直接复用 `PhoneBridgeManager` -> 无障碍
  - guest 用 `wget --post-data` 调用，支持 `available/dump/find/tap/swipe/text/back/home`
  - VM 页面新增复制 QEMU guest 手机桥命令，粘贴到 VM 串口执行即可安装 `phone`
- Linux VM 稳定性：
  - 退出 VM 页面不再调用 `stopSession`
  - 重新进入时通过 `QemuManager.currentSession()` 直接接管正在运行的会话
  - 串口输出、running 状态保留，不会重启 QEMU
  - 新增 `QemuKeepAliveService` 前台服务，保持 App 进程和 QEMU 不被系统过早回收
- 已验证：
  - 返回主界面后 QEMU 进程仍运行，KeepAlive 前台服务仍在
  - 重新进入 VM 页面串口输出仍在
  - 通过 `adb forward` 访问桥：`/phone/ping` 200，错误 token 403
  - `/phone/exec` 的 `available` 返回 `1`，`dump` 返回当前 Android 无障碍树
- versionCode 265 / versionName 2.59.6

## v2.59.5（主动模式点了没反应修复）

- 真机反馈：点开始主动陪伴后无通知、无声望，退出后通知栏也没有
- 根因：
  - Android 13+ 通知权限未授予时，旧代码直接 `return`，根本没调用 startNow
  - 并且没有任何提示，用户只看到点了没反应
  - 通知频道被关闭/OEM 后台限制时也没有任何诊断信息
- 修复：
  - 无论 POST_NOTIFICATIONS 是否授予，都先启动前台服务
  - 启动后 Toast 明确提示已启动，X 分钟后首次心跳或通知被关闭，通知栏不会显示
  - 主动模式对话框直接显示通知权限/频道状态：
    - 未授权  提供去开启通知
    - 频道被关闭  打开主动模式频道设置
  - 增加后台被限制？打开电池优化设置，应对小米/华为等 OEM 杀后台
  - ActiveModeService 增加日志：通知关闭、频道关闭、startForeground 失败都会写 logcat
- 已在 Android 14 模拟器用 `pm revoke POST_NOTIFICATIONS` 复现并验证：
  - 旧逻辑：服务不启动
  - 新逻辑：服务正常前台运行，日志显示通知被系统关闭，授权后通知栏立即出现默认助手 正在陪伴 / 15分钟后首次心跳
- versionCode 264 / versionName 2.59.5

## v2.59.4（修复 arm64 busybox-binsh 离线包损坏）

- 根因：早期为了避免 `/bin/sh -> /bin/busybox` 绝对链接，曾用 Python tarfile 重打包
  `busybox-binsh`，破坏了 Alpine APK v2 的签名 + control + data多 gzip 段格式，
  真机 `apk add` 报 `v2 package format error`（exit=99）
- 已重新下载官方原版 `busybox-binsh-1.37.0-r31.apk` 替换损坏包
- rootfs 绝对符号链接仍由 `fixRootfsSymlinks()` 在安装后统一修复，不再改包
- `fetch-vm-assets.py` 增加 APK 格式校验：如果已有 `.apk` 不是合法的 v2 多段 gzip 格式，
  自动删除并重新下载，避免再次出现跳过后一直用坏包
- 已重新构建 arm64 内置 QEMU 包
- versionCode 263 / versionName 2.59.4

## v2.59.3（GitHub 发布前安全清理）

- 移除源码里的个人绝对路径 `C:/Users/Lenovo/...` fallback，Python 解释器改为按平台自动选择
- 移除未使用的 `SYSTEM_ALERT_WINDOW` 权限
- 新增 `THIRD_PARTY_NOTICES.md`，列明 PRoot/QEMU/Alpine/Chaquopy 等许可证与 GPL 义务
- 新增 `SECURITY.md`，说明 API Key 存储、无障碍/Python/Linux 高权限风险和网络注意事项
- ProfileScreen 对 `http://` Base URL 显示明文传输风险警告
- 修正 LICENSE 编码并补全 MIT + Commons Clause 文本
- 更新 README / LINUX_RUNTIME 过时说明
- versionCode 262 / versionName 2.59.3

## v2.59.2（默认 Flash + 内置 rootfs 复验）

- 按用户要求默认模型改为 `deepseek-flash`，默认不再使用 `deepseek-v4-pro`
- 已有 DeepSeek v4-pro 配置读取时自动迁移到 flash，避免继续用贵模型
- 清空 App 数据后复验内置 rootfs：
  - APK 内 `assets/linux/alpine-x86_64.tar.gz` 解压成功
  - `rootfs: `
  - `bin/sh -> busybox` 相对链接正确
  - Linux 环境执行 `uname` 返回 `Linux`，shell 正常
- 真实 key 复验：
  - flash 文本对话 
  - 图片直传识别 CODE7391 
  - 主动模式 5 分钟心跳真实推送 
- versionCode 261 / versionName 2.59.2

## v2.59.1（真实 API 验证）

- DeepSeek 图片直传自动选模型：
  - `deepseek-v4-pro` 是纯文本模型，`deepseek-flash` 才支持图片
  - 未配置独立视觉模型且当前是 DeepSeek v4-pro 时，带图的这一轮自动切到 `deepseek-flash`
  - 文本轮仍走用户选择的模型
- 用真实 test key 端到端验证：
  - 文本对话 
  - 图片直传：正确读出红框、蓝圆、绿三角和 CODE7391 
  - 退出对话重进：旧答案不再重新打字机播放 
  - 主动模式：5 分钟心跳真实调用 API，通知显示已推送，消息写回对话 
  - 心跳闹钟：`dumpsys alarm` 确认使用 `PendingIntent.getForegroundService` 
- versionCode 260 / versionName 2.59.1

## v2.59（多模态直传 + 主动模式/保活修复）

- 图片处理：
  - 未配置独立视觉模型时，直接把图片以 OpenAI `image_url` 多模态格式发给当前模型
  - 历史图片只发文字占位，避免每轮重复上传 base64
  - 大图最长边压缩到 1600，控制请求体积
  - 配置了独立视觉模型时仍走视觉模型描述 -> 主模型旧流程
- 修复退出对话重进后旧答案重新打字机播放：
  - ViewModel 记录已播放消息 key，进对话时把历史消息标记为已播放
  - 陈旧 `.agent_state.json` 断点自动清理，不再误导恢复
- rootfs/VM 资源内置：
  - Alpine rootfs、virt ISO 本就打进 APK
  - 增加 `checkBundledLinuxAssets`，缺失时直接构建失败并提示
- 主动模式 / 防杀后台修复：
  - 所有 FGS 启动路径先调用 `startForeground`，避免 5 秒超时
  - 心跳闹钟改用 `PendingIntent.getForegroundService`，兼容 Android 12+ 后台限制
  - 已运行角色再次启动时更新配置和下一次闹钟，而不是直接 return
  - 新增 `onTaskRemoved`：划掉最近任务后自动补闹钟并拉起自己
  - 闹钟到点但配置已删除时自动结束孤儿前台服务
- versionCode 259 / versionName 2.59

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