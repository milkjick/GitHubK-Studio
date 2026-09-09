package com.example.myempty.githubk.terminal

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * TermuxSandbox: 用 proot 把 BuiltinRuntime 的 Termux bionic 运行时
 * （filesDir/runtime，下称 $PREFIX）安全地放进 proot 沙盒执行。
 *
 * 背景（华为/鸿蒙 ROM）：
 * - untrusted_app 禁止直接 exec 自身 app_data_file（filesDir）里的二进制，
 *   因此直接 ProcessBuilder(files/runtime/bin/bash) 会 error=13。
 * - LinuxSandbox 已验证：proot(jniLibs 的 apk_data_file 可 exec) + libloader.so
 *   (PROOT_LOADER) 能把 rootfs 内的动态 ELF 加载执行，无需 Shizuku/root。
 *
 * 方案要点（方案 B）：
 * 1. Termux 二进制是 bionic ELF，PT_INTERP 已被 ensureElfPatched 改写为
 *    /system/bin/linker64（部分新装包仍是原版 /data/data/com.termux/... 路径），
 *    因此沙盒必须把宿主 /system、/apex bind 进 guest。
 * 2. BuiltinRuntime 的 env()/命令字符串大量使用宿主绝对路径
 *    /data/user/0/<pkg>/files/runtime/...，因此把 app dataRoot 镜像 bind 到 guest 的
 *    同一绝对路径，命令无需改写。
 * 3. 同时把 $PREFIX bind 到 /data/data/com.termux/files/usr，兼容 Termux 原始
 *    RUNPATH 与原版解释器路径（dpkg 新装 .deb 尚未 repatch 时也能运行）。
 * 4. rootfs 用 cache/termux-proot-root 空壳即可，proot 的 glue 会补齐 bind 路径；
 *    PROOT_TMP_DIR=cache/proot-tmp 提供 glue/临时目录。
 */
object TermuxSandbox {

    /** 沙盒组件是否齐全（jniLibs 释放 + loader）。 */
    fun available(context: Context): Boolean {
        if (!LinuxSandbox.isSupported(context)) return false
        val lib = LinuxSandbox.nativeLibDir(context) ?: return false
        return File(lib, "libloader.so").isFile &&
            File(lib, "libproot.so").isFile
    }

    /** proot guest rootfs（空壳即可，binds 提供全部内容）。 */
    fun rootDir(context: Context): File = File(context.cacheDir, "termux-proot-root")

    fun tmpDir(context: Context): File =
        File(context.cacheDir, "proot-tmp").apply { mkdirs() }

    /** guest 内 bash 绝对路径（com.termux 视图，经 prefix bind 映射到 $PREFIX/bin/bash）。 */
    const val GUEST_PREFIX = "/data/data/com.termux/files/usr"
    const val GUEST_HOME = "/data/data/com.termux/files/home"
    const val GUEST_BASH = "$GUEST_PREFIX/bin/bash"

