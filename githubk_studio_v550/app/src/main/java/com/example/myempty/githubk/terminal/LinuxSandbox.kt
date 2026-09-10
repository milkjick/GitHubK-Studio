package com.example.myempty.githubk.terminal

import android.content.Context
import android.os.Build
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * LinuxSandbox: proot + Linux rootfs execution engine (aarch64).
 *
 * 参考已证实可行的架构（CodeForge 打包后端 / 华为真机 agent 应用）：
 *
 * 1. proot 打成 libproot.so 放进 APK 的 jniLibs/arm64-v8a/；
 * 2. Android 安装时把 jniLibs 释放到 /data/app/<pkg>/lib/arm64/，
 *    该目录 SELinux 上下文是 apk_data_file —— untrusted_app 允许执行
 *    自己 apk_data_file 中的文件，因此 execve(libproot.so) 在华为/EMUI
 *    这类禁止执行 app_data_file（filesDir）的 ROM 上也能成功；
 * 3. proot 用内部 loader（libloader.so）加载“真实 Linux rootfs”里的
 *    musl/glibc 解释器与 ELF，沙盒内所有命令都不依赖 bionic linker hack，
 *    也不需要 Shizuku/root；
 * 4. 沙盒 = 自包含 Linux 用户态：apk 可直接 `apk add` 安装包，任何离线包
 *    落到 rootfs 内即与主程序隔离。
 *
 * 旧方案直接 exec Termux bionic 二进制：bionic 二进制无法被 proot loader
 * 解释（实测 execve ENOENT），且需要 ELF 解释器改写 + Shizuku，因此本类
 * 使用真实 Linux rootfs 而非 bionic bootstrap。
 */
object LinuxSandbox {
    const val ENGINE_PROOT = "proot-linux"
    const val ENGINE_TERMUX = "termux-direct"

    private const val ROOTFS_ASSET = "linuxroot-aarch64.tar.gz"
    private const val ROOTFS_DIR_NAME = "linuxroot"
    private const val ROOTFS_MARKER = ".linuxroot_ok_v1"

    /** jniLibs 里随 APK 打包的 proot 运行组件。 */
    private val PROOT_NATIVE_LIBS = listOf(
        "libproot.so", "libtalloc.so", "libandroid-shmem.so",
        "libloader.so", "libloader32.so"
    )

    // ------------------------------------------------------------------
    // 基础能力
    // ------------------------------------------------------------------

    fun isArm64(): Boolean =
        Build.SUPPORTED_ABIS?.any { it.contains("arm64") } == true

    /** Android 释放 jniLibs 的目录（/data/app/<pkg>/lib/<abi>）。 */
    fun nativeLibDir(context: Context): File? {
        return try {
            val dir = File(context.applicationInfo.nativeLibraryDir)
            if (dir.isDirectory && File(dir, "libproot.so").isFile) dir else null
        } catch (_: Throwable) { null }
    }

    /** proot 可执行文件（native lib 目录中，untrusted_app 可 exec）。 */
    fun prootBinary(context: Context): File? {
        val dir = nativeLibDir(context) ?: return null
        val p = File(dir, "libproot.so")
        return if (p.isFile) p else null
    }

    /** 是否具备 proot 内核通道（jniLibs 释放 + arm64）。 */
    fun isSupported(context: Context): Boolean {
        if (!isArm64()) return false
        val proot = prootBinary(context) ?: return false
        // libtalloc/libandroid-shmem 必须与 proot 同目录（LD_LIBRARY_PATH）
        return PROOT_NATIVE_LIBS.all { name ->
            if (name == "libproot.so") true else File(proot.parentFile, name).isFile
        }
    }

    fun rootfsDir(context: Context): File = File(context.filesDir, ROOTFS_DIR_NAME)

    /** rootfs 已解压且含可用 /bin/sh（busybox/ash）。 */
    fun isRootfsInstalled(context: Context): Boolean {
        val r = rootfsDir(context)
        if (!r.isDirectory) return false
        if (!File(r, "bin/sh").exists() || !File(r, "bin/busybox").isFile) return false
        // 首次安装写 marker；后续解包异常（比如被系统清理）可据此判定
        return File(r, ROOTFS_MARKER).isFile ||
            File(r, "etc/alpine-release").isFile
    }

    /** 引擎标签（用于 UI）。 */
    fun engineLabel(context: Context): String = when {
        isSupported(context) && isRootfsInstalled(context) -> "proot + Alpine Linux"
        isSupported(context) -> "proot (rootfs 未安装)"
        else -> "Termux(legacy)"
    }

    // ------------------------------------------------------------------
    // rootfs 安装
    // ------------------------------------------------------------------

