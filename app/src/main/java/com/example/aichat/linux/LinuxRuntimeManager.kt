package com.example.aichat.linux

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files

/**
 * PRoot + Alpine Linux 运行时。
 *
 * 设计目标：
 * 1. 用自带/下载的静态 proot 启动 Alpine 根文件系统，不需要 root；
 * 2. 不把 App 私有目录整体暴露给 Linux，只挂载 phone_bridge 和当前对话 workspace；
 * 3. proot 以 libproot_exec.so 形式随 APK 的 jniLibs 一起打包，避开 Android 10+ 对 app 私有目录 exec 的限制。
 *
 * 运行时依赖（由项目根目录 fetch-linux-runtime.ps1 下载）：
 * - app/src/main/jniLibs/arm64-v8a/libproot_exec.so
 * - app/src/main/jniLibs/x86_64/libproot_exec.so
 * - app/src/main/assets/linux/alpine-aarch64.tar.gz
 * - app/src/main/assets/linux/alpine-x86_64.tar.gz
 */
class LinuxRuntimeManager(private val appContext: Context) {

    companion object {
        const val BRIDGE_DIR_NAME = "phone_bridge"
        private const val LINUX_DIR_NAME = "linux"
        private const val ROOTFS_DIR_NAME = "alpine"
        private const val PROOT_NAME = "libproot_exec.so"
        private const val MAX_OUTPUT_CHARS = 200_000
        private const val DEFAULT_TIMEOUT_SEC = 600L
        private const val PHONE_SCRIPT = """
#!/bin/sh
# AI Chat phone bridge CLI. 由 LinuxRuntimeManager 自动写入 guest rootfs。
BRIDGE="${'$'}{PHONE_BRIDGE:-/phone}"
ID="${'$'}${'$'}"
REQ="${'$'}BRIDGE/request_${'$'}ID"
RESP="${'$'}BRIDGE/response_${'$'}ID"
mkdir -p "${'$'}BRIDGE" 2>/dev/null
{
  printf '%s\n' "${'$'}1"
  shift
  for a in "${'$'}@"; do printf '%s\n' "${'$'}a"; done
} > "${'$'}REQ.tmp" || exit 1
mv "${'$'}REQ.tmp" "${'$'}REQ" || exit 1
i=0
while [ ! -f "${'$'}RESP" ]; do
  i=${'$'}((i+1))
  if [ "${'$'}i" -gt 300 ]; then
    echo "ERR bridge timeout"
    rm -f "${'$'}REQ"
    exit 1
  fi
  sleep 0.1 2>/dev/null || sleep 1
done
cat "${'$'}RESP"
rm -f "${'$'}RESP"
"""
    }

    data class ExecResult(
        val output: String,
        val exitCode: Int,
        val timedOut: Boolean = false,
        val fatal: String = ""
    )

    val bridgeDir: File = File(appContext.filesDir, BRIDGE_DIR_NAME).also { it.mkdirs() }
    private val linuxRoot = File(appContext.filesDir, LINUX_DIR_NAME)
    val rootfsDir = File(linuxRoot, ROOTFS_DIR_NAME)
    private val workspaceRoot = File(appContext.filesDir, "workspace").also { it.mkdirs() }
    val vmDir = File(appContext.filesDir, "vm").also { it.mkdirs() }
    private val cacheDir = appContext.cacheDir
    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun rootfsInstalled(): Boolean = File(rootfsDir, "bin/sh").exists()

    /**
     * PRoot 需要把加载器解压到一个可写目录，然后再 chmod/exec 它。
     * 之前用 cacheDir  Android 在低存储时会**自动清空 App 缓存目录**，目录一消失 PRoot 就报
     *   can't chmod '.../cache/proot-xxxx': No such file or directory
     * 紧接着 execve("/usr/bin/env"): Permission denied，
     * 之后所有 guest 命令（含 QEMU 启动）全部失败。
     * 现在固定在 filesDir 下（系统不会自动清理），并且每次启动前重建 + 写测试。
     */
    val prootTmpDir: File = File(linuxRoot, "proot-tmp")

    fun ensureProotTmp(): File? {
        val d = prootTmpDir
        return try {
            if (!d.exists()) d.mkdirs()
            val probe = File(d, ".probe")
            probe.writeText("ok")
            probe.delete()
            // 清掉上次残留的半成品加载器（同名文件会让 PRoot 误判）
            d.listFiles()?.forEach { f -> if (f.name.startsWith("proot-")) f.delete() }
            d
        } catch (e: Exception) {
            null
        }
    }

