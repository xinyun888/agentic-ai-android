package com.example.aichat.vm

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import com.example.aichat.linux.LinuxRuntimeManager
import com.example.aichat.linux.PhoneBridgeHttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.TimeUnit

class QemuManager private constructor(private val context: Context, val linux: LinuxRuntimeManager) {

    companion object {
        private const val TAG = "QemuManager"

        @Volatile
        private var instance: QemuManager? = null

        fun get(context: Context, linux: LinuxRuntimeManager): QemuManager =
            instance ?: synchronized(this) {
                instance ?: QemuManager(context.applicationContext, linux).also {
                    instance = it
                    it.cleanupStaleProcesses()
                }
            }
        const val ALPINE_VERSION = "v3.24"
        const val NETBOOT_BASE =
            "https://dl-cdn.alpinelinux.org/alpine/$ALPINE_VERSION/releases/aarch64/netboot"
        const val QEMU_GUEST_PATH = "/usr/bin/qemu-system-aarch64"

        /** guest 启动看门狗：超过该时间还没进 shell 就自动用快速模式重试 */
        private const val BOOT_WATCHDOG_MS = 240_000L
        /** 磁盘启动要走完整 OpenRC（首次还有 ext4 journal 回放），给更宽的预算 */
        private const val BOOT_WATCHDOG_DISK_MS = 900_000L
        /** 发完安装命令后，等 guest 回显 AICHAT_SETUP_BEGIN 的时间 */
        private const val SETUP_RETRY_MS = 60_000L
        /** 安装命令最多重发次数（串口写入/网络偶发失败时自救） */
        private const val MAX_SETUP_ATTEMPTS = 3

        /** ANSI 转义序列：guest 的 busybox ash 在提示符后会发 ESC[6n 查询光标位置，必须剥掉才能识别提示符 */
        private val ANSI_ESCAPE = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
    }

    val vmDir: File = linux.vmDir

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    val isoFile = File(vmDir, "alpine-virt.iso")
    val kernelFile = File(vmDir, "vmlinuz-virt")
    val initrdFile = File(vmDir, "initramfs-virt")
    val diskFile = File(vmDir, "alpine.qcow2")

    fun rootfsInstalled(): Boolean = linux.rootfsInstalled()

    private fun hostAbi(): String? = Build.SUPPORTED_ABIS.firstOrNull {
        it.contains("arm64") || it.contains("x86_64")
    }

    private fun qemuAssetDir(): String =
        if (hostAbi()?.contains("x86_64") == true) "qemu-x86_64" else "qemu"

    fun abiSupported(): Boolean = hostAbi() != null

    fun qemuApkAssetsReady(): Boolean =
        abiSupported() && (context.assets.list(qemuAssetDir())?.count { it.endsWith(".apk") } ?: 0) > 0

    fun netbootAssetsReady(): Boolean {
        if (!abiSupported()) return false
        val names = context.assets.list("vm")?.toSet() ?: emptySet()
        return names.containsAll(setOf("alpine-virt.iso", "vmlinuz-virt", "initramfs-virt"))
    }

    suspend fun installRootfs(onProgress: (String) -> Unit): Result<Unit> =
        linux.installRootfs(onProgress)

