# 命苦打工人 — Android AI 助手

一个运行在 Android 上的 Agentic AI 助手。内置 Python 运行时，可以执行代码、操作手机、管理文件、搜索网络——完全由 AI 自主决策和行动。

**不是聊天机器人。是能动手的 AI。**

## 亮点

| 功能 | 说明 |
|------|------|
| 🐍 **嵌入 Python 运行时** | Chaquopy 内嵌 Python 3.12，AI 可以写代码并本地执行——不需要沙箱 API |
| 🛠️ **Agent 循环** | AI 自主选择工具、执行、观察结果、决定下一步，直到任务完成 |
| 📱 **无障碍服务** | 读取屏幕、模拟点击、操作 App，不给 API 也能控手机 |
| 💾 **工作区文件系统** | /workspace 目录，AI 读写文件、构建 HTML 页面、生成文档 |
| 👤 **可自定义角色** | 6 分栏结构（身份/性格/说话/禁忌/详细），自定义心跳和完整上下文 |
| 🔍 **事实查证模式** | 发消息前搜索验证，标注引源 |
| 🎯 **深度研究模式** | 多轮搜索 → 摘要 → 交叉验证，输出研究报告 |
| 📊 **规划解析器** | AI 自动制定 Plan → Task → Optimize 树，用户审批后执行 |
| ⚡ **DeepSeek 缓存优化** | 静态前缀在前，动态内容在后，闲聊缓存命中率 50%+ |
| 🔗 **多后端支持** | DeepSeek / 千问 / Ollama / 自定义 OpenAI 兼容 |

## 架构

```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐
│  ChatScreen  │ →  │ ChatViewModel │ →  │ API Service  │
│  (Compose)   │     │  (Agent Loop) │     │  (OkHttp)    │
└─────────────┘     └──────┬───────┘     └──────┬──────┘
                           │                     │
                    ┌──────┴───────┐     ┌──────┴───────┐
                    │ PythonSession│     │  DeepSeek    │
                    │  (Chaquopy)  │     │  千问/Ollama │
                    └──────────────┘     └──────────────┘
                           │
                    ┌──────┴───────┐
                    │ Accessibility │
                    │  Service      │
                    └──────────────┘
```

## 技术栈

- **语言:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **Python:** Chaquopy 16.0 (Python 3.12 内嵌)
- **网络:** OkHttp + Retrofit + Gson
- **无障碍:** AccessibilityService (BIND_ACCESSIBILITY_SERVICE)
- **最低 SDK:** Android 8.0 (API 26)
- **构建:** Gradle 8.6 + JDK 17

## 快速开始

### 1. 获取 API Key

注册 [DeepSeek 开放平台](https://platform.deepseek.com/) 获取 API Key。也支持千问、Ollama 等。

### 2. 构建

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。

### 3. 安装和配置

1. 安装 APK
2. 打开 App → 点击齿轮图标 → 填入 API Key 和模型名
3. （可选）在系统设置中开启无障碍服务，AI 就能操作手机

### 4. 开始使用

- 直接打字聊天
- AI 会自动选择工具（Python/搜索/文件读写）
- 开启「事实查证」或「深度研究」模式获得更强分析能力
- 创建自定义角色，获取个性化 AI 体验

## 项目结构

```
app/src/main/java/com/example/aichat/
├── ui/
│   ├── ChatScreen.kt        # 主聊天界面
│   ├── ProfileScreen.kt     # API 配置
│   └── PlanApproval.kt      # 规划审批 UI
├── viewmodel/
│   ├── ChatViewModel.kt     # Agent 循环核心
│   └── MainViewModel.kt     # 对话管理
├── data/
│   ├── StorageManager.kt    # 本地存储 (SharedPrefs)
│   ├── Personas.kt          # 角色系统 + 系统提示词
│   ├── ChatMessage.kt       # 消息模型
│   └── tools/
│       └── ToolRegistry.kt  # 工具注册表
├── service/
│   ├── ScreenControlService.kt  # 无障碍服务
│   └── ActiveModeService.kt     # 主动模式
├── python/
│   └── PythonSessionManager.kt  # Chaquopy 管理
└── api/
    └── ApiService.kt        # API 调用层
```

## 工具列表

AI 可以自主调用的工具：

| 工具 | 功能 |
|------|------|
| `python_exec` | 执行 Python 代码，返回输出 |
| `pip_install` | 安装 Python 包 |
| `read_file` | 读取工作区文件 |
| `write_file` | 写入工作区文件 |
| `web_fetch` | 获取网页内容 |
| `web_search` | 搜索网络 |
| `memory_save` | 保存长期记忆 |
| `memory_load` | 读取长期记忆 |
| `build_html` | 生成 HTML 页面 |
| `screen_read` | 读取手机屏幕内容 |
| `screen_gesture` | 模拟点击/滑动 |
| `python_close` | 关闭 Python 会话释放资源 |

## License

MIT