    /**
     * 公共 proot 基础参数（不含 argv[0]）：r + binds + sdcard。
     *
     * @param root 是否以 UID=0(proot --root-id) 运行。默认 false：
     *   Termux 的 apt/pkg 官方检测到 EUID=0 会永久禁用（保护 app 私有目录所有权），
     *   而 proot 的 root 只是伪造 uid，无内核特权，runtime 文件本就属于 app 自身，
     *   非 root 即可安装/写包；因此 Termux 包管理场景必须非 root。
     *   仅当确实需要"看起来是 root"的兼容性时才传 true（例如 Alpine/LinuxSandbox）。
     */
    fun baseArgs(context: Context, prefixHost: File, root: Boolean = false): MutableList<String> {
        val dataRoot = context.filesDir.parentFile // /data/user/0/<pkg>
        val pkgName = context.packageName
        val args = mutableListOf<String>()
        // 原生 Termux proot 登录参数：--link2symlink 将 bind 符号链接转为 symlink，
        // 避免 proot glue 在只读/特殊目录上创建实体文件；rootfs 空壳自动补齐。
        args += listOf("--link2symlink")
        rootDir(context).mkdirs()
        args += listOf("-r", rootDir(context).absolutePath)
        // Termux 原始 PREFIX 视图（原版 com.termux 解释器/RUNPATH/相对链接）
        args += listOf("-b", "${prefixHost.absolutePath}:$GUEST_PREFIX")
        // guest /bin 与 /usr/bin 由 Termux bin 提供：
        // - /bin/sh 使 shebang="#!/bin/sh" 的脚本（gradle/gradlew）可解析
        // - /usr/bin/env 使 shebang="#!/usr/bin/env bash" 的脚本（flutter）可解析
        args += listOf("-b", "${prefixHost.absolutePath}/bin:/bin")
        args += listOf("-b", "${prefixHost.absolutePath}/bin:/usr/bin")
        // Termux 官方 home 视图：$PREFIX/../home（即 filesDir/runtime/home），
        // 与原生 Termux 的 /data/data/com.termux/files/home 一致；/home 同步挂出。
        args += listOf("-b", "${prefixHost.absolutePath}/home:$GUEST_HOME")
        args += listOf("-b", "${prefixHost.absolutePath}/home:/home")
        // 宿主绝对路径镜像（BuiltinRuntime env/命令/脚本里的 /data/user/0/<pkg>/files/...
        // 与 /data/data/<pkg>/files/... 都能解析）
        if (dataRoot != null) {
            args += listOf("-b", "${dataRoot.absolutePath}:${dataRoot.absolutePath}")
            args += listOf("-b", "${dataRoot.absolutePath}:/data/data/$pkgName")
        }
        // App 工作区目录互通：/sdcard 之外的 workspace 统一在 guest 内挂到 /workspace
        runCatching {
            val ws = File(context.filesDir, "workspace")
            if (ws.isDirectory) args += listOf("-b", "${ws.absolutePath}:/workspace")
        }
        args += listOf("-b", "/proc:/proc", "-b", "/dev:/dev", "-b", "/sys:/sys")
        args += listOf("-b", "/system:/system", "-b", "/apex:/apex")
        args += listOf("-b", "/dev/null:/proc/sys/kernel/cap_last_cap")
        // guest /tmp 映射到宿主可写 cache；/sdcard 可通过 /storage/emulated/0 访问
        args += listOf("-b", "${tmpDir(context).absolutePath}:/tmp")
        // DNS：Android 宿主没有 /etc/resolv.conf，proot guest 内解析会失败。
        // 把一份可写的 resolv.conf 绑定为 guest /etc/resolv.conf，保证
        // curl/gradle/git 等联网命令在沙盒内可解析域名。
        val resolvFile = File(context.cacheDir, "termux-resolv.conf")
        try {
            if (!resolvFile.isFile) {
                resolvFile.parentFile?.mkdirs()
                resolvFile.writeText("nameserver 223.5.5.5\nnameserver 8.8.8.8\n")
            }
            args += listOf("-b", "${resolvFile.absolutePath}:/etc/resolv.conf")
        } catch (_: Exception) {
        }
        if (File("/storage/emulated/0").isDirectory) {
            args += listOf("-b", "/storage/emulated/0:/sdcard")
            args += listOf("-b", "/storage/emulated/0:/storage/emulated/0")
        }
        if (root) args += listOf("--root-id")
        return args
    }

    /** proot argv（不含 argv[0]）；command 由 guest bash -c 执行。 */
    fun argv(context: Context, prefixHost: File, command: String, root: Boolean = false): List<String> {
        val args = baseArgs(context, prefixHost, root)
        args += listOf(GUEST_BASH, "-c", command)
        return args
    }

    /** 登录/交互式终端 argv（不含 argv[0]）：proot ... bash -l（挂 pty）。默认非 root。 */
    fun loginArgv(context: Context, prefixHost: File, cwdHost: String? = null, root: Boolean = false): List<String> {
        val args = baseArgs(context, prefixHost, root)
        if (cwdHost != null) args += listOf("--cwd=$cwdHost")
        args += listOf(GUEST_BASH, "-l")
        return args
    }

    /**
     * proot 进程 env：baseEnv 通常是 BuiltinRuntime.env()（宿主绝对路径），
     * 覆盖 LD_LIBRARY_PATH（proot 自身需要 native lib，guest linker 需要 $PREFIX/lib）。
     */
    fun processEnv(context: Context, prefixHost: File, baseEnv: Map<String, String>): MutableMap<String, String> {
        val m = mutableMapOf<String, String>()
        m.putAll(baseEnv)
        val lib = LinuxSandbox.nativeLibDir(context)?.absolutePath ?: ""
        // ---- guest 前缀归一化：proot guest 内必须用 Termux 原版视图路径 ----
        // 若不归一化，bin/pkg/apt/termux-* 等原生命令的 shebang 与库搜索
        // 仍指向宿主绝对路径（/data/user/0/<pkg>/files/runtime/...），在 guest
        // 里虽被 dataRoot bind 覆盖，但部分 Termux 二进制/脚本硬编码
        // /data/data/com.termux/... 前缀，导致"原生 termux 命令无法运行"。
        val hp = prefixHost.absolutePath
        fun norm(v: String?): String? = v?.replace(hp, GUEST_PREFIX)
        m["PREFIX"] = GUEST_PREFIX
        m["PATH"] = norm(m["PATH"]) ?: "$GUEST_PREFIX/bin:$GUEST_PREFIX/bin/applets:/system/bin:/system/xbin"
        // guest linker 需要 $PREFIX/lib（libandroid-support/libutil/libmd 等 Termux 库）
        // guest linker 需要 $PREFIX/lib；proot host 自身动态链接需要 native lib（libtalloc.so 等），两者合并。
        m["LD_LIBRARY_PATH"] = (if (lib.isNotEmpty()) "$lib:" else "") + "$GUEST_PREFIX/lib"
        // HOME：guest 内统一指向官方 Termux home（/data/data/com.termux/files/home，
        // 已 bind 到 $PREFIX/../home 即 runtime/home，.bashrc/.profile 在其中）
        m["HOME"] = GUEST_HOME
        m["TMPDIR"] = norm(m["TMPDIR"]) ?: "/tmp"
        m["LANG"] = m["LANG"] ?: "C.UTF-8"
        m["LC_ALL"] = m["LC_ALL"] ?: "C.UTF-8"
        m["TERM"] = m["TERM"] ?: "xterm-256color"
        m["COLORTERM"] = m["COLORTERM"] ?: "truecolor"
        // proot 宿主进程自身需要（native lib + loader），与 guest linker 无关
        m["PROOT_LOADER"] = "$lib/libloader.so"
        m["PROOT_TMP_DIR"] = tmpDir(context).absolutePath
        m["TMPDIR"] = m["TMPDIR"] ?: tmpDir(context).absolutePath
        return m
    }

