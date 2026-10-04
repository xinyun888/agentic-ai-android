## v2.61.27（面相报告导出 + guest 脚本重跑修复）

### 新增：面相报告导出
- 人物卡面板新增 **「面相报告」** 按钮：生成 markdown 报告  复制到剪贴板 + 弹系统分享（发微信/文件/备忘录）
- 报告结构：**一、客观特征（观测）**（按部位分组，带置信度与来源） **二、解读（含依据 卡[id]）** 
  **三、八字与面相互证**（双向：面相引用八字 / 八字引用面相） **四、待确认/冲突/反例** + 免责声明
- 卡片从不存图，报告也不含图片；纯函数 `buildFaceReport(card)` 可离线自检（新增第 17 组用例）

### 修复：guest 脚本重跑会误报 DSH 失败（真机隐患）
- 现象：脚本被重跑（App 超时重试 / 手动再执行）时会**再起一个 `dsh web`**，端口 3080 被占  新实例报错并
  把 `/tmp/dsh-web.log` **覆盖**掉  token 丢失  界面误报 `AICHAT_DSH_FAIL`（其实 DSH 一直正常运行）
- 修复：
  - 启动前检查 `/tmp/dsh-web.log` 里已有 `token=`  打印 `AICHAT_DSH_REUSE` 直接复用，不再重启
  - 日志改为追加（`>>`）而不是覆盖
  - 转发进程写 `/tmp/dsh-forward.pid`，重跑时 `kill -0` 检查存活  复用，避免抢占 8000 端口

- versionCode 303 / versionName 2.61.27

## v2.61.26（面相加强：几何参照 + 对称/互证 + 直接分析）

### 图片管线（全部本地、零新依赖、**不做质量判断**）
- **人脸检测只用于几何**（`android.media.FaceDetector`）：检出脸框就裁切放大，并把参照线画在"脸部"
- 参照线：绿框(脸框) + 脸部三等分线(青) + 中轴(黄) + 10x10 网格(白)；没有脸框才退回画面三等分
- **已按要求撤掉所有本地质量结论**（偏暗/过曝/侧脸/多人/头部偏转一律不下结论、也不注入提示）
  照片质量交给模型自己判断，系统只在几何层面给参照物，避免本地启发式把模型卡住
- EXIF 方向纠正 + 长边 1280；**不美颜/不磨皮/不锐化**

### 提示词升级
- 结果分**几何类**（三停占比%、脸宽长比、眉眼距眼宽几倍、鼻宽/脸宽、下颌角）+ **印象类**（浓淡/光泽/气色/神态）
- **左右对称检查**：以中轴为参照逐项比较（眉高/眼大小/嘴角/颧骨/下颌线），用可核对的说法
- **八字面相互证映射表**（伤官旺眉眼锐利 / 印星重面部圆润 / 比劫旺轮廓硬朗 / 财星旺鼻挺唇厚 /
  官杀重眉浓法令 / 身弱面部柔和），标注"一致/部分一致/不一致"
- **用户明确说"直接分析/直接看/从面相分析/不用确认"时不再等确认**：同一轮里先给特征表、紧接着给解读，
  末尾加一句"若某项特征与你不符，说一声我据此修正"

### 审计补强
- 新增硬规则：本轮没有照片却出现**面相测量数值**（上停/脸宽/眉眼距 + 数字） 拦

### 验证
- `CardStore.selfTest()` 16 组 PASS；面相 demo 5 个审计场景符合预期
- 图片管线自检：EXIF / 人脸检测 / 裁切 / 脸部三等分 / 网格 / 中轴 均在；
  已确认本地质量结论相关代码（偏暗/过曝/侧脸/多人提示）**全部移除**

- versionCode 302 / versionName 2.61.26

## v2.61.25（命理师面相：多模态看相 + 客观特征表 + 审计兜底）

### 功能
- **面相分析走主模型多模态**（用户指定）：命理师角色发照片时不走独立视觉模型，直接进主模型上下文
- **图片预处理**：EXIF 方向纠正  长边缩到 1280  叠**辅助参照**（三等分横线 + 10x10 网格 + 中轴） JPEG 88
  - 只做几何变换，**不做美颜/磨皮/锐化**（会改骨相与纹理，影响看相）
  - 提示词明确：这是**画面**三等分不是脸部三等分，要求先用网格坐标报告关键位置（发际线 R2.5、眉心 R4）
