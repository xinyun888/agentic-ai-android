# 命苦打工人 — Android AI 助手

一个运行在 Android 上的 Agentic AI 助手。它自带三层执行环境——内嵌 Python、PRoot Linux、QEMU 全系统虚拟机——AI 可以在手机上执行代码、跑真实 shell、开虚拟机、操作手机、管理文件、搜索网络，完全自主决策。

**不是聊天机器人。是能在你手机上动手干活的 AI。**

## 亮点

| 功能 | 说明 |
|------|------|
| 🐍 **内嵌 Python 运行时** | Chaquopy 内嵌 Python 3.13 + 12 个科学计算/办公库，AI 写代码即刻本地执行 |
| 🐧 **PRoot Linux 环境** | Alpine rootfs + 静态 PRoot，不用 root 就是一个真 POSIX shell（`linux_exec`） |
| 🖥️ **QEMU 全系统虚拟机** | 完整 Alpine VM，串口终端 + 磁盘持久化，guest 里能跑 Node/pnpm/git 工具链 |
| 🔗 **双向手机桥** | guest Linux ↔ 手机 的文件/HTTP 桥；guest 里的 agent 能反过来截屏、调用宿主模型 |
| 🧠 **DS Harness** | QEMU guest 内跑 agent harness，App 侧 WebView / 系统浏览器双渲染模式 |
| 🛠️ **Agent 循环** | AI 自主选择工具、执行、观察结果、决定下一步，直到任务完成 |
| 📱 **无障碍服务** | 读取屏幕、模拟点击/输入/滑动，不给 API 也能控手机 |
| 💾 **工作区文件系统** | 读写文件、构建 HTML 页面、生成文档 |
| 👤 **8 个内置角色** | 默认助手 / 打工人 / 程序员 / 翻译官 / 写作助手 / 学术助手 / 创意伙伴 / 命理师 |
| 🔮 **命理师人格** | 八字排盘、农历干支、六爻/梅花/小六壬起卦，配合规则审计防编造 |
| 🪪 **人物卡与看相** | 人物卡 + 引用校验；多模态面相分析、客观特征表、报告导出 |
| ✅ **答案审计** | AnswerAuditor 规则审计：卦象、日期、空承诺均按规则拦截 |
| ❤️ **主动模式** | 前台常驻 + 自适应心跳（20 秒 → 5 分钟），角色主动发起对话 |
| 🔍 **事实查证模式** | 注入核验协议，关键事实强制附来源，无法验证标注「未核实」 |
| 🌐 **联网搜索** | 多源 OkHttp 搜索（国内可用），可自动触发 |
| ⚡ **缓存优化** | 静态前缀在前、动态内容在后，长对话缓存命中率显著提升 |
| 📊 **用量统计** | 累计 Token、缓存命中/未命中实时可见 |
| 🎚️ **推理档位** | fast / deep 两档，映射到底层 `reasoning_effort`（low / max） |
| 🔗 **多后端支持** | DeepSeek / 千问 / Ollama / 自定义 OpenAI 兼容 |

## 三层执行环境

这是本项目的核心设计：把「AI 能干什么」按成本与表达力分成三档，工具接口统一，互不干扰。

```
┌──────────────────────────────────────────────────────────────┐
│  L1  内嵌 Python（Chaquopy，进程内，毫秒级）                    │
│      python_exec / bazi_paipan / date_convert / gua_yao       │
│      固定工具表，零启动开销，命理与日常计算走这条              │
├──────────────────────────────────────────────────────────────┤
│  L2  PRoot + Alpine rootfs（独立进程，秒级）                   │
│      linux_exec                                                │
│      真 POSIX shell、真 apt/pip，无需 root，无需虚拟机         │
├──────────────────────────────────────────────────────────────┤
│  L3  QEMU 全系统 VM（独立 Linux 内核，10 秒~1 分钟）           │
│      QemuManager / 串口终端 / DSH Harness                      │
│      完整 Linux，guest 内可跑 Node/pnpm/git，可持久装盘        │
└──────────────────────────────────────────────────────────────┘
```

**为什么要分三层**：日常任务（排盘、算数、读写文件）必须毫秒级响应，塞进虚拟机是纯体验倒退；而需要真 Linux 才能做的任务（装包、编译、跑工具链）又必须有个真内核。所以做成分层而不是替换。

### L2 是怎么绕过 Android 限制的

Android 10+ 在 `targetSdk >= 29` 后禁止 App 在可写数据目录里 `exec` 文件，这是 PRoot/Termux 类方案的死穴。本项目的做法是把 PRoot 伪装成原生库：

- `app/src/main/jniLibs/arm64-v8a/libproot_exec.so`（同时提供 x86_64）
- 开启 `useLegacyPackaging = true`，让它实际解压到 `nativeLibraryDir`
- 从 `nativeLibraryDir` 启动，即绕过 W^X 限制
- guest ELF 由 PRoot 自己 mmap 加载，内核不参与 `exec`，因此完全不受限

`build.gradle.kts` 里保留了 `-PtargetSdk=28` 的侧载开关作为备选，默认仍是现代 target。

