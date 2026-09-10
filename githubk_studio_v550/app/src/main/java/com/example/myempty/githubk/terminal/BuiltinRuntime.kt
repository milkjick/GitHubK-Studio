package com.example.myempty.githubk.terminal

import com.example.myempty.githubk.terminal.ShizukuShell

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import org.json.JSONObject

/**
 * BuiltinRuntime v3（参考 Termux/ZeroTermux bootstrap 安装流程）：
 *
 * 在应用私有目录内置完整 POSIX 运行时（Termux 官方 bootstrap，aarch64）。
 *
 * 安装流程：
 * 1. 下载 Termux 官方最新 bootstrap-aarch64.zip
 * 2. 解压到 filesDir/runtime/（bin/ etc/ lib/ share/ ...）
 * 3. 处理 SYMLINKS.txt 创建符号链接
 * 4. 设置文件权限（bin/ 可执行、lib/ 可读）
 * 5. 如有 Shizuku，用 shell 用户 chmod -R 755 确保可访问
 *
 * 运行方式：
 * - 普通设备：app 直接 exec bash（untrusted_app 域）
 * - 华为/鸿蒙：Shizuku 以 shell 用户 exec bash（shell 域允许 exec app_data_file）
 */
class BuiltinRuntime(private val c: Context) {
    // detectTool 探测缓存（3s 短 TTL）：避免 status/构建前多次 spawn bash 子进程
    private val toolProbeCache = java.util.concurrent.ConcurrentHashMap<String, Triple<Long, Boolean, String>>()

    val root: File = File(c.filesDir, "runtime")
    val prefix: String get() = root.absolutePath
    val home: File = File(root, "home")
    val tmp: File = File(root, "tmp")

    // 延迟创建 ToolchainManager，用于合并开发环境（JDK/Gradle/Android SDK/Dart/Flutter）
    private val _toolchain by lazy { ToolchainManager(c) }

    // Termux bootstrap 中 usr/ 的前缀（符号链接需要重写）
    private val TERMUX_PREFIX = "/data/data/com.termux/files/usr"

    fun isInstalled(): Boolean {
        val bash = File(root, "bin/bash")
        return bash.exists() && bash.length() > 100000 && isPlausibleElf64(bash)
    }

