package com.example.myempty.githubk.terminal.zerotermux

import android.content.Context
import android.net.Uri
import com.example.myempty.githubk.terminal.BuiltinRuntime
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * ZeroTermux-style extension layer for GitHubK Studio.
 *
 * This layer does not pretend that Android's /system shell is Termux. It works
 * on the same private PREFIX used by the native PTY runtime and exposes the
 * useful ZeroTermux features: mirror switching, runtime backup/restore,
 * proot-distro/container helpers, X11/QEMU environment helpers, storage setup
 * and plugin diagnostics.
 *
 * Commands that need an interactive shell are intentionally returned to the
 * real PTY terminal instead of being executed through a pipe.
 */
class ZeroTermuxManager(private val context: Context, private val runtime: BuiltinRuntime) {
    data class Feature(val id: String, val title: String, val description: String, val command: String? = null)
    data class Mirror(val id: String, val title: String, val url: String)

    val mirrors = listOf(
        Mirror("official", "Termux 官方源", "https://packages.termux.dev/apt/termux-main"),
        Mirror("tuna", "清华 TUNA", "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"),
        Mirror("ustc", "中科大 USTC", "https://mirrors.ustc.edu.cn/termux/apt/termux-main"),
        Mirror("bfsu", "北外 BFSU", "https://mirrors.bfsu.edu.cn/termux/apt/termux-main")
    )

    fun features(): List<Feature> = listOf(
        Feature("mirror", "软件源切换", "官方 / TUNA / USTC / BFSU，一键切换 APT 镜像"),
        Feature("pkg", "软件包管理", "更新、升级、安装 vim/htop/tmux/git/python/node", "pkg update && pkg upgrade"),
        Feature("proot", "PRoot-Distro / Linux 容器", "Ubuntu、Debian、Alpine 等用户态 Linux", "pkg install proot-distro && proot-distro list"),
        Feature("backup", "运行时备份", "备份 PREFIX、HOME、配置和已安装扩展"),
        Feature("restore", "运行时恢复", "从 tar/tar.gz/tar.xz 恢复到当前 PREFIX"),
        Feature("x11", "Termux:X11 环境", "准备 DISPLAY、XDG_RUNTIME_DIR 和 X11 socket 环境", "export DISPLAY=:0; export XDG_RUNTIME_DIR=\$TMPDIR"),
        Feature("qemu", "QEMU 用户态", "检测并安装 qemu-user-*，用于跨架构 PRoot", "pkg search qemu-user"),
        Feature("storage", "共享存储", "执行 termux-setup-storage 并检查 ~/storage", "termux-setup-storage"),
        Feature("plugins", "插件诊断", "检测 Termux:API、Styling、Tasker、Widget 等外部插件"),
        Feature("diagnostics", "环境诊断", "检查 PREFIX、PATH、bash、apt、pkg、proot-distro、qemu"),
        Feature("tmux", "终端工具集", "安装 vim、nano、less、htop、tmux", "pkg install vim nano less htop tmux")
    )

    fun runtimeRoot(): File = runtime.root
    fun prefix(): File = runtime.root
    fun home(): File = runtime.home

    fun diagnostics(): String {
        val p = runtime.root
        val bins = listOf("bash", "sh", "pkg", "apt", "git", "python", "node", "proot", "proot-distro", "qemu-aarch64", "qemu-x86_64")
        val sb = StringBuilder()
        sb.append("GitHubK / ZeroTermux diagnostics\n")
        sb.append("PREFIX=${p.absolutePath}\n")
        sb.append("HOME=${runtime.home.absolutePath}\n")
        sb.append("runtimeInstalled=${runtime.isInstalled()}\n")
        bins.forEach { b ->
            val f = File(p, "bin/$b")
            sb.append(String.format("%-16s %s\n", b, if (f.exists()) f.absolutePath else "missing"))
        }
        val aptFiles = listOf(
            File(p, "etc/apt/sources.list"),
            File(p, "etc/apt/sources.list.d/termux-main.list"),
            File(p, "etc/apt/sources.list.d/termux-root.list")
        )
        sb.append("APT sources:\n")
        aptFiles.filter { it.exists() }.forEach { sb.append("  ${it.absolutePath}\n") }
        return sb.toString()
    }