## 架构

```
┌─────────────┐     ┌───────────────┐     ┌──────────────┐
│  ChatScreen  │ →  │ ChatViewModel │ →  │ ApiService    │
│  (Compose)   │     │ (Agent Loop)  │     │  (OkHttp)     │
└─────────────┘     └───────┬───────┘     └──────┬───────┘
                            │                     │
              ┌─────────────┼─────────────┐       │
              │             │             │       │
     ┌────────┴─────┐ ┌─────┴──────┐ ┌────┴──────┐│
     │  ToolRegistry│ │LinuxRuntime│ │QemuManager││
     │  (L1 工具表) │ │Manager (L2)│ │   (L3)    ││
     └────────┬─────┘ └─────┬──────┘ └────┬──────┘│
              │             │             │        │
     ┌────────┴─────┐ ┌─────┴─────────────┴───┐ ┌──┴────────┐
     │ PythonSession│ │  PhoneBridgeHttpServer │ │ DeepSeek  │
     │  (Chaquopy)  │ │  (guest ↔ 手机 双向桥) │ │ 千问/Ollama│
     └──────────────┘ └───────────┬────────────┘ └───────────┘
                                  │
                       ┌──────────┴──────────┐
                       │ ScreenControlService │
                       │  (无障碍，截图/点击)  │
                       └──────────────────────┘
```

## 界面

| 页面 | 文件 | 说明 |
|------|------|------|
| 对话列表 | `ui/MainScreen.kt` | 会话管理、重命名、删除 |
| 聊天 | `ui/ChatScreen.kt` | Agent 对话主界面、工具卡、打字机、主动模式 |
| API 配置 | `ui/ProfileScreen.kt` | 模型/Key/BaseURL、推理档位、视觉模型、Token 统计 |
| Linux | `ui/LinuxScreen.kt` | rootfs 安装、`linux_exec` 命令行、运行时状态 |
| 虚拟机 | `ui/VmScreen.kt` | 四步装机、串口终端、DSH 地址、空间清理 |
| DS Harness | `ui/HarnessScreen.kt` | guest harness，WebView / 系统浏览器双模式 |

## 技术栈

- **语言:** Kotlin 100%
- **UI:** Jetpack Compose + Material 3（Compose BOM 2024.02.00）
- **Python:** Chaquopy 3.13（内嵌 Python 3.13）
- **网络:** OkHttp 4.12 + Gson（Retrofit 已移除：其 suspend 泛型签名会被 R8 剥离导致崩溃）
- **压缩:** commons-compress（解压 Alpine rootfs）
- **图片:** Coil
- **无障碍:** AccessibilityService（含截图能力）
- **虚拟机:** QEMU 11.0.3（以 Alpine 离线包形式内置，装机时本地安装，全程不联网）
- **构建:** Gradle 8.6 + JDK 17

## 快速开始

### 1. 准备内置资源

运行时资源随 APK 内置（离线可用），首次构建前必须生成，缺失会在 `preBuild` 阶段直接报错：

```bash
# Windows
powershell -ExecutionPolicy Bypass -File fetch-linux-runtime.ps1   # PRoot + Alpine rootfs
python fetch-vm-assets.py                                          # QEMU 包 + 内核 + ISO + guest APK 仓库
python fetch-guest-toolchain.py                                    # guest Node/pnpm/git 离线工具链
```

对应的 `assets/` 产物：

| 目录 | 内容 |
|------|------|
| `assets/linux/` | Alpine rootfs（aarch64 / x86_64 各一份，约 4MB） |
| `assets/qemu/` | QEMU 11.0.3 及依赖的 Alpine 离线包，54 个 |
| `assets/guest-apks/` | guest 内 Python 用 APK 仓库，aarch64 44 个 |
| `assets/vm/` | `alpine-virt.iso` + `vmlinuz-virt` + `initramfs-virt` |
| `assets/dsh/` | DSH 前端 bundle + 预装系统盘 qcow2 |

> 合计数百 MB（`preinstall-disk.qcow2.bin` 单文件约 265MB），已在 `.gitignore` 中排除，**不进仓库**。注意 `assets/dsh/` 的单文件超过 GitHub 100MB 硬上限，切勿用 `git add -A`。

### 2. 获取 API Key

