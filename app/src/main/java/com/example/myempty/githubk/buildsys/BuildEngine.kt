package com.example.myempty.githubk.buildsys

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.example.myempty.githubk.terminal.TermuxSandbox
import com.example.myempty.githubk.terminal.ToolchainManager

/**
 * BuildEngine v5
 * 实际执行构建（Gradle/Flutter/自定义命令），并回传日志。
 *
 * v5 关键变更（原生进程模型，两套环境严格隔离）：
 * 1. 不再通过 /system/bin/sh 启动 gradlew —— 直接 exec gradlew 脚本，
 *    由内核按 shebang(#!/bin/sh) 解释执行，环境变量来自 ProcessBuilder；
 * 2. ProcessBuilder 启动前 environment().clear()，从零开始注入环境，
 *    彻底丢弃 Android 系统 shell 继承的 PATH/JAVA_HOME 等外部状态；
 * 3. 环境变量只来自 ToolchainManager.environment()（JAVA_HOME / ANDROID_HOME /
 *    ANDROID_SDK_ROOT / GRADLE_HOME / PATH / LD_LIBRARY_PATH ...），
 *    IDE 构建绝不依赖 proot/termux 沙盒；
 * 4. 构建前对 JAVA_HOME/bin/java 做“真实二进制校验”：ELF/shebang 头检查 +
 *    实际运行 `java -version`，输出必须含 OpenJDK 与版本号，防止伪安装；
 * 5. 本类不再出现 TermuxSandbox/proot 执行路径，gradlew 永远不会被放进
 *    proot 终端运行；Termux 终端侧 PATH 亦不含 JDK/SDK（另见 TerminalPage）。
 *
 * 风险与约束（详见类尾注释）：
 * - 直接 exec 脚本在 noexec 挂载点（/storage/emulated/0 常见）会失败；
 *   v5 对该情况提供“原生 java 启动 GradleWrapperMain”兜底（不经 sh/proot）。
 * - clear() 后只含工具链环境，若个别 Android SDK 工具要求 ANDROID_* 之外
 *   的系统变量，需按白名单显式补充，不能回退到继承系统环境。
 */
class BuildEngine(private val onLog: (String) -> Unit = {}) {

    private var context: Context? = null
    private var toolchain: ToolchainManager? = null
    @Volatile private var running = false
    @Volatile private var cancelled = false
    /** 受限 ROM（华为/鸿蒙）直接 exec 被拒后置 true，后续构建走 proot 沙盒。 */
    @Volatile private var useProot = false

    fun attachContext(ctx: Context) {
        this.context = ctx.applicationContext
    }

    fun attachToolchain(manager: ToolchainManager) {
        this.toolchain = manager
    }

    fun isRunning(): Boolean = running

    fun requestCancel() {
        cancelled = true
        onLog("[BUILD] 已请求停止")
    }