    /**
     * 检查文件是否仍是结构完整的 arm64 ELF。
     * 早期实现把 ELF 当文本做过路径替换，导致 e_machine/e_phoff 等字段被
     * UTF-8 替换字节（EF BF BD）破坏；这类损坏无法原地修复，只能重装。
     */
    private fun isPlausibleElf64(f: File): Boolean {
        if (!f.exists() || f.length() < 64L) return false
        return try {
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(0)
                val h = ByteArray(64)
                raf.readFully(h)
                val ok = h[0] == 0x7f.toByte() && h[1] == 'E'.code.toByte() &&
                    h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte() &&
                    h[4] == 2.toByte() && h[5] == 1.toByte()
                if (!ok) return false
                val machine = (h[18].toInt() and 0xff) or ((h[19].toInt() and 0xff) shl 8)
                val phoff = le32(h, 0x20)
                machine == 183 && phoff > 0L && phoff < f.length() &&
                    le16(h, 0x36) == 56 && le16(h, 0x38) in 1..128
            }
        } catch (_: Throwable) { false }
    }

    fun supported(): Boolean =
        Build.SUPPORTED_ABIS?.any { it.contains("arm64") } == true

    private companion object {
        // Official Termux apt-android-7 bootstrap. Pinned instead of using /latest so
        // a future incompatible bootstrap cannot silently break an installed runtime.
        const val BOOTSTRAP_VERSION = "2026.02.12-r1%2Bapt.android-7"
        const val BOOTSTRAP_AARCH64_SHA256 = "ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3"

        // Termux APT 软件源候选（顺序即尝试顺序）：国内镜像优先，官方源兜底。
        val APT_SOURCES = listOf(
            "deb https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main stable main",
            "deb https://mirrors.ustc.edu.cn/termux/apt/termux-main stable main",
            "deb https://mirrors.bfsu.edu.cn/termux/apt/termux-main stable main",
            "deb https://packages-cf.termux.dev/apt/termux-main stable main"
        )

        // bootstrap zip 下载候选（顺序即尝试顺序）：GitHub 直连 + 常用加速镜像。
        val BOOTSTRAP_DOWNLOAD_PREFIXES = listOf(
            "https://github.com",
            "https://ghfast.top/https://github.com",
            "https://gh-proxy.com/https://github.com",
            "https://ghproxy.net/https://github.com"
        )
    }

    fun bootstrapUrl(): String =
        "https://github.com/termux/termux-packages/releases/download/bootstrap-$BOOTSTRAP_VERSION/bootstrap-aarch64.zip"

    private fun latestBootstrapUrl(): String? = bootstrapUrl()

    /** 环境变量（供 ProcessBuilder / Shizuku 使用）。 */
    fun env(): Map<String, String> = mutableMapOf(
        "PREFIX" to prefix,
        "HOME" to home.absolutePath,
        "TMPDIR" to tmp.absolutePath,
        "LANG" to "C.UTF-8",
        "LC_ALL" to "C.UTF-8",
        "PATH" to "$prefix/bin:$prefix/bin/applets:/system/bin:/system/xbin",
        "LD_LIBRARY_PATH" to "$prefix/lib",
        "TERM" to "xterm-256color",
        "TERMUX_APP__PACKAGE_NAME" to c.packageName,
        "TERMUX_VERSION" to "0.118",
        "TERMUX_APK_RELEASE" to "githubk",
        "TERMUX_PREFIX" to prefix,
        "SHELL" to "$prefix/bin/bash",
        "COLORTERM" to "truecolor",
        "EDITOR" to "nano",
        "VISUAL" to "nano",
        "ANDROID_DATA" to "/data",
        "ANDROID_ROOT" to "/system"
    )

    /**
     * 合并开发环境（ToolchainManager.environment() 的 PATH/JAVA_HOME/ANDROID_HOME/
     * GRADLE_HOME/DART_HOME/FLUTTER_HOME），供 proot 沙盒与直接执行统一使用。
     * 解决“开发环境未挂载到 proot 沙箱”：终端/IDE/AI 在 ProotTermux 沙盒内
     * 找不到 java/gradle/flutter/dart/android 等命令的问题。
     */
    fun toolchainEnv(): Map<String, String> {
        val base = env().toMutableMap()
        try {
            _toolchain.environment().forEach { (k, v) -> base[k] = v }
        } catch (_: Throwable) {}
        return base
    }

    fun bashPath(): String? {
        if (!isInstalled()) return null
        // 启动前确保脚本 shebang 已修复（对已存在的 runtime 也生效）
        try { fixShebangs() } catch (_: Throwable) {}
        // 启动前确保可执行权限
        try { forceFixPermissions() } catch (_: Throwable) {}
        return File(root, "bin/bash").absolutePath
    }

    /**
     * 安装内置运行时：优先读取 APK 离线包（当前版本不再打包误标的 IDE 包），
     * 缺失时自动从 Termux 官方 GitHub release API 获取最新 bootstrap。
     */
    fun install(onProgress: (Float) -> Unit) {
        // 已有 runtime 可能被早期“文本替换”损坏（ELF 头被写成 UTF-8 替换字节），
        // 校验失败则整目录重装；bash 存在但结构损坏同样会被判定为未安装。
        if (root.exists() && !isInstalled()) {
            runCatching { root.deleteRecursively() }
            root.mkdirs()
        }
        root.mkdirs()
        onProgress(0.02f)

        // APK assets 才是可靠的离线来源。旧实现错误地把 APK 内部路径
        // /sdcard/projects/... 当成真实文件路径，导致“安装成功但 bash 不存在”。
        val installedFromAsset = runCatching {
            c.assets.open("bootstrap-aarch64.zip").use { input ->
                val zip = File(c.cacheDir, "githubk-bootstrap.zip")
                FileOutputStream(zip).use { input.copyTo(it) }
                onProgress(0.18f)
                extract(zip, root) { p -> onProgress(0.18f + p * 0.70f) }
                zip.delete()
                prepare()
                // 强制修复可执行权限：APK 解压后二进制可能丢失 exec 位
                forceFixPermissions()
                isInstalled()
            }
        }.getOrDefault(false)

        if (installedFromAsset) {
            onProgress(0.95f)
            // 这个 bootstrap 是 POSIX/终端基础运行时，不包含 JDK/Gradle/Android SDK。
            // 编译环境由“IDE 环境中心”单独安装，避免把两种环境混为一谈。
            // 首次安装完成后 lists 为空，标记“待 apt update”，由终端/工具页后台拉取。
            markTermuxAptUpdatePending()
            onProgress(1f)
            return
        }

        // APK 中没有离线包时，才走网络下载。
        onProgress(0.10f)
        installFromNetwork(onProgress)
        if (!isInstalled()) throw IllegalStateException("运行时安装完成但 bash 校验失败")
        markTermuxAptUpdatePending()
        onProgress(1f)
    }

    /**
     * 从本地 bootstrap ZIP 安装内置运行时（完全离线，不访问网络）。
     * 注意：若 runtime 已安装 JDK/Gradle/Android SDK 等组件，为避免破坏现有环境，
     * 此方法会直接返回 false；完整离线环境请使用“导入离线工具链目录”。
     */
    fun installRuntimeFromLocalZip(zipFile: File, onProgress: (Float) -> Unit): Boolean {
        if (!zipFile.isFile) return false
        try {
            if (root.exists()) {
                val hasComponents =
                    File(root, "lib/jvm").exists() || File(root, "opt/gradle").exists() ||
                    File(root, "home/android-sdk").exists() || File(root, "home/dart-sdk").exists()
                if (hasComponents && isInstalled()) return false
                runCatching { root.deleteRecursively() }
            }
            root.mkdirs()
            onProgress(0.05f)
            extract(zipFile, root) { p -> onProgress(0.05f + p * 0.85f) }
            onProgress(0.92f)
            prepare()
            forceFixPermissions()
            runCatching { ensureElfPatched() }
            markTermuxAptUpdatePending()
            onProgress(1f)
            return isInstalled()
        } catch (_: Throwable) {
            return false
        }
    }

    /**
     * 从本地已解压的 Termux 风格目录（bin/bash 或 usr/bin/bash）导入运行时。
     * 完整离线工具链目录可同时包含 jdk/、gradle/、android-sdk/ 等，由
     * ToolchainManager 负责随后安装组件。
     */
    fun importRuntimeRoot(srcDir: File): Boolean {
        val srcPrefix = when {
            File(srcDir, "bin/bash").isFile -> srcDir
            File(srcDir, "usr/bin/bash").isFile -> File(srcDir, "usr")
            else -> return false
        }
        return try {
            if (root.exists()) {
                val hasComponents =
                    File(root, "lib/jvm").exists() || File(root, "opt/gradle").exists() ||
                    File(root, "home/android-sdk").exists() || File(root, "home/dart-sdk").exists()
                // 已有完整组件时拒绝直接覆盖；完整离线工具链由 ToolchainManager
                // 先重置 runtime 再按组件逐一重装，避免这里误删 JDK/Gradle。
                if (hasComponents && isInstalled()) return false
                runCatching { root.deleteRecursively() }
            }
            root.mkdirs()
            if (srcPrefix.canonicalFile != root.canonicalFile) {
                srcPrefix.copyRecursively(root, overwrite = true)
            }
            prepare()
            forceFixPermissions()
            runCatching { ensureElfPatched() }
            isInstalled()
        } catch (_: Throwable) { false }
    }
    
    /** 通过 Shizuku 批量修复所有脚本的 shebang 和硬编码路径。 */
    private fun fixAllScripts() {
        if (!ShizukuShell.isServiceAvailable() || !ShizukuShell.hasPermission()) return
        
        val old = "/data/data/com.termux/files/usr"
        val new = "/data/data/com.example.myempty.githubk/files/runtime"
        
        // 批量替换 bin/ 和 libexec/ 下所有文件中的旧路径
        val cmd = "run-as com.example.myempty.githubk sh -c '" +
            "old=\"\$old\"; new=\"\$new\"; " +
            "for file in /data/data/com.example.myempty.githubk/files/runtime/bin/* /data/data/com.example.myempty.githubk/files/runtime/libexec/*; do " +
            "[ -f \"\$file\" ] && grep -q \"\$old\" \"\$file\" 2>/dev/null && sed -i \"s|\$old|\$new|g\" \"\$file\"; " +
            "done; " +
            "chmod -R 755 /data/data/com.example.myempty.githubk/files/runtime/bin; " +
            "chmod -R 755 /data/data/com.example.myempty.githubk/files/runtime/lib" +
            "'"
        
        ShizukuShell.execAsApp(cmd, timeoutSec = 60)
    }
    
    /** 用 ZipFile 解压（最可靠，支持大文件）。 */
    private fun extractWithZipFile(zipFile: File, dest: File, onProgress: (Float) -> Unit) {
        java.util.zip.ZipFile(zipFile).use { zf ->
            val entries = zf.entries()
            var count = 0
            val total = zf.size()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (name == "SYMLINKS.txt") {
                    val symFile = File(dest, "SYMLINKS.txt")
                    symFile.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { input ->
                        FileOutputStream(symFile).use { out ->
                            input.copyTo(out)
                        }
                    }
                } else if (entry.isDirectory) {
                    val dir = File(dest, name)
                    dir.mkdirs()
                    try { dir.setExecutable(true, false); dir.setReadable(true, false) } catch (_: Throwable) {}
                } else {
                    val outFile = File(dest, name)
                    outFile.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { input ->
                        FileOutputStream(outFile).use { out ->
                            input.copyTo(out)
                        }
                    }
                    val isExec = name.startsWith("bin/") || name.startsWith("libexec/")
                    val isLib = name.startsWith("lib/") || name.endsWith(".so")
                    try {
                        outFile.setReadable(true, false)
                        if (isExec) outFile.setExecutable(true, false)
                        if (isLib) outFile.setExecutable(true, false)
                    } catch (_: Throwable) {}
                }
                count++
                if (count % 20 == 0) {
                    onProgress(0.1f + (count.toFloat() / total) * 0.75f)
                }
            }
        }
    }
    
    /** 从网络下载 bootstrap 并安装（回退方案）。 */
    private fun installFromNetwork(onProgress: (Float) -> Unit) {
        root.mkdirs()
        val zipFile = File(c.cacheDir, "bootstrap.zip")
        // GitHub 直连 + 多个加速镜像逐个尝试，解决国内下载慢/失败问题。
        val rel = "termux/termux-packages/releases/download/bootstrap-$BOOTSTRAP_VERSION/bootstrap-aarch64.zip"
        var downloaded = false
        var lastErr = "unknown"
        for (base in BOOTSTRAP_DOWNLOAD_PREFIXES) {
            try {
                onProgress(0.10f)
                download(base + "/" + rel, zipFile, onProgress)
                downloaded = true
                break
            } catch (e: Throwable) {
                lastErr = e.message ?: e.javaClass.simpleName
                runCatching { zipFile.delete() }
            }
        }
        if (!downloaded) throw IllegalStateException("Termux bootstrap 下载失败：$lastErr")
        val actual = sha256(zipFile)
        if (!actual.equals(BOOTSTRAP_AARCH64_SHA256, ignoreCase = true)) {
            zipFile.delete()
            throw IllegalStateException("Termux bootstrap SHA-256 校验失败：$actual")
        }
        onProgress(0.92f)
        root.deleteRecursively()
        root.mkdirs()
        extract(zipFile, root)
        prepare()
        forceFixPermissions()
        zipFile.delete()
    }

    private fun sha256(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun download(url: String, dest: File, onProgress: (Float) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 30000
        conn.readTimeout = 60000
        conn.setRequestProperty("User-Agent", "GitHubK-Studio/4.0")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("下载失败 HTTP $code")
            val total = conn.contentLength.toLong()
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(128 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        done += read
                        if (total > 0) onProgress((done.toFloat() / total * 0.85f).coerceIn(0f, 0.85f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 解压 zip（参考 Termux BootstrapInstaller）。
     * 关键：设置可执行权限 + 可读权限。
     */
    private fun extract(zipFile: File, dest: File, onProgress: ((Float) -> Unit)? = null) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            var count = 0
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                val target = File(dest, name).canonicalFile
                val base = dest.canonicalFile
                require(target.path == base.path || target.path.startsWith(base.path + File.separator)) {
                    "Unsafe zip entry: $name"
                }
                if (name == "SYMLINKS.txt") {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { zis.copyTo(it) }
                } else if (entry.isDirectory) {
                    target.mkdirs()
                    target.setExecutable(true, false)
                    target.setReadable(true, false)
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { zis.copyTo(it) }
                    val isExec = name.startsWith("bin/") || name.startsWith("libexec/")
                    val isLib = name.startsWith("lib/") || name.endsWith(".so")
                    target.setReadable(true, false)
                    if (isExec || isLib) target.setExecutable(true, false)
                }
                count++
                if (count % 20 == 0) onProgress?.invoke((count % 1000) / 1000f)
                zis.closeEntry()
                entry = zis.nextEntry
            }
            onProgress?.invoke(1f)
        }
    }

    // ------------------------------------------------------------------
    // ELF 解释器重写：Termux bootstrap 二进制默认要求
    // /data/data/com.termux/files/usr/lib/ld-android.so 作为动态链接器。
    // 该文件位于“另一个 app”的私有数据中，untrusted_app/shell 均无法访问，
    // 因此会报 "not executable: 64-bit ELF file"，导致 bash/apt/git/java 全部
    // 无法运行。我们把它原位改写为系统 linker64（+ LD_LIBRARY_PATH 指向
    // $PREFIX/lib），即可在自身私有目录内直接执行这些二进制。
    private val ELF_PATCH_MARKER = ".githubk_elf_linker_patched_v1"
    private val ELF_OLD_INTERP_PREFIX = "/data/data/com.termux/"
    private val ELF_NEW_INTERP = "/system/bin/linker64".toByteArray(Charsets.UTF_8)

    private fun elfPatchMarker(): File = File(root, ELF_PATCH_MARKER)

    /** 幂等入口：marker 存在则跳过，避免每次执行前都全量扫描。 */
    fun ensureElfPatched(): Int {
        if (elfPatchMarker().exists()) return 0
        val n = runCatching { scanAndPatchElfInterpreters() }.getOrDefault(0)
        runCatching { elfPatchMarker().writeText(n.toString()) }
        return n
    }

    /** 安装新软件包后调用：无条件重新扫描并更新 marker。 */
    private fun repatchElfAfterInstall(): Int {
        val n = runCatching { scanAndPatchElfInterpreters() }.getOrDefault(0)
        runCatching { elfPatchMarker().writeText(n.toString()) }
        return n
    }

    private fun scanAndPatchElfInterpreters(): Int {
        if (!root.isDirectory) return 0
        var patched = 0
        root.walkTopDown().forEach { f ->
            if (java.nio.file.Files.isSymbolicLink(f.toPath())) return@forEach
            if (!f.isFile || f.length() < 64L) return@forEach
            if (patchSingleElfInterpreter(f)) patched++
        }
        return patched
    }

    /** 若 ELF 的 PT_INTERP 指向 com.termux 私有数据，则改写为系统 linker64。 */
    private fun patchSingleElfInterpreter(f: File): Boolean {
        return try {
            RandomAccessFile(f, "rw").use { raf ->
                raf.seek(0)
                val eh = ByteArray(64)
                raf.readFully(eh)
                if (eh[0] != 0x7f.toByte() || eh[1] != 'E'.code.toByte() ||
                    eh[2] != 'L'.code.toByte() || eh[3] != 'F'.code.toByte()
                ) return false
                if (eh[4] != 2.toByte() || eh[5] != 1.toByte()) return false // 仅 arm64 LE
                val phoff = le32(eh, 0x20)
                val phentsize = le16(eh, 0x36).toLong()
                val phnum = le16(eh, 0x38).toLong()
                if (phoff <= 0 || phnum <= 0 || phnum > 128 || phentsize < 56L) return false
                for (i in 0 until phnum) {
                    raf.seek(phoff + i * phentsize)
                    val ph = ByteArray(56)
                    raf.readFully(ph)
                    if (le32(ph, 0) != 3L) continue // PT_INTERP
                    val poff = le64(ph, 8)
                    val psz = le64(ph, 32)
                    if (psz > 512L) return false
                    raf.seek(poff)
                    val buf = ByteArray(psz.toInt())
                    raf.readFully(buf)
                    val interp = String(buf, Charsets.UTF_8)
                        .substringBefore('\u0000').trim { it <= ' ' }
                    if (interp.startsWith(ELF_OLD_INTERP_PREFIX)) {
                        if (ELF_NEW_INTERP.size + 1 > buf.size) return false
                        raf.seek(poff)
                        raf.write(ELF_NEW_INTERP)
                        var j = ELF_NEW_INTERP.size
                        while (j < buf.size) {
                            raf.write(0)
                            j++
                        }
                        return true
                    }
                    return false
                }
                false
            }
        } catch (_: Throwable) { false }
    }

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xffL) or
            ((b[o + 1].toLong() and 0xffL) shl 8) or
            ((b[o + 2].toLong() and 0xffL) shl 16) or
            ((b[o + 3].toLong() and 0xffL) shl 24)

    private fun le64(b: ByteArray, o: Int): Long =
        (le32(b, o) and 0xffffffffL) or (le32(b, o + 4) shl 32)

    /** 修补 ELF RUNPATH：将 com.termux/lib 替换为 $ORIGIN/../lib，使动态链接器能从 runtime/lib 加载库。 */
    private fun patchElfRunpath() {
        if (!root.isDirectory) return
        val needle = "/data/data/com.termux/files/usr/lib"
        val replacement = "\$ORIGIN/../lib"
        root.walkTopDown().forEach { f ->
            if (java.nio.file.Files.isSymbolicLink(f.toPath())) return@forEach
            if (!f.isFile || f.length() < 64L) return@forEach
            try {
                RandomAccessFile(f, "rw").use { raf ->
                    val eh = ByteArray(64)
                    raf.seek(0)
                    raf.readFully(eh)
                    if (eh[0] != 0x7f.toByte() || eh[1] != 'E'.code.toByte() ||
                        eh[2] != 'L'.code.toByte() || eh[3] != 'F'.code.toByte()
                    ) return@use
                    if (eh[4] != 2.toByte() || eh[5] != 1.toByte()) return@use // arm64 LE
                    val phoff = le32(eh, 0x20)
                    val phentsize = le16(eh, 0x36).toLong()
                    val phnum = le16(eh, 0x38).toLong()
                    if (phoff <= 0 || phnum <= 0 || phnum > 128 || phentsize < 56L) return@use
                    // 找 PT_DYNAMIC 段和 dynstr 范围
                    var dynOff = -1L
                    var dynSz = 0L
                    var strOff = -1L
                    var strSz = 0L
                    for (i in 0 until phnum) {
                        raf.seek(phoff + i * phentsize)
                        val ph = ByteArray(56)
                        raf.readFully(ph)
                        if (le32(ph, 0) == 2L) { // PT_DYNAMIC
                            dynOff = le64(ph, 8)
                            dynSz = le64(ph, 32)
                        }
                    }
                    if (dynOff < 0 || dynSz < 64) return@use
                    // 解析动态段找 DT_STRTAB 和 DT_STRSZ
                    var dtStrTab = -1L
                    var dtStrSz = 0L
                    var dynEnd = dynOff + dynSz
                    var pos = dynOff
                    while (pos + 16 <= dynEnd) {
                        raf.seek(pos)
                        val tag = le64(ByteArray(8).also { raf.readFully(it) }, 0)
                        val val_ = le64(ByteArray(8).also { raf.readFully(it) }, 0)
                        if (tag == 5L) dtStrTab = val_      // DT_STRTAB
                        else if (tag == 10L) dtStrSz = val_ // DT_STRSZ
                        pos += 16
                    }
                    if (dtStrTab < 0 || dtStrSz <= 0) return@use
                    // 映射 vaddr -> file offset
                    var strFileOff = -1L
                    for (i in 0 until phnum) {
                        raf.seek(phoff + i * phentsize)
                        val ph = ByteArray(56)
                        raf.readFully(ph)
                        if (le32(ph, 0) != 1L) continue // PT_LOAD
                        val vaddr = le64(ph, 16)
                        val memsz = le64(ph, 48)
                        val off = le64(ph, 8)
                        if (dtStrTab >= vaddr && dtStrTab < vaddr + memsz) {
                            strFileOff = off + (dtStrTab - vaddr)
                            break
                        }
                    }
                    if (strFileOff < 0) return@use
                    // 在 dynstr 范围内查找并替换 needle
                    raf.seek(strFileOff)
                    val data = ByteArray(strSz.toInt())
                    raf.readFully(data)
                    var found = false
                    var idx = 0
                    while (idx + needle.length <= data.size) {
                        val match = (0 until needle.length).all { data[idx + it] == needle[it].code.toByte() }
                        if (match) {
                            found = true
                            // 写 replacement，然后清零剩余部分（原 needle 长度）
                            val replBytes = replacement.toByteArray(Charsets.US_ASCII)
                            for (j in replBytes.indices) data[idx + j] = replBytes[j]
                            for (j in replBytes.size until needle.length) data[idx + j] = 0
                            idx += needle.length
                        } else {
                            idx++
                        }
                    }
                    if (found) {
                        raf.seek(strFileOff)
                        raf.write(data)
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * 自研 deb 安装器：替代 dpkg，将 assets/offline-toolchain 下的 .deb 解包到 runtime。
     * 1. 解析 ar 格式提取 data.tar.xz
     * 2. 用 runtime/bin/xz -dc + tar -x 解压到临时目录
     * 3. 移动 usr 内容到 runtime 根，创建 openjdk bin 链接
     */
    private fun installOfflineDebs() {
        val archiveDir = File(root, "var/cache/apt/archives")
        archiveDir.mkdirs()

        // 从 assets 复制 deb 到 cache
        val debNames = try {
            c.assets.list("offline-toolchain")?.filter { it.endsWith(".deb") }?.sorted() ?: return
        } catch (_: Throwable) { return }

        var copied = 0
        val copiedFiles = mutableListOf<File>()
        debNames.forEach { name ->
            val target = File(archiveDir, name)
            if (!target.exists() || target.length() < 1024L) {
                try {
                    c.assets.open("offline-toolchain/$name").use { input ->
                        target.outputStream().use { out -> input.copyTo(out) }
                    }
                } catch (_: Throwable) { return@forEach }
            }
            if (target.exists() && target.length() > 1024L) {
                copiedFiles.add(target)
                copied++
            }
        }
        if (copiedFiles.isEmpty()) return

        // 确保 xz/tar 已准备好（RUNPATH 已 patch，符号链接已创建）
        val workDir = File(root, ".deb_extract")
        workDir.mkdirs()

        copiedFiles.forEach { deb ->
            try {
                // 提取 data.tar.xz
                val dataTar = File(workDir, "${deb.nameWithoutExtension}.data.tar.xz")
                if (!dataTar.exists()) {
                    extractArMember(deb, "data.tar.xz", dataTar)
                }
                if (!dataTar.exists()) return@forEach

                // 解压到 stage
                val stage = File(workDir, "stage_${deb.nameWithoutExtension}")
                stage.mkdirs()
                val cmd = buildString {
                    append("export PREFIX='$prefix' HOME='$home' TMPDIR='$tmp' PATH='$prefix/bin:/system/bin'; ")
                    append("cd '${stage.absolutePath}' && ")
                    append("xz -dc '${dataTar.absolutePath}' | tar -x 2>/dev/null; ")
                    append("echo __done__")
                }
                val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
                pb.environment().putAll(toolchainEnv())
                pb.redirectErrorStream(true)
                val p = pb.start()
                p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
                p.destroy()

                // 移动 usr/* 到 runtime 根
                val usrDir = File(stage, "data/data/com.termux/files/usr")
                if (usrDir.exists() && usrDir.isDirectory) {
                    usrDir.listFiles()?.forEach { src ->
                        val dst = File(root, src.name)
                        if (src.isDirectory) {
                            src.copyRecursively(dst, overwrite = true)
                        } else {
                            src.copyTo(dst, overwrite = true)
                        }
                    }
                }
                // 清理 stage
                try { stage.deleteRecursively() } catch (_: Throwable) {}
                try { dataTar.delete() } catch (_: Throwable) {}
            } catch (_: Throwable) {}
        }

        // 清理 workDir
        try { workDir.deleteRecursively() } catch (_: Throwable) {}

        // 补丁所有新 ELF
        try { patchElfRunpath() } catch (_: Throwable) {}

        // 创建 openjdk bin 链接
        createOpenJdkLinks()
    }

    /** 从 ar 归档中提取指定成员 */
    private fun extractArMember(arFile: File, memberName: String, output: File) {
        RandomAccessFile(arFile, "r").use { raf ->
            // ar 魔数 "!<arch>\n"
            val magic = ByteArray(8)
            raf.readFully(magic)
            if (!magic.contentEquals("!<arch>\n".toByteArray())) return
            while (raf.filePointer < raf.length()) {
                val header = ByteArray(60)
                raf.readFully(header)
                val name = String(header, 0, 16, Charsets.US_ASCII).trim()
                val sizeStr = String(header, 48, 10, Charsets.US_ASCII).trim()
                val size = sizeStr.toLongOrNull() ?: 0L
                if (name == memberName) {
                    // 提取内容
                    output.parentFile?.mkdirs()
                    output.outputStream().use { out ->
                        val buf = ByteArray(8192)
                        var remaining = size
                        while (remaining > 0) {
                            val chunk = buf.size.coerceAtMost(remaining.toInt()).toInt()
                            val read = raf.read(buf, 0, chunk)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                            remaining -= read
                        }
                    }
                    return
                } else {
                    // 跳过内容 + 对齐
                    raf.seek(raf.filePointer + size + if (size % 2 == 1L) 1 else 0)
                }
            }
        }
    }

    /** 为 openjdk 创建 bin 链接（模拟 update-alternatives） */
    private fun createOpenJdkLinks() {
        val jvmDir = File(root, "lib/jvm")
        if (!jvmDir.exists()) return
        val jvms = jvmDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("java-") }?.sortedBy { it.name } ?: return
        if (jvms.isEmpty()) return
        // 选最新版本（如 java-21-openjdk）
        val defaultJvm = jvms.lastOrNull { it.name.contains("openjdk") } ?: jvms.last()
        val binDir = File(root, "bin")
        binDir.mkdirs()
        val jvmBin = File(defaultJvm, "bin")
        if (!jvmBin.exists()) return
        jvmBin.listFiles()?.forEach { tool ->
            if (tool.isFile && tool.canExecute()) {
                val link = File(binDir, tool.name)
                if (!link.exists()) {
                    try {
                        java.nio.file.Files.createSymbolicLink(
                            link.toPath(),
                            tool.toPath()
                        )
                    } catch (_: Throwable) {
                        // fallback: 复制文件
                        try {
                            tool.copyTo(link, overwrite = true)
                            link.setExecutable(true, false)
                        } catch (_: Throwable) {}
                    }
                }
            }
        }
    }

    /** 创建 home/tmp，处理符号链接，写 .bashrc。 */
    private fun prepare() {
        // apt/dpkg 崩溃残留锁清理（proot/杀进程后 lock 可能残留导致后续包管理卡死）
        cleanupDpkgLocks()
        try { home.mkdirs() } catch (_: Throwable) {}
        try { tmp.mkdirs() } catch (_: Throwable) {}
        // 递归设置目录权限
        try { setDirPermissionsRecursive(root) } catch (_: Throwable) {}

        // Official Termux bootstraps are built for com.termux. A fork must rebuild
        // packages for its own prefix; for this embedded runtime we aggressively
        // rewrite textual launcher/config paths and provide our own pkg wrapper.
        rewriteTermuxPrefixReferences()
        installPortablePkgWrapper()
        // apt 信任密钥：bootstrap 的 trusted.gpg.d/ 为空，必须补入 termux-keyring
        deployTrustedGpgKeys()
        // 默认软件源：优先部署官方 packages-cf 配置；安装/更新失败时 installPackages()
        // 会按 APT_SOURCES 顺序自动切换国内镜像重试（ZeroTermux 模型）。
        deployTermuxAptSources()

        // 清理 bootstrap 二阶段残留：本 runtime 由 App 解压 + prepare() 完成全部
        // 符号链接/权限/解释器处理，不再需要 Termux 官方二阶段。若 01-fallback 存在，
        // 每次 bash -l 登录都会尝试运行二阶段（产生 shebang 报错与登录延迟），必须删除。
        try {
            File(root, "etc/profile.d/01-termux-bootstrap-second-stage-fallback.sh").delete()
            val secondStage = File(root, "etc/termux/termux-bootstrap")
            if (secondStage.exists()) secondStage.deleteRecursively()
        } catch (_: Throwable) {}

        // 处理 SYMLINKS.txt
        // 格式：TARGET ← LINKPATH  （左=链接目标，右=链接文件路径）
        try {
            val f = File(root, "SYMLINKS.txt")
            if (f.exists()) {
                f.readLines().forEach { line ->
                    val idx = line.indexOf('←')
                    if (idx > 0) {
                        val target = line.substring(0, idx).trim()
                        val linkPath = line.substring(idx + 1).trim()
                        // linkPath 可能以 ./ 开头或为绝对 com.termux 路径
                        val localLink = linkPath
                            .removePrefix("./")
                            .removePrefix(TERMUX_PREFIX)
                            .removePrefix("/")
                        val linkFile = File(root, localLink)
                        if (!linkFile.exists()) {
                            try {
                                linkFile.parentFile?.mkdirs()
                                // 处理 target
                                // 若 target 是绝对 com.termux 路径，转为 runtime 绝对路径
                                // 若 target 是相对路径（如 "coreutils"），保留为相对字符串，
                                // Files.createSymbolicLink 会相对于 link 所在目录解析
                                val targetPath = if (target.startsWith(TERMUX_PREFIX)) {
                                    File(root, target.removePrefix(TERMUX_PREFIX).removePrefix("/")).toPath()
                                } else if (target.startsWith("/")) {
                                    // 其他绝对路径（如 /system/bin/env），直接使用
                                    File(target).toPath()
                                } else {
                                    // 相对路径，保持字符串，Java 会相对于 link 所在目录解析
                                    java.nio.file.Paths.get(target)
                                }
                                Files.createSymbolicLink(linkFile.toPath(), targetPath)
                            } catch (_: Throwable) {
                                // 符号链接失败时尝试复制文件
                                try {
                                    val relTarget = relativeToRoot(target)
                                    val targetFile = File(root, relTarget)
                                    if (targetFile.exists() && targetFile.isFile) {
                                        linkFile.parentFile?.mkdirs()
                                        targetFile.copyTo(linkFile, overwrite = true)
                                        linkFile.setExecutable(true, false)
                                        linkFile.setReadable(true, false)
                                    }
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // 修复 ELF RUNPATH：将 com.termux/lib 替换为 $ORIGIN/../lib
        try { patchElfRunpath() } catch (_: Throwable) {}

        // 安装离线工具链 deb（自研解包器，不使用 dpkg）
        try { installOfflineDebs() } catch (_: Throwable) {}

        // .bashrc
        try {
            File(home, ".bashrc").writeText(buildString {
                append("export PREFIX=$prefix\n")
                append("export HOME=$home\n")
                append("export TMPDIR=$tmp\n")
                append("export PATH=$prefix/bin:$prefix/bin/applets:/system/bin:/system/xbin\n")
                append("export LD_LIBRARY_PATH=$prefix/lib\n")
                append("export LANG=C.UTF-8\n")
                append("export TERM=xterm-256color\n")
                append("export TERMUX_VERSION=0.118\n")
                append("alias ll='ls -la'\n")
                append("alias la='ls -a'\n")
                append("alias ..='cd ..'\n")
                append("alias grep='grep --color=auto'\n")
                append("PS1='\\[\\e[32m\\]\\u@githubk\\[\\e[0m\\]:\\[\\e[34m\\]\\w\\[\\e[0m\\]\\$ '\n")
            })
        } catch (_: Throwable) {}

        // .profile
        try {
            File(home, ".profile").writeText(
                "[ -f ~/.bashrc ] && source ~/.bashrc\n"
            )
        } catch (_: Throwable) {}

        // 修复 shebang 路径（将 /data/data/com.termux/ 替换为实际路径）
        fixShebangs()

        // 重写 ELF 解释器（com.termux 私有路径 -> /system/bin/linker64）
        try { ensureElfPatched() } catch (_: Throwable) {}

        // 创建关键符号链接 bin/sh -> bin/bash
        try {
            val shLink = File(root, "bin/sh")
            if (!shLink.exists()) {
                Files.createSymbolicLink(shLink.toPath(), File(root, "bin/bash").toPath())
            }
        } catch (_: Throwable) {}

        // 创建 bin/env 符号链接（指向 /system/bin/env 或 busybox env）
        try {
            val envLink = File(root, "bin/env")
            if (!envLink.exists()) {
                val target = File("/system/bin/env")
                if (target.exists()) {
                    Files.createSymbolicLink(envLink.toPath(), target.toPath())
                }
            }
        } catch (_: Throwable) {}
    }

    private fun rewriteTermuxPrefixReferences() {
        val oldRoot = "/data/data/com.termux/files"
        val oldPrefix = "/data/data/com.termux/files/usr"
        val newRoot = root.absolutePath
        val newPrefix = prefix
        // 注意：必须先替换更具体的 oldPrefix（/data/data/com.termux/files/usr），
        // 再替换 oldRoot（/data/data/com.termux/files）。顺序颠倒会把
        // /data/data/com.termux/files/usr/bin/... 先写成 <runtime>/usr/bin/...，
        // 而本 runtime 目录本身就是 Termux $PREFIX(=usr 内容) 解压所得，
        // 顶层不存在 usr/，导致 profile/bash.bashrc/motd 等路径全部错位失效。
        val roots = listOf(File(root, "bin"), File(root, "libexec"), File(root, "etc"), File(root, "share"))
        roots.filter { it.exists() }.forEach { dir ->
            dir.walkTopDown().filter { it.isFile && it.length() <= 2L * 1024L * 1024L }.forEach { f ->
                runCatching {
                    val bytes = f.readBytes()
                    if (bytes.any { it == 0.toByte() }) return@runCatching
                    val text = bytes.toString(Charsets.UTF_8)
                    if (text.contains(oldRoot) || text.contains(oldPrefix) || text.contains("$newRoot/usr/")) {
                        var out = text
                        out = out.replace(oldPrefix, newPrefix)   // 先：完整旧前缀
                        out = out.replace(oldRoot, newRoot)       // 后：一般旧根
                        // 自愈：历史 bug 产生的 <runtime>/usr/ 错位段（runtime 本身即 usr 内容）
                        out = out.replace("$newRoot/usr/", "$newRoot/")
                        f.writeText(out, Charsets.UTF_8)
                    }
                }
            }
        }
        runCatching {
            val aptEtc = File(root, "etc/apt")
            aptEtc.mkdirs()
            // 清理 sources.list.d 旧配置，避免双源冲突
            File(aptEtc, "sources.list.d").takeIf { it.isDirectory }?.listFiles()?.forEach { runCatching { it.delete() } }
            File(aptEtc, "sources.list").writeText(APT_SOURCES.first() + "\n")
        }
    }

    /**
     * 部署 bin/pkg：优先【保留官方 Termux pkg 脚本】（具备 root 检查、镜像检测、
     * termux-change-repo 联动与包管理分发能力），不再用简易 wrapper 覆盖官方脚本。
     *
     * 覆盖策略（自上而下）：
     * 1. 若 runtime/bin/pkg 已是官方脚本（体积 > 3KB 且引用 termux-setup-package-manager），
     *    原样保留 —— 这才是原生 Termux 体验（pkg install/search/show/list-all 全支持）。
     * 2. 官方脚本缺失或被旧版简易 wrapper 覆盖时，从 APK assets/termux-conf/bin/pkg 兜底恢复。
     * 3. assets 也没有时才回退到简易 apt wrapper（保证 pkg 至少有 install/update 可用）。
     */
    private fun installPortablePkgWrapper() {
        val pkg = File(root, "bin/pkg")
        val officialPkgPresent = try {
            pkg.isFile && pkg.length() > 3_000L &&
                pkg.readBytes().toString(Charsets.UTF_8).contains("termux-setup-package-manager")
        } catch (_: Throwable) { false }
        if (officialPkgPresent) return
        // 从 APK assets 兜底恢复官方 pkg 脚本
        val restored = runCatching {
            c.assets.open("termux-conf/bin/pkg").use { input ->
                pkg.parentFile?.mkdirs()
                FileOutputStream(pkg).use { out -> input.copyTo(out) }
            }
            pkg.setExecutable(true, false)
            pkg.setReadable(true, false)
            pkg.length() > 3_000L
        }.getOrDefault(false)
        if (restored) return
        // 最终兜底：简易 wrapper（仅保证基本命令可用）
        runCatching {
            pkg.writeText("#!/bin/bash\nexport PREFIX=\"$prefix\"\nexport TERMUX_PREFIX=\"$prefix\"\nexport HOME=\"$home\"\nexport PATH=\"$prefix/bin:$prefix/bin/applets:/system/bin:/system/xbin\"\ncase \"$1\" in\n  install|reinstall|remove|uninstall|update|upgrade|full-upgrade|search|show|list) exec $prefix/bin/apt \"$@\" ;;\n  *) exec $prefix/bin/apt \"$@\" ;;\nesac\n")
            pkg.setExecutable(true, false)
            pkg.setReadable(true, false)
        }
    }

    /**
     * 部署 apt 信任密钥（Termux 官方仓库 GPG）到 etc/apt/trusted.gpg.d/。
     *
     * bootstrap 内 termux-keyring 的密钥实际只解包到 usr/share/termux-keyring/，
     * 而 etc/apt/trusted.gpg.d/ 是空目录 —— 直接运行 apt update 会因缺少受信
     * 公钥而失败（NO_PUBKEY）。这里把 keyring 复制到 apt 信任目录，并优先使用
     * APK assets/termux-conf/trusted-gpg.d/ 的官方密钥兜底。
     */
    private fun deployTrustedGpgKeys() {
        val gpgDir = File(root, "etc/apt/trusted.gpg.d")
        try { gpgDir.mkdirs() } catch (_: Throwable) {}
        var deployed = 0
        // 1) 运行时自带 keyring（bootstrap 解压所得）
        try {
            val keyring = File(root, "share/termux-keyring")
            if (keyring.isDirectory) {
                keyring.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.endsWith(".gpg")) {
                        val dst = File(gpgDir, f.name)
                        if (!dst.exists() || dst.length() != f.length()) {
                            f.copyTo(dst, overwrite = true)
                        }
                        if (!dst.exists() || dst.length() < 128L) return@forEach
                        dst.setReadable(true, false)
                        deployed++
                    }
                }
            }
        } catch (_: Throwable) {}
        // 2) assets 兜底（防止 runtime keyring 缺失时 apt 完全无信任源）
        if (deployed == 0) {
            try {
                val names = c.assets.list("termux-conf/trusted-gpg.d") ?: emptyArray()
                names.filter { it.endsWith(".gpg") }.forEach { name ->
                    runCatching {
                        val dst = File(gpgDir, name)
                        if (!dst.exists() || dst.length() < 128L) {
                            c.assets.open("termux-conf/trusted-gpg.d/$name").use { input ->
                                FileOutputStream(dst).use { out -> input.copyTo(out) }
                            }
                            dst.setReadable(true, false)
                        }
                        deployed++
                    }
                }
            } catch (_: Throwable) {}
        }
        // 3) 清理可能的 .asc/.txt 非密钥残留
        runCatching {
            gpgDir.listFiles()?.forEach { f ->
                if (f.isFile && !f.name.endsWith(".gpg")) runCatching { f.delete() }
            }
        }
    }

    /** 清除 apt/dpkg 崩溃残留的锁文件（进程被杀后 lock 不会自动释放）。 */
    fun cleanupDpkgLocks() {
        val locks = listOf(
            File(root, "var/lib/dpkg/lock"),
            File(root, "var/lib/dpkg/lock-frontend"),
            File(root, "var/lib/apt/lists/lock"),
            File(root, "var/cache/apt/archives/lock")
        )
        locks.forEach { f -> runCatching { if (f.exists()) f.delete() } }
    }

    /**
     * 部署 /etc/apt/sources.list：优先 APK assets/termux-conf/apt/sources.list
     * （官方 Termux 仓库 packages-cf / packages.termux.dev，bootstrap 同款），
     * assets 缺失时写入内置官方源字符串。
     */
    private fun deployTermuxAptSources() {
        runCatching {
            val aptEtc = File(root, "etc/apt")
            aptEtc.mkdirs()
            // sources.list.d 清空，避免多源冲突
            File(aptEtc, "sources.list.d").takeIf { it.isDirectory }?.listFiles()?.forEach { runCatching { it.delete() } }
            val f = File(aptEtc, "sources.list")
            var ok = false
            try {
                c.assets.open("termux-conf/apt/sources.list").use { input ->
                    FileOutputStream(f).use { out -> input.copyTo(out) }
                }
                ok = f.exists() && f.length() > 64L && f.readText(Charsets.UTF_8).contains("termux")
            } catch (_: Throwable) {}
            if (!ok) {
                f.writeText(
                    "# The main termux repository, with cloudflare cache\n" +
                    "deb https://packages-cf.termux.dev/apt/termux-main/ stable main\n" +
                    "# The main termux repository, without cloudflare cache\n" +
                    "# deb https://packages.termux.dev/apt/termux-main/ stable main\n"
                )
            }
        }
    }

    // ==================== Termux 终端包管理（apt update / pkg） ====================

    private fun aptPendingMarker(): File = File(root, "etc/termux/.githubk_apt_update_pending")

    /** 标记“首次安装后需要 apt update”（lists 尚未拉取）。 */
    fun markTermuxAptUpdatePending() {
        runCatching {
            val f = aptPendingMarker()
            f.parentFile?.mkdirs()
            f.writeText("1")
        }
    }

    fun isTermuxAptUpdatePending(): Boolean = runCatching { aptPendingMarker().isFile }.getOrDefault(false)

    /** var/lib/apt/lists 是否已含有效（非空）列表文件。 */
    fun termuxAptListsReady(): Boolean = runCatching {
        val d = File(root, "var/lib/apt/lists")
        d.isDirectory && (d.listFiles()?.any { it.isFile && it.length() > 128L } ?: false)
    }.getOrDefault(false)

    /** pkg 命令与软件源/GPG 信任链是否就绪（终端包管理器状态）。 */
    fun termuxPkgReady(): Boolean = runCatching {
        val pkg = File(root, "bin/pkg")
        val bash = File(root, "bin/bash")
        val sources = File(root, "etc/apt/sources.list")
        val gpgDir = File(root, "etc/apt/trusted.gpg.d")
        bash.isFile && pkg.isFile &&
            sources.isFile && sources.readText(Charsets.UTF_8).contains("deb ") &&
            (gpgDir.listFiles()?.any { it.isFile && it.name.endsWith(".gpg") } ?: false)
    }.getOrDefault(false)

    /**
     * 更新 Termux 软件源（apt-get update）。保留用户已选的 sources.list；
     * 全部镜像失败后自动按 APT_SOURCES 顺序切换（清华/中科大/北外/官方）。
     * force=false 时若 lists 已就绪且无待办标记则直接跳过（后台/首启场景）；
     * force=true 强制重新拉取（用户显式点击“更新软件源”）。
     * 返回 true 表示 lists 已就绪。
     */
    fun updateTermuxRepositories(onOutput: (String) -> Unit = {}, force: Boolean = false): Boolean {
        if (!isInstalled()) {
            onOutput("[TERMUX] 内置终端运行时尚未安装\n")
            return false
        }
        cleanupDpkgLocks()
        try { prepare() } catch (_: Throwable) {}
        try { forceFixPermissions() } catch (_: Throwable) {}
        if (!force && termuxAptListsReady() && !isTermuxAptUpdatePending()) {
            onOutput("[TERMUX] 软件源 lists 已就绪，跳过 apt update\n")
            return true
        }
        onOutput("[TERMUX] 准备 apt-get update（Termux 官方源 + 多镜像自动切换）…\n")
        val candidates = APT_SOURCES.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        val cmd = buildString {
            append("export DEBIAN_FRONTEND=noninteractive; ")
            append("export PREFIX='").append(prefix.replace("'", "'\\''")).append("'; ")
            append("export HOME='").append(home.absolutePath.replace("'", "'\\''")).append("'; ")
            append("export PATH='").append(prefix).append("/bin:").append(prefix).append("/bin/applets:/system/bin:/system/xbin'; ")
            append("export TMPDIR='").append(tmp.absolutePath.replace("'", "'\\''")).append("'; ")
            append("command -v apt-get >/dev/null 2>&1 || { echo '[TERMUX] apt-get 不存在，请重新安装内置运行时'; exit 127; }; ")
            // 保留当前 sources.list（含官方/镜像选择）；缺失才写官方源
            append("if ! grep -q '^deb ' \"\$PREFIX/etc/apt/sources.list\" 2>/dev/null; then rm -rf \"\$PREFIX/etc/apt/sources.list.d\"; mkdir -p \"\$PREFIX/etc/apt\"; echo '").append(APT_SOURCES.first().replace("'", "'\\''")).append("' > \"\$PREFIX/etc/apt/sources.list\"; fi; ")
            append("apt-get update -y || { ok=0; for src in ").append(candidates).append("; do echo \"\$src\" > \"\$PREFIX/etc/apt/sources.list\"; if apt-get update -y; then ok=1; break; fi; echo '[TERMUX] 软件源不可用，尝试下一个镜像'; done; [ \"\$ok\" = 1 ] || { echo '[TERMUX] 所有软件源均不可用'; exit 1; }; }; ")
            append("echo '__apt_update_done__'")
        }
        val ok = runStreaming(cmd, onOutput)
        if (ok) {
            runCatching { aptPendingMarker().delete() }
            onOutput("[TERMUX] apt update 完成 ✓\n")
        } else {
            onOutput("[TERMUX] apt update 未完成（网络/镜像不可达），后续可用 pkg 命令时重试\n")
            markTermuxAptUpdatePending()
        }
        return ok
    }

    /**
     * 首次安装后自动后台执行 apt update（不阻塞 UI）。
     * 仅在 proot Termux 沙盒通道可用时自动执行，避免后台无提示弹 Shizuku 授权；
     * UI 显式入口（工具页/终端抽屉）调用 updateTermuxRepositories() 时再走完整通道。
     */
    fun scheduleAptUpdateIfPending(onOutput: (String) -> Unit = {}): Boolean {
        if (!isTermuxAptUpdatePending()) return false
        if (termuxAptListsReady()) {
            runCatching { aptPendingMarker().delete() }
            return false
        }
        if (!sandboxAvailable()) return false
        runCatching { aptPendingMarker().delete() } // 防并发重复触发
        Thread {
            try {
                updateTermuxRepositories(onOutput = { s -> onOutput("[TERMUX] " + s) })
            } catch (_: Throwable) {}
        }.apply { isDaemon = true }.start()
        return true
    }

    /**
     * 校验 APK assets/offline-toolchain 内置 .deb 的 SHA-256 是否与打包清单一致，
     * 防止“伪安装/被替换/混入 Debian 包”（校验不通过返回 false）。
     * assets 无 SHA256SUMS.txt 时不做强校验（兼容旧包），仅返回 true。
     */
    private fun verifyBundledDeb(deb: File): Boolean {
        return runCatching {
            val sumLines = c.assets.open("offline-toolchain/SHA256SUMS.txt").use { input ->
                input.bufferedReader(Charsets.UTF_8).readLines()
            }
            val expected = sumLines.firstNotNullOfOrNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size == 2 && parts[1] == deb.name) parts[0] else null
            } ?: return true
            val digest = sha256(deb)
            digest == expected.lowercase()
        }.getOrDefault(true)
    }

    /** 递归设置目录权限为 755。 */
    private fun setDirPermissionsRecursive(dir: File) {
        dir.walkTopDown().forEach { f ->
            try {
                if (f.isDirectory) {
                    f.setExecutable(true, false)
                    f.setReadable(true, false)
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * 强制修复 runtime 下所有文件的可执行权限。
     * APK 解压后原生二进制可能丢失 exec 位（error=13 Permission denied），
     * 此方法遍历 bin/、libexec/、lib/ 下所有文件并设置 755 权限。
     * 同时尝试通过 Shizuku 以 shell 用户身份执行 chmod（更可靠）。
     */
    private fun forceFixPermissions() {
        // 方案一：Java 层 setExecutable + Android 系统 chmod。
        // 某些 Android 设备/ROM 在 ZIP 解压后不会保留 exec 位，导致
        // /system/bin/sh: .../bin/apt: Permission denied。
        val dirs = listOf("bin", "libexec", "lib", "share")
        for (sub in dirs) {
            val d = File(root, sub)
            if (!d.exists()) continue
            d.walkTopDown().forEach { f ->
                try {
                    f.setReadable(true, false)
                    f.setWritable(true, false)
                    if (f.isFile) {
                        // 对 bin/ 和 libexec/ 下的文件设置可执行
                        if (sub == "bin" || sub == "libexec" || sub == "lib") {
                            f.setExecutable(true, false)
                        }
                    }
                    if (f.isDirectory) {
                        f.setExecutable(true, false)
                    }
                } catch (_: Throwable) {}
            }
        }
        // 确保 bash 本身有执行权限
        try {
            val bash = File(root, "bin/bash")
            if (bash.exists()) {
                bash.setExecutable(true, false)
                bash.setReadable(true, false)
            }
        } catch (_: Throwable) {}

        // 方案二：直接调用 Android 自带 chmod。先于 Shizuku 执行，避免
        // shell/UserService 继承到错误的文件 mode。
        runCatching {
            val chmod = ProcessBuilder("/system/bin/chmod", "-R", "755", File(root, "bin").absolutePath, File(root, "libexec").absolutePath)
                .redirectErrorStream(true).start()
            chmod.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        }
        // .so 不需要执行位；保留 644。
        runCatching {
            ProcessBuilder("/system/bin/chmod", "-R", "755", File(root, "lib").absolutePath)
                .redirectErrorStream(true).start().waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        }

        // 方案三：如果有 Shizuku，用 shell 用户 chmod（更可靠）
        try {
            if (ShizukuShell.isServiceAvailable() && ShizukuShell.hasPermission()) {
                val runtimePath = root.absolutePath
                val cmd = "chmod -R 755 ${runtimePath}/bin ${runtimePath}/libexec ${runtimePath}/lib 2>/dev/null; " +
                          "echo __done__"
                ShizukuShell.execAsApp(cmd, timeoutSec = 30)
            }
        } catch (_: Throwable) {}
    }

    private fun relativeToRoot(target: String): String {
        val t = target.removePrefix("./")
        return if (t.startsWith(TERMUX_PREFIX)) t.removePrefix(TERMUX_PREFIX).removePrefix("/")
        else t
    }

    /** 在内置运行时中执行一条命令（非交互）。
     *  方案二：优先用 /system/bin/sh 执行（绕过 bash linker 兼容性问题）。
     *  如果 pkg 等脚本需要 bash 特性，通过 sh -c "bash -c '...'" 间接调用。
     */
    fun run(command: String): String {
        if (!isInstalled()) return ""
        try { ensureElfPatched() } catch (_: Throwable) {}
        // 优先 proot Termux 沙盒（华为/鸿蒙无需 Shizuku 即可执行内置二进制）
        if (sandboxAvailable()) {
            val out = TermuxSandbox.runCapture(c, root, command, toolchainEnv(), 90)
            if (out.isNotEmpty()) return out
        }
        return try {
            // 方案二：用系统 sh 执行，设置 PATH 指向 runtime/bin
            val shPath = "/system/bin/sh"
            val pb = ProcessBuilder(shPath, "-c", command)
            pb.environment().putAll(toolchainEnv())
            // 确保 SHELL 指向 runtime bash（供脚本内部引用）
            pb.environment()["SHELL"] = "${root.absolutePath}/bin/bash"
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            out.trim()
        } catch (_: Throwable) { "" }
    }

    /**
     * 启动交互式终端（方案二）。
     * 使用 /system/bin/sh -i 作为交互式 shell，绕过 bash 二进制的 linker 兼容性问题。
     * 通过 ENV 设置 PATH/LD_LIBRARY_PATH 指向 runtime，使 pkg/git 等工具可用。
     * 返回 Process 或 null。
     */
    fun startInteractive(): Process? {
        if (!isInstalled()) return null
        try { ensureElfPatched() } catch (_: Throwable) {}
        try { forceFixPermissions() } catch (_: Throwable) {}
        return try {
            // 先尝试 bash 直接执行（某些设备可能支持）
            if (canDirectExecBash()) {
                val bash = File(root, "bin/bash").absolutePath
                val pb = ProcessBuilder(bash, "--rcfile", File(home, ".bashrc").absolutePath, "-i")
                pb.environment().putAll(toolchainEnv())
                pb.environment()["SHELL"] = bash
                pb.directory(home)
                pb.redirectErrorStream(true)
                return pb.start()
            }
            // 兜底：用系统 sh 作为交互式 shell
            val shPath = "/system/bin/sh"
            val pb = ProcessBuilder(shPath, "-i")
            pb.environment().putAll(toolchainEnv())
            pb.environment()["SHELL"] = "${root.absolutePath}/bin/bash"
            pb.environment()["PS1"] = "\\u@githubk:\\w\\$ "
            pb.directory(home)
            pb.redirectErrorStream(true)
            pb.start()
        } catch (_: Throwable) { null }
    }

    /** 测试 bash 是否可直接 exec（无 linker 错误）。 */
    private fun canDirectExecBash(): Boolean {
        // 测试前先确保权限
        try { forceFixPermissions() } catch (_: Throwable) {}
        val bash = File(root, "bin/bash").absolutePath
        return try {
            val pb = ProcessBuilder(bash, "-c", "echo __ok__")
            pb.environment().putAll(toolchainEnv())
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            out.contains("__ok__")
        } catch (_: Throwable) { false }
    }

    /**
     * 修正 ID 中 `$PREFIX/bin/java` 等软链：清掉失效/指向 Termux alternatives
     * （/data/data/com.termux/...，在本 App 私有 runtime 内不存在）的旧软链，
     * 重建为指向 `$PREFIX/lib/jvm/<jdk>/bin` 的真实工具，并给 JDK bin 赋可执行位。
     * 保证默认 PATH（$PREFIX/bin）下 `java` 可用（Termux openjdk 默认不建软链）。
     */
    fun ensureJavaSymlinks(): Int {
        var linked = 0
        val binDir = File(root, "bin")
        val jvmDir = File(root, "lib/jvm")
        if (!binDir.isDirectory || !jvmDir.isDirectory) return 0
        // 选一个包含真实 bin/java 的 JDK（优先 java-17，其次 java-21）
        val jdk = jvmDir.listFiles()?.firstOrNull { File(it, "bin/java").isFile } ?: return 0
        val jbin = File(jdk, "bin")
        listOf("java", "javac", "javadoc", "jar", "keytool", "jlink", "jcmd", "jarsigner", "jdeps").forEach { name ->
            val src = File(jbin, name)
            val link = File(binDir, name)
            if (!src.isFile) return@forEach
            // 已有软链且指向目标 JDK → 跳过；否则（失效/指向 alternatives/非软链）清除重造
            if (link.exists() || Files.isSymbolicLink(link.toPath())) {
                val target = runCatching { link.toPath().toRealPath().toString() }.getOrDefault("")
                if (target == src.canonicalPath) return@forEach
                runCatching { link.delete() }
            }
            if (!link.exists()) {
                try {
                    Files.createSymbolicLink(link.toPath(), src.toPath())
                    linked++
                } catch (_: Throwable) {}
            }
        }
        // 确保 JDK bin 下工具全可执行
        jbin.listFiles()?.forEach { if (it.isFile) runCatching { it.setExecutable(true, false) } }
        return linked
    }

    /** 检测工具是否已安装。 */
    fun detectTool(cmd: String): Pair<Boolean, String> {
        if (!isInstalled()) return false to "内置环境未安装"
        val now = System.currentTimeMillis()
        toolProbeCache[cmd]?.let { (at, ok, msg) ->
            if (now - at < 3000L) return ok to msg
        }
        // 单次进程调用同时取版本行与真实退出码：先 command -v，再执行 --version，
        // 最后输出哨兵行 __GHK_EXIT_<code>__（防止伪安装把报错文本误判为“已安装”）。
        val out = run(
            "if command -v $cmd >/dev/null 2>&1; then v=\$($cmd --version 2>&1); c=\$?; " +
                "printf '%s\n' \"\$v\" | head -1; echo \"__GHK_EXIT_\${c}__\"; " +
                "else echo '__GHK_EXIT_127__'; fi"
        ).trim()
        val markerIdx = out.lastIndexOf("__GHK_EXIT_")
        val msg = if (markerIdx > 0) out.substring(0, markerIdx).trim() else out.take(60)
        val code = if (markerIdx >= 0) {
            out.substring(markerIdx).trim().removePrefix("__GHK_EXIT_").removeSuffix("__").toIntOrNull()
        } else null
        val ok = code == 0 && msg.isNotBlank()
        val result = ok to (if (msg.isBlank()) "已就绪" else msg.take(60))
        toolProbeCache[cmd] = Triple(System.currentTimeMillis(), result.first, result.second)
        return result
    }

    /** 安装/解包后使探测缓存失效，避免旧结果导致“装完仍报缺失”。 */
    private fun invalidateToolCache() { toolProbeCache.clear() }

    /** 解析 var/lib/dpkg/status：返回 {包名: 版本}，仅含已正确 configure 的包。 */
    private fun dpkgInstalledVersions(): Map<String, String> {
        val f = File(root, "var/lib/dpkg/status")
        if (!f.isFile || f.length() == 0L) return emptyMap()
        val map = HashMap<String, String>()
        try {
            f.readText().split(Regex("\n\\s*\n")).forEach { sec ->
                val pkg = Regex("(?m)^Package: (.+)$").find(sec)?.groupValues?.get(1)?.trim() ?: return@forEach
                val ver = Regex("(?m)^Version: (.+)$").find(sec)?.groupValues?.get(1)?.trim() ?: return@forEach
                val status = Regex("(?m)^Status: (.+)$").find(sec)?.groupValues?.get(1).orEmpty()
                if (status.contains("install ok installed")) map[pkg] = ver
            }
        } catch (_: Throwable) { }
        return map
    }

    /** 从 Termux .deb 文件名还原 (包名, 版本)，例如 gradle_1_9.7.1_all.deb → (gradle, 1:9.7.1)。 */
    private fun debIdentity(fileName: String): Pair<String, String>? {
        val base = fileName.removeSuffix(".deb")
        val parts = base.split("_")
        if (parts.size < 3) return null
        val pkg = parts[0]
        val middle = parts.subList(1, parts.size - 1).joinToString("_")
        var ver = middle
        val epochSep = middle.indexOf('_')
        if (epochSep > 0 && middle.take(epochSep).all { it.isDigit() }) {
            ver = middle.substring(0, epochSep) + ":" + middle.substring(epochSep + 1)
        }
        return pkg to ver
    }

    /** 构建/检测前统一准备：修复脚本 shebang、补齐 java 软链、修正权限。 */
    fun prepareShell(): Boolean {
        if (!isInstalled()) return false
        runCatching { fixShebangs() }
        runCatching { ensureJavaSymlinks() }
        runCatching { forceFixPermissions() }
        return true
    }

    /**
     * 验证 runtime 是否可用（方案二：通过 /system/bin/sh 执行）。
     * 返回 true=runtime 可用，false=不可用。
     */
    fun canDirectExec(): Boolean {
        if (!isInstalled()) return false
        return try {
            // 方案二：用系统 sh 检测，设置 PATH 指向 runtime
            val pb = ProcessBuilder("/system/bin/sh", "-c", "echo __ok__")
            pb.environment().putAll(toolchainEnv())
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            out.contains("__ok__")
        } catch (_: Throwable) { false }
    }

    /**
     * 验证当前进程能否直接执行内置运行时中的原生二进制（bash）。
     * 常规 AOSP 设备可以；部分华为/鸿蒙 ROM 禁止 app 直接 exec 自身 data 目录，
     * 此时返回 false，需要改用 Shizuku run-as 通道执行。
     */
    fun canExecRuntimeBash(onOutput: (String) -> Unit = {}): Boolean {
        if (!isInstalled()) {
            onOutput("[ENV] canExecRuntimeBash: runtime 未安装\n")
            return false
        }
        val bash = File(root, "bin/bash")
        onOutput("[ENV] canExecRuntimeBash: 尝试直接执行 ${bash.absolutePath}\n")
        return try {
            val pb = ProcessBuilder(bash.absolutePath, "-c", "echo __runtime_exec_ok__")
            pb.environment().putAll(toolchainEnv())
            pb.environment()["SHELL"] = bash.absolutePath
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val exited = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            onOutput("[ENV] canExecRuntimeBash: 进程已启动 exited=$exited out=${out.takeLast(200).replace("\n", "\\n")}\n")
            out.contains("__runtime_exec_ok__")
        } catch (t: Throwable) {
            onOutput("[ENV] canExecRuntimeBash: exec 抛出 ${t.javaClass.simpleName}: ${t.message}\n")
            false
        }
    }

    /** proot Termux 沙盒是否可用（jniLibs proot + loader 齐全）。 */
    private fun sandboxAvailable(): Boolean =
        TermuxSandbox.available(c)

    /**
     * 确保内置运行时具备可执行通道（安装 dpkg/apt 前调用）。
     * 1) 优先直接 exec（常规设备）；失败后先修复 exec 位/ELF 解释器再试一次；
     * 2) 仍失败则判定为华为/鸿蒙式 ROM 限制，改用 Shizuku run-as 通道；
     *    未授权时自动弹出授权窗口并等待用户点击。
     * 返回 true 表示后续 runStreaming 可正常执行命令。
     */
    fun ensureExecCapability(onOutput: (String) -> Unit = {}): Boolean {
        if (canExecRuntimeBash(onOutput)) return true
        // 先尝试修复权限/ELF 后重试一次直接执行
        onOutput("[ENV] 内置运行时直接执行失败，尝试修复 exec 位/ELF 解释器…\n")
        try { forceFixPermissions() } catch (_: Throwable) {}
        try { ensureElfPatched() } catch (_: Throwable) {}
        if (canExecRuntimeBash(onOutput)) {
            onOutput("[ENV] 修复后可直接执行运行时 ✓\n")
            return true
        }
        onOutput("[ENV] 仍无法直接执行（华为/鸿蒙 ROM 常见：禁止 app exec 自身 data 目录二进制）。\n")
        // 优先使用 proot Termux 沙盒（无需 Shizuku）：沙盒内由 proot loader 加载执行。
        if (sandboxAvailable()) {
            onOutput("[ENV] 检测到 proot Termux 沙盒通道，尝试在沙盒内执行运行时…\n")
            val sb = StringBuilder()
            val code = TermuxSandbox.runStreaming(c, root, "echo __sandbox_exec_ok__", toolchainEnv(), { sb.append(it) }, 1)
            if (code == 0 && sb.contains("__sandbox_exec_ok__")) {
                onOutput("[ENV] proot 沙盒内执行成功 ✓（无需 Shizuku）\n")
                return true
            }
            onOutput("[ENV] proot 沙盒执行失败(exit=$code)，回退 Shizuku 通道…\n")
        }
        if (ShizukuShell.isServiceAvailable()) {
            if (ShizukuShell.hasPermission()) {
                onOutput("[ENV] Shizuku 已授权，将通过 run-as 通道执行 dpkg/apt ✓\n")
                return true
            }
            onOutput("[ENV] 即将弹出 Shizuku 授权窗口，请在弹窗点击「允许」…\n")
            val granted = ShizukuShell.requestPermissionSync(c, 45_000L)
            if (granted) {
                onOutput("[ENV] Shizuku 授权成功 ✓，将通过 run-as 通道执行 dpkg/apt\n")
                return true
            }
            onOutput("[ENV] 未获得 Shizuku 授权。请打开「设置 → Shizuku 权限中心 → 请求 Shizuku 权限」并允许后重试。\n")
            return false
        }
        onOutput("[ENV] 未检测到 Shizuku/Dhizuku 服务；此设备需要 Shizuku 才能执行内置运行时。\n")
        onOutput("[ENV] 请先启动 Shizuku（无线调试/ADB 启动）或更换常规 AOSP 设备后重试。\n")
        return false
    }

    /**
     * 修复所有脚本：
     * 1. 修复 shebang 路径（#! 行）
     * 2. 递归替换脚本内容中的硬编码旧路径
     * 3. 恢复可执行权限
     */
    private fun fixShebangs() {
        val binDir = File(root, "bin")
        val libexecDir = File(root, "libexec")
        val aptDir = File(root, "lib/apt")
        val targetPrefix = "/data/data/com.example.myempty.githubk/files/runtime"
        val targetShebang = "#!/system/bin/sh"
        val oldPrefix = "/data/data/com.termux/files/usr"

        // 待修复的目录集合
        val dirs = listOfNotNull(binDir, libexecDir, aptDir)

        for (dir in dirs) {
            if (!dir.exists()) continue
            dir.walkTopDown().forEach { file ->
                if (file.isFile && file.canRead() && file.length() <= 4L * 1024L * 1024L) {
                    // 跳过 ELF 二进制，绝不能把原生文件当作文本替换（会破坏 ELF 头）
                    try {
                        val head = ByteArray(4)
                        val read = file.inputStream().use { it.read(head) }
                        if (read >= 4 && head[0] == 0x7f.toByte() && head[1] == 'E'.code.toByte() &&
                            head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
                        ) return@forEach
                    } catch (_: Throwable) { return@forEach }
                    try {
                        val content = file.readText(Charsets.UTF_8)
                        var modified = false
                        var newContent = content

                        // 1. 修复 shebang（#! 行）
                        val firstLine = content.lines().firstOrNull() ?: ""
                        if (firstLine.startsWith("#!") && firstLine.contains("/data/data/")) {
                            newContent = newContent.replaceFirst(firstLine, targetShebang)
                            modified = true
                        }

                        // 2. 替换内容中的硬编码旧路径
                        if (newContent.contains(oldPrefix)) {
                            newContent = newContent.replace(oldPrefix, targetPrefix)
                            modified = true
                        }

                        // 3. 如果有修改则写回文件
                        if (modified) {
                            file.writeText(newContent, Charsets.UTF_8)
                            // 恢复可执行位和可读位
                            try { file.setExecutable(true, false) } catch (_: Throwable) {}
                            try { file.setReadable(true, false) } catch (_: Throwable) {}
                        }
                    } catch (_: Throwable) {
                        // 非文本文件或读取失败，忽略
                    }
                }
            }
        }

        // 再次确保关键符号链接存在
        try {
            val shLink = File(root, "bin/sh")
            if (!shLink.exists()) {
                Files.createSymbolicLink(shLink.toPath(), File(root, "bin/bash").toPath())
            }
        } catch (_: Throwable) {}
        try {
            val envLink = File(root, "bin/env")
            if (!envLink.exists()) {
                val target = File("/system/bin/env")
                if (target.exists()) {
                    Files.createSymbolicLink(envLink.toPath(), target.toPath())
                } else {
                    val busybox = File(root, "bin/busybox")
                    if (busybox.exists()) {
                        Files.createSymbolicLink(envLink.toPath(), busybox.toPath())
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun ensureRuntimeExecutable(file: File, onOutput: (String) -> Unit) {
        if (!file.exists()) {
            onOutput("[ENV] 缺少 ${file.name}：${file.absolutePath}\n")
            return
        }
        runCatching { file.setExecutable(true, false) }
        runCatching {
            ProcessBuilder("/system/bin/chmod", "755", file.absolutePath).redirectErrorStream(true).start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        }
        if (!file.canExecute()) {
            onOutput("[ENV] ${file.name} 仍不可执行，设备可能禁止 app 私有目录执行原生文件。\n")
        }
    }

    /** 在内置 Termux-compatible prefix 中初始化软件源并安装开发工具。 */
    fun installPackages(packages: List<String>, onOutput: (String) -> Unit = {}): Boolean {
        if (!isInstalled()) throw IllegalStateException("内置终端运行时尚未安装")
        if (packages.isEmpty()) return true
        prepare()
        // ============ 优先从 APK assets 离线包安装（真正离线，无需网络） ============
        if (tryInstallOfflineDebs(packages, onOutput)) return true
        onOutput("[ENV] assets 离线包不可用或未完全覆盖，尝试联网 apt 安装...\n")
        if (!ensureExecCapability(onOutput)) {
            onOutput("[ENV] 在线 apt 安装中止：当前无法执行内置运行时二进制（详见上方说明）。\n")
            return false
        }

        forceFixPermissions()
        val patchedBefore = runCatching { ensureElfPatched() }.getOrDefault(0)
        if (patchedBefore > 0) {
            onOutput("[ENV] 已重写 $patchedBefore 个 ELF 解释器为系统 linker64\n")
        }
        // 在 apt update 前先验证 apt 本身可执行。若 ROM/解压流程丢失 exec 位，
        // 立即修复而不是让后续所有包安装连续失败。
        ensureRuntimeExecutable(File(root, "bin/apt"), onOutput)
        ensureRuntimeExecutable(File(root, "bin/apt-get"), onOutput)
        val candidates = APT_SOURCES.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        val cmd = buildString {
            append("export DEBIAN_FRONTEND=noninteractive; ")
            append("export PREFIX='").append(prefix.replace("'", "'\\''")).append("'; ")
            append("export HOME='").append(home.absolutePath.replace("'", "'\\''")).append("'; ")
            append("export PATH='").append(prefix).append("/bin:").append(prefix).append("/bin/applets:/system/bin:/system/xbin'; ")
            append("export TMPDIR='").append(tmp.absolutePath.replace("'", "'\\''")).append("'; ")
            append("command -v apt >/dev/null 2>&1 || { echo '[ENV] apt 不存在，请重新安装内置运行时'; exit 127; }; ")
            // 已有有效软件源则保留（尊重 ZeroTermux 镜像选择）；否则写入国内镜像
            append("if ! grep -q '^deb ' \"\$PREFIX/etc/apt/sources.list\" 2>/dev/null; then rm -rf \"\$PREFIX/etc/apt/sources.list.d\"; mkdir -p \"\$PREFIX/etc/apt\"; echo '").append(APT_SOURCES.first().replace("'", "'\\''")).append("' > \"\$PREFIX/etc/apt/sources.list\"; fi; ")
            // apt update 失败则逐个切换镜像重试
            append("apt-get update -y || { ok=0; for src in ").append(candidates).append("; do echo \"\$src\" > \"\$PREFIX/etc/apt/sources.list\"; if apt-get update -y; then ok=1; break; fi; echo '[ENV] 软件源不可用，尝试下一个镜像'; done; [ \"\$ok\" = 1 ] || { echo '[ENV] 所有软件源均不可用'; exit 1; }; }; ")
            append("apt-get install -y ")
            packages.forEach { append(it.filter { ch -> ch.isLetterOrDigit() || ch in "-_+." }).append(' ') }
        }
        val firstOk = runStreaming(cmd, onOutput)
        val patchedAfter = runCatching { repatchElfAfterInstall() }.getOrDefault(0)
        if (patchedAfter > 0) {
            onOutput("[ENV] 软件包解压完成，重写 $patchedAfter 个新 ELF 解释器为系统 linker64\n")
        }
        val linkedOnline = runCatching { ensureJavaSymlinks() }.getOrDefault(0)
        if (linkedOnline > 0) onOutput("[ENV] 已补 $linkedOnline 个 JDK 软链（\$PREFIX/bin/java 等）\n")
        invalidateToolCache()
        if (!firstOk && patchedAfter > 0) {
            onOutput("[ENV] 首次安装因 ELF 链接器问题中断，已修复，重试一次...\n")
            return runStreaming(cmd, onOutput)
        }
        return firstOk
    }

    /**
     * 纯离线安装：只允许 APK assets/offline-toolchain 内置 .deb 解包安装；
     * 若内置包未完整覆盖所需组件则直接返回 false，绝不触发联网 apt。
     */
    fun installPackagesOffline(packages: List<String>, onOutput: (String) -> Unit = {}): Boolean {
        if (!isInstalled()) throw IllegalStateException("内置终端运行时尚未安装")
        if (packages.isEmpty()) return true
        prepare()
        // ============ 仅使用 APK assets 离线包（真正离线，无需网络） ============
        if (tryInstallOfflineDebs(packages, onOutput)) return true
        onOutput("[ENV] 内置离线包未完整覆盖所需组件；已禁止联网，离线安装失败。\n")
        return false
    }

    /** 安装 Android IDE 常用工具链：JDK、Gradle、Git、Python、Node.js。 */
    fun installIdeToolchain(onOutput: (String) -> Unit = {}): Boolean =
        installPackages(listOf("openjdk-17", "gradle", "git", "python", "nodejs"), onOutput)

    /**
     * 离线安装：若 APK assets/offline-toolchain 目录存在 deb 包，则把 .deb 复制到 runtime
     * 并用 dpkg 本地安装（先全部 unpack 再 configure），避免依赖顺序与网络问题。
     * 返回 true 表示所有请求的包已就绪（或离线包完全可用）。
     */
    private fun tryInstallOfflineDebs(packages: List<String>, onOutput: (String) -> Unit): Boolean {
        val debNames = runCatching {
            c.assets.list("offline-toolchain")?.filter { it.endsWith(".deb") }.orEmpty()
        }.getOrDefault(emptyList())
        if (debNames.isEmpty()) {
            return false
        }
        onOutput("[ENV] 发现内置离线工具链包 ${debNames.size} 个，检查是否已安装...\n")
        // 核心工具链已就绪则跳过重复安装（避免每次启动都重新 unpack 数百 MB）。
        // 注意：按请求的包逐个判断，不能只看 java/gradle/git —— 否则用户单独请求
        // dart/flutter 时会被错误跳过。
        val probeNames = mapOf(
            "java" to listOf("java", "openjdk"),
            "gradle" to listOf("gradle"),
            "git" to listOf("git"),
            "python" to listOf("python"),
            "nodejs" to listOf("node"),
            "node" to listOf("node"),
            "dart" to listOf("dart"),
            "flutter" to listOf("flutter"),
            "clang" to listOf("clang"),
            "cmake" to listOf("cmake"),
            "binutils" to listOf("ld", "as", "ar"),
            "aapt2" to listOf("aapt2"),
            "aapt" to listOf("aapt"),
            "d8" to listOf("d8"),
            "apksigner" to listOf("apksigner"),
            "zipalign" to listOf("zipalign"),
            "adb" to listOf("adb", "android-tools"),
            "android-tools" to listOf("adb")
        )
        val requested = packages.flatMap { p ->
            probeNames.entries.firstOrNull { e ->
                p == e.key || p.contains(e.key) || e.value.any { p.contains(it) }
            }?.value ?: listOf(p)
        }.distinct()
        // 无论 JDK 何时装过，先补齐 $PREFIX/bin 下的 java 软链（幂等），
        // 保证后续用 command -v 探测与实际运行都稳定。
        val preLinked = runCatching { ensureJavaSymlinks() }.getOrDefault(0)
        if (preLinked > 0) onOutput("[ENV] 已补 $preLinked 个 JDK 软链（\$PREFIX/bin/java 等）\n")
        invalidateToolCache()
        val statusMap = linkedMapOf<String, Boolean>()
        requested.forEach { bin ->
            val ok = detectTool(bin).first
            statusMap[bin] = ok
        }
        val allOk = statusMap.values.all { it }
        if (allOk) {
            onOutput("[ENV] 离线工具链已就绪（${requested.joinToString("/")}），跳过重复安装\n")
            return true
        }
        val needDesc = statusMap.entries.joinToString(" ") { "${it.key}=${if (it.value) "OK" else "缺失"}" }
        onOutput("[ENV] 需要安装: $needDesc。开始离线安装...\n")
        // 预检可执行通道：华为/鸿蒙设备需 Shizuku run-as，必要时自动弹出授权。
        if (!ensureExecCapability(onOutput)) {
            onOutput("[ENV] 离线安装中止：当前无法执行内置运行时二进制（详见上方说明）。\n")
            return false
        }
        try { prepare() } catch (_: Throwable) {}
        try { forceFixPermissions() } catch (_: Throwable) {}
        try { ensureElfPatched() } catch (_: Throwable) {}

        val archiveDir = File(root, "var/cache/apt/archives")
        archiveDir.mkdirs()
        // 增量安装：解析 dpkg status，已安装且版本一致的 .deb 不再重复 unpack/覆盖，
        // 避免每次“一键安装”都重装数百个依赖包导致卡死数十小时。
        val installed = dpkgInstalledVersions()
        var copied = 0
        var skipped = 0
        val copiedFiles = mutableListOf<File>()
        try {
            debNames.sorted().forEach { name ->
                val id = debIdentity(name)
                if (id != null && installed[id.first] == id.second) {
                    skipped++
                    return@forEach
                }
                val target = File(archiveDir, name)
                if (!target.exists() || target.length() < 1024L) {
                    try {
                        c.assets.open("offline-toolchain/$name").use { input ->
                            target.outputStream().use { out -> input.copyTo(out) }
                        }
                    } catch (_: Throwable) { return@forEach }
                }
                if (target.exists() && target.length() > 1024L && verifyBundledDeb(target)) {
                    copiedFiles.add(target)
                    copied++
                } else if (target.exists() && target.length() > 1024L) {
                    // 伪安装防护：SHA-256 与打包清单不符（可能被替换/混入非 Termux 包），删除拒绝
                    runCatching { target.delete() }
                }
            }
        } catch (_: Throwable) {}
        if (copiedFiles.isEmpty()) {
            onOutput("[ENV] 本次无需新装离线包（全部已安装），进入 configure 修复阶段。\n")
            if (skipped > 0) onOutput("[ENV] 跳过已安装离线包 $skipped 个\n")
        } else {
            onOutput("[ENV] 需要安装 $copied 个离线包（已跳过版本一致的 $skipped 个）→ ${archiveDir.absolutePath}\n")
        }

        // 1) 全部 unpack（忽略依赖顺序）；无新装包时跳过 unpack 只做 configure
        val unpackCmd = buildString {
            append("export DEBIAN_FRONTEND=noninteractive; ")
            append("export PREFIX='").append(prefix.replace("'", "'\\''")).append("'; ")
            append("export HOME='").append(home.absolutePath.replace("'", "'\\''")).append("'; ")
            append("export PATH='").append(prefix).append("/bin:").append(prefix).append("/bin/applets:/system/bin:/system/xbin'; ")
            append("export TMPDIR='").append(tmp.absolutePath.replace("'", "'\\''")).append("'; ")
            append("cd '").append(archiveDir.absolutePath.replace("'", "'\\''")).append("' && ")
            if (copiedFiles.isNotEmpty()) {
                append("dpkg --force-depends --force-all --unpack ")
                copiedFiles.forEach { f ->
                    append("'").append(f.name.replace("'", "'\\''")).append("' ")
                }
                append(" 2>&1; echo '[unpack_exit='$?']'; ")
            } else {
                append("echo '[unpack_exit=skip]'; ")
            }
            append("dpkg --force-depends --force-all --configure -a 2>&1; echo '[cfg_exit='$?']'")
        }
        onOutput("[ENV] 执行 dpkg 离线安装（${if (copiedFiles.isNotEmpty()) "unpack ${copiedFiles.size} 个新包 + " else ""}configure -a）...\n")
        val sb = StringBuilder()
        val ok = runStreaming(unpackCmd) { sb.append(it); onOutput(it) }
        onOutput("[ENV] dpkg 输出末尾：${sb.takeLast(1200)}\n")
        // 安装后重写新 ELF 解释器
        val patchedAfter = runCatching { repatchElfAfterInstall() }.getOrDefault(0)
        if (patchedAfter > 0) onOutput("[ENV] 已重写 $patchedAfter 个新 ELF 解释器\n")
        // 修复权限
        try { forceFixPermissions() } catch (_: Throwable) {}
        // Flutter 附加 post_install（Termux Flutter deb 需要此步才能跑 flutter 命令）
        val hasFlutterDeb = debNames.any { it.startsWith("flutter_") }
        if (hasFlutterDeb && File(prefix, "share/flutter/post_install.sh").exists()) {
            val flCmd = "export PREFIX='${prefix}'; export HOME='${home.absolutePath}'; export TMPDIR='${tmp.absolutePath}'; " +
                "export PATH='${prefix}/bin:${prefix}/bin/applets:/system/bin:/system/xbin'; " +
                "bash '${prefix}/share/flutter/post_install.sh' 2>&1 | tail -30; echo '[flutter_post_exit='$?']'"
            runStreaming(flCmd) { onOutput(it) }
            onOutput("[ENV] Flutter post_install 执行完成\n")
        }
        // Termux openjdk 不会把 java/javac 放进 $PREFIX/bin，补齐软链，
        // 让 PATH 只含 $PREFIX/bin 的 shell 也能运行 java。
        val linkedJvm = runCatching { ensureJavaSymlinks() }.getOrDefault(0)
        if (linkedJvm > 0) onOutput("[ENV] 已补 $linkedJvm 个 JDK 软链（\$PREFIX/bin/java 等）\n")
        // 使探测缓存失效，避免上面安装前的“缺失”结果导致这里误报仍缺失
        invalidateToolCache()
        // 验证
        val javaOk = detectTool("java").first
        val gradleOk = detectTool("gradle").first
        val gitOk = detectTool("git").first
        val dartOk = detectTool("dart").first
        val flutterOk = detectTool("flutter").first
        onOutput("[ENV] 验证: java=${if (javaOk) "OK" else "缺失"} gradle=${if (gradleOk) "OK" else "缺失"} git=${if (gitOk) "OK" else "缺失"} dart=${if (dartOk) "OK" else "缺失"} flutter=${if (flutterOk) "OK" else "缺失"}\n")
        return javaOk && gradleOk && gitOk
    }

    private fun cleanOutput(line: String): String? =
        if (line.contains("WARNING: linker") || line.contains("failed to find generated linker configuration")) null else line

    private fun runStreaming(command: String, onOutput: (String) -> Unit): Boolean {
        val out: (String) -> Unit = { s -> cleanOutput(s)?.let(onOutput) }
        try { ensureElfPatched() } catch (_: Throwable) {}
        try { forceFixPermissions() } catch (_: Throwable) {}
        // 优先 proot Termux 沙盒（无需 Shizuku 授权）
        if (sandboxAvailable()) {
            onOutput("[ENV] proot Termux 沙盒 → built-in runtime（无需 Shizuku）\n")
            val code = TermuxSandbox.runStreaming(c, root, command, toolchainEnv(), out, 30)
            if (code == 0) return true
            onOutput("[ENV] proot 沙盒执行退出码=$code\n")
            return false
        }
        return try {
            if (ShizukuShell.isServiceAvailable() && ShizukuShell.hasPermission()) {
                onOutput("[ENV] Shizuku UserService → run-as → built-in runtime\n")
                val result = ShizukuShell.execUserService(c, command, toolchainEnv(), root.absolutePath, 30 * 60)
                if (result != null) {
                    result.lineSequence().forEach { s -> out(s + "\n") }
                    val code = Regex("\\[exit=(\\d+)\\]").find(result)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    return code == null || code == 0
                }
            }
            val pb = ProcessBuilder("/system/bin/sh", "-c", command)
            pb.environment().putAll(toolchainEnv())
            pb.environment()["SHELL"] = File(root, "bin/bash").absolutePath
            pb.redirectErrorStream(true)
            val p = pb.start()
            val deniedHint = StringBuilder()
            p.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    out(line + "\n")
                    if (line.contains("Permission denied") && deniedHint.length < 400) {
                        deniedHint.append(line).append('\n')
                    }
                }
            }
            p.waitFor(30, java.util.concurrent.TimeUnit.MINUTES)
            val runOk = p.exitValue() == 0
            if (!runOk && deniedHint.isNotEmpty() && ShizukuShell.isServiceAvailable() && !ShizukuShell.hasPermission()) {
                onOutput("[ENV] 提示：检测到「Permission denied」。华为/鸿蒙设备通常禁止 app 直接执行内置运行时，\n")
                onOutput("[ENV] 请在「设置 → Shizuku 权限中心」点击「请求 Shizuku 权限」并允许后重试。\n")
            }
            runOk
        } catch (e: Throwable) {
            onOutput("[ENV] ${e.message ?: e.javaClass.simpleName}\n")
            false
        }
    }

    /** 删除损坏的 runtime 后可安全重装。 */
    fun reset() {
        try { root.deleteRecursively() } catch (_: Throwable) {}
    }


    /**
     * 修补 runtime 中所有 ELF 的解释器路径
     * 华为/鸿蒙设备上 /system/bin/linker64 不可见，改为 /lib/ld-linux-aarch64.so.1
     */
    private fun patchRuntimeElfInterpreters(root: File, onOutput: (String) -> Unit = {}): Int {
        val newInterp = "/lib/ld-linux-aarch64.so.1"
        val oldInterp = "/system/bin/linker64"
        var patched = 0

        fun isElf(file: File): Boolean {
            if (!file.isFile || !file.canRead()) return false
            return try {
                val header = file.inputStream().use { it.readNBytes(4) }
                header.contentEquals(byteArrayOf(0x7F, 0x45, 0x4C, 0x46))
            } catch (_: Exception) { false }
        }

        fun bytesIndexOf(hay: ByteArray, needle: ByteArray): Int {
            if (needle.isEmpty()) return 0
            if (hay.size < needle.size) return -1
            outer@ for (i in 0..hay.size - needle.size) {
                for (j in needle.indices) {
                    if (hay[i + j] != needle[j]) continue@outer
                }
                return i
            }
            return -1
        }

        fun patchSingle(file: File): Boolean {
            if (!isElf(file)) return false
            val data = file.readBytes()
            if (bytesIndexOf(data, oldInterp.toByteArray()) == -1) return false

            val e_phoff = java.nio.ByteBuffer.wrap(data, 32, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong()
            val e_phentsize = java.nio.ByteBuffer.wrap(data, 54, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort().toInt()
            val e_phnum = java.nio.ByteBuffer.wrap(data, 56, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort().toInt()

            for (i in 0 until e_phnum) {
                val off = e_phoff + i * e_phentsize
                val p_type = java.nio.ByteBuffer.wrap(data, off.toInt(), 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt()
                if (p_type == 3) {
                    val p_offset = java.nio.ByteBuffer.wrap(data, off.toInt() + 8, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong()
                    val p_filesz = java.nio.ByteBuffer.wrap(data, off.toInt() + 32, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong()
                    val interpBytes = data.sliceArray(p_offset.toInt() until p_offset.toInt() + p_filesz.toInt())
                    val interp = String(interpBytes).trimEnd(' ')
                    if (interp == oldInterp) {
                        val newBytes = (newInterp + " ").toByteArray()
                        val newData = data.copyOf()
                        System.arraycopy(newBytes, 0, newData, p_offset.toInt(), minOf(newBytes.size, p_filesz.toInt()))
                        file.writeBytes(newData)
                        onOutput("[ELF] 修补 ${file.name}: $oldInterp → $newInterp\n")
                        return true
                    }
                }
            }
            return false
        }

        val targetDirs = listOf(File(root, "bin"), File(root, "libexec"), File(root, "lib"))
        for (dir in targetDirs) {
            if (!dir.exists()) continue
            dir.walkTopDown().forEach { f ->
                if (f.isFile && f.canRead() && isElf(f)) {
                    if (patchSingle(f)) patched++
                }
            }
        }
        return patched
    }

}
