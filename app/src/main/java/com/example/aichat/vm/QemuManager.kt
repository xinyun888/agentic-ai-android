package com.example.aichat.vm

import android.content.Context
import android.os.Build
import com.example.aichat.linux.LinuxRuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class QemuManager(private val context: Context, val linux: LinuxRuntimeManager) {

    companion object {
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
    val efiVarsFile = File(vmDir, "efi-vars.fd")
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
        return names.contains("alpine-virt.iso")
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

    /** 完全离线：从 assets/vm 复制 Alpine virt ISO 到 /vm */
    suspend fun installIsoFromAssets(onProgress: (String) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                if (!abiSupported()) {
                    return@withContext Result.failure(IllegalStateException("当前设备/ABI 不支持内置 arm64 VM 运行时"))
                }
                val names = context.assets.list("vm")?.toSet() ?: emptySet()
                if ("alpine-virt.iso" !in names) {
                    return@withContext Result.failure(IllegalStateException("缺少 assets/vm/alpine-virt.iso"))
                }
                if (isoFile.exists() && isoFile.length() > 0) {
                    onProgress("ISO 已存在")
                    return@withContext Result.success(Unit)
                }
                onProgress("释放 Alpine virt ISO ...")
                context.assets.open("vm/alpine-virt.iso").use { input ->
                    isoFile.outputStream().use { output -> input.copyTo(output) }
                }
                onProgress("Alpine ISO 已就绪 (${isoFile.length() / 1024 / 1024}MB)")
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun qemuInstalled(): Boolean = File(linux.rootfsDir, "usr/bin/qemu-system-aarch64").exists()

    fun imagesReady(): Boolean = isoFile.exists() && isoFile.length() > 0

    fun diskReady(): Boolean = diskFile.exists() && diskFile.length() > 0

    fun statusText(): String = buildString {
        appendLine("rootfs: " + if (rootfsInstalled()) "\u2705 已安装" else "\u274C 未安装")
        appendLine("QEMU:   " + if (qemuInstalled()) "\u2705 已安装" else "\u274C 未安装")
        appendLine("ISO:    " + if (imagesReady()) "\u2705 ${isoFile.length() / 1024 / 1024}MB" else "\u274C 未释放")
        appendLine("磁盘:   " + if (diskReady()) "\u2705 ${diskFile.name} (${diskFile.length() / 1024 / 1024}MB)" else "\u274C 未创建")
        val assetDir = qemuAssetDir()
        val qemuApkCount = context.assets.list(assetDir)?.count { it.endsWith(".apk") } ?: 0
        appendLine("离线 QEMU 包: " + if (qemuApkAssetsReady()) "\u2705 $qemuApkCount 个 ($assetDir)" else "\u274C 缺失")
        appendLine("ISO 包: " + if (netbootAssetsReady()) "\u2705 已内置" else "\u274C 缺失")
        appendLine("架构: " + if (abiSupported()) "\u2705 ${hostAbi()}" else "\u274C 当前仅支持 arm64 / x86_64 VM 运行时")
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
                val r = linux.exec(
                    "qemu-img create -f qcow2 /vm/alpine.qcow2 ${sizeGb}G",
                    timeoutSec = 300
                )
                if (r.exitCode != 0) {
                    return@withContext Result.failure(
                        IllegalStateException("qemu-img 失败:\n${r.output.takeLast(2000)}")
                    )
                }
                onProgress("磁盘创建完成")
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun buildQemuArgs(memoryMb: Int = 1024, smp: Int = 1): List<String> = listOf(
        QEMU_GUEST_PATH,
        "-accel", "tcg",
        "-M", "virt",
        "-cpu", "cortex-a57",
        "-smp", smp.toString(),
        "-m", memoryMb.toString(),
        "-drive", "if=pflash,format=raw,readonly=on,file=/usr/share/qemu/edk2-aarch64-code.fd",
        "-drive", "if=pflash,format=raw,file=/vm/efi-vars.fd",
        "-drive", "file=/vm/alpine.qcow2,if=virtio,format=qcow2",
        "-cdrom", "/vm/alpine-virt.iso",
        "-boot", "d",
        "-netdev", "user,id=n0",
        "-device", "virtio-net-pci,netdev=n0",
        "-nographic",
        "-monitor", "none",
        "-no-reboot"
    )

    private var activeSession: QemuSession? = null

    fun startSession(memoryMb: Int = 1024, smp: Int = 1): QemuSession? {
        if (!abiSupported()) return null
        if (!qemuInstalled() || !imagesReady() || !diskReady()) return null
        if (!ensureEfiVars()) return null
        stopSession()
        return try {
            QemuSession(linux, buildQemuArgs(memoryMb, smp)).also {
                activeSession = it
                it.start()
            }
        } catch (_: Exception) {
            stopSession()
            null
        }
    }

    private fun ensureEfiVars(): Boolean {
        return try {
            val systemVars = File(linux.rootfsDir, "usr/share/qemu/edk2-arm-vars.fd")
            if (!systemVars.exists()) return false
            if (!efiVarsFile.exists() || efiVarsFile.length() != systemVars.length()) {
                systemVars.copyTo(efiVarsFile, overwrite = true)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun stopSession() {
        activeSession?.shutdown()
        activeSession = null
    }

    fun shutdown() {
        stopSession()
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