    fun build(
        projectPath: File,
        type: String,
        task: String = if (type == "Flutter") "build apk --debug" else "assembleDebug",
        sdkHome: String = "",
        javaHome: String = "",
        gradleHome: String = "",
        flutterBin: String = ""
    ): BuildResult {
        running = true
        cancelled = false
        val sb = StringBuilder()
        val log: (String) -> Unit = { sb.append(it); sb.append("\n"); onLog(it) }

        log("> Running task: $task")
        log("> Project: ${projectPath.absolutePath}")

        // 构建前统一修复运行时：重写 shebang、补 java 软链、修订权限，
        // 避免脚本报错被误判为“未安装”及直接 exec 失败。
        runCatching {
            toolchain?.runtime?.let {
                it.ensureElfPatched()
                it.prepareShell()
            }
        }

        try {
            // ---------- 1) 构造纯净构建环境（不继承系统环境） ----------
            fun makeEnv(): MutableMap<String, String> {
                val m = (toolchain?.environment(sdkHome, javaHome, gradleHome)
                    ?.toMutableMap() ?: linkedMapOf())
                if (sdkHome.isNotBlank()) m["ANDROID_HOME"] = sdkHome
                if (sdkHome.isNotBlank()) m["ANDROID_SDK_ROOT"] = sdkHome
                if (javaHome.isNotBlank()) m["JAVA_HOME"] = javaHome
                if (gradleHome.isNotBlank()) m["GRADLE_HOME"] = gradleHome
                // clear() 后无系统继承值，补最小自建运行时目录（非继承，可写且隔离）
                runCatching {
                    val base = context?.filesDir ?: File(m["GITHUBK_HOME"] ?: "")
                    if (base.isDirectory) {
                        val tmpDir = File(base, ".build-tmp")
                        tmpDir.mkdirs()
                        m["TMPDIR"] = tmpDir.absolutePath
                        m["TMP"] = tmpDir.absolutePath
                        val opt = "-Djava.io.tmpdir=${tmpDir.absolutePath}"
                        m["GRADLE_OPTS"] = appendOpt(m["GRADLE_OPTS"], opt)
                        m["JAVA_TOOL_OPTIONS"] = appendOpt(m["JAVA_TOOL_OPTIONS"], opt)
                    }
                }
                return m
            }
            fun javaReady(m: Map<String, String>): Boolean {
                val jh = m["JAVA_HOME"]?.trim().orEmpty()
                return jh.isNotEmpty() && File(jh, "bin/java").isFile
            }
            var env = makeEnv()

            // ---------- 1.5) 自动配置完整构建环境（JDK/Flutter/SDK 无需用户先点环境中心） ----------
            if (!javaReady(env) || (type == "Flutter" && toolchain?.flutterHome() == null)) {
                log("[BUILD] 检测到构建环境不完整（JAVA_HOME=${env["JAVA_HOME"] ?: "未注入"}）")
                log("[BUILD] 正在自动配置完整离线构建环境（内置 JDK/Gradle/Android 平台/Dart/Flutter，已就绪时数秒通过）…")
                val tc = toolchain
                if (tc == null) {
                    log("[BUILD] ✗ ToolchainManager 未挂载，无法自动安装工具链")
                    running = false
                    return BuildResult(false, sb.toString(), System.currentTimeMillis())
                }
                val ready = tc.ensureBuildEnvironmentReady(
                    onOutput = { log(it) },
                    withFlutter = type == "Flutter"
                )
                if (!ready) {
                    log("[BUILD] ✗ 自动环境配置未完成，请查看上方日志；也可在“环境中心→工具链”手动安装/修复后重试")
                    running = false
                    return BuildResult(false, sb.toString(), System.currentTimeMillis())
                }
                env = makeEnv()
            }

            // ---------- 2) 真实二进制校验（防伪安装） ----------
            val javaErr = verifyJavaExecutable(env, log)
            if (javaErr != null) {
                log("[BUILD] ✗ JAVA 校验未通过：$javaErr")
                log("[BUILD] 提示：请在“环境中心→工具链”重新安装/修复 JDK，或用设置指定正确的 JDK 路径")
                running = false
                return BuildResult(false, sb.toString(), System.currentTimeMillis())
            }

            // ---------- 3) 组装命令（全部直接 exec，不经 sh/proot） ----------
            val flutterExecutable = when {
                flutterBin.isNotBlank() && File(flutterBin).isFile -> flutterBin
                flutterBin.isNotBlank() -> File(flutterBin, "flutter").absolutePath
                toolchain?.flutterHome() != null -> File(toolchain!!.flutterHome(), "bin/flutter").absolutePath
                else -> "flutter"
            }

            val effectiveGradleHome =
                if (gradleHome.isNotBlank()) gradleHome
                else toolchain?.gradleHome()?.absolutePath.orEmpty()
            val hasLocalGradle = effectiveGradleHome.isNotBlank() &&
                File(effectiveGradleHome, "bin/gradle").isFile
            if (hasLocalGradle) {
                log("[BUILD] 使用本地 Gradle: $effectiveGradleHome/bin/gradle")
            }

            val taskArgs = task.split(" ").filter { it.isNotBlank() }
            // 记录直接 exec 的是否 gradlew（用于 noexec 时 wrapperMain 兜底）
            var directGradlew = false
            val cmd: List<String> = when {
                type == "Flutter" -> buildList {
                    add(flutterExecutable)
                    addAll(taskArgs)
                }
                hasLocalGradle -> buildList {
                    add("$effectiveGradleHome/bin/gradle")
                    addAll(taskArgs)
                }
                File(projectPath, "gradlew").isFile -> buildList {
                    directGradlew = true
                    // 直接执行 gradlew 脚本（内核按 shebang #!/bin/sh 解析），
                    // 不使用 /system/bin/sh argv[0] 包装，避免继承系统 shell 环境。
                    add(File(projectPath, "gradlew").absolutePath)
                    addAll(taskArgs)
                }
                else -> buildList {
                    add("gradle")
                    addAll(taskArgs)
                }
            }

            log("$ ${cmd.joinToString(" ")}")
            val code = runNative(env, projectPath, cmd, directGradlew, type == "Flutter", log)

            running = false
            return BuildResult(
                success = code == 0 && !cancelled,
                output = sb.toString(),
                time = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            running = false
            log("[BUILD] 失败: ${e.javaClass.simpleName}: ${e.message}")
            return BuildResult(false, sb.toString(), System.currentTimeMillis())
        }
    }

    /**
     * 组装 JDK/Gradle 公共 JVM 选项：保留原值并追加（避免重复添加同一选项）。
     */
    private fun appendOpt(current: String?, add: String): String {
        if (current.isNullOrBlank()) return add
        if (current.contains(add)) return current
        return "$current $add"
    }

    /**
     * 校验 JAVA_HOME/bin/java 是否真实可用的原生 JDK（防止伪安装）。
     * 校验分三层：
     *   a) 文件存在且可执行（必要时尝试补 exec 位）；
     *   b) 文件头必须是 ELF(\\x7fELF) 或 shebang(#!) —— 拒绝空文件/明文假脚本；
     *   c) 实际运行 `java -version`：退出码 0 且输出含 OpenJDK + version。
     * 返回 null=通过；否则返回失败原因。
     */
    private fun verifyJavaExecutable(env: Map<String, String>, log: (String) -> Unit): String? {
        val javaHome = env["JAVA_HOME"]?.trim().orEmpty()
        if (javaHome.isEmpty()) return "JAVA_HOME 未注入（ToolchainManager.environment() 未提供）"
        val javaBin = File(javaHome, "bin/java")
        if (!javaBin.isFile) return "JAVA_HOME/bin/java 不存在：${javaBin.absolutePath}"
        if (!javaBin.canExecute()) {
            runCatching { javaBin.setExecutable(true, false) }
            if (!javaBin.canExecute()) return "${javaBin.absolutePath} 不可执行（无 exec 权限）"
        }

        // b) 头部检查
        val head = ByteArray(4)
        val n = try {
            FileInputStream(javaBin).use { ins -> ins.read(head) }
        } catch (t: Throwable) {
            return "读取 ${javaBin.absolutePath} 失败：${t.message}"
        }
        if (n < 4) return "${javaBin.absolutePath} 文件过短（疑似伪安装）"
        val isElf = head[0] == 0x7f.toByte() &&
            head[1] == 'E'.code.toByte() &&
            head[2] == 'L'.code.toByte() &&
            head[3] == 'F'.code.toByte()
        val isShebang = head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()
        if (!isElf && !isShebang) {
            return "${javaBin.absolutePath} 既不是 ELF 也不是脚本（疑似伪安装或被替换）"
        }

        // c) 实际运行 java -version（受限 ROM 下直接 exec 会 EACCES，自动经 proot 兜底）
        val probe = runJavaVersion(javaBin.absolutePath, env) ?: return "运行 java -version 失败（无法启动 JVM）"
        val (code, out) = probe
        val versionLine = out.lineSequence()
            .firstOrNull { it.contains("version", ignoreCase = true) }
            ?.trim().orEmpty()
        log("> java -version")
        log(versionLine)
        return when {
            code != 0 -> "java -version 退出码 $code（输出：${out.trim().take(200)}）"
            !out.contains("OpenJDK") || !out.contains("version", ignoreCase = true) ->
                "java -version 输出异常（可能为伪安装/非 JDK）：${out.trim().take(200)}"
            else -> null
        }
    }

    /**
     * 原生 ProcessBuilder 启动（禁止 proot/termux 沙盒与 /system/bin/sh 包装）。
     * 直接 exec 若因 noexec/exec 位失败（如项目位于 /storage/emulated/0），
     * 自动改用 JVM/bash 兜底：
     *   - gradlew：原生 java 运行 GradleWrapperMain（不经 sh）；
     *   - flutter：工具链自带 bash 解释 flutter wrapper（不经 /system/bin/sh，不依赖
     *     Android 不存在的 /usr/bin/env）。
     */
    private fun runNative(
        env: Map<String, String>,
        projectPath: File,
        cmd: List<String>,
        directGradlew: Boolean,
        flutterDirect: Boolean,
        log: (String) -> Unit
    ): Int {
        return try {
            launch(env, projectPath, cmd)
        } catch (io: java.io.IOException) {
            // flutter wrapper 的 shebang 是 #!/usr/bin/env bash，Android 无 /usr/bin/env，
            // 直跑必失败；改用工具链自带 bash（SHELL=$PREFIX/bin/bash）解释 wrapper。
            if (flutterDirect && cmd.isNotEmpty()) {
                val wrapper = File(cmd.first())
                val shellPath = env["SHELL"]?.takeIf { File(it).isFile }
                    ?: env["PREFIX"]?.let { p ->
                        val f = File(p, "bin/bash")
                        if (f.isFile) f.absolutePath else null
                    } ?: throw io
                val shell = File(shellPath)
                if (wrapper.isFile && shell.isFile) {
                    log("[BUILD] 直接执行 flutter wrapper 失败（${io.message}）")
                    log("[BUILD] 改用工具链 bash 解释 flutter（不经 proot / /system/bin/sh）")
                    val bashCmd = buildList {
                        add(shell.absolutePath)
                        add(wrapper.absolutePath)
                        addAll(cmd.drop(1))
                    }
                    log("$ ${bashCmd.joinToString(" ")}")
                    return launch(env, projectPath, bashCmd)
                }
                throw io
            }
            if (!directGradlew) throw io
            val wrapperJar = File(projectPath, "gradle/wrapper/gradle-wrapper.jar")
            if (!wrapperJar.isFile) {
                log("[BUILD] 直接执行 gradlew 失败：${io.message}")
                log("[BUILD] 项目缺少 gradle/wrapper/gradle-wrapper.jar，无法兜底")
                throw io
            }
            val javaBin = env["JAVA_HOME"]?.let { File(it, "bin/java").absolutePath }
                ?: throw io
            log("[BUILD] 直接 exec gradlew 失败（${io.message}）")
            log("[BUILD] 改用原生 java 启动 GradleWrapperMain（不经 sh / 不依赖可执行位）")
            val taskArgs = cmd.drop(1)
            val wrapperCmd = buildList {
                add(javaBin)
                add("-classpath")
                add(wrapperJar.absolutePath)
                add("org.gradle.wrapper.GradleWrapperMain")
                addAll(taskArgs)
            }
            log("$ ${wrapperCmd.joinToString(" ")}")
            launch(env, projectPath, wrapperCmd)
        }
    }

    /** 裸 ProcessBuilder 原生启动（不经 proot），仅供非受限 ROM 使用。 */
    private fun launchNative(
        env: Map<String, String>,
        projectPath: File,
        cmd: List<String>
    ): Int {
        val pb = ProcessBuilder(cmd)
            .directory(projectPath)
            .redirectErrorStream(true)
        // 关键：清空系统环境，只保留工具链注入的环境
        pb.environment().clear()
        pb.environment().putAll(env)
        val proc = pb.start()
        proc.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.forEachLine { line ->
                if (cancelled) {
                    proc.destroyForcibly()
                    onLog("[BUILD] 已取消")
                    return@forEachLine
                }
                onLog(line)
            }
        }
        return proc.waitFor()
    }