- **强制两段式**（防幻视）：
  1. 拍摄质量检查（不合格要求重拍，不硬看）+ **逐部位客观特征表**（部位｜观察值｜置信度，看不清就写看不清）
  2. 用户核对后才解读；每条结论必须引用 `卡[面相-额#f1]` 这类观测条目
- **结构化成人物卡**：新增 `面相-部位` 类别（脸型/三停/额/眉/眼/鼻/颧/法令/口/下巴/痣纹/气色）、
  新类型 `观测`、新字段 `source`（图1/正面 之类）；面板按类别自动分组显示，导出导入沿用
- 与八字**互证**：并列不强行统一

### 审计（零 token，4 条新规则）
1. 硬：本轮没有照片却给面相结论  "无法核对，请勿采信"
2. 软：提到面相部位但没有对应「观测」条目  "缺乏支撑，建议先给特征表"
3. 硬：**健康/疾病诊断**（"你肝不好"） 拦
4. 硬：**年龄/性别/身份判断、整容/医美建议**  拦

### 验证
- `CardStore.selfTest()` 扩到 **16 组**（新增面相类别透传/来源保留/问面相时可渲染），JVM 直跑 **PASS**
- 面相端到端 demo（JVM）：
  - 观测入卡  类别落成 `面相额`/`面相眉`、`source=图1/正面` 保留 
  - 问"帮我看看面相"  注入片段含 `面相*` 条目 + `来源:图1/正面` 
  - 审计：无照片给结论硬告警  / 有照片+有观测无告警  / 肝不好硬告警  / 看年龄+整容硬告警  / 无观测支撑的部位软提示 

### 本轮修掉的问题
- 模型只写 `cat="面相"` 而部位在正文时，类别没落到具体部位  `normalizeEntry()` 从正文推导（并在 `merge` 内统一归一化，覆盖 JSON 解析与直接构造两条路径）
- 自检期望值里的 `` 被编码吞掉导致误报  改用 `\u00B7` 转义

- versionCode 301 / versionName 2.61.25

## v2.61.24（命理师：人物卡 + 适度联想 + 引用校验）