    /**
     * 清理 runtime 脚本的 shebang：把被 BuiltinRuntime.rewriteTermuxPrefixReferences
     * 改写为【宿主绝对路径】或【错位路径】的脚本首行改回 Termux guest 前缀。
     * 原生 Termux 命令（pkg/apt/termux-*）多为文本脚本，其 shebang
     * #!/data/data/com.termux/files/usr/bin/bash 在 proot guest 视图下有效。
     * 若被改写成 files/runtime/... 宿主路径（旧 bug 错位为 <runtime>/usr/bin/...），
     * guest 下解释器不存在 -> "can't execute: 执行格式错误 / not found"。
     */
    fun ensureGuestShebangs(prefixHost: File) {
        val hp = prefixHost.absolutePath
        // 旧版错位路径：<runtime>/usr/<...> 应回落到 guest prefix
        val misplaced = if (hp.endsWith("runtime")) "$hp/usr" else null
        val dirs = listOf(
            File(prefixHost, "bin"), File(prefixHost, "usr/bin"),
            File(prefixHost, "libexec"), File(prefixHost, "bin/applets")
        )
        for (d in dirs) {
            if (!d.isDirectory) continue
            d.listFiles()?.forEach { f ->
                if (!f.isFile || !f.canRead()) return@forEach
                if (f.name.endsWith(".so") || f.name.endsWith(".jar")) return@forEach
                runCatching {
                    val head = java.io.FileInputStream(f).use { ins ->
                        val b = ByteArray(260)
                        val n = ins.read(b)
                        if (n >= 0) b.copyOf(n) else b
                    }
                    val s = String(head, Charsets.UTF_8)
                    if (!s.startsWith("#!")) return@forEach
                    val full = String(f.readBytes(), Charsets.UTF_8)
                    var fixed = full.replace(hp, GUEST_PREFIX)
                    if (misplaced != null) fixed = fixed.replace(misplaced, GUEST_PREFIX)
                    if (fixed != full) {
                        java.io.RandomAccessFile(f, "rw").use { raf ->
                            raf.setLength(0L); raf.write(fixed.toByteArray(Charsets.UTF_8))
                        }
                    }
                }
            }
        }
    }

    /** 流式执行（回调每行输出），返回退出码；超时返回 124。 */
    fun runStreaming(
        context: Context,
        prefixHost: File,
        command: String,
        baseEnv: Map<String, String>,
        onOutput: (String) -> Unit,
        timeoutMin: Int = 30,
        root: Boolean = false
    ): Int {
        val proot = LinuxSandbox.prootBinary(context) ?: return -1
        rootDir(context).mkdirs()
        tmpDir(context).mkdirs()
        val argv = mutableListOf(proot.absolutePath)
        argv += argv(context, prefixHost, command, root)
        return try {
            val pb = ProcessBuilder(argv)
            processEnv(context, prefixHost, baseEnv).forEach { (k, v) -> pb.environment()[k] = v }
            pb.redirectErrorStream(true)
            val p = pb.start()
            p.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { onOutput(it + "\n") }
            }
            val done = p.waitFor(timeoutMin.toLong(), TimeUnit.MINUTES)
            if (!done) {
                p.destroyForcibly()
                return 124
            }
            p.exitValue()
        } catch (t: Throwable) {
            onOutput("[ENV] TermuxSandbox 启动失败：${t.message ?: t.javaClass.simpleName}\n")
            -2
        }
    }

    /** 捕获执行（非流式），返回 stdout+stderr；失败返回空串。 */
    fun runCapture(
        context: Context,
        prefixHost: File,
        command: String,
        envExtra: Map<String, String> = emptyMap(),
        timeoutSec: Long = 60,
        root: Boolean = false
    ): String {
        val proot = LinuxSandbox.prootBinary(context) ?: return ""
        rootDir(context).mkdirs()
        tmpDir(context).mkdirs()
        val argv = mutableListOf(proot.absolutePath)
        argv += argv(context, prefixHost, command, root)
        return try {
            val pb = ProcessBuilder(argv)
            val env = pb.environment()
            processEnv(context, prefixHost, envExtra).forEach { (k, v) -> env[k] = v }
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val done = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!done) {
                p.destroyForcibly()
                return ""
            }
            out.trim()
        } catch (_: Throwable) { "" }
    }
}