注册 [DeepSeek 开放平台](https://platform.deepseek.com/) 获取 API Key。也支持千问、Ollama 等 OpenAI 兼容后端。

### 3. 构建

```bash
build.bat                 # Windows 一键构建
# 或
build-linux.bat           # 侧载 Linux 环境构建（可指定 targetSdk=28）
# 或手动
./gradlew assembleRelease
```

APK 输出在 `app/build/outputs/apk/`。

> **签名说明**：`release.keystore` 不存在时自动回退 debug 签名，保证 APK 始终可安装。

### 4. 安装和配置

1. 安装 APK
2. 打开 App → 齿轮图标 → 填入 API Key、模型名、Base URL
3. 按需开启无障碍服务（截图与控机）；开启后 `ScreenControlService` 生效
4. 需要真 Linux 时，进「Linux 环境」安装 rootfs，或进「Linux VM」按四步装 QEMU

## 项目结构

```
app/src/main/java/com/example/aichat/
├── MainActivity.kt              # 入口与页面调度；启动 PhoneBridge
├── viewmodel/
│   ├── ChatViewModel.kt         # Agent 循环核心（流式/工具/审计）
│   ├── MainViewModel.kt         # 会话与页面状态
│   └── AnswerAuditor.kt         # 卦象/日期/空承诺规则审计
├── data/
│   ├── StorageManager.kt        # 对话持久化（原子写）
│   ├── Personas.kt              # 8 个角色 + 系统提示词
│   ├── PersonCard.kt            # 人物卡模型与解析
│   ├── ToolCallSanitizer.kt     # 工具调用协议清洗
│   ├── ChatMessage.kt / AgentStep.kt / PlanData.kt
│   ├── ApiService.kt / HttpClient.kt / GsonTypes.kt
│   ├── UsageMeter.kt            # Token 用量统计
│   └── tools/ToolRegistry.kt    # 工具注册与执行
├── python/
│   └── PythonSessionManager.kt  # Chaquopy 会话管理（L1）
├── linux/                       # L2
│   ├── LinuxRuntimeManager.kt   # PRoot + Alpine 运行时
│   ├── PhoneBridgeManager.kt    # 手机桥的生命周期
│   └── PhoneBridgeHttpServer.kt # guest ↔ 手机 HTTP 桥服务端
├── vm/                          # L3
│   ├── QemuManager.kt           # QEMU 装机/启动/超时/空间管理
│   ├── QemuSession.kt           # 会话状态
│   ├── QemuKeepAliveService.kt  # VM 保活前台服务
│   └── DshState.kt              # DSH Harness 状态
├── service/
│   ├── ScreenControlService.kt  # 无障碍服务（截图/点击/输入）
│   ├── ActiveModeService.kt     # 主动模式 + 自适应心跳
│   └── BootReceiver.kt          # 开机自启
├── ui/                          # 六个页面 + theme
app/src/main/python/
└── bazi_paipan.py               # 八字排盘（Chaquopy 侧）
app/src/main/jniLibs/
└── {arm64-v8a,x86_64}/libproot_*.so   # PRoot 伪装的 .so
```

## 工具列表

AI 可自主调用的工具：

### 文件与工作区
| 工具 | 功能 |
|------|------|
| `read_file` | 读取工作区文件 |
| `write_file` | 写入工作区文件 |
| `delete_file` | 删除工作区文件 |
| `list_files` | 列出工作区文件 |
| `build_html` | 生成 HTML 页面 |

### 执行环境
| 工具 | 功能 |
|------|------|
| `python_exec` | 执行 Python 代码，返回输出 |
| `session_close` | 关闭 Python 会话释放资源 |
| `linux_exec` | 在 PRoot Alpine 里执行真 shell 命令 |

### 网络
| 工具 | 功能 |
|------|------|
| `web_fetch` | 获取网页内容 |
| `http_request` | 发起任意 HTTP 请求 |
| （联网搜索） | 多源 OkHttp 搜索，由开关自动注入 |

### 文本与系统
| 工具 | 功能 |
|------|------|
| `run_regex` | 正则提取/替换 |
| `get_time` | 获取当前时间 |
| `clipboard_write` | 写入剪贴板 |
| `share_text` | 调起系统分享 |

### 命理
| 工具 | 功能 |
|------|------|
| `bazi_paipan` | 八字排盘（年/月/日/时/性别） |
| `date_convert` | 干支/农历/节气/星期/生肖换算 |
| `gua_yao` | 起卦（六爻 / 梅花易数 / 小六壬，支持外应随机起卦） |

### 记忆
| 工具 | 功能 |
|------|------|
| `memory_save` | 保存长期记忆 |
| `memory_load` | 读取长期记忆 |

### 无障碍
| 工具 | 功能 |
|------|------|
| `screen_info` | 读取屏幕内容与控件树 |
| `screen_tap` | 模拟点击 |
| `screen_swipe` | 模拟滑动 |
| `screen_type` | 模拟输入 |
| `screen_back` | 返回键 |
| `screen_home` | Home 键 |

## 安全与合规

API Key 本地保存，不上传服务器，支持自定义 Base URL。无障碍、Python、Linux、QEMU、文件/URL 工具均属高权限能力，使用前请阅读 [`SECURITY.md`](SECURITY.md)。

QEMU（GPLv2）、PRoot、Alpine、Chaquopy 等第三方组件的许可与分发义务见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

## 文档

| 文件 | 内容 |
|------|------|
| [`VERSION_NOTES.md`](VERSION_NOTES.md) | 逐版本变更记录 |
| [`LINUX_RUNTIME.md`](LINUX_RUNTIME.md) | PRoot / Linux 运行时细节 |
| [`SECURITY.md`](SECURITY.md) | 权限模型与安全边界 |
| [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) | 第三方许可声明 |

## License

MIT License with Commons Clause（禁止未授权商业售卖/SaaS）。