### 新增能力
- **人物卡（有出处、可增量维护）**：`data/PersonCard.kt`（CardStore）
  - 排盘确认后建卡，按八字指纹隔离（换会话也能接上）；每条带 类别/结论/类型(自述命盘联想)/强度(明确倾向待确认)/**逐字原句+轮次**
  - 三类来源可区分；联想默认"待确认"；**反例**（counter）自动降级该条 + **联动降级**依赖它的联想
  - 冲突不覆盖，新旧并列交用户裁决；每类目上限 8 条，超出合并为"历史摘要"；单轮增量硬上限 6 条
  - 每轮只注入**相关类别 + 待确认/冲突/反例**（省 token，不翻原文）
- **允许适度联想**：提示词新增「人物卡与上下文联想规则」16 条依据 卡[id] + 推理链、默认 1 跳最多 2 跳、
  自动联想（先"接上文"再分析）、联想不得覆盖命盘、负面结论要建设性+给补位方案、可证伪+给验证问题、
  敏感领域只给倾向+建议、每轮最多问一个聚焦问题
- **卡片面板 UI**（聊天输入框上方入口）：查看全部条目 + 一键 `对/不对/删除` + `导出/导入`（格式 `AICHAT_CARD_V1`）
- **增量协议**：模型回答末尾附 `<<CARD_UPDATE>>{json}<<END>>`，App 解析合并后从可见文本里剔除

### 解析与校验（零 token）
- 三级降级解析：strict JSON  修复(去 code fence/尾逗号)  正则抽取三元组；全失败只去块不动卡片
- `AnswerAuditor` 新增 4 条规则（软/硬分级）：
  - 硬：引用 `卡[id]` 但条目不存在  "凭空引用"
  - 软：有【推断】无依据 / 引用原句在卡片与最近上下文都找不到 / 把"联想"说成命盘结论

### 自检与验证
- `CardStore.selfTest()` 14 组回归用例（指纹、拆块、三级解析、去重合并、确认、反例、**反例联动**、
  冲突、溢出合并、单轮上限、渲染、引用校验、未知 id 容错）；debug 启动自动跑并打日志
- 已用 JVM 直跑：`SELF-TEST PASS`；并用你的例子端到端验证：
  「我性子比较急」+「做事急躁」（去重合并成 1 条，两句原句都留） 问职业（生成联想条目，依据 卡[x1]）
   用户说「我其实挺能坐得住」（反例：本条 + 依赖它的联想一起降级为待确认）
   审计器：引用 卡[x9] 报硬告警、正常引用无告警、编造原句报软提示

### 本轮修掉的 3 个 bug（demo 抓到）
1. 「做事急躁」与「性子急」不合并（相似度阈值 0.8 太死） 0.6 + 同域特征词命中
2. 正则兜底把 "原句: " 吞进结论正文  `cutAtKeys()` 截断
3. 反例只降级自身，依赖它的联想不降级  反例联动降级

- versionCode 300 / versionName 2.61.24

## v2.61.23（真机验证通过 + 脚本加固 + 自动进 QEMU 模式）

- **真机实测全链路成功**（用户手机，磁盘启动）：
  `AICHAT_TOOLCHAIN_OK`  `dsh 0.2.0-rc.2`  `AICHAT_DSH_OK` 
  `AICHAT_DSH_URL=http://127.0.0.1:18000/?token=...`  `AICHAT_SETUP_DONE`
- guest 脚本加固：
  - 模型配置改用 `printf` 写（不再用 heredoc，避免终止符问题把后续启动代码吞掉）
  - 打印 `AICHAT_DSH_CONFIG_WRITTEN` / `AICHAT_DSH_WAIT=30s` 进度 + DSH 日志尾行，便于排错
  - 转发脚本已存在时不再重写
- DS Harness 页：若 DSH 已就绪（`DshState.ready`）**默认直接进入 QEMU 模式**，省一次点击
- versionCode 299 / versionName 2.61.23

## v2.61.22（磁盘启动失败自动回退 Live）

- 磁盘启动看门狗超时（900s 仍未进 shell）时**自动切回 Live 模式**重试：
  `setDiskBootEnabled(false)` + 重新以 Live ISO + 离线 apk + DSH 包安装（已验证的流程），
  预装镜像仍保留在 `/vm/alpine.qcow2`，之后仍可用「当前:磁盘」切回
- 背景：预装镜像在真实 aarch64 QEMU（PC，App 相同参数）90 秒进 login、`dsh web` 正常；
  但 Android 模拟器上 PRoot 拦截磁盘 I/O 导致卡在 `Mounting root`（实测 30 秒内 stime 25s vs utime 14s），
  属于环境极端情况，加自动回退兜底
- versionCode 298 / versionName 2.61.22

## v2.61.21（预装镜像改为"解压后的普通 qcow2" + 磁盘启动看门狗放宽）

- 预装镜像资源改为 **gzip 过的未压缩 qcow2**（253MB，比压缩 qcow2 还小）：
  App 展开时解压一次（`GZIPInputStream`），之后 guest 读写就是普通镜像。
  原因：**压缩 qcow2 在 PRoot 下逐簇解压极慢**模拟器实测卡在 `Mounting root:` 十几分钟
  （同一镜像在 PC 上用 App 完全相同的 QEMU 参数 90 秒就进 login）。
- 磁盘启动看门狗 240s  **900s**（完整 OpenRC + ext4 journal 回放比 Live 快启慢得多）
- versionCode 297 / versionName 2.61.21

## v2.61.20（点"启动 VM"即自动展开预装系统）

- 「启动 VM」按钮：如果检测到内置预装镜像还没展开（缺 `.preinstalled` 标记），
  自动先展开到 `/vm/alpine.qcow2`（含 node/pnpm/git/DSH）再启动，用户只需点一次
- 「4. 创建磁盘」按钮在预装镜像未展开时也保持可用（不再是"已有空磁盘就禁用"）
- 启动按钮可用条件放宽为 `diskReady() || preinstallImageReady()`
- versionCode 296 / versionName 2.61.20

## v2.61.19（内置"预装系统"磁盘镜像：首次 30-60 秒直接可用）

- 新增 `assets/dsh/preinstall-disk.qcow2.bin`（286.8MB，压缩 qcow2）：
  一台真正装好的 aarch64 Alpine  `node v24.18.1 / npm / pnpm / git / bash`
  + `@deepseek-ai/dsh@0.2.0-rc.2`（/opt/dsh，实测 `dsh web` 正常输出带 token 的 URL）
  + `dsh-forward.js`（0.0.0.0:8000  127.0.0.1:3080 转发）
- 点「创建磁盘」= 展开这个镜像到 `/vm/alpine.qcow2` 并自动切磁盘启动；
  开机后 Guest 脚本只需写模型配置 + 起 DSH，**不再需要装任何东西、也不需要联网**
- 实测：`dsh web: http://127.0.0.1:3080/?token=...`（磁盘镜像内启动成功）
- 需要说明的两个坑（已解决）：
  1. 第一次做镜像时 DSH 起不来，报 `SyntaxError: Unexpected end of JSON input`（`collectInstallationScopePackages`）
      根因是解压时 tar 被截断，留下空的 `package.json`；重新完整解压后正常
  2. `setup-disk` 装盘后必须补内核模块（见 v2.61.18），否则磁盘模式没网络
- 若镜像缺失，自动回退到旧流程（Live ISO + 离线 apk + DSH 包），不会坏
- versionCode 295 / versionName 2.61.19

## v2.61.18（内置预装系统镜像 + 修 3 个装盘致命 bug）

- **新增 `assets/dsh/preinstall-disk.qcow2.bin`**：一台真正装好的 aarch64 Alpine（234MB）：
  `node v24.18.1 / npm / pnpm / git / bash` + `@deepseek-ai/dsh@0.2.0-rc.2`（/opt/dsh）+
  DSH 模型配置 + `dsh-forward.js`。点「创建磁盘」即展开为 `/vm/alpine.qcow2` 并自动切磁盘启动，
  **首次 30-60 秒即可用，不需要装任何东西、不需要联网**
- 磁盘模式下 `imagesReady()` 允许只靠预装镜像（ISO 可缺省）
- **修 3 个"安装到磁盘"致命 bug**（实测发现）：
  1. aarch64 上 `setup-disk` 默认要 `u-boot`（仓库没有） 加 `-B none`
     （反正 App 用 `-kernel/-initrd` 直接引导，不需要 bootloader）
  2. `init=/bin/sh` 快速模式没有 mdev 守护，分区后 `/dev/vdaX` 不出现  装盘前先起 `mdev -d`
  3. **磁盘系统 `/lib/modules/<uname -r>` 为空**（setup-disk 装的是 CDN 最新内核，与 assets 内核不同版本）
      `AF_PACKET`/`virtio_net` 全加载不了，磁盘模式完全没有网络  装盘后把 Live 的匹配模块树拷进目标根
- 实测：Live 装盘  磁盘启动  DHCP 正常  离线装 node/DSH 成功（磁盘 755MB，压缩 234MB）
- versionCode 294 / versionName 2.61.18

## v2.61.17（首次运行自动"安装到磁盘"：DSH 与环境持久化）

- DSH 就绪后，如果当前还是 Live ISO（`bootFromDisk=false`），App 自动：
  1. 调 `installToDisk()`（手机桥的 `disk-install.sh` + `setup-disk`）把 Alpine 装到 `/dev/vda`
  2. 成功后自动重启会话切到磁盘启动（`root=/dev/vda3`），无需手动操作
  3. 磁盘模式下离线脚本会重跑一遍（node/DSH 装到磁盘根），之后**重启 VM 不再重新解压，
     会话/设置/工作区都保留**
- 失败时给出提示并继续用内存模式（不影响使用）
- 只在首次触发一次；`bootFromDisk=true` 会持久保存，之后一直在磁盘模式
- 说明：
  - 装盘那一步的 `setup-disk` 需要联网（从 Alpine CDN 取 kernel/bootloader）；node/DSH 仍是离线包
  - **工作区**：DSH 的 workspace 注册表由它自己的 API（`workspace.initializeDefault()` / 默认目录
    `<Documents>/deepseek-harness`）在 Web UI 首次点击时创建，App 侧未找到可预置的配置/接口，
    因此第一次仍需在 DSH Web UI 点一次「选择工作区」；装盘之后该选择会**永久保留**（只此一次）
- versionCode 293 / versionName 2.61.17

## v2.61.16（全离线集成：Node 工具链 + DeepSeek Harness，一键直达）

- **下载全部内置**（APK 体积换开箱即用）：
  - `assets/guest-apks/aarch64/`：nodejs / npm / pnpm / git / bash 及其依赖的 aarch64 Alpine 离线包
    （合并 main+community 的 APKINDEX，共 41.7MB），guest 只从手机桥 `10.0.2.2` 装，**无需外网**
  - `assets/dsh/dsh-bundle.tar.gz`：在 aarch64 Alpine 上真实编译好的 DeepSeek Harness
    `@deepseek-ai/dsh@0.2.0-rc.2` 整棵依赖树（540 个包，124MB；koffi 已用 cmake 源码编译），
    guest 解压到 `/opt/dsh`，无需 npm 安装、无需联网
- **guest 自动流程**（`guestSetupScript`）：
  1. 离线装 node/npm/pnpm/git/bash，打印版本 + `AICHAT_TOOLCHAIN_OK`
  2. 从手机桥下载并解压 DSH，打印 `dsh 0.2.0-rc.2`
  3. 预置模型：把 App 当前 API 配置写成 `~/.dsh/profiles/web/cordis.patch.yml`
     （`llm-pi-ai` + `openai-completions`，key 走 `AICHAT_API_KEY` 环境变量）
  4. 启动 `dsh --profile web --no-open --port 3080 --host 127.0.0.1`
     （DSH 官方只允许 loopback；用 Node 写了一个 0.0.0.0:8000  127.0.0.1:3080 的 TCP 转发）
  5. 就绪后打印 `AICHAT_DSH_OK` 和改写后的带 token URL（`http://127.0.0.1:18000/?token=...`）
- **App 侧**：
  - `DshState` 捕获 URL/就绪状态；DS Harness 页的 QEMU 模式直接内嵌 **WebView** 显示 DSH Web UI
  - VM 默认内存 1024MB  **2048MB**（DSH + 解压需要）
  - VM 页面状态：` 正在解压并启动 DeepSeek Harness ...` / ` DeepSeek Harness 已就绪`
- **真机同构验证**（aarch64 Alpine + QEMU，走 App 同一份脚本 + 手机桥离线资源）：
  ```
  AICHAT_TOOLCHAIN_OK  node v24.18.1 / npm 11.12.1 / git 2.54.0 / pnpm 11.20.0
  dsh-bundle.tar.gz 123M saved / dsh 0.2.0-rc.2
  AICHAT_DSH_OK
  AICHAT_DSH_URL=http://127.0.0.1:18000/?token=...
  ```
  PC 侧访问 hostfwd 返回 401（无 token 被安全栅栏拒绝，符合预期）
- versionCode 292 / versionName 2.61.16

## v2.61.15（VM 自动配置 node / pnpm / git 环境）

- guest 自动安装脚本从"装 python3 + 内置 harness"改为安装 **Node 工具链**：
  - 配置 Alpine 官方源（main/community）+ 手机桥离线源 + DNS
  - `apk add --no-cache nodejs npm git`
  - `npm install -g --prefix /usr/local pnpm@11.7.0`（版本与 DeepSeek Harness
    桌面运行时一致；Alpine 上 npm 全局 bin 不在 PATH，脚本里加了 /usr/local/bin 兜底与软链）
  - 依次打印版本并输出 `AICHAT_TOOLCHAIN_OK` / `AICHAT_TOOLCHAIN_FAIL`
- VM 页面状态改成"Guest 环境"：
  - ` 正在安装 node / pnpm / git ...`
  - ` node / pnpm / git 已就绪`
  - ` 工具链安装失败，请看下方串口日志`
- 真机同构验证（aarch64 Alpine + QEMU，走 App 同一套短命令 + 同一份脚本）：
  ```
  AICHAT_TOOLCHAIN_BEGIN
  node v24.18.1
  npm  11.12.1
  git  git version 2.54.0
  pnpm 11.7.0
  AICHAT_TOOLCHAIN_OK
  ```
- versionCode 291 / versionName 2.61.15

## v2.61.14（真正的元凶：串口输出被 150ms 节流吞掉）

- 用户实测：QEMU 启动正常，但终端停在 `/`；看门狗报"仍未检测到 shell"，harness 永远连不上。
- 真机复现（Android 模拟器 + App 自带 PRoot/QEMU）：
  - App 的 reader 线程停在 `pipe_read`（等数据），QEMU 的 TCG 线程停在 `futex_wait`（空闲），
    即 guest 早已输出完、在等输入，但最后一批数据没进到 App 里。
  - 根因：QemuSession 读取循环把 `_output` 发布做了 150ms 节流。若最后一批串口输出
    落在节流窗口内、之后 guest 不再输出，这批数据就永远只在 StringBuilder 里，
    `read()` 又一直阻塞，于是终端停在半行（`/`）、提示符 `~ #` 检测不到、
    自动安装命令永远不发  时好时坏，取决于时序。
- 修复：
  - 每读到一块数据立即发布一次（去掉手动节流；StateFlow 会自行合并，Compose 每帧最多重绘一次）。
- 另加兜底（针对提示符被切半行的情况）：
  - 快速模式下只要看到 `Installing packages to root filesystem`，20 秒后即使用户提示符
    没被识别出来，也直接发送安装命令；仍会在收不到 `AICHAT_SETUP_BEGIN` 时重发（最多 3 次）。
- 模拟器实测（最恶劣情况：假 initramfs 只输出 `/" 且不给提示符）：
  - 终端完整显示到最后一行；
  - 20 秒后自动盲发 8 条安装命令，guest 逐条执行（`GOT: ...`）；
  - 正常假 initramfs（有真实 `~ #` 提示符）下同样全链路通过。
- versionCode 290 / versionName 2.61.14

## v2.61.13（修复 QEMU 连不上 Guest Harness）

- 用户实测：QEMU 跑起来了，但 DS Harness 一直「未连接 / 等待 guest 自动安装」，
  guest-setup 永远不执行，harness 永远装不上。
- 根因 1（致命）：App 向 guest 串口写命令时用 `\r` 结尾。guest 控制台是 icanon 规范
  模式，getty/login 还会清掉 ICRNL，只有 LF 才能结束一行；只发 CR 时 `root`、
  guest-setup 命令都停在行缓冲里不执行，所以既登录不上，harness 也装不上。
  - 现在统一改发 `\n`。
- 根因 2（致命）：guest 的 busybox ash 会在提示符后发 `ESC[6n` 查询光标位置，
  `lastLine.endsWith("~ #")` 因此永远不成立，提示符检测一直失败。
  - 现在识别前先剥掉 ANSI 转义序列再判断。
- 根因 3：串口没有流控，PL011 RX FIFO 只有 16 字节。之前按 48 字节/25ms 灌 500+ 字符的
  guest-setup 长命令会被静默丢字符写坏。
  - 写入改成 12 字符分块 + 等 guest 回显确认后再发下一块；
  - guest-setup 拆成多条短命令，并新增「等 AICHAT_SETUP_BEGIN，收不到就重发（最多 3 次）」。
- 根因 4：部分机型完整 OpenRC 会卡在 firstboot 前，永远到不了 login。
  - 默认改为快速模式（`init=/bin/sh`），跳过 OpenRC/getty/login；
  - 安全模式也改用 `init=/bin/sh`；
  - 看门狗 240 秒还没进 shell 时，自动停掉旧 QEMU 并改用快速模式重启一次，不再只提示。
- 「安装到磁盘」的 wget/sh 也拆成两条短命令。
- Windows QEMU 实测：快速模式约 5 秒进 `~ #`，约 11 秒出现 `AICHAT_HARNESS_OK`，
  `127.0.0.1:18000/v1/models` 正常返回 `ds-harness`。
- versionCode 289 / versionName 2.61.13

## v2.61.12（定位 OpenRC 卡死：单线程 TCG）

- 用户实测：Alpine 完整 OpenRC 在部分设备卡在：
  ```text
  * Starting firstboot ... [ ok ]
  ```
  之后不再出现 Welcome/login
- 对照测试：
  - 同一 guest、同一内核、同一 ISO
  - `-accel tcg,thread=multi` + cortex-a57：部分环境 OpenRC 卡死
  - `-accel tcg,thread=single` + cortex-a53：完整 OpenRC 正常到 login
- 结论：
  - Android + PRoot 下 QEMU TCG 多线程和 OpenRC 并行服务启动存在兼容问题
  - 这不是 Harness 问题，是 TCG 线程模型问题
- v2.61.12 修复：
  - 完整模式默认改为：
    ```text
    -accel tcg,thread=single
    -cpu cortex-a53
    ```
  - 快速模式 `init=/bin/sh` 保留为备用
  - 默认改回完整模式（fast_boot 默认 false）
- Windows QEMU 验证：
  - 单线程完整 OpenRC 启动到 login
  - phone 桥 + AICHAT_HARNESS_OK 正常
- versionCode 288 / versionName 2.61.12

## v2.61.11（快速模式默认启用）

- 真机/模拟器实测：Alpine 完整 OpenRC 启动在部分环境会停在 firstboot 后，不再出现 login
- 新增并默认启用快速模式：
  - QEMU 内核参数：
    ```text
    init=/bin/sh
    ```
  - 直接进入 root shell，绕过 OpenRC / getty / login
  - 通常几秒进入 `~ #`
  - Guest 自动安装 phone 桥 + Python + Harness 仍正常执行
- Windows QEMU 用快速模式实测通过：
  - `~ #` root shell          
  - `AICHAT_HARNESS_OK`       
  - hostfwd `/v1/models` JSON 
- 修复快速模式下 `phone` 不在 PATH 的问题：
  - 改用绝对路径 `/usr/local/bin/phone available`
- VM 页面保留快速模式 / 完整模式切换：
  - 快速模式：默认，推荐，用于 Harness/手机控制
  - 完整模式：完整 OpenRC，用于需要完整 Alpine 系统的场景
- versionCode 287 / versionName 2.61.11

## v2.61.10（修复把 apk 进度条误判成 shell 提示符）

- Android 模拟器实测发现：
  - Alpine 安装 base 包时会输出大量进度条，末尾也是 `#`
  - 旧逻辑把最后一行以 `#` 结尾当成 shell 提示符
  - 于是在 login 之前就把 guest 配置命令输入了串口
  - 终端表现为反复出现启动前的命令，然后 login 后再也不自动登录
- 修复：
  - 只认真正提示符：
    ```text
    :~#
    ~ #
    :/#
    ```
  - 必须已经检测到 `login:` 后才发送 root
  - 必须已检测到 login 且出现真正提示符后，才发送 guest 配置命令
  - apk 进度条 `#` 不再误触发
- Android 模拟器验证：
  - VM 终端可见
  - 退出 VM 页面再进入，会话和输出保留
  - QEMU 单实例 + pidfile + 180s 不再重复启动
  - HarnessScreen 在 guest 未就绪时显示：
    ```text
    已连接（宿主 Harness）
    ```
- versionCode 286 / versionName 2.61.10

## v2.61.9（QEMU 单实例保护）

- 修复 Android 模拟器实测发现的严重问题：
  - guest 启动较慢时，180 秒 watchdog 会启动第二个 QEMU
  - 第一个 QEMU 还活着并锁着 alpine.qcow2
  - 第二个 QEMU 报：
    ```text
    Failed to get "write" lock
    Is another process using the image [/vm/alpine.qcow2]?
    ```
  - 终端只剩第二个 QEMU 的错误，看起来像卡住/重复输出
- 修复：
  - QEMU 启动参数新增 `-pidfile /vm/qemu.pid`
  - `stopSession` 直接读取 pidfile 并向该 PID 发送 SIGKILL
  - watchdog 只有在 QEMU 进程已经退出时才自动切安全模式
  - 如果进程还活着只是慢，不再启动第二个 QEMU，只在终端提示
  - 保留安全模式按钮由用户手动选择
- versionCode 285 / versionName 2.61.9

## v2.61.8（Guest 未就绪时 Harness 也可用）

- 用户实测：guest 内核起来了，但有时停在 OpenRC/登录前，导致 Harness 永远未连接
- 新增宿主兜底 Harness：
  - `PhoneBridgeHttpServer` 增加 OpenAI 兼容端点：
    ```text
    GET  /v1/models?token=...
    POST /v1/chat/completions?token=...
    GET  /health?token=...
    ```
  - HarnessScreen 先连 guest `127.0.0.1:18000`
  - guest 未就绪时自动回退到宿主 `127.0.0.1:48879`
  - 状态显示：
    - `已连接`
    - `已连接（宿主 Harness）`
  - guest Harness 一旦就绪，会自动切回 guest
- VM 页面新增安全模式按钮：
  - `-m 512 -smp 1 -cpu cortex-a53 -accel tcg,thread=single`
  - 用于 guest 卡在 OpenRC/登录前时快速重试
- 保留 v2.61.7 的自动登录重试
- versionCode 284 / versionName 2.61.8

## v2.61.7（Guest 自动登录/Harness 安装修复）

- 用户实测日志：guest 已正常启动到：
  ```text
  localhost login:
  ```
  但自动登录和 Harness 安装没有触发
- 修复 `startGuestSetup`：
  - 不再依赖 StateFlow collect 的一次性判断
  - 全新轮询循环：
    - 检测到 `login:` 后发送 `root`
    - 如果 5 秒内没有 shell 提示符，最多重试 5 次
    - 检测到 `~ #` / `localhost:~#` 后发送完整 guest 配置命令
  - App 会在终端显示：
    ```text
    [App] 检测到 login，发送 root（第 1 次）
    [App] guest shell 已就绪，开始安装 phone bridge + Guest Harness
    ```
- 修复 Guest Harness 在有内核输出但没有自动登录场景下永远不连接的问题
- versionCode 283 / versionName 2.61.7

## v2.61.6（QEMU 串口输出诊断）

- QEMU 启动参数从：
  ```text
  -nographic
  ```
  改为更明确的：
  ```text
  -display none -serial stdio
  ```
  避免 App 管道环境下 `-nographic` 把串口/监视器混在一起导致看不到内核输出
- QemuSession 新增 exitCode：
  - QEMU 退出时 VM 终端显示 `QEMU 进程退出，exit=...`
- 新增启动诊断：
  - 启动 20 秒没有任何输出时，终端自动打印诊断
  - 每次启动显示内核/initramfs/磁盘大小和完整 QEMU 参数
  - 重复点启动会先清空上一次日志，不再刷屏
- versionCode 282 / versionName 2.61.6

## v2.61.5（VM 终端显示修复）

- 修复 Linux VM 页面看不到终端的问题：
  - 之前页面 Column 不可滚动，按钮占满屏幕时终端区域可能被压到 0 高度/屏幕外
  - 现在整页可滚动
  - 终端固定最小 360dp 高度
  - 终端标题栏显示当前输出字符数
  - 新增放大按钮：全屏 AlertDialog 查看串口输出
  - 即使没有串口输出，也会显示安装日志/状态提示
- Guest Harness 状态仍显示在 VM 页面：
  -  等待 guest 自动安装
  -  已就绪
  -  安装失败/Python 安装失败
- versionCode 281 / versionName 2.61.5

## v2.61.4（Guest Harness 状态可见 + DNS 兜底增强）

- VM 页面新增 Guest Harness 状态提示：
  -  等待 guest 自动安装（通常 1-3 分钟）
  -  已就绪，可以打开 DS Harness
  -  安装失败 / Python 安装失败，请看串口日志
- HarnessScreen 未连接时显示等待提示，并继续每 4 秒自动重试
- DNS 继承增强：
  - 遍历 `ConnectivityManager.allNetworks`
  - 优先 active network 和 WiFi 网络
  - 取第一个 IPv4 DNS
  - 读不到时回退 `223.5.5.5`（国内可达，替代之前的 1.1.1.1）
- 说明：VM 启动后请不要立刻停在 Harness 页面等；先看 VM 终端是否出现：
  ```text
  AICHAT_HARNESS_OK
  AICHAT_SETUP_DONE
  ```
  出现后再打开 Harness，或直接留在 Harness 页面，它会自动重试连上。
- versionCode 280 / versionName 2.61.4

## v2.61.3（QEMU Guest 真正继承手机 WiFi/DNS）

- 定位 guest 网络不通根因：
  - QEMU user networking（slirp）不是直接把 WiFi 网卡桥接给 guest
  - guest 的以太网是 QEMU 在 App 进程里虚拟出来的 NAT
  - guest 出站 TCP 会走 App 的 Android socket，因此能继承手机 WiFi
  - 但 QEMU 启动时需要用 host 的 `/etc/resolv.conf` 找上游 DNS
  - Android 没有传统 `/etc/resolv.conf`，QEMU 拿不到手机 WiFi 的 DNS
  - 结果：guest 能连 `10.0.2.2`（App 自身），但无法解析 `dl-cdn.alpinelinux.org`
- 修复：
  - `QemuManager` 通过 `ConnectivityManager.getLinkProperties(activeNetwork).dnsServers`
    读取手机当前 WiFi/数据网络的 DNS
  - 把 IPv4 DNS 通过 QEMU 参数显式传入：
    ```text
    -netdev user,id=n0,hostfwd=...,dns=<手机当前DNS>
    ```
  - 读不到时回退 `1.1.1.1`
  - guest 自动配置写入：
    ```sh
    echo "nameserver 10.0.2.3" > /etc/resolv.conf
    ```
    其中 `10.0.2.3` 是 QEMU slirp 的虚拟 DNS，再转发到上面显式传入的手机 DNS
- 保留 v2.61.2 的内置离线 Python 仓库作为断网/受限网络兜底
- versionCode 279 / versionName 2.61.3

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