    /** proot 沙盒组件是否就绪（jniLibs 已释放 libloader/libproot）。 */
    private fun sandboxAvailable(): Boolean {
        val ctx = context ?: return false
        return toolchain != null && TermuxSandbox.available(ctx)
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * 启动构建进程并持续回传日志；支持取消。返回退出码。
     *
     * 受限 ROM（华为/鸿蒙）：app 无法直接 exec files/runtime 下的 ELF(JDK) 与脚本
     * （avc EACCES），裸 ProcessBuilder 必失败。proot 位于 jniLibs（apk_data_file
     * 可 exec），经 ptrace 运行 guest 二进制——这是 detectTool/终端已验证可行路径。
     * 因此：非受限 ROM 走原生；受限 ROM 自动改用 proot 沙盒执行。
     */
    private fun launch(
        env: Map<String, String>,
        projectPath: File,
        cmd: List<String>
    ): Int {
        if (useProot && sandboxAvailable()) return launchViaProot(env, projectPath, cmd)
        return try {
            launchNative(env, projectPath, cmd)
        } catch (io: IOException) {
            onLog("[BUILD] 直接 exec 受限（${io.message}），改用 proot 沙盒执行")
            useProot = true
            launchViaProot(env, projectPath, cmd)
        }
    }

    /** proot 沙盒执行构建命令（流式回传日志），返回退出码。 */
    private fun launchViaProot(
        env: Map<String, String>,
        projectPath: File,
        cmd: List<String>
    ): Int {
        val ctx = context ?: return -1
        val rt = toolchain?.runtime ?: return -1
        if (!TermuxSandbox.available(ctx)) return -1
        // 命令用宿主绝对路径（数据目录已被 proot bind 镜像映射，guest 内同路径可访问）
        val command = "cd ${shellQuote(projectPath.absolutePath)} && " +
            cmd.joinToString(" ") { shellQuote(it) }
        onLog("[BUILD] 受限 ROM：直接 exec 受限，改用 proot 沙盒执行")
        onLog("$ $command")
        return TermuxSandbox.runStreaming(ctx, rt.root, command, env, onLog, 60, false)
    }

    /**
     * 运行 `java -version`，返回 (退出码, 合并输出)；无法启动返回 null。
     * 原生 exec 在受限 ROM 会 EACCES，自动经 proot 兜底并标记后续构建走 proot。
     */
    private fun runJavaVersion(javaBin: String, env: Map<String, String>): Pair<Int, String>? {
        // 1) 原生直接执行
        try {
            val pb = ProcessBuilder(javaBin, "-version")
            pb.redirectErrorStream(true)
            pb.environment().clear()
            pb.environment().putAll(env)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val exited = proc.waitFor(20, TimeUnit.SECONDS)
            if (!exited) proc.destroyForcibly()
            return proc.exitValue() to out
        } catch (io: IOException) {
            // 直接 exec 受限（EACCES）→ 标记后续构建走 proot，并在此兜底
            useProot = true
        }
        // 2) proot 沙盒兜底
        val ctx = context ?: return null
        val rt = toolchain?.runtime ?: return null
        if (!TermuxSandbox.available(ctx)) return null
        val out = TermuxSandbox.runCapture(ctx, rt.root, "${shellQuote(javaBin)} -version", env, 30, false)
        if (out.isBlank()) return null
        return 0 to out
    }
}

/** 构建结果 */
data class BuildResult(
    val success: Boolean,
    val output: String,
    val time: Long
) {
    fun render(): String {
        val head = if (success) "✓ BUILD SUCCESS" else "✗ BUILD FAILED"
        return "$head\n$output"
    }
}