    /** 可用空间（MB），-1 表示取不到 */
    fun freeSpaceMb(): Long = try {
        android.os.StatFs(appContext.filesDir.absolutePath).availableBytes / 1024 / 1024
    } catch (e: Exception) {
        -1L
    }

    /** 空间预检：不足时返回给用户看的错误文案，够则返回 null */
    fun requireFreeSpace(needMb: Long, what: String): String? {
        val free = freeSpaceMb()
        if (free in 0 until needMb) {
            return "\u274C 存储空间不足：$what 需要约 ${needMb}MB，当前可用 ${free}MB。\n" +
                "请先清理空间（可用「清理临时文件」按钮，或删除手机里不用的文件），然后重试。"
        }
        return null
    }

    /** 清理可再生的大文件，返回释放的字节数 */
    fun cleanupTemp(): Long {
        var freed = 0L
        fun zap(f: File) {
            if (!f.exists()) return
            val size = if (f.isDirectory) {
                f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            } else f.length()
            if (runCatching { if (f.isDirectory) f.deleteRecursively() else f.delete() }.getOrDefault(false)) {
                freed += size
            }
        }
        zap(File(vmDir, "alpine.qcow2.part"))
        zap(File(vmDir, "qemu-apks"))
        zap(File(vmDir, "disk-install.log"))
        zap(File(linuxRoot, "$ROOTFS_DIR_NAME.tmp"))
        zap(File(appContext.cacheDir, "dsh-bundle.tar.gz"))
        zap(File(appContext.cacheDir, "aichat-dsh-untar.log"))
        zap(File(appContext.cacheDir, "aichat-toolchain.log"))
        runCatching {
            appContext.cacheDir.listFiles()?.forEach { f ->
                if (f.name.startsWith("proot-")) zap(f)
            }
        }
        return freed
    }

    /** 给 ProcessBuilder 统一注入 PRoot 运行环境；tmp 目录不可写时抛带指引的异常 */
    private fun applyProotEnv(pb: ProcessBuilder) {
        val tmp = ensureProotTmp() ?: throw IllegalStateException(
            "\u274C PRoot 临时目录不可写：${prootTmpDir.absolutePath}\n" +
                "常见原因：手机存储已满，或系统已清理 App 缓存。请清理空间后重试（VM 页有「清理临时文件」）。" +
                (compatibilityWarning()?.let { "\n" + it } ?: "")
        )
        pb.environment()["PROOT_TMP_DIR"] = tmp.absolutePath
        pb.environment()["PROOT_NO_SECCOMP"] = "1"
        val proot = prootFile()
        if (proot != null) {
            ensureLoader(File(proot.parentFile, "libproot_loader.so"), File(linuxRoot, "proot_loader"))?.let {
                pb.environment()["PROOT_LOADER"] = it.absolutePath
            }
            ensureLoader(File(proot.parentFile, "libproot_loader_m32.so"), File(linuxRoot, "proot_loader_m32"))?.let {
                pb.environment()["PROOT_LOADER_32"] = it.absolutePath
            }
        }
    }

    fun prootFile(): File? {
        val nativeFile = File(appContext.applicationInfo.nativeLibraryDir, PROOT_NAME)
        if (nativeFile.exists()) {
            nativeFile.setExecutable(true, false)
            return nativeFile
        }
        // 兼容：如果设备允许，也支持手动放在 files/linux/proot
        val fallback = File(linuxRoot, "proot")
        if (fallback.exists()) {
            fallback.setExecutable(true, false)
            return fallback
        }
        return null
    }

    fun compatibilityWarning(): String? {
        val target = appContext.applicationInfo.targetSdkVersion
        return if (Build.VERSION.SDK_INT >= 29 && target >= 29) {
            "\u26A0\uFE0F 你装的是 targetSdk=$target 的包：Android 10+ 会阻止 proot 执行 rootfs 内二进制\n" +
                "（典型报错：execve(\"/usr/bin/env\"): Permission denied）。\n" +
                "请改装文件名里带 **target28** 的那个 APK（本仓库每次都会一起产出），PRoot / QEMU / DSH 才能工作。"
        } else null
    }