    /** 从 APK assets 解压内置 Linux rootfs（离线，~4MB tar.gz）。 */
    fun installRootfs(context: Context, onProgress: (Float) -> Unit = {}): Boolean {
        return try {
            val target = rootfsDir(context)
            if (target.isDirectory) {
                runCatching { deleteRecursivelyQuiet(target) }
            }
            target.mkdirs()
            onProgress(0.05f)
            val tmp = File(context.cacheDir, "linuxroot-install.tmp")
            tmp.delete()
            context.assets.open(ROOTFS_ASSET).use { input ->
                val gz = GZIPInputStream(BufferedInputStream(input, 64 * 1024), 64 * 1024)
                extractTarGz(gz, target) { p -> onProgress(0.05f + p * 0.90f) }
                gz.close()
            }
            // 确保基本目录存在（tar 里可能有也可能没有）
            listOf("proc", "dev", "sys", "tmp", "root", "data", "home").forEach {
                File(target, it).mkdirs()
            }
            // DNS：Android 不提供 /etc/resolv.conf，沙盒内 DNS 需显式配置
            val resolv = File(target, "etc/resolv.conf")
            if (!resolv.exists() || resolv.readText().isBlank()) {
                resolv.writeText("nameserver 8.8.8.8\nnameserver 114.114.114.114\n")
            }
            File(target, ROOTFS_MARKER).writeText("ok")
            onProgress(1f)
            true
        } catch (t: Throwable) {
            android.util.Log.e("LinuxSandbox", "installRootfs failed", t)
            false
        }
    }

    private fun deleteRecursivelyQuiet(f: File) {
        try {
            if (f.isDirectory) f.listFiles()?.forEach { deleteRecursivelyQuiet(it) }
            f.delete()
        } catch (_: Throwable) {}
    }

    /**
     * 极简 ustar 解包器（支持 GNU longname/L、longlink/K，跳过 PAX x/g）。
     * Android 无内置 tar，离线场景不能依赖 runtime 的 tar 命令。
     */
    private fun extractTarGz(gz: java.io.InputStream, dest: File, onProgress: (Float) -> Unit) {
        val buf = java.io.ByteArrayOutputStream()
        val tmp = ByteArray(64 * 1024)
        var n: Int
        while (gz.read(tmp).also { n = it } != -1) buf.write(tmp, 0, n)
        val data = buf.toByteArray()
        var off = 0
        val total = data.size
        var count = 0
        var pendingLongName: String? = null
        var pendingLongLink: String? = null
        while (off + 512 <= data.size) {
            val header = data.copyOfRange(off, off + 512)
            off += 512
            if (header.all { it == 0.toByte() }) break
            val name = cstr(header, 0, 100)
            val mode = parseOctalInt(header, 100, 8).toInt()
            val size = parseOctalInt(header, 124, 12)
            val type = header[156].toInt().toChar()
            val linkName = cstr(header, 157, 100)
            val magic = cstr(header, 257, 6)
            val prefix = if (magic.startsWith("ustar")) cstr(header, 345, 155) else ""
            if (off + size > data.size) break
            val content = data.copyOfRange(off, off + size)
            off += ((size + 511) / 512) * 512

            val fullName = pendingLongName ?: (if (prefix.isEmpty()) name else "$prefix/$name")
            val fullLink = pendingLongLink ?: linkName
            pendingLongName = null
            pendingLongLink = null
            if (fullName.isEmpty()) continue

            when (type) {
                'L' -> pendingLongName = String(content, Charsets.UTF_8).trimEnd('\u0000').trim()
                'K' -> pendingLongLink = String(content, Charsets.UTF_8).trimEnd('\u0000').trim()
                'x', 'g' -> { /* PAX: ignore */ }
                '5' -> File(dest, fullName).mkdirs()
                '2' -> {
                    val f = File(dest, fullName)
                    f.parentFile?.mkdirs()
                    if (f.exists()) f.delete()
                    try { java.nio.file.Files.createSymbolicLink(f.toPath(), java.nio.file.Paths.get(fullLink)) }
                    catch (_: Throwable) { /* 某些 fs 不支持 symlink，跳过 */ }
                }
                '1' -> {
                    val f = File(dest, fullName)
                    f.parentFile?.mkdirs()
                    val target = File(dest, fullLink)
                    if (target.isFile) target.copyTo(f, overwrite = true)
                    else FileOutputStream(f).use { it.write(content) }
                }
                '0', '\u0000' -> {
                    val f = File(dest, fullName)
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { it.write(content) }
                    val exec = (mode and 0x49) != 0
                    if (exec) f.setExecutable(true, false)
                    f.setReadable(true, false)
                }
                else -> {
                    // unknown entry type: skip
                }
            }
            count++
            if (count % 40 == 0) onProgress((off.toFloat() / total).coerceIn(0f, 1f))
        }
        onProgress(1f)
    }

    private fun cstr(b: ByteArray, start: Int, len: Int): String {
        val end = minOf(start + len, b.size)
        var i = start
        while (i < end && b[i] != 0.toByte()) i++
        return String(b, start, i - start, Charsets.UTF_8)
    }

    private fun parseOctalInt(b: ByteArray, start: Int, len: Int): Int {
        var v = 0
        var i = start
        val end = minOf(start + len, b.size)
        while (i < end) {
            val c = b[i].toInt().toChar()
            if (c == ' ') break
            if (c !in '0'..'7') { i++; continue }
            v = v * 8 + (c - '0')
            i++
        }
        return v
    }

