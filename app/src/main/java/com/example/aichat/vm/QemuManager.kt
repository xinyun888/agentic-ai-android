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

    fun imagesReady(): Boolean =
        isoFile.exists() && isoFile.length() > 10_000_000 &&
        kernelFile.exists() && kernelFile.length() > 1_000_000 &&
        initrdFile.exists() && initrdFile.length() > 1_000_000

    fun diskReady(): Boolean = diskFile.exists() && diskFile.length() > 0

    fun statusText(): String = buildString {
        appendLine("rootfs: " + if (rootfsInstalled()) "\u2705 已安装" else "\u274C 未安装")
        appendLine("QEMU:   " + if (qemuInstalled()) "\u2705 已安装" else "\u274C 未安装")
        appendLine("内核:   " + if (kernelFile.exists() && initrdFile.exists()) "\u2705 已释放" else "\u274C 未释放")
        appendLine("ISO:    " + if (isoFile.exists() && isoFile.length() > 0) "\u2705 ${isoFile.length() / 1024 / 1024}MB" else "\u274C 未释放")
        appendLine("磁盘:   " + if (diskReady()) "\u2705 ${diskFile.name} (${diskFile.length() / 1024 / 1024}MB)" else "\u274C 未创建")
        val assetDir = qemuAssetDir()
        val qemuApkCount = context.assets.list(assetDir)?.count { it.endsWith(".apk") } ?: 0
        appendLine("离线 QEMU 包: " + if (qemuApkAssetsReady()) "\u2705 $qemuApkCount 个 ($assetDir)" else "\u274C 缺失")
        appendLine("ISO 包: " + if (netbootAssetsReady()) "\u2705 已内置" else "\u274C 缺失")
        appendLine("架构: " + if (abiSupported()) "\u2705 ${hostAbi()}" else "\u274C 当前仅支持 arm64 / x86_64 VM 运行时")
        appendLine("启动模式: " + if (bootFromDisk) "\u2705 磁盘启动" else "\u2705 Live ISO")
        appendLine("手机桥: " + if (PhoneBridgeHttpServer.isRunning) "\u2705 guest -> 10.0.2.2:${PhoneBridgeHttpServer.PORT}" else "\u274C 未启动")
        appendLine("VM 目录: ${vmDir.absolutePath}")
    }

    suspend fun installQemuOnline(onProgress: (String) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        try {
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

    suspend fun createDisk(sizeGb: Int = 8, onProgress: (String) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
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

    fun buildQemuArgs(memoryMb: Int = 1024, smp: Int = 1, safeMode: Boolean = false): List<String> {
        val accel = if (safeMode) "tcg,thread=single" else "tcg"
        val cpu = if (safeMode) "cortex-a53" else "cortex-a57"
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
                "console=ttyAMA0 ip=dhcp nowatchdog"
            },
            "-drive", "file=/vm/alpine.qcow2,if=virtio,format=qcow2"
        )
        if (!bootFromDisk) {
            args.addAll(listOf("-cdrom", "/vm/alpine-virt.iso"))
        }
        args.addAll(listOf(
            "-netdev", "user,id=n0,hostfwd=tcp:127.0.0.1:18000-:8000,dns=" + upstreamDns,
            "-device", "virtio-net-pci,netdev=n0",
            "-nographic",
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
            val cmd = "wget -qO /tmp/aichat-disk.sh 'http://10.0.2.2:${PhoneBridgeHttpServer.PORT}/disk-install.sh?token=$token'; sh /tmp/aichat-disk.sh"
            session.write(cmd)
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
    private val vmPrefs = context.getSharedPreferences("qemu_vm", Context.MODE_PRIVATE)
    @Volatile private var bootFromDisk = vmPrefs.getBoolean("boot_from_disk", false)
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
            val network = cm?.activeNetwork
            val servers = cm?.getLinkProperties(network)?.dnsServers
            servers?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
                ?: "1.1.1.1"
        } catch (_: Exception) {
            "1.1.1.1"
        }
    }

    fun isRunning(): Boolean = activeSession?.running?.value == true

    fun currentSession(): QemuSession? = activeSession

    fun phoneBridgeSetupCommand(): String = PhoneBridgeHttpServer.guestSetupCommand()

    fun startSession(memoryMb: Int = 1024, smp: Int = 1): QemuSession? =
        startSessionInternal(memoryMb, smp, safeMode = false)

    @Synchronized
    private fun startSessionInternal(memoryMb: Int, smp: Int, safeMode: Boolean): QemuSession? {
        if (!abiSupported()) return null
        if (!qemuInstalled() || !imagesReady() || !diskReady()) return null
        if (!safeMode) safeModeAttempted = false
        stopSession()
        killStaleQemu()
        return try {
            QemuSession(linux, buildQemuArgs(memoryMb, smp, safeMode)).also {
                activeSession = it
                it.start()
                // 给 guest 打开设备能力 HTTP 桥
                PhoneBridgeHttpServer.start(context)
                // 自动登录 guest 并安装 phone 桥 / 内置 harness
                startGuestSetup(it)
                // 首次启动失败时自动降级到安全模式
                startBootWatchdog(it, safeMode)
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
        bootWatchdogJob = setupScope.launch {
            delay(180_000)
            if (activeSession !== session) return@launch
            val out = session.output.value
            val booted = out.contains("login:") || out.contains("~ #") || out.contains("localhost:~#")
            if (!booted && !safeMode && !safeModeAttempted) {
                safeModeAttempted = true
                Log.w(TAG, "guest 180s 未进入 login，尝试安全模式重启")
                bootWatchdogJob = null
                // 独立协程里重启，避免 stopSession 取消当前 watchdog 自己
                setupScope.launch { startSessionInternal(512, 1, safeMode = true) }
            }
        }
    }

    private fun startGuestSetup(session: QemuSession) {
        setupJob?.cancel()
        setupJob = setupScope.launch {
            val cmd = PhoneBridgeHttpServer.guestSetupCommand()
            if (cmd.isBlank()) return@launch
            var loginSent = false
            var sent = false
            session.output.collect { output ->
                if (sent || guestSetupDone) return@collect
                val tail = output.takeLast(6000)
                val hasLogin = tail.contains("login:", ignoreCase = true)
                val hasPrompt = tail.contains("~ #") ||
                    tail.contains("localhost:~#") ||
                    tail.lines().lastOrNull()?.trimEnd()?.endsWith("#") == true
                if (hasLogin && !loginSent) {
                    loginSent = true
                    session.write("root")
                }
                // 如果镜像自动登录 root，也会直接出现提示符
                if (hasPrompt && (loginSent || !hasLogin)) {
                    sent = true
                    guestSetupDone = true
                    delay(300)
                    session.write(cmd)
                }
            }
        }
    }

    @Synchronized
    fun stopSession() {
        setupJob?.cancel()
        setupJob = null
        bootWatchdogJob?.cancel()
        bootWatchdogJob = null
        guestSetupDone = false
        activeSession?.shutdown()
        activeSession = null
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