    fun abiTag(): String? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return null
        return when {
            abi.contains("arm64") -> "aarch64"
            abi.contains("x86_64") -> "x86_64"
            else -> null
        }
    }

    fun statusText(): String = buildString {
        val proot = prootFile()
        appendLine("proot: " + if (proot == null) "\u274C 未打包" else "\u2705 ${proot.absolutePath}")
        val loader = proot?.parentFile?.let { File(it, "libproot_loader.so") }
        appendLine("loader: " + if (loader?.exists() == true) "\u2705 ${loader.absolutePath}" else "\u274C 未打包")
        appendLine("rootfs: " + if (rootfsInstalled()) "\u2705 ${rootfsDir.absolutePath}" else "\u274C 未安装")
        appendLine("bridge: ${bridgeDir.absolutePath}")
    }

    /**
     * 安装/修复 Alpine 根文件系统。优先从 assets 解压，不会联网。
     * 需要在构建前运行 fetch-linux-runtime.ps1 把 alpine-<arch>.tar.gz 放进 assets。
     */
    suspend fun installRootfs(onProgress: (String) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val abi = abiTag() ?: throw IllegalStateException("当前 ABI 不支持 Linux 运行时")
            if (rootfsInstalled()) {
                onProgress("rootfs 已存在，检查配置")
                configureRootfs(rootfsDir)
                return@withContext Result.success(Unit)
            }
            val stream = openRootfsAsset(abi)
                ?: throw IllegalStateException(
                    "未找到内置 Alpine rootfs。请先在项目根目录运行 fetch-linux-runtime.ps1，然后重新构建 APK。"
                )

            onProgress("解压 Alpine rootfs ...")
            val tmp = File(linuxRoot, "$ROOTFS_DIR_NAME.tmp")
            if (tmp.exists()) tmp.deleteRecursively()
            tmp.mkdirs()
            stream.use { extractTarStream(it, tmp) }
            onProgress("配置 DNS / phone 桥接脚本 ...")
            configureRootfs(tmp)

            if (rootfsDir.exists()) rootfsDir.deleteRecursively()
            if (!tmp.renameTo(rootfsDir)) {
                // 极少见：同分区 rename 失败时退化为整树复制
                tmp.copyRecursively(rootfsDir, overwrite = true)
                tmp.deleteRecursively()
            }
            onProgress("Alpine rootfs 安装完成")
            Result.success(Unit)
        } catch (e: Exception) {
            onProgress("安装失败: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun exec(
        command: String,
        convId: String = "",
        timeoutSec: Long = DEFAULT_TIMEOUT_SEC
    ): ExecResult = withContext(Dispatchers.IO) {
        val proot = prootFile()
            ?: return@withContext ExecResult(
                output = "\u274C 未找到 PRoot 可执行文件。请先运行 fetch-linux-runtime.ps1 并重新构建 APK。",
                exitCode = -1
            )
        if (!rootfsInstalled()) {
            return@withContext ExecResult(
                output = "\u274C Alpine rootfs 未安装。请先到 Linux 环境页点击安装 Alpine 或运行安装接口。",
                exitCode = -1
            )
        }
        val wsDir = if (convId.isBlank()) workspaceRoot
        else File(workspaceRoot, convId.replace(Regex("[^a-zA-Z0-9_-]"), "_")).also { it.mkdirs() }

        val cmd = mutableListOf<String>()
        cmd += proot.absolutePath
        cmd += listOf(
            "--link2symlink",
            "--kill-on-exit",
            "-0",
            "-r", rootfsDir.absolutePath,
            "-w", "/root",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${bridgeDir.absolutePath}:/phone",
            "-b", "${wsDir.absolutePath}:/workspace",
            "-b", "${vmDir.absolutePath}:/vm"
        )
        cmd += listOf(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PHONE_BRIDGE=/phone",
            "/bin/sh", "-lc", command
        )

        val pb = ProcessBuilder(cmd)
        pb.directory(rootfsDir)
        pb.redirectErrorStream(true)
        try {
            applyProotEnv(pb)
        } catch (e: Exception) {
            return@withContext ExecResult(output = e.message ?: "PRoot 环境准备失败", exitCode = -1)
        }

        val process = try {
            pb.start()
        } catch (e: Exception) {
            return@withContext ExecResult(
                output = "\u274C 启动 proot 失败: ${e.message}" +
                    (compatibilityWarning()?.let { "\n" + it } ?: ""),
                exitCode = -1
            )
        }

        val outputDeferred = scope.async(Dispatchers.IO) {
            readCapped(process.inputStream, MAX_OUTPUT_CHARS)
        }

        var waited = 0L
        val timeoutMs = timeoutSec * 1000L
        while (process.isAlive && waited < timeoutMs) {
            delay(100)
            waited += 100
        }
        if (process.isAlive) {
            process.destroyForcibly()
            runCatching { process.waitFor() }
            val partial = runCatching { outputDeferred.await() }.getOrDefault("")
            ExecResult(
                output = "\u274C 命令执行超时（${timeoutSec}s）\n\n$partial",
                exitCode = -1,
                timedOut = true
            )
        } else {
            val output = runCatching { outputDeferred.await() }.getOrDefault("")
            ExecResult(output = output, exitCode = process.exitValue())
        }
    }

    /** 构建 PRoot 启动参数；QEMU 等交互式进程可复用。 */
    fun buildProotCommand(
        payload: List<String>,
        extraBinds: List<Pair<String, String>> = emptyList()
    ): List<String> {
        val proot = prootFile() ?: throw IllegalStateException("PRoot 未打包")
        val cmd = mutableListOf<String>()
        cmd += proot.absolutePath
        cmd += listOf(
            "--link2symlink",
            "--kill-on-exit",
            "-0",
            "-r", rootfsDir.absolutePath,
            "-w", "/root",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${bridgeDir.absolutePath}:/phone",
            "-b", "${workspaceRoot.absolutePath}:/workspace",
            "-b", "${vmDir.absolutePath}:/vm"
        )
        extraBinds.forEach { (host, guest) -> cmd += listOf("-b", "$host:$guest") }
        cmd += payload
        return cmd
    }

    /** 启动交互式进程（QEMU、shell 等）。 */
    fun startProcess(
        payload: List<String>,
        extraBinds: List<Pair<String, String>> = emptyList()
    ): Process {
        val cmd = buildProotCommand(payload, extraBinds)
        val pb = ProcessBuilder(cmd)
        pb.directory(rootfsDir)
        pb.redirectErrorStream(true)
        applyProotEnv(pb)
        return pb.start()
    }

    fun shutdown() {
        // PRoot 没有常驻守护进程；这里只取消内部辅助协程。
        scope.cancel()
    }

    // ==================== 私有工具 ====================

    /** APK 打包可能把 .tar.gz 解压并改名为 .tar，这里两种都兼容 */
    private fun openRootfsAsset(abi: String): InputStream? {
        for (name in listOf("linux/alpine-$abi.tar.gz", "linux/alpine-$abi.tar")) {
            try {
                return appContext.assets.open(name)
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Alpine 的 apk add 会把包里的绝对符号链接原样展开，例如 /bin/sh -> /bin/busybox。
     * 在 Android 宿主上这会导致 guest 解析到宿主根目录，所以安装完 QEMU 后要把
     * rootfs 内的绝对符号链接统一改成相对链接。
     */
    fun fixRootfsSymlinks() {
        if (!rootfsInstalled()) return
        try {
            walkNoFollow(rootfsDir) { file ->
                try {
                    if (!Files.isSymbolicLink(file.toPath())) return@walkNoFollow
                    val target = Os.readlink(file.absolutePath) ?: return@walkNoFollow
                    if (!target.startsWith("/")) return@walkNoFollow
                    val parent = file.parentFile ?: return@walkNoFollow
                    val targetHost = File(rootfsDir, target.trimStart('/'))
                    val relative = parent.toPath().relativize(targetHost.toPath())
                        .toString().replace('\\', '/')
                    file.delete()
                    Os.symlink(relative, file.absolutePath)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun walkNoFollow(dir: File, action: (File) -> Unit) {
        dir.listFiles()?.forEach { file ->
            action(file)
            if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) {
                walkNoFollow(file, action)
            }
        }
    }

    private fun configureRootfs(root: File) {
        root.mkdirs()
        File(root, "dev").mkdirs()
        File(root, "proc").mkdirs()
        File(root, "sys").mkdirs()
        File(root, "tmp").mkdirs()
        File(root, "root").mkdirs()

        val dns = currentDnsServers()
        val resolv = File(root, "etc/resolv.conf")
        resolv.parentFile?.mkdirs()
        resolv.writeText(dns.joinToString("\n") { "nameserver $it" } + "\n", Charsets.UTF_8)

        val hosts = File(root, "etc/hosts")
        if (!hosts.exists()) {
            hosts.parentFile?.mkdirs()
            hosts.writeText("127.0.0.1 localhost\n::1 localhost\n", Charsets.UTF_8)
        }

        val binDir = File(root, "usr/local/bin").also { it.mkdirs() }
        val phone = File(binDir, "phone")
        phone.writeText(PHONE_SCRIPT.trimIndent() + "\n", Charsets.UTF_8)
        phone.setExecutable(true, false)
    }

    private fun currentDnsServers(): List<String> {
        return try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val lp = network?.let { cm.getLinkProperties(it) }
            val dns = lp?.dnsServers?.mapNotNull { it.hostAddress }?.filter { it.isNotBlank() } ?: emptyList()
            if (dns.isEmpty()) listOf("1.1.1.1", "8.8.8.8") else dns
        } catch (_: Exception) {
            listOf("1.1.1.1", "8.8.8.8")
        }
    }

    /**
     * 仅支持 tar/tar.gz 里的普通文件/目录/符号链接/硬链接。Alpine minirootfs 不会包含设备节点。
     */
    private fun extractTarStream(input: InputStream, destDir: File) {
        val destCanonical = destDir.canonicalFile
        val buffered = BufferedInputStream(input)
        buffered.mark(8)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        val tarStream = if (b0 == 0x1f && b1 == 0x8b) {
            TarArchiveInputStream(GzipCompressorInputStream(buffered))
        } else {
            TarArchiveInputStream(buffered)
        }
        tarStream.use { tar ->
            var entry: TarArchiveEntry? = tar.nextTarEntry
            while (entry != null) {
                    val name = entry.name.replace('\\', '/').removePrefix("./").trim()
                    if (name.isEmpty()) {
                        entry = tar.nextTarEntry
                        continue
                    }
                    val out = File(destDir, name).canonicalFile
                    val parent = out.parentFile
                    val ok = out.path == destCanonical.path ||
                        (parent != null && (out.path.startsWith(destCanonical.path + File.separator)))
                    if (!ok) throw SecurityException("非法的 tar 路径: $name")

                    when {
                        entry.isDirectory -> {
                            out.mkdirs()
                            applyExecIfNeeded(out, entry.mode, true)
                        }
                        entry.isSymbolicLink -> {
                            parent?.mkdirs()
                            if (out.exists()) out.delete()
                            Os.symlink(relativeLinkTarget(destDir, out, entry.linkName), out.absolutePath)
                        }
                        entry.isLink -> {
                            parent?.mkdirs()
                            if (out.exists()) out.delete()
                            val target = File(destDir, entry.linkName).canonicalFile
                            Os.link(target.absolutePath, out.absolutePath)
                        }
                        entry.isFile -> {
                            parent?.mkdirs()
                            FileOutputStream(out).use { fos -> tar.copyTo(fos) }
                            applyExecIfNeeded(out, entry.mode, false)
                        }
                        // FIFO / char / block：Android app 无法创建，rootfs 里通常没有
                        else -> {}
                    }
                entry = tar.nextTarEntry
            }
        }
    }

    /** loader 需要可执行/可读权限，复制到 files 目录一份更稳 */
    private fun ensureLoader(src: File, dst: File): File? {
        if (!src.exists()) return null
        return try {
            if (!dst.exists() || dst.length() != src.length()) {
                src.copyTo(dst, overwrite = true)
                dst.setExecutable(true, false)
            }
            dst
        } catch (_: Exception) {
            src
        }
    }

    /**
     * Alpine rootfs 里有大量绝对符号链接，例如 /usr/bin/yes -> /bin/busybox。
     * 在 Android 宿主机上直接创建绝对链接会指向宿主 /bin，PRoot 下解析会出错。
     * 这里把绝对目标改成相对目标，保证 guest 内解析仍然落在 rootfs 内。
     */
    private fun relativeLinkTarget(destDir: File, linkFile: File, linkName: String): String {
        if (!linkName.startsWith("/")) return linkName
        return try {
            // Android 上 /data/user/0 可能是指向 /data/data 的符号链接；
            // linkFile 已经 canonicalize 过，target 也必须 canonicalize，
            // 否则 relativize 会跨别名计算，生成错误的相对路径。
            val base = destDir.canonicalFile
            val target = File(base, linkName.trimStart('/')).canonicalFile
            val parent = linkFile.parentFile?.canonicalFile ?: return linkName
            parent.toPath().relativize(target.toPath()).toString().replace('\\', '/')
        } catch (_: Exception) {
            linkName
        }
    }

    private fun applyExecIfNeeded(file: File, mode: Int, isDir: Boolean) {
        val hasExecBit = (mode and 0b001_000_000) != 0 ||
            (mode and 0b000_001_000) != 0 ||
            (mode and 0b000_000_001) != 0
        if (isDir || hasExecBit) {
            file.setExecutable(true, false)
        }
    }

    private fun readCapped(input: InputStream, limit: Int): String {
        val sb = StringBuilder()
        input.bufferedReader(Charsets.UTF_8).use { reader ->
            val buf = CharArray(8192)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                if (sb.length < limit) {
                    val take = minOf(n, limit - sb.length)
                    sb.append(buf, 0, take)
                }
                // 超过上限后继续读并丢弃，防止子进程写满管道后卡死
            }
        }
        if (sb.length >= limit) sb.append("\n...[输出已截断]")
        return sb.toString()
    }
}