    // ------------------------------------------------------------------
    // 环境与启动参数
    // ------------------------------------------------------------------

    /** 启动 proot 所需的环境变量（host 侧）。 */
    fun prootEnv(context: Context): Map<String, String> {
        val lib = nativeLibDir(context)?.absolutePath ?: ""
        val tmp = File(context.cacheDir, "proot-tmp").apply { mkdirs() }.absolutePath
        return mapOf(
            "LD_LIBRARY_PATH" to lib,
            "PROOT_LOADER" to "$lib/libloader.so",
            "PROOT_TMP_DIR" to tmp
        )
    }

    /** guest 内环境（供登录 shell 使用）。 */
    fun guestEnv(context: Context, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val m = linkedMapOf<String, String>()
        m.putAll(prootEnv(context))
        m["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        m["HOME"] = "/root"
        m["TERM"] = "xterm-256color"
        m["COLORTERM"] = "truecolor"
        m["LANG"] = "C.UTF-8"
        m["LC_ALL"] = "C.UTF-8"
        m["SHELL"] = "/bin/sh"
        m.putAll(extra)
        return m
    }

    /** host 绝对路径 → guest 绝对路径（经 /host 与 /sdcard 绑定）。 */
    fun guestPath(context: Context, hostPath: String): String {
        val f = File(hostPath)
        val files = context.filesDir.absolutePath
        val ext = "/storage/emulated/0"
        val p = f.absolutePath
        return when {
            p == files -> "/host"
            p.startsWith(files + "/") -> "/host/" + p.removePrefix(files + "/")
            p.startsWith(ext) -> p // /storage/emulated/0 已绑定为 /storage/emulated/0
            p == "/sdcard" -> "/sdcard"
            p.startsWith("/sdcard/") -> p
            else -> p
        }
    }

    /**
     * 构建完整 proot argv（不含 argv[0]）。
     * guestCmd 为 rootfs 内的命令（如 ["/bin/sh", "-l"]）。
     * cwdHost 可选：映射为 guest 内的 --cwd。
     */
    fun prootArgs(context: Context, guestCmd: List<String>, cwdHost: String? = null): List<String> {
        val rootfs = rootfsDir(context).absolutePath
        val files = context.filesDir.absolutePath
        val cache = context.cacheDir.absolutePath
        val args = mutableListOf<String>()
        args += listOf("-r", rootfs)
        args += listOf("-b", "/proc:/proc", "-b", "/dev:/dev", "-b", "/sys:/sys")
        args += listOf("-b", "/dev/null:/proc/sys/kernel/cap_last_cap")
        args += listOf("-b", "$files:/host")
        if (File("/storage/emulated/0").isDirectory) {
            args += listOf("-b", "/storage/emulated/0:/sdcard")
            args += listOf("-b", "/storage/emulated/0:/storage/emulated/0")
        }
        // 运行期的临时/工作目录也可选地映射进去
        val prootTmp = File(cache, "proot-tmp")
        if (prootTmp.isDirectory) args += listOf("-b", "${prootTmp.absolutePath}:/host-tmp")
        args += listOf("--root-id")
        if (cwdHost != null) {
            val g = guestPath(context, cwdHost)
            args += listOf("--cwd=$g")
        }
        args += guestCmd
        return args
    }

    /** 登录终端 argv（proot ... /bin/sh -l）。 */
    fun loginArgv(context: Context, cwdHost: String?): List<String> {
        val argv = prootArgs(context, listOf("/bin/sh", "-l"), cwdHost)
        return argv
    }

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    /** 在沙盒中执行一条命令（非交互），返回 stdout+stderr。 */
    fun run(
        context: Context,
        command: String,
        extraEnv: Map<String, String> = emptyMap(),
        cwdHost: File? = null,
        timeoutSeconds: Long = 180
    ): Pair<Int, String> {
        val proot = prootBinary(context) ?: return -1 to "proot 不可用"
        val argv = mutableListOf(proot.absolutePath)
        argv += prootArgs(context, listOf("/bin/sh", "-c", command), cwdHost?.absolutePath)
        return try {
            val pb = ProcessBuilder(argv)
            val env = pb.environment()
            guestEnv(context, extraEnv).forEach { (k, v) -> env[k] = v }
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!done) {
                p.destroyForcibly()
                return 124 to out + "\n[TIMEOUT]\n"
            }
            p.exitValue() to out
        } catch (t: Throwable) {
            -2 to (t.message ?: t.javaClass.simpleName)
        }
    }

    /** 在沙盒中安装 Alpine 包（联网时可用；离线时可用本地下发的 .apk 包）。 */
    fun installAlpinePackages(context: Context, packages: List<String>, onOutput: (String) -> Unit = {}): Boolean {
        val names = packages.joinToString(" ")
        val script = "apk update 2>&1 && apk add --no-interactive $names 2>&1; echo \"[exit=\$?]\""
        val (code, out) = run(context, script, timeoutSeconds = 600)
        onOutput(out)
        return code == 0
    }
}