    /** 完全离线：从 assets/qemu 复制 .apk 到 /vm/qemu-apks，再用本地 apk 安装 */
    suspend fun installQemuFromAssets(onProgress: (String) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                if (!abiSupported()) {
                    return@withContext Result.failure(IllegalStateException("当前设备/ABI 不支持内置 arm64 VM 运行时"))
                }
                if (qemuInstalled()) {
                    onProgress("QEMU 已安装")
                    return@withContext Result.success(Unit)
                }
                if (!rootfsInstalled()) {
                    return@withContext Result.failure(IllegalStateException("请先安装 Alpine rootfs"))
                }
                linux.requireFreeSpace(400, "离线安装 QEMU")?.let {
                    return@withContext Result.failure(IllegalStateException(it))
                }
                onProgress("检查并修复 rootfs 符号链接 ...")
                linux.fixRootfsSymlinks()
                val assetDir = qemuAssetDir()
                val names = context.assets.list(assetDir)?.filter { it.endsWith(".apk") } ?: emptyList()
                if (names.isEmpty()) {
                    return@withContext Result.failure(IllegalStateException("assets/$assetDir 为空"))
                }
                val targetDir = File(vmDir, "qemu-apks").also { it.mkdirs() }
                targetDir.listFiles()?.forEach { it.delete() }
                onProgress("复制 ${names.size} 个离线包 ...")
                names.forEach { name ->
                    context.assets.open("$assetDir/$name").use { input ->
                        File(targetDir, name).outputStream().use { output -> input.copyTo(output) }
                    }
                }
                onProgress("apk add --no-network（离线安装 QEMU）...")
                val result = linux.exec(
                    "apk add --no-network --allow-untrusted /vm/qemu-apks/*.apk",
                    timeoutSec = 2400
                )
                if (!qemuInstalled()) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "离线安装 QEMU 失败（exit=${result.exitCode}）:\n${result.output.takeLast(4000)}"
                        )
                    )
                }
                if (result.exitCode != 0) {
                    onProgress("安装脚本有警告（exit=${result.exitCode}），但 QEMU 二进制已安装，继续")
                }
                onProgress("修复 rootfs 绝对符号链接 ...")
                linux.fixRootfsSymlinks()
                onProgress("QEMU 离线安装完成")
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** 完全离线：复制直接内核启动所需的 ISO / vmlinuz / initramfs 到 /vm */
    suspend fun installIsoFromAssets(onProgress: (String) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                if (!abiSupported()) {
                    return@withContext Result.failure(IllegalStateException("当前设备/ABI 不支持内置 arm64 VM 运行时"))
                }
                val names = context.assets.list("vm")?.toSet() ?: emptySet()
                val required = setOf("alpine-virt.iso", "vmlinuz-virt", "initramfs-virt")
                if (!names.containsAll(required)) {
                    return@withContext Result.failure(IllegalStateException("缺少 assets/vm 内核/ISO 资源"))
                }
                if (isoFile.exists() && isoFile.length() > 0 &&
                    kernelFile.exists() && kernelFile.length() > 0 &&
                    initrdFile.exists() && initrdFile.length() > 0
                ) {
                    onProgress("内核/ISO 已存在")
                    return@withContext Result.success(Unit)
                }
                linux.requireFreeSpace(300, "释放内核/ISO")?.let {
                    return@withContext Result.failure(IllegalStateException(it))
                }
                onProgress("释放 vmlinuz / initramfs / ISO ...")
                listOf(
                    "vmlinuz-virt" to kernelFile,
                    "initramfs-virt" to initrdFile,
                    "alpine-virt.iso" to isoFile
                ).forEach { (name, target) ->
                    // 先写 .part 再原子改名，避免退出/被杀时留下半截文件却通过 imagesReady
                    val tmp = File(target.parentFile, target.name + ".part")
                    context.assets.open("vm/$name").use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (target.exists()) target.delete()
                    if (!tmp.renameTo(target)) {
                        throw IllegalStateException("无法重命名 ${tmp.name}")
                    }
                    onProgress("  $name -> ${target.length() / 1024 / 1024}MB")
                }
                onProgress("内核/ISO 已就绪")
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun qemuInstalled(): Boolean = File(linux.rootfsDir, "usr/bin/qemu-system-aarch64").exists()

    /** 预装镜像解压后的体积（MB）：gzip 尾部 4 字节是 ISIZE（未压缩大小，LE） */
    private fun preinstallUncompressedMb(): Long = try {
        val total = context.assets.openFd(preinstallAsset).use { it.length }
        if (total < 8) -1L else context.assets.open(preinstallAsset).use { input ->
            var remain = total - 4
            while (remain > 0) {
                val skipped = input.skip(remain)
                if (skipped <= 0) break
                remain -= skipped
            }
            val b = ByteArray(4)
            var read = 0
            while (read < 4) {
                val n = input.read(b, read, 4 - read)
                if (n < 0) break
                read += n
            }
            if (read < 4) -1L else {
                val isize = (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
                    ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
                isize / 1024 / 1024
            }
        }
    } catch (e: Exception) {
        -1L
    }

    fun freeSpaceMb(): Long = linux.freeSpaceMb()

    /** 清理可再生的大文件（半成品镜像、离线包副本、PRoot 残留），返回释放字节数 */
    fun cleanupTemp(): Long = linux.cleanupTemp()

    /** 内置的"预装系统"磁盘镜像（node/pnpm/git/DeepSeek Harness 已装好） */
    private val preinstallAsset = "dsh/preinstall-disk.qcow2.bin"
    private val preinstallMarker = File(vmDir, ".preinstalled")

    fun preinstallImageReady(): Boolean =
        try { context.assets.open(preinstallAsset).use { true } } catch (_: Exception) { false }

    fun preinstalledDiskLive(): Boolean = preinstallMarker.exists() && diskReady()

    fun imagesReady(): Boolean =
        (isoFile.exists() && isoFile.length() > 10_000_000 || preinstallImageReady()) &&
        kernelFile.exists() && kernelFile.length() > 1_000_000 &&
        initrdFile.exists() && initrdFile.length() > 1_000_000

    fun diskReady(): Boolean = diskFile.exists() && diskFile.length() > 0

    fun statusText(): String = buildString {
        appendLine("rootfs: " + if (rootfsInstalled()) "\u2705 已安装" else "\u274C 未安装")
        appendLine("QEMU:   " + if (qemuInstalled()) "\u2705 已安装" else "\u274C 未安装")
        appendLine("内核:   " + if (kernelFile.exists() && initrdFile.exists()) "\u2705 已释放" else "\u274C 未释放")
        appendLine("ISO:    " + if (isoFile.exists() && isoFile.length() > 0) "\u2705 ${isoFile.length() / 1024 / 1024}MB" else "\u274C 未释放")
        appendLine("磁盘:   " + if (diskReady()) "\u2705 ${diskFile.name} (${diskFile.length() / 1024 / 1024}MB)" else "\u274C 未创建")
        appendLine("预装包: " + if (preinstallImageReady()) "\u2705 内置 node/pnpm/git/DSH" else "\u274C 缺失")
        val assetDir = qemuAssetDir()
        val qemuApkCount = context.assets.list(assetDir)?.count { it.endsWith(".apk") } ?: 0
        appendLine("离线 QEMU 包: " + if (qemuApkAssetsReady()) "\u2705 $qemuApkCount 个 ($assetDir)" else "\u274C 缺失")
        appendLine("ISO 包: " + if (netbootAssetsReady()) "\u2705 已内置" else "\u274C 缺失")
        appendLine("架构: " + if (abiSupported()) "\u2705 ${hostAbi()}" else "\u274C 当前仅支持 arm64 / x86_64 VM 运行时")
        appendLine("启动模式: " + if (bootFromDisk) "\u2705 磁盘启动" else "\u2705 Live ISO")
        appendLine("快速模式: " + if (fastBoot && !bootFromDisk) "\u2705 init=/bin/sh（推荐）" else "\u274C 完整 OpenRC")
        appendLine("手机桥: " + if (PhoneBridgeHttpServer.isRunning) "\u2705 guest -> 10.0.2.2:${PhoneBridgeHttpServer.PORT}" else "\u274C 未启动")
        linux.compatibilityWarning()?.let { appendLine(it) }
        val freeMb = linux.freeSpaceMb()
        appendLine("存储:   " + if (freeMb < 0) "\u2753 取不到" else if (freeMb < 500) "\u26A0\uFE0F 仅剩 ${freeMb}MB（偏低，可能装不下镜像）" else "\u2705 可用 ${freeMb}MB")
        appendLine("VM 目录: ${vmDir.absolutePath}")
    }

    suspend fun installQemuOnline(onProgress: (String) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            linux.requireFreeSpace(600, "在线安装 QEMU")?.let {
                return@withContext Result.failure(IllegalStateException(it))
            }
            if (qemuInstalled()) {
                onProgress("QEMU 已安装")
                return@withContext Result.success(Unit)
            }
            if (!rootfsInstalled()) {
                return@withContext Result.failure(IllegalStateException("请先安装 Alpine rootfs"))
            }
            onProgress("检查并修复 rootfs 符号链接 ...")
            linux.fixRootfsSymlinks()
            onProgress("apk update ...")
            val update = linux.exec("apk update", timeoutSec = 600)
            if (update.exitCode != 0) {
                return@withContext Result.failure(
                    IllegalStateException("apk update 失败:\n${update.output.takeLast(2000)}")
                )
            }
            onProgress("apk add qemu-system-aarch64 qemu-img ...（可能需要几分钟）")
            val install = linux.exec(
                "apk add qemu-system-aarch64 qemu-img ca-certificates",
                timeoutSec = 1800
            )
            if (install.exitCode != 0) {
                return@withContext Result.failure(
                    IllegalStateException("安装 QEMU 失败:\n${install.output.takeLast(3000)}")
                )
            }
            onProgress("修复 rootfs 绝对符号链接 ...")
            linux.fixRootfsSymlinks()
            onProgress("QEMU 安装完成")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadIso(onProgress: (String) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-virt-3.24.2-aarch64.iso"
            downloadOne(url, isoFile, onProgress)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 展开内置预装系统镜像到 /vm/alpine.qcow2（已含 node/npm/pnpm/git/bash + DeepSeek Harness），
     * 首次 30-60 秒，之后直接磁盘启动、无需任何安装。
     */
    suspend fun installPreinstalledDisk(onProgress: (String) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                if (preinstalledDiskLive()) {
                    setDiskBootEnabled(true)
                    onProgress("预装系统已就绪")
                    return@withContext Result.success(Unit)
                }
                // 展开需要"解压后体积 + 余量"的空间，先算准再检查（gzip 尾部带 ISIZE）
                val needMb = preinstallUncompressedMb().let { if (it > 0) it + 300 else 2000 }
                linux.requireFreeSpace(needMb, "展开内置预装系统")?.let {
                    return@withContext Result.failure(IllegalStateException(it))
                }
                val tmp = File(vmDir, "alpine.qcow2.part")
                tmp.parentFile?.mkdirs()
                if (tmp.exists()) tmp.delete()
                onProgress("展开内置预装系统（约 ${needMb}MB 空间，首次 1-2 分钟）...")
                // 资源是 gzip 过的未压缩 qcow2：这里解压一次，之后 guest 读写就是普通镜像
                // （压缩 qcow2 在 PRoot 下逐簇解压会非常慢）
                java.util.zip.GZIPInputStream(context.assets.open(preinstallAsset), 1 shl 20).use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out, 1 shl 20) }
                }
                if (diskFile.exists()) diskFile.delete()
                if (!tmp.renameTo(diskFile)) {
                    return@withContext Result.failure(IllegalStateException("无法重命名 " + tmp.name))
                }
                preinstallMarker.writeText("1")
                setDiskBootEnabled(true)
                onProgress("预装系统展开完成（node / pnpm / git / DeepSeek Harness 已内置）")
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun createDisk(sizeGb: Int = 8, onProgress: (String) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                if (preinstallImageReady() && !preinstalledDiskLive()) {
                    return@withContext installPreinstalledDisk(onProgress)
                }
                if (!rootfsInstalled() || !qemuInstalled()) {
                    return@withContext Result.failure(IllegalStateException("请先安装 rootfs 和 QEMU"))
                }
                if (diskReady()) {
                    onProgress("磁盘已存在")
                    return@withContext Result.success(Unit)
                }
                onProgress("创建 qcow2 磁盘（${sizeGb}G）...")
                val tmp = File(vmDir, "alpine.qcow2.part")
                if (tmp.exists()) tmp.delete()
                val r = linux.exec(
                    "qemu-img create -f qcow2 /vm/alpine.qcow2.part ${sizeGb}G",
                    timeoutSec = 300
                )
                if (r.exitCode != 0) {
                    return@withContext Result.failure(
                        IllegalStateException("qemu-img 失败:\n${r.output.takeLast(2000)}")
                    )
                }
                if (diskFile.exists()) diskFile.delete()
                if (!tmp.renameTo(diskFile)) {
                    return@withContext Result.failure(IllegalStateException("无法重命名 ${tmp.name}"))
                }
                onProgress("磁盘创建完成")
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun buildQemuArgs(
        memoryMb: Int = 1024,
        smp: Int = 1,
        safeMode: Boolean = false,
        fastBootOverride: Boolean? = null
    ): List<String> {
        // Android + PRoot 下 TCG 多线程 + OpenRC 并行服务会卡死；统一用单线程 TCG 和 cortex-a53
        val accel = "tcg,thread=single"
        val cpu = "cortex-a53"
        val mem = if (safeMode) minOf(memoryMb, 512) else memoryMb
        val cpus = if (safeMode) 1 else smp
        val uploadedKernel = File(vmDir, "upload/vmlinuz-virt")
        val uploadedInitrd = File(vmDir, "upload/initramfs-virt")
        val useUploaded = bootFromDisk && uploadedKernel.exists() && uploadedKernel.length() > 0 &&
            uploadedInitrd.exists() && uploadedInitrd.length() > 0
        val kernelPath = if (useUploaded) "/vm/upload/vmlinuz-virt" else "/vm/vmlinuz-virt"
        val initrdPath = if (useUploaded) "/vm/upload/initramfs-virt" else "/vm/initramfs-virt"
        val upstreamDns = resolveUpstreamDns()
        val args = mutableListOf(
            QEMU_GUEST_PATH,
            "-accel", accel,
            "-M", "virt",
            "-cpu", cpu,
            "-smp", cpus.toString(),
            "-m", mem.toString(),
            "-kernel", kernelPath,
            "-initrd", initrdPath,
            "-append", if (bootFromDisk) {
                // VM 实测：安装到磁盘后 root 在 /dev/vda3；
                // 主机内核的 initramfs 需要显式 rootfstype + ext4 模块才能挂载磁盘根分区
                "root=/dev/vda3 rw rootfstype=ext4 modules=virtio_blk,virtio_pci,ext4 rootwait console=ttyAMA0 nowatchdog"
            } else {
                "console=ttyAMA0 ip=dhcp nowatchdog" +
                    if (fastBootOverride ?: fastBoot) " init=/bin/sh" else ""
            },
            "-drive", "file=/vm/alpine.qcow2,if=virtio,format=qcow2",
            "-pidfile", "/vm/qemu.pid"
        )
        if (!bootFromDisk) {
            args.addAll(listOf("-cdrom", "/vm/alpine-virt.iso"))
        }
        args.addAll(listOf(
            "-netdev", "user,id=n0,hostfwd=tcp:127.0.0.1:18000-:8000,dns=" + upstreamDns,
            "-device", "virtio-net-pci,netdev=n0",
            "-display", "none",
            "-serial", "stdio",
            "-monitor", "none",
            "-no-reboot"
        ))
        return args
    }

    fun isDiskBootEnabled(): Boolean = bootFromDisk

    fun setDiskBootEnabled(enabled: Boolean) {
        bootFromDisk = enabled
        vmPrefs.edit().putBoolean("boot_from_disk", enabled).apply()
    }

    fun isFastBoot(): Boolean = fastBoot

    fun setFastBoot(enabled: Boolean) {
        fastBoot = enabled
        vmPrefs.edit().putBoolean("fast_boot", enabled).apply()
    }

    /** 在 guest 里执行 setup-disk 安装到 /dev/vda，之后可切到磁盘启动。 */
    suspend fun installToDisk(
        session: QemuSession,
        onProgress: (String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!session.running.value) {
                return@withContext Result.failure(IllegalStateException("QEMU 没有运行"))
            }
            val token = PhoneBridgeHttpServer.token
            if (token.isBlank()) {
                return@withContext Result.failure(IllegalStateException("手机桥未启动"))
            }
            onProgress("等待 guest 进入 shell ...")
            val ready = withTimeoutOrNull(180_000) {
                session.output.first { out ->
                    out.contains("login:") || out.contains("~ #") || out.contains("localhost:~#")
                }
            }
            if (ready == null) {
                return@withContext Result.failure(IllegalStateException("guest 未在 180 秒内进入 shell"))
            }
            if (ready.contains("login:")) {
                session.write("root")
                withTimeoutOrNull(60_000) {
                    session.output.first { it.contains("~ #") || it.contains("localhost:~#") }
                }
            }
            onProgress("开始安装 Alpine 到 /dev/vda（后台，可能需要几分钟）...")
            // 拆成两条短命令：串口上长行容易被丢字符写坏
            session.write("wget -qO /tmp/aichat-disk.sh 'http://10.0.2.2:${PhoneBridgeHttpServer.PORT}/disk-install.sh?token=$token'")
            session.write("sh /tmp/aichat-disk.sh")
            val done = withTimeoutOrNull(1_200_000) {
                session.output.first { it.contains("AICHAT_DISK_DONE") }
            }
            if (done == null) {
                return@withContext Result.failure(IllegalStateException("setup-disk 超时，请查看 VM 串口日志"))
            }
            if (done.contains("AICHAT_DISK_EXIT_0")) {
                setDiskBootEnabled(true)
                onProgress("安装完成，已切换为磁盘启动。停止 VM 后重新启动即可生效。")
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("setup-disk 返回非 0，请查看串口日志"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private var activeSession: QemuSession? = null
    private val setupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var setupJob: Job? = null
    private var bootWatchdogJob: Job? = null
    @Volatile private var safeModeAttempted = false
    @Volatile private var fastFallbackAttempted = false
    /** 首次运行自动"安装到磁盘"只做一次（成功后 bootFromDisk 会持久化为 true） */
    @Volatile private var diskAutoInstallTriggered = false
    private val vmPrefs = context.getSharedPreferences("qemu_vm", Context.MODE_PRIVATE)
    @Volatile private var bootFromDisk = vmPrefs.getBoolean("boot_from_disk", false)

    // 默认快速模式（init=/bin/sh）：跳过 OpenRC/getty/login，慢设备上 harness 才能稳定装起来。
    // 完整 OpenRC 模式仍可在 VM 页面手动切换。
    @Volatile private var fastBoot = vmPrefs.getBoolean("fast_boot", true)
    @Volatile private var guestSetupDone = false

    /** 返回当前会话，页面重新进入时直接复用，不再重启 QEMU。 */
    private fun cleanupStaleProcesses() {
        setupScope.launch {
            try {
                // 上一进程如果被杀，PRoot 子进程可能残留；残留 QEMU 会锁住 qcow2 和 hostfwd 端口
                linux.exec("pkill -9 -f qemu-system-aarch64 2>/dev/null || true", timeoutSec = 15)
                linux.exec("pkill -9 -f qemu-system-x86_64 2>/dev/null || true", timeoutSec = 15)
            } catch (_: Exception) {
            }
        }
    }

    private fun killPidFileQemu() {
        try {
            val f = File(vmDir, "qemu.pid")
            if (f.exists()) {
                val pid = f.readText().trim().toIntOrNull()
                if (pid != null && pid > 0) {
                    try {
                        android.os.Process.sendSignal(pid, 9)
                    } catch (_: Exception) {
                    }
                }
                f.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun killStaleQemu() {
        try {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(15_000) {
                    linux.exec("pkill -9 -f qemu-system-aarch64 2>/dev/null || true", timeoutSec = 15)
                }
            }
        } catch (_: Exception) {
        }
        try { Thread.sleep(400) } catch (_: InterruptedException) {}
    }

    /** 把手机当前 WiFi/数据的 DNS 显式喂给 QEMU slirp，guest 才能真正继承手机网络。 */
    private fun resolveUpstreamDns(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val active = cm?.activeNetwork
            val candidates = cm?.allNetworks.orEmpty().sortedWith(
                compareBy(
                    { if (it == active) 0 else 1 },
                    { network ->
                        val caps = cm?.getNetworkCapabilities(network)
                        if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true) 0 else 1
                    }
                )
            )
            for (network in candidates) {
                val dns = cm?.getLinkProperties(network)?.dnsServers
                    ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                    ?.hostAddress
                if (!dns.isNullOrBlank() && dns != "0.0.0.0") return dns
            }
            "223.5.5.5"
        } catch (_: Exception) {
            "223.5.5.5"
        }
    }

    fun isRunning(): Boolean = activeSession?.running?.value == true

    fun currentSession(): QemuSession? = activeSession

    /** 用户改了 API key/模型后调用：重跑 guest setup，重新写 DSH 模型配置并重启 dsh web。 */
    @Synchronized
    fun restartGuestHarness() {
        val session = activeSession ?: return
        val ignoreBefore = session.output.value.length
        DshState.reset()
        setupScope.launch {
            try {
                session.appendSynthetic("[App] 正在重配 DSH（使用当前 API 配置，重新加载插件可能较慢）...")
                session.write("pkill -f 'dsh --profile web' 2>/dev/null")
                delay(1500)
            } catch (_: Exception) {}
            // 只看重配后的新串口输出，避免旧 AICHAT_SETUP_DONE 让新 setup 循环提前结束
            startGuestSetup(session, ignoreBefore)
        }
    }

    fun phoneBridgeSetupCommand(): String = PhoneBridgeHttpServer.guestSetupCommand()

    fun startSession(memoryMb: Int = 2048, smp: Int = 1): QemuSession? =
        startSessionInternal(memoryMb, smp, safeMode = false)

    /** 安全模式：小内存 + 快速启动（init=/bin/sh），用于 guest 卡在 OpenRC/登录前时自救。 */
    fun startSafeSession(): QemuSession? =
        startSessionInternal(512, 1, safeMode = true, forceFastBoot = true)

    @Synchronized
    private fun startSessionInternal(
        memoryMb: Int,
        smp: Int,
        safeMode: Boolean,
        forceFastBoot: Boolean = false
    ): QemuSession? {
        if (!abiSupported()) return null
        if (!qemuInstalled() || !imagesReady() || !diskReady()) return null
        if (!safeMode) {
            safeModeAttempted = false
            fastFallbackAttempted = false
        }
        DshState.reset()
        // 救援重启时把快速模式写回偏好，保证 VM 页面开关和真实启动参数一致
        if (forceFastBoot && !bootFromDisk && !fastBoot) setFastBoot(true)
        val fastOverride = if (forceFastBoot && !bootFromDisk) true else null
        stopSession()
        killPidFileQemu()
        killStaleQemu()
        return try {
            QemuSession(linux, buildQemuArgs(memoryMb, smp, safeMode, fastOverride)).also {
                activeSession = it
                it.start()
                // 给 guest 打开设备能力 HTTP 桥
                PhoneBridgeHttpServer.start(context)
                // 自动登录 guest 并安装 phone 桥 / 内置 harness
                startGuestSetup(it)
                // 首次启动失败时自动降级到安全模式
                startBootWatchdog(it, safeMode)
                // 20 秒无任何串口输出时，在终端里显示诊断，便于真机定位
                setupScope.launch {
                    delay(20_000)
                    if (activeSession === it && it.output.value.isBlank()) {
                        it.appendSynthetic(
                            "[App] QEMU 启动 20 秒没有任何串口输出。" +
                                "请检查：资源是否完整、18000 端口是否被占用、QEMU 是否需要安全模式。"
                        )
                    }
                }
                // 保持进程不被系统回收，退出页面/退到后台 VM 继续跑
                QemuKeepAliveService.start(context)
            }
        } catch (_: Exception) {
            stopSession()
            null
        }
    }

    private fun startBootWatchdog(session: QemuSession, safeMode: Boolean) {
        bootWatchdogJob?.cancel()
        val budgetMs = if (bootFromDisk) BOOT_WATCHDOG_DISK_MS else BOOT_WATCHDOG_MS
        bootWatchdogJob = setupScope.launch {
            delay(budgetMs)
            if (activeSession !== session) return@launch
            val out = session.output.value
            val booted = out.contains("login:") || out.contains("~ #") || out.contains("localhost:~#")
            if (booted) return@launch
            val seconds = budgetMs / 1000
            if (session.running.value) {
                if (bootFromDisk) {
                    // 磁盘启动在少数环境（PRoot 下磁盘 I/O 极慢）会卡在 "Mounting root"：
                    // 自动切回已验证的 Live 模式重试（预装镜像仍留在 /vm，不影响下次）
                    setDiskBootEnabled(false)
                    session.appendSynthetic(
                        "[App] 磁盘启动 $seconds 秒仍未进 shell（PRoot 下磁盘 I/O 慢时可能发生）；" +
                            "自动切回 Live 模式重试，稍后可用当前:磁盘切回。"
                    )
                    Log.w(TAG, "磁盘启动 ${seconds}s 未进 shell，回退 Live 模式")
                    setupScope.launch { startSessionInternal(2048, 1, safeMode = false) }
                    return@launch
                }
                // 进程还活着说明不是 QEMU 崩了，而是 guest 卡在 OpenRC/挂载阶段（部分机型会卡死在
                // firstboot 之前）。此时不能直接再起第二个 QEMU 抢 qcow2 锁，必须先把旧的停掉。
                if (!safeMode && !fastBoot && !fastFallbackAttempted) {
                    fastFallbackAttempted = true
                    session.appendSynthetic(
                        "[App] QEMU 已运行 $seconds 秒仍未进入 shell，判定 guest 卡在 OpenRC；" +
                            "自动改用快速模式（init=/bin/sh）重启，绕过 OpenRC 与 login。"
                    )
                    Log.w(TAG, "guest ${seconds}s 未进入 shell，自动切换快速模式重启")
                    setupScope.launch { startSessionInternal(1024, 1, safeMode = false, forceFastBoot = true) }
                } else {
                    session.appendSynthetic(
                        "[App] QEMU 已运行 $seconds 秒仍未检测到 shell；可点停止，再点安全模式" +
                            "（安全模式会用 init=/bin/sh 快速启动）。"
                    )
                }
            } else if (!safeMode && !safeModeAttempted) {
                safeModeAttempted = true
                Log.w(TAG, "guest ${seconds}s 未进入 shell 且进程已退出，尝试安全模式重启")
                bootWatchdogJob = null
                setupScope.launch { startSessionInternal(512, 1, safeMode = true, forceFastBoot = true) }
            }
        }
    }

    /** 已经完成 token->cookie 交换的 URL（一次性 token，绝不重复交换） */
    private var dshUrlExchangedFor: String? = null

    private fun startGuestSetup(session: QemuSession, ignoreBefore: Int = 0) {
        setupJob?.cancel()
        setupJob = setupScope.launch {
            val commands = PhoneBridgeHttpServer.guestSetupCommands()
            var rootAttempts = 0
            var sawLogin = false
            var setupSent = false
            var setupBegun = false
            var attempts = 0
            var sentAt = 0L
            var rootfsReadyAt = 0L
            val fast = fastBoot && !bootFromDisk
            while (isActive && activeSession === session) {
                // busybox ash 的提示符后面会跟 ESC[6n（光标位置查询），不剥掉 ANSI 就永远匹配不到提示符。
                // ignoreBefore 用于重配 DSH：只看本次重配之后的新输出，避免旧 AICHAT_SETUP_DONE 让新循环秒退。
                val fullOutput = session.output.value
                val from = ignoreBefore.coerceIn(0, fullOutput.length)
                val tail = stripAnsi(fullOutput.substring(from).takeLast(20000))
                if (tail.contains("AICHAT_SETUP_BEGIN")) setupBegun = true
                if (tail.contains("login:", ignoreCase = true)) sawLogin = true
                // 捕获 Guest 里 dsh web 的启动 URL / 就绪状态，供 DS Harness 页直接内嵌
                if (tail.contains("AICHAT_DSH_OK")) {
                    DshState.ready = true
                    // 首次运行（Live ISO）：自动把系统装到磁盘并切到磁盘启动，让 node/DSH/会话持久化
                    if (!bootFromDisk && !diskAutoInstallTriggered) {
                        diskAutoInstallTriggered = true
                        session.appendSynthetic(
                            "[App] 首次运行：正在把 Alpine 安装到磁盘以便持久化（约 2-5 分钟，请勿关闭）..."
                        )
                        setupScope.launch {
                            if (activeSession !== session) return@launch
                            val r = installToDisk(session) { msg ->
                                session.appendSynthetic("[App] $msg")
                            }
                            if (r.isSuccess) {
                                session.appendSynthetic("[App] 磁盘安装完成，正在切换到磁盘启动（DSH 与配置将持久保留）...")
                                delay(2000)
                                if (activeSession === session) {
                                    startSessionInternal(2048, 1, safeMode = false)
                                }
                            } else {
                                session.appendSynthetic(
                                    "[App] 磁盘安装失败：" + (r.exceptionOrNull()?.message ?: "未知错误") +
                                        "；继续使用内存模式（重启 VM 会重新解压 DSH）"
                                )
                            }
                        }
                    }
                }
                if (tail.contains("AICHAT_DSH_FAIL")) { DshState.ready = false; DshState.webUrl = null }
                // 取最后一条 AICHAT_DSH_URL=（脚本会重复打印，最后一条最完整）；
                // 并且必须校验 token 长度：管道被截断时会出现 token 为空的 URL，
                // 用它去加载 DSH 只会得到 "dsh web authentication required"。
                val dshIdx = tail.lastIndexOf("AICHAT_DSH_URL=")
                if (dshIdx >= 0) {
                    val line = tail.substring(dshIdx).lineSequence().firstOrNull().orEmpty()
                    val url = line.removePrefix("AICHAT_DSH_URL=").trim()
                    val tokenOk = Regex("token=[A-Za-z0-9_-]{20,}").containsMatchIn(url)
                    if (url.startsWith("http") && tokenOk && dshUrlExchangedFor != url) {
                        dshUrlExchangedFor = url
                        val clean = exchangeDshToken(url)
                        // 成功：把"干净地址"交给 WebView（靠 cookie 会话，可安全重载）
                        // 失败：退回带 token 的地址（WebView 可能仍能自己换成功）
                        DshState.webUrl = clean ?: url
                        DshState.ready = true
                    }
                }
                // 个别机型串口会把 /bin/sh 的提示符切成半行（只能看到 "/"），提示符识别会失效。
                // 快速模式的 root shell 是 init 自己起的，装完包就一定有，所以到点直接盲发命令。
                if (rootfsReadyAt == 0L && tail.contains("Installing packages to root filesystem")) {
                    rootfsReadyAt = System.currentTimeMillis()
                }

                // 只认真正的 shell 提示符，不能把 apk 进度条末尾的 # 误判成提示符
                val lastLine = tail.lines().lastOrNull { it.isNotBlank() }?.trimEnd().orEmpty()
                val hasPrompt = lastLine.endsWith(":~#") ||
                    lastLine.endsWith("~ #") ||
                    lastLine.endsWith(":/#") ||
                    lastLine.endsWith(": #") ||
                    lastLine == "#"
                val fastPrompt = lastLine.endsWith("~ #") ||
                    lastLine.endsWith("/ #") ||
                    lastLine == "#"
                val shellReady = if (fast) fastPrompt else (sawLogin && hasPrompt)
                val blindSend = fast && rootfsReadyAt > 0L &&
                    System.currentTimeMillis() - rootfsReadyAt > 20_000L

                val needFirstSend = (shellReady || blindSend) && !setupSent
                val needRetry = setupSent && !setupBegun && attempts < MAX_SETUP_ATTEMPTS &&
                    System.currentTimeMillis() - sentAt > SETUP_RETRY_MS
                if (needFirstSend || needRetry) {
                    if (commands.isEmpty()) {
                        setupSent = true
                        session.appendSynthetic("[App] 手机桥未启动，无法自动安装 Guest Harness；请先启动 VM。")
                    } else {
                        attempts++
                        setupSent = true
                        session.appendSynthetic(
                            if (attempts == 1) {
                                if (shellReady) {
                                    if (fast) {
                                        "[App] 快速模式 root shell 已就绪，开始安装 phone bridge + Guest Harness"
                                    } else {
                                        "[App] guest shell 已就绪，开始安装 phone bridge + Guest Harness"
                                    }
                                } else {
                                    "[App] 未识别到 shell 提示符，快速模式下直接发送安装命令（兜底）"
                                }
                            } else {
                                "[App] 未收到 AICHAT_SETUP_BEGIN，重发安装命令（第 " + attempts + " 次）"
                            }
                        )
                        delay(200)
                        for (line in commands) {
                            if (!isActive || activeSession !== session) break
                            session.write(line)
                        }
                        // 发送本身可能耗时（分块 + 等回显），重试计时从发完开始算
                        sentAt = System.currentTimeMillis()
                    }
                } else if (!fast && sawLogin && !hasPrompt && rootAttempts < 6) {
                    // login: 出现后 getty 可能还没完全就绪，稍等并重试
                    rootAttempts++
                    session.appendSynthetic("[App] 检测到 login，发送 root（第 " + rootAttempts + " 次）")
                    delay(600)
                    session.write("root")
                }

                // 注意：不能一看到 AICHAT_SETUP_BEGIN 就退出循环AICHAT_DSH_URL / AICHAT_DSH_OK
                // 是脚本末尾才打印的，提前退出会导致抓不到 URL（DS Harness 页就一直显示旧界面）。
                if (setupBegun && (tail.contains("AICHAT_SETUP_DONE") || tail.contains("AICHAT_DSH_FAIL"))) break
                delay(1200)
            }
        }
    }

    /**
     * DSH 的 `?token=` 是【进程一次性启动令牌】：用它请求一次会得到 303 + Set-Cookie（dsh-auth-* 会话 cookie）。
     * 把这个交换交给 WebView 做有两个坑：
     *   1) 一次性 token 若被加载第二次（重组/重进页面） 401，页面显示 "authentication required"；
     *   2) 主框架 401 不会触发 onReceivedHttpError，界面上看不出原因。
     * 所以这里由 App 自己用 HttpURLConnection 完成交换，把 cookie 注入 WebView 的 CookieManager，
     * 之后 WebView 只加载【不带 token 的干净地址】确定性的做法，且可重载、可重进。
     * 前提：guest 的 dsh-forward.js 必须是透明 TCP 转发、Host 保持 127.0.0.1:18000；
     * 一旦转发器改写 Host，这里换到的 cookie authority 会和后续 /api 请求对不上（HTTP 401）。
     * @return 干净地址（交换成功）或 null（失败，调用方自行决定是否回退）
     */
    private suspend fun exchangeDshToken(tokenUrl: String): String? = withContext(Dispatchers.IO) {
        val clean = tokenUrl.substringBefore("?token=")
        try {
            val conn = (java.net.URL(tokenUrl).openConnection() as java.net.HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
                setRequestProperty("Accept", "text/html")
            }
            val code = conn.responseCode
            // 303 响应可能不止一个 Set-Cookie；必须精确挑 dsh-auth-*，
            // 否则误选其它 cookie 会让 WebView 仍然 401。
            val setCookies = conn.headerFields.entries
                .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                .flatMap { it.value.orEmpty() }
            val setCookie = setCookies.firstOrNull { it.trimStart().startsWith("dsh-auth-") }
                ?: setCookies.firstOrNull().orEmpty()
            runCatching { conn.inputStream?.close(); conn.errorStream?.close() }
            conn.disconnect()
            if (setCookie.isBlank()) {
                Log.w(TAG, "DSH token 交换未拿到 Set-Cookie (HTTP $code) url=$clean")
                return@withContext null
            }
            val pair = setCookie.substringBefore(";").trim()
            if (pair.isBlank() || !pair.contains("=") || !pair.startsWith("dsh-auth-")) {
                Log.w(TAG, "DSH token 交换拿到的不是 dsh-auth-* cookie (HTTP $code): ${setCookie.take(120)}")
                return@withContext null
            }
            val cm = android.webkit.CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setCookie(clean, pair)
            cm.flush()
            Log.i(TAG, "DSH token 交换成功：HTTP $code，已注入 cookie ${pair.substringBefore("=")}")
            clean
        } catch (e: Exception) {
            Log.w(TAG, "DSH token 交换失败: ${e.message}")
            null
        }
    }

    /** 去掉 ANSI 转义序列，便于识别 shell 提示符 / login 提示。 */
    private fun stripAnsi(text: String): String =
        if (text.indexOf('\u001B') < 0) text else text.replace(ANSI_ESCAPE, "")

    @Synchronized
    fun stopSession() {
        setupJob?.cancel()
        setupJob = null
        bootWatchdogJob?.cancel()
        bootWatchdogJob = null
        guestSetupDone = false
        activeSession?.shutdown()
        activeSession = null
        killPidFileQemu()
        killStaleQemu()
        QemuKeepAliveService.stop(context)
        PhoneBridgeHttpServer.stop()
    }

    fun shutdown() {
        stopSession()
        PhoneBridgeHttpServer.stop()
        linux.shutdown()
    }

    private fun downloadOne(
        url: String,
        target: File,
        onProgress: (String) -> Unit
    ) {
        if (target.exists() && target.length() > 0) {
            onProgress("已存在 ${target.name}")
            return
        }
        onProgress("下载 ${target.name} ...")
        val tmp = File(target.parentFile, target.name + ".part")
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: $url")
            }
            val body = response.body ?: throw IllegalStateException("空响应: $url")
            body.byteStream().use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
        }
        if (!tmp.renameTo(target)) {
            throw IllegalStateException("无法重命名 ${tmp.name}")
        }
        onProgress("下载完成 ${target.name} (${target.length() / 1024 / 1024}MB)")
    }
}