    /** Rewrite the common Termux source files. A backup is created before editing. */
    fun switchMirror(mirror: Mirror): Result<String> {
        val etc = File(runtime.root, "etc/apt")
        if (!etc.exists()) return Result.failure(IllegalStateException("APT 目录不存在，请先安装 Termux bootstrap"))
        val candidates = listOf(
            File(etc, "sources.list"),
            File(etc, "sources.list.d/termux-main.list")
        )
        val existing = candidates.filter { it.exists() }
        if (existing.isEmpty()) return Result.failure(IllegalStateException("没有找到 Termux APT 源文件"))
        return runCatching {
            existing.forEach { file ->
                val backup = File(file.parentFile, file.name + ".githubk.bak")
                file.copyTo(backup, overwrite = true)
                val lines = file.readLines()
                val rewritten = if (lines.any { it.trimStart().startsWith("deb ") }) {
                    lines.joinToString("\n") { line ->
                        if (line.trimStart().startsWith("deb ")) "deb ${mirror.url} stable main" else line
                    } + "\n"
                } else {
                    "deb ${mirror.url} stable main\n"
                }
                file.writeText(rewritten)
            }
            "已切换到：${mirror.title}\n${mirror.url}"
        }
    }

    /** Create a tar.gz backup inside the app's files/zero-backups directory. */
    fun backupInternal(): Result<File> {
        val outDir = File(context.filesDir, "zero-backups").apply { mkdirs() }
        val out = File(outDir, "githubk-termux-${System.currentTimeMillis()}.tar.gz")
        val ok = runCommand(listOf("/system/bin/sh", "-c", "tar -czf '${quote(out.absolutePath)}' -C '${quote(runtime.root.absolutePath)}' ."), 10)
        return if (ok.exitCode == 0 && out.exists() && out.length() > 0) Result.success(out)
        else Result.failure(IllegalStateException("tar 备份失败: ${ok.output}"))
    }

    fun restoreFromUri(uri: Uri): Result<Unit> {
        val tmp = File(context.cacheDir, "restore-${System.currentTimeMillis()}.tar.gz")
        return runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取备份文件" }
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            }
            runtime.root.mkdirs()
            val result = runCommand(
                listOf("/system/bin/sh", "-c", "tar -xzf '${quote(tmp.absolutePath)}' -C '${quote(runtime.root.absolutePath)}'"),
                15
            )
            if (result.exitCode != 0) error("tar 恢复失败: ${result.output}")
        }.also { tmp.delete() }
    }

    fun storageCommand(): String = "termux-setup-storage"
    fun x11Command(): String = "export DISPLAY=:0; export XDG_RUNTIME_DIR=\$TMPDIR; mkdir -p \$TMPDIR/.X11-unix; echo 'X11 environment ready: DISPLAY=\$DISPLAY'"
    fun qemuCommand(): String = "pkg search qemu-user && echo '安装对应架构后可使用 proot-distro login --emulator PATH'"
    fun prootCommand(): String = "pkg install proot-distro && proot-distro list"
    fun packageCommand(): String = "pkg update && pkg upgrade && pkg install git python nodejs vim nano less htop tmux"

    fun pluginDiagnostics(): String {
        val pm = context.packageManager
        val candidates = listOf(
            "com.termux.api" to "Termux:API",
            "com.termux.styling" to "Termux:Styling",
            "com.termux.tasker" to "Termux:Tasker",
            "com.termux.widget" to "Termux:Widget",
            "com.termux.boot" to "Termux:Boot",
            "com.termux.x11" to "Termux:X11"
        )
        return buildString {
            append("External Termux plugins\n")
            candidates.forEach { (pkg, label) ->
                val installed = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
                append("${if (installed) "✓" else "·"} $label ($pkg)\n")
            }
        }
    }

    private data class CmdResult(val exitCode: Int, val output: String)

    private fun runCommand(args: List<String>, timeoutMinutes: Long): CmdResult {
        return try {
            val p = ProcessBuilder(args).redirectErrorStream(true).start()
            val output = p.inputStream.bufferedReader().use { it.readText() }
            if (!p.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                p.destroyForcibly()
                CmdResult(124, output + "\nTIMEOUT")
            } else CmdResult(p.exitValue(), output)
        } catch (t: Throwable) {
            CmdResult(1, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun quote(path: String): String = "'" + path.replace("'", "'\\''") + "'"
}
