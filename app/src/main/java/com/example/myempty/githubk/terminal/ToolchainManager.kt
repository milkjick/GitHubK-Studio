package com.example.myempty.githubk.terminal

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * GitHubK Toolchain Center 4.3.
 *
 * 统一管理 IDE 构建环境：
 *   Shizuku UserService -> run-as -> BuiltinRuntime -> JDK/Gradle/SDK
 *
 * 不把 PATH、JAVA_HOME、ANDROID_HOME 散落在 BuildEngine/UI 中，避免不同入口
 * 使用不同环境导致“终端能编译、IDE 不能编译”的问题。
 */
data class ToolchainStatus(
    val runtime: Boolean,
    val java: Boolean,
    val gradle: Boolean,
    val git: Boolean,
    val sdk: Boolean,
    val adb: Boolean,
    val dart: Boolean,
    val flutter: Boolean,
    val ready: Boolean,
    val javaVersion: String = "",
    val gradleVersion: String = "",
    val sdkPath: String = ""
)

class ToolchainManager(private val context: Context) {
    private val app = context.applicationContext
    val runtime = BuiltinRuntime(app)
    private val downloader = ToolchainDownloadManager(app, runtime)

    // 状态结果内存缓存：避免 Settings/环境中心/首页多处同步检测时反复启动
    // bash/JVM 子进程（gradle --version 每次可达 1~3s，是进入页面卡顿的主因）。
    @Volatile private var statusCache: ToolchainStatus? = null
    @Volatile private var statusCacheAt: Long = 0L
    @Volatile private var statusRunning: Boolean = false
    private val statusLock = Any()

    /** 使状态缓存立即失效（安装/导入完成后调用，保证下一次 status 真实刷新）。 */
    fun invalidateStatusCache() { synchronized(statusLock) { statusCache = null; statusCacheAt = 0L } }

    /**
     * 异步获取状态：不阻塞调用线程。若已有 [ttlMs] 内的缓存，直接在主线程回调；
     * 否则在独立线程执行真实检测（status 内部会再走 3s 短缓存去抖）。
     */
    fun statusAsync(onResult: (ToolchainStatus) -> Unit, ttlMs: Long = 4000L) {
        synchronized(statusLock) {
            val now = System.currentTimeMillis()
            val cached = statusCache
            if (cached != null && now - statusCacheAt < ttlMs) {
                onResult(cached)
                return
            }
        }
        Thread {
            val s = status()
            synchronized(statusLock) { statusCache = s; statusCacheAt = System.currentTimeMillis() }
            onResult(s)
        }.start()
    }

    fun sdkPath(user: String = ""): File? {
        val candidates = buildList {
            if (user.isNotBlank()) add(File(user))
            val stored = app.getSharedPreferences("githubk", Context.MODE_PRIVATE)
                .getString("sdk_home", "") ?: ""
            if (stored.isNotBlank()) add(File(stored))
            add(File(runtime.home, "android-sdk"))
            add(File("/sdcard/Android/sdk"))
        }
        return candidates.firstOrNull { File(it, "platforms").isDirectory || File(it, "platform-tools").exists() }
    }

    fun javaHome(user: String = ""): File? {
        if (user.isNotBlank() && File(user).isDirectory) return File(user)
        val candidates = listOf(
            File(runtime.prefix, "lib/jvm/java-17-openjdk"),
            File(runtime.prefix, "lib/jvm/java-21-openjdk"),
            File(runtime.prefix, "opt/java"),
            File("/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk")
        )
        return candidates.firstOrNull { File(it, "bin/java").exists() }
    }

    fun gradleHome(user: String = ""): File? {
        if (user.isNotBlank()) {
            val f = File(user)
            if (File(f, "bin/gradle").exists()) return f
        }
        val candidates = listOf(
            File(runtime.prefix),
            File("/data/data/com.termux/files/usr")
        )
        return candidates.firstOrNull { File(it, "bin/gradle").exists() }
    }

    fun dartHome(user: String = ""): File? {
        val prefs = app.getSharedPreferences("githubk", Context.MODE_PRIVATE)
        val configured = if (user.isNotBlank()) user else (prefs.getString("dart_home", "") ?: "")
        val candidates = buildList {
            if (configured.isNotBlank()) add(File(configured))
            add(File(runtime.prefix, "lib/dart-sdk"))
            add(File(runtime.prefix, "opt/dart-sdk"))
            add(File(runtime.home, "dart-sdk"))
            add(File(runtime.home, "opt/dart-sdk"))
            add(File("/sdcard/Download/dart-sdk"))
        }
        return candidates.firstOrNull { File(it, "bin/dart").exists() }
    }

    fun flutterHome(user: String = ""): File? {
        val prefs = app.getSharedPreferences("githubk", Context.MODE_PRIVATE)
        val configured = if (user.isNotBlank()) user else (prefs.getString("flutter_home", "") ?: "")
        val configuredBin = prefs.getString("flutter_bin", "") ?: ""
        val candidates = buildList {
            if (configured.isNotBlank()) add(File(configured))
            if (configuredBin.isNotBlank()) {
                val f = File(configuredBin)
                add(if (f.name == "flutter") f.parentFile?.parentFile ?: f else f)
            }
            add(File(runtime.prefix, "opt/flutter"))
            add(File(runtime.prefix, "share/flutter"))
            add(File(runtime.home, "flutter"))
            add(File(runtime.home, "flutter-sdk"))
            add(File("/sdcard/Download/flutter"))
        }
        return candidates.firstOrNull { File(it, "bin/flutter").exists() }
    }

    /** 为 IDE/Agent/Terminal 构造完全一致的环境。 */
    fun environment(
        sdk: String = "",
        java: String = "",
        gradle: String = "",
        dart: String = "",
        flutter: String = ""
    ): MutableMap<String, String> {
        val out = linkedMapOf<String, String>()
        if (runtime.isInstalled()) out.putAll(runtime.env())

        val sdkDir = sdkPath(sdk)
        if (sdkDir != null) {
            out["ANDROID_HOME"] = sdkDir.absolutePath
            out["ANDROID_SDK_ROOT"] = sdkDir.absolutePath
            out["PATH"] = sdkDir.resolve("platform-tools").absolutePath + ":" +
                sdkDir.resolve("cmdline-tools/latest/bin").absolutePath + ":" +
                sdkDir.resolve("tools/bin").absolutePath + ":" + (out["PATH"] ?: "/system/bin")
        }

        val javaDir = javaHome(java)
        if (javaDir != null) {
            out["JAVA_HOME"] = javaDir.absolutePath
            out["PATH"] = javaDir.resolve("bin").absolutePath + ":" + (out["PATH"] ?: "/system/bin")
        }

        gradleHome(gradle)?.let {
            out["GRADLE_HOME"] = it.absolutePath
            out["PATH"] = it.resolve("bin").absolutePath + ":" + (out["PATH"] ?: "/system/bin")
        }

        val dartDir = dartHome(dart)
        if (dartDir != null) {
            out["DART_HOME"] = dartDir.absolutePath
            out["DART_SDK"] = dartDir.absolutePath
            out["PATH"] = dartDir.resolve("bin").absolutePath + ":" + (out["PATH"] ?: "/system/bin")
        }
        val flutterDir = flutterHome(flutter)
        if (flutterDir != null) {
            out["FLUTTER_HOME"] = flutterDir.absolutePath
            out["PATH"] = flutterDir.resolve("bin").absolutePath + ":" + (out["PATH"] ?: "/system/bin")
        }

        out["GRADLE_USER_HOME"] = File(runtime.home, "gradle-home").absolutePath
        out["GRADLE_OPTS"] = "-Dfile.encoding=UTF-8"
        out["JAVA_TOOL_OPTIONS"] = "-Dfile.encoding=UTF-8"
        out["GITHUBK_HOME"] = app.filesDir.absolutePath
        return out
    }

    fun status(sdk: String = "", java: String = "", gradle: String = ""): ToolchainStatus {
        synchronized(statusLock) {
            val cached = statusCache
            if (cached != null && System.currentTimeMillis() - statusCacheAt < 3000L) return cached
        }
        val s = computeStatus(sdk, java, gradle)
        synchronized(statusLock) { statusCache = s; statusCacheAt = System.currentTimeMillis() }
        return s
    }

    private fun computeStatus(sdk: String, java: String, gradle: String): ToolchainStatus {
        val rt = runtime.isInstalled()
        // 幂等补齐 Android SDK 目录结构（缺失时仅复制一次内置 asset），保证 status 能识别 SDK
        if (sdkPath(sdk) == null) {
            runCatching { ensureAndroidSdkLayout() }
        }
        // 先检测内置 runtime 中的工具，再 fallback 到系统级检测
        val j = detectToolWithFallback("java", "java -version 2>&1 | head -1")
        val g = detectToolWithFallback("gradle", "gradle --version 2>&1 | head -1")
        val git = detectToolWithFallback("git", "git --version 2>&1 | head -1")
        val sdkDir = sdkPath(sdk)
        val sdkOk = sdkDir != null
        val adbOk = sdkDir?.resolve("platform-tools/adb")?.exists() == true ||
            runtime.detectTool("adb").first ||
            detectSystemTool("adb")
        val dartOk = dartHome().let { it != null && File(it, "bin/dart").canExecute() } ||
            detectSystemTool("dart")
        val flutterOk = flutterHome().let { it != null && File(it, "bin/flutter").canExecute() } ||
            detectSystemTool("flutter")
        val ready = j.first && g.first && git.first && sdkOk && adbOk
        return ToolchainStatus(rt, j.first, g.first, git.first, sdkOk, adbOk, dartOk, flutterOk, ready, j.second, g.second, sdkDir?.absolutePath ?: "")
    }

    /**
     * 先检测内置 runtime 中的工具，如果未找到则 fallback 到系统 PATH 检测。
     * 解决问题：即使系统已安装 JDK/Gradle/Git 等，但内置 runtime 未安装时全部显示 x。
     */
    private fun detectToolWithFallback(tool: String, versionCmd: String): Pair<Boolean, String> {
        // 1. 先尝试内置 runtime
        if (runtime.isInstalled()) {
            val rt = runtime.detectTool(tool)
            if (rt.first) return rt
        }
        // 2. Fallback: 检测系统 PATH 中的工具
        if (detectSystemTool(tool)) {
            val ver = runSystemCommand(versionCmd).take(60)
            return true to (if (ver.isNotBlank()) ver else "system: $tool")
        }
        // 3. 检测常见系统路径
        val systemPaths = when (tool) {
            "java" -> listOf("/system/bin/java", "/usr/bin/java", "/data/data/com.termux/files/usr/bin/java")
            "gradle" -> listOf("/data/data/com.termux/files/usr/bin/gradle")
            "git" -> listOf("/system/bin/git", "/usr/bin/git", "/data/data/com.termux/files/usr/bin/git")
            "adb" -> listOf("/system/bin/adb", "/system/xbin/adb")
            else -> emptyList()
        }
        for (path in systemPaths) {
            if (File(path).exists() && File(path).canExecute()) {
                val ver = runSystemCommand("$path --version 2>&1 | head -1").take(60)
                return true to (if (ver.isNotBlank()) ver else path)
            }
        }
        return false to "未安装"
    }

    /** 检测系统 PATH 中是否存在某个命令。 */
    private fun detectSystemTool(cmd: String): Boolean {
        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", "command -v $cmd >/dev/null 2>&1 && echo __found__")
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().use { it.readText().trim() }
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            out.contains("__found__")
        } catch (_: Throwable) { false }
    }

    /** 用系统 sh 执行命令并返回输出。 */
    private fun runSystemCommand(cmd: String): String {
        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().use { it.readText().trim() }
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            out
        } catch (_: Throwable) { "" }
    }

    /**
     * 自动补齐基础 IDE 工具链。SDK 不会被静默下载到未知位置；若已有 sdkmanager，
     * 可由 ensureAndroidSdkComponents() 明确安装平台/Build Tools。
     */
    fun ensureIdeToolchain(onOutput: (String) -> Unit = {}): Boolean {
        // 关键修复：Termux bootstrap 的 ELF 解释器指向 com.termux 私有数据，
        // 必须重写为 /system/bin/linker64 后 bash/apt/git/java 才能执行。
        try { runtime.ensureElfPatched() } catch (_: Throwable) {}
        // 检测前统一准备：修复 shebang、补 java 软链、修正权限，避免脚本报错被误判。
        try { runtime.prepareShell() } catch (_: Throwable) {}
        if (!runtime.isInstalled()) {
            onOutput("[TOOLCHAIN] 内置运行时未安装，开始自动安装...\n")
            try {
                runtime.install { p -> onOutput("[TOOLCHAIN] runtime ${(p * 100).toInt()}%\n") }
            } catch (e: Throwable) {
                onOutput("[TOOLCHAIN] runtime 安装失败：${e.message}\n")
                return false
            }
            if (!runtime.isInstalled()) return false
        }
        val missing = mutableListOf<String>()
        if (!runtime.detectTool("java").first) missing += "openjdk-17"
        if (!runtime.detectTool("gradle").first) missing += "gradle"
        if (!runtime.detectTool("git").first) missing += "git"
        if (missing.isEmpty()) {
            onOutput("[TOOLCHAIN] JDK/Gradle/Git 已就绪\n")
            if (!runtime.detectTool("python3").first) onOutput("[TOOLCHAIN] Python 可选组件未安装（可在环境中心安装，不影响构建）\n")
            if (!runtime.detectTool("node").first) onOutput("[TOOLCHAIN] Node.js 可选组件未安装（可在环境中心安装，不影响构建）\n")
            if (dartHome() == null) onOutput("[TOOLCHAIN] Dart SDK 尚未配置（可在设置中安装/指定）\n")
            if (flutterHome() == null) onOutput("[TOOLCHAIN] Flutter SDK 尚未配置（可在设置中安装/指定）\n")
            return true
        }
        onOutput("[TOOLCHAIN] 缺少：${missing.joinToString(", ")}\n")

        // 最高优先级：APK assets 内置离线 .deb（真正的离线安装，覆盖 JDK/Gradle/Git）
        val bundledDebs = runCatching {
            app.assets.list("offline-toolchain")?.filter { it.endsWith(".deb") }.orEmpty()
        }.getOrDefault(emptyList())
        if (bundledDebs.isNotEmpty()) {
            onOutput("[TOOLCHAIN] 发现 APK 内置离线工具链包 ${bundledDebs.size} 个，开始离线安装...\n")
            val ok = runtime.installPackages(missing, onOutput)
            val stillMissing = mutableListOf<String>()
            if (!runtime.detectTool("java").first) stillMissing += "java"
            if (!runtime.detectTool("gradle").first) stillMissing += "gradle"
            if (!runtime.detectTool("git").first) stillMissing += "git"
            if (ok && stillMissing.isEmpty()) {
                onOutput("[TOOLCHAIN] 内置离线包安装验证通过\n")
                return true
            }
            onOutput("[TOOLCHAIN] 内置离线包安装后仍缺少：${stillMissing.joinToString(", ")}，尝试其他离线源...\n")
        }

        // 其次从外部离线包安装（无需网络）
        if (installFromOfflinePackage(missing, onOutput)) {
            onOutput("[TOOLCHAIN] 离线包安装完成\n")
            // 验证安装结果
            val stillMissing = mutableListOf<String>()
            if (!runtime.detectTool("java").first) stillMissing += "java"
            if (!runtime.detectTool("gradle").first) stillMissing += "gradle"
            if (!runtime.detectTool("git").first) stillMissing += "git"
            if (stillMissing.isEmpty()) {
                onOutput("[TOOLCHAIN] 离线安装验证通过\n")
                return true
            }
            onOutput("[TOOLCHAIN] 离线安装后仍缺少：${stillMissing.joinToString(", ")}\n")
        } else {
            onOutput("[TOOLCHAIN] 离线包不可用，尝试网络安装...\n")
        }

        // 回退到网络安装
        return runtime.installPackages(missing, onOutput)
    }

    /**
     * 从离线包安装 IDE 工具链。
     * 离线包位置：
     *   1. /sdcard/Download/githubk-offline-toolchain/ (用户手动放置)
     *   2. /sdcard/githubk-offline-toolchain/
     *   3. app filesDir/offline-toolchain/ (应用内部)
     * 离线包结构：
     *   jdk/   - Corretto 17 (bin/ + lib/ + conf/ + release)
     *   gradle/ - Gradle 8.5 (lib/ + bin/ + init.d/)
     *   android-sdk/ - Android SDK (platforms/ + build-tools/ + licenses/)
     *   git/   - Git 二进制 + 依赖库
     */
    private fun installFromOfflinePackage(packages: List<String>, onOutput: (String) -> Unit): Boolean {
        // 查找已解压的离线包目录
        val offlineDirs = listOf(
            File("/sdcard/Download/githubk-offline-toolchain"),
            File("/sdcard/githubk-offline-toolchain"),
            File(app.filesDir, "offline-toolchain"),
            File("/storage/emulated/0/Download/githubk-offline-toolchain")
        )
        var offlineRoot = offlineDirs.firstOrNull { File(it, "jdk").exists() || File(it, "gradle").exists() }

        // 如果没有已解压的离线包，尝试从 APK assets 中解压内置的 offline-toolchain.tar.gz
        if (offlineRoot == null) {
            onOutput("[OFFLINE] 未找到外部离线包，尝试从内置 assets 解压...\n")
            val destDir = File(app.filesDir, "offline-toolchain")
            try {
                app.assets.open("offline-toolchain.tar.gz").use { input ->
                    destDir.mkdirs()
                    val tarFile = File(app.filesDir, "offline-toolchain.tar.gz")
                    tarFile.outputStream().use { output -> input.copyTo(output) }
                    onOutput("[OFFLINE] 内置工具链包已复制 (${tarFile.length() / 1024 / 1024}MB)，开始解压...\n")
                    // 用 ProcessBuilder 调用 tar 解压
                    val pb = ProcessBuilder("tar", "xzf", tarFile.absolutePath, "-C", destDir.absolutePath)
                    pb.redirectErrorStream(true)
                    val proc = pb.start()
                    val output = proc.inputStream.bufferedReader().readText()
                    val exitCode = proc.waitFor()
                    if (exitCode == 0) {
                        onOutput("[OFFLINE] 内置工具链解压完成\n")
                        tarFile.delete()
                    } else {
                        onOutput("[OFFLINE] tar 解压失败(exit=$exitCode): $output\n")
                        // 回退：尝试用 Java GZIP + TarInputStream 解压
                        extractTarGzJava(tarFile, destDir, onOutput)
                        tarFile.delete()
                    }
                }
                offlineRoot = destDir
            } catch (e: Throwable) {
                onOutput("[OFFLINE] 内置 assets 解压失败：${e.message}\n")
                // 检查是否部分解压成功
                val partial = offlineDirs.firstOrNull { File(it, "jdk").exists() || File(it, "gradle").exists() }
                if (partial != null) {
                    offlineRoot = partial
                    onOutput("[OFFLINE] 找到部分解压的离线包：${partial.absolutePath}\n")
                }
            }
        }

        if (offlineRoot == null) {
            onOutput("[OFFLINE] 未找到离线工具链包\n")
            onOutput("[OFFLINE] 请将 githubk-offline-toolchain 文件夹放到 /sdcard/Download/\n")
            return false
        }
        return installOfflineComponents(offlineRoot, packages, onOutput)
    }
    /**
     * 安装指定离线目录中的 JDK/Gradle/Android SDK/Git 组件。
     * 调用前须确认 offlineRoot 存在且至少包含 jdk/ 或 gradle/ 布局。
     */
    private fun installOfflineComponents(offlineRoot: File, packages: List<String>, onOutput: (String) -> Unit): Boolean {
        onOutput("[OFFLINE] 找到离线包：${offlineRoot.absolutePath}\n")

        val runtimePrefix = runtime.prefix
        val needChmod = ShizukuShell.isServiceAvailable() && ShizukuShell.hasPermission()
        var allOk = true

        // 安装 JDK
        if (packages.contains("openjdk-17")) {
            val jdkSrc = File(offlineRoot, "jdk")
            if (jdkSrc.exists()) {
                onOutput("[OFFLINE] 安装 JDK 17...\n")
                val jdkDest = File(runtimePrefix, "lib/jvm/java-17-openjdk")
                try {
                    jdkDest.parentFile?.mkdirs()
                    jdkSrc.copyRecursively(jdkDest, overwrite = true)
                    // 创建符号链接
                    val javaLink = File(runtimePrefix, "bin/java")
                    if (!javaLink.exists()) {
                        try {
                            javaLink.parentFile?.mkdirs()
                            javaLink.writeText("#!/system/bin/sh\nexec ${jdkDest.absolutePath}/bin/java \"\$@\"\n")
                            javaLink.setExecutable(true, false)
                        } catch (_: Throwable) {}
                    }
                    val javacLink = File(runtimePrefix, "bin/javac")
                    if (!javacLink.exists()) {
                        try {
                            javacLink.parentFile?.mkdirs()
                            javacLink.writeText("#!/system/bin/sh\nexec ${jdkDest.absolutePath}/bin/javac \"\$@\"\n")
                            javacLink.setExecutable(true, false)
                        } catch (_: Throwable) {}
                    }
                    onOutput("[OFFLINE] JDK 17 安装完成\n")
                } catch (e: Throwable) {
                    onOutput("[OFFLINE] JDK 安装失败：${e.message}\n")
                    allOk = false
                }
            }
        }

        // 安装 Gradle
        if (packages.contains("gradle")) {
            val gradleSrc = File(offlineRoot, "gradle")
            if (gradleSrc.exists()) {
                onOutput("[OFFLINE] 安装 Gradle...\n")
                val gradleDest = File(runtimePrefix, "opt/gradle")
                try {
                    gradleDest.parentFile?.mkdirs()
                    gradleSrc.copyRecursively(gradleDest, overwrite = true)
                    // 创建符号链接
                    val gradleLink = File(runtimePrefix, "bin/gradle")
                    if (!gradleLink.exists()) {
                        try {
                            gradleLink.parentFile?.mkdirs()
                            gradleLink.writeText("#!/system/bin/sh\nexec ${gradleDest.absolutePath}/bin/gradle \"\$@\"\n")
                            gradleLink.setExecutable(true, false)
                        } catch (_: Throwable) {}
                    }
                    onOutput("[OFFLINE] Gradle 安装完成\n")
                } catch (e: Throwable) {
                    onOutput("[OFFLINE] Gradle 安装失败：${e.message}\n")
                    allOk = false
                }
            }
        }

        // 安装 Android SDK
        val sdkSrc = File(offlineRoot, "android-sdk")
        if (sdkSrc.exists()) {
            onOutput("[OFFLINE] 安装 Android SDK...\n")
            val sdkDest = File(runtime.home, "android-sdk")
            try {
                sdkDest.parentFile?.mkdirs()
                sdkSrc.copyRecursively(sdkDest, overwrite = true)
                // 保存 SDK 路径
                app.getSharedPreferences("githubk", Context.MODE_PRIVATE)
                    .edit().putString("sdk_home", sdkDest.absolutePath).apply()
                onOutput("[OFFLINE] Android SDK 安装完成\n")
            } catch (e: Throwable) {
                onOutput("[OFFLINE] Android SDK 安装失败：${e.message}\n")
            }
        }

        // 安装 Git
        if (packages.contains("git")) {
            val gitSrc = File(offlineRoot, "git/git")
            if (gitSrc.exists()) {
                onOutput("[OFFLINE] 安装 Git...\n")
                try {
                    // 复制 git 主二进制
                    val gitDest = File(runtimePrefix, "bin/git")
                    gitDest.parentFile?.mkdirs()
                    gitSrc.copyTo(gitDest, overwrite = true)
                    gitDest.setExecutable(true, false)

                    // 复制 git 依赖库到 lib 目录
                    val gitLibSrc = File(offlineRoot, "git/lib")
                    if (gitLibSrc.exists()) {
                        val libDest = File(runtimePrefix, "lib")
                        gitLibSrc.copyRecursively(libDest, overwrite = true) { _, _ -> OnErrorAction.SKIP }
                    }

                    // 复制 git-core 子命令
                    val gitCoreSrc = File(offlineRoot, "git/libexec/git-core")
                    if (gitCoreSrc.exists()) {
                        val coreDest = File(runtimePrefix, "libexec/git-core")
                        coreDest.parentFile?.mkdirs()
                        gitCoreSrc.copyRecursively(coreDest, overwrite = true) { _, _ -> OnErrorAction.SKIP }
                        // 设置可执行权限
                        coreDest.walkTopDown().filter { it.isFile }.forEach { file -> file.setExecutable(true, false) }
                    }

                    // 创建 git 的 LD_LIBRARY_PATH wrapper 脚本
                    val gitWrapper = File(runtimePrefix, "bin/git")
                    val realGit = File(runtimePrefix, "bin/git.real")
                    if (!realGit.exists()) {
                        gitDest.renameTo(realGit)
                        gitWrapper.writeText(
                            "#!/system/bin/sh\n" +
                            "export LD_LIBRARY_PATH=\"${runtimePrefix}/lib:\$LD_LIBRARY_PATH\"\n" +
                            "export GIT_EXEC_PATH=\"${runtimePrefix}/libexec/git-core\"\n" +
                            "exec ${realGit.absolutePath} \"\$@\"\n"
                        )
                        gitWrapper.setExecutable(true, false)
                    }
                    onOutput("[OFFLINE] Git 安装完成\n")
                } catch (e: Throwable) {
                    onOutput("[OFFLINE] Git 安装失败：${e.message}\n")
                    allOk = false
                }
            } else {
                onOutput("[OFFLINE] 离线包中未找到 git 二进制\n")
                allOk = false
            }
        }

        // 通过 Shizuku 修复权限
        if (needChmod) {
            try {
                onOutput("[OFFLINE] 修复权限...\n")
                val cmd = "chmod -R 755 ${runtimePrefix}/bin ${runtimePrefix}/lib ${runtimePrefix}/opt 2>/dev/null; " +
                          "find ${runtimePrefix}/lib/jvm -name 'java' -exec chmod 755 {} \\; 2>/dev/null; " +
                          "find ${runtimePrefix}/lib/jvm -name 'javac' -exec chmod 755 {} \\; 2>/dev/null; " +
                          "find ${runtimePrefix}/opt/gradle -name 'gradle' -exec chmod 755 {} \\; 2>/dev/null; " +
                          "echo __done__"
                ShizukuShell.execAsApp(cmd, timeoutSec = 60)
            } catch (_: Throwable) {}
        }

        return allOk
    }

    /** 本地运行时 ZIP 导入（离线安装内置运行时）。 */
    fun installRuntimeFromLocalZip(zipFile: File, onProgress: (Float) -> Unit): Boolean =
        runtime.installRuntimeFromLocalZip(zipFile, onProgress)

    /**
     * 从用户选择的本地目录安装/导入完整离线环境（无需网络）。
     *
     * 支持两种布局：
     *  1. 完整离线包：包含 bin/bash（或 usr/bin/bash，即 Termux prefix）+ jdk/ + gradle/ +
     *     android-sdk/ + git/ + dart-sdk/ + flutter/（均可选）；
     *  2. 单个组件目录：bin/java 所在 JDK 目录、bin/gradle 所在 Gradle 目录、
     *     platforms/ 所在 Android SDK 目录，或含 bin/dart / bin/flutter 的 SDK 目录。
     * 返回 true 表示本次操作没有致命失败（可用 status() 复查）。
     */
    fun installFromLocalOfflineDir(srcRoot: File, onOutput: (String) -> Unit): Boolean {
        if (srcRoot == null || !srcRoot.isDirectory) {
            onOutput("[LOCAL] 所选目录无效\n")
            return false
        }
        var ok = true
        var matchedAny = false
        val coreLayout = File(srcRoot, "jdk").isDirectory || File(srcRoot, "gradle").isDirectory ||
            File(srcRoot, "android-sdk").isDirectory || File(srcRoot, "git").isDirectory

        // 1. 完整离线包中的 runtime（Termux prefix）
        val hasRuntimePrefix = File(srcRoot, "bin/bash").isFile || File(srcRoot, "usr/bin/bash").isFile
        if (hasRuntimePrefix) {
            matchedAny = true
            onOutput("[LOCAL] 检测到内置 runtime，导入 bash/apt/pkg...\n")
            // 完整离线包（runtime+组件）允许整体重装旧环境；仅导入 bootstrap
            // 目录且已有组件时由 importRuntimeRoot 自行保护。
            if (coreLayout && runtime.root.exists() &&
                runtime.root.canonicalFile != srcRoot.canonicalFile) {
                runCatching { runtime.root.deleteRecursively() }
            }
            val runtimeOk = runtime.importRuntimeRoot(srcRoot)
            if (!runtimeOk) {
                onOutput("[LOCAL] runtime 导入失败（bootstrap 目录可能不完整）\n")
                ok = false
            } else {
                onOutput("[LOCAL] runtime 导入完成\n")
            }
        }

        // 2. JDK / Gradle / Android SDK / Git 组件
        if (coreLayout) {
            matchedAny = true
            val coreOk = installOfflineComponents(srcRoot, listOf("openjdk-17", "gradle", "git"), onOutput)
            ok = coreOk && ok
        } else {
            val kind = when {
                File(srcRoot, "bin/java").isFile -> "jdk"
                File(srcRoot, "bin/gradle").isFile -> "gradle"
                File(srcRoot, "platforms").isDirectory || File(srcRoot, "platform-tools").exists() ||
                    File(srcRoot, "cmdline-tools").exists() -> "android-sdk"
                else -> null
            }
            if (kind != null) {
                matchedAny = true
                // 单个组件：直接安装到 runtime 标准位置（不复制整包）
                if (kind == "jdk") {
                    val jdkDest = File(runtime.prefix, "lib/jvm/java-17-openjdk")
                    try {
                        jdkDest.parentFile?.mkdirs()
                        srcRoot.copyRecursively(jdkDest, overwrite = true)
                        val javaLink = File(runtime.prefix, "bin/java")
                        if (!javaLink.exists()) {
                            javaLink.parentFile?.mkdirs()
                            javaLink.writeText("#!/system/bin/sh\nexec ${jdkDest.absolutePath}/bin/java \"\$@\"\n")
                            javaLink.setExecutable(true, false)
                        }
                        onOutput("[LOCAL] JDK 17 安装完成\n")
                    } catch (e: Throwable) {
                        onOutput("[LOCAL] JDK 安装失败：${e.message}\n")
                        ok = false
                    }
                } else if (kind == "gradle") {
                    val gradleDest = File(runtime.prefix, "opt/gradle")
                    try {
                        gradleDest.parentFile?.mkdirs()
                        srcRoot.copyRecursively(gradleDest, overwrite = true)
                        val gradleLink = File(runtime.prefix, "bin/gradle")
                        if (!gradleLink.exists()) {
                            gradleLink.parentFile?.mkdirs()
                            gradleLink.writeText("#!/system/bin/sh\nexec ${gradleDest.absolutePath}/bin/gradle \"\$@\"\n")
                            gradleLink.setExecutable(true, false)
                        }
                        onOutput("[LOCAL] Gradle 安装完成\n")
                    } catch (e: Throwable) {
                        onOutput("[LOCAL] Gradle 安装失败：${e.message}\n")
                        ok = false
                    }
                } else {
                    val sdkDest = File(runtime.home, "android-sdk")
                    try {
                        sdkDest.parentFile?.mkdirs()
                        srcRoot.copyRecursively(sdkDest, overwrite = true)
                        app.getSharedPreferences("githubk", Context.MODE_PRIVATE)
                            .edit().putString("sdk_home", sdkDest.absolutePath).apply()
                        onOutput("[LOCAL] Android SDK 安装完成\n")
                    } catch (e: Throwable) {
                        onOutput("[LOCAL] Android SDK 安装失败：${e.message}\n")
                        ok = false
                    }
                }
            }
        }

        // 3. Dart / Flutter（完整离线包或独立 SDK 目录）
        var foundDartOrFlutter = false
        val dartSrc = listOf(File(srcRoot, "dart-sdk"), File(srcRoot, "dart"))
            .firstOrNull { File(it, "bin/dart").isFile }
        if (dartSrc != null) {
            foundDartOrFlutter = true
            matchedAny = true
            val dest = File(runtime.home, "dart-sdk")
            try {
                dest.parentFile?.mkdirs()
                dartSrc.copyRecursively(dest, overwrite = true)
                app.getSharedPreferences("githubk", Context.MODE_PRIVATE)
                    .edit().putString("dart_home", dest.absolutePath).apply()
                onOutput("[LOCAL] Dart SDK 安装完成\n")
            } catch (e: Throwable) {
                onOutput("[LOCAL] Dart SDK 安装失败：${e.message}\n")
                ok = false
            }
        }
        val flutterSrc = listOf(File(srcRoot, "flutter"), File(srcRoot, "flutter-sdk"))
            .firstOrNull { File(it, "bin/flutter").isFile }
        if (flutterSrc != null) {
            foundDartOrFlutter = true
            matchedAny = true
            val dest = File(runtime.home, "flutter")
            try {
                dest.parentFile?.mkdirs()
                flutterSrc.copyRecursively(dest, overwrite = true)
                app.getSharedPreferences("githubk", Context.MODE_PRIVATE)
                    .edit().putString("flutter_home", dest.absolutePath)
                    .putString("flutter_bin", File(dest, "bin/flutter").absolutePath).apply()
                onOutput("[LOCAL] Flutter SDK 安装完成\n")
            } catch (e: Throwable) {
                onOutput("[LOCAL] Flutter SDK 安装失败：${e.message}\n")
                ok = false
            }
        }
        if (foundDartOrFlutter) onOutput("[LOCAL] Dart/Flutter 导入完成\n")
        if (!matchedAny) {
            onOutput("[LOCAL] 未识别到可导入内容：目录中需要 jdk/、gradle/、android-sdk/、git/、dart-sdk/、flutter/ 等子目录，\n")
            onOutput("[LOCAL] 或直接选择含 bin/java、bin/gradle、platforms/、bin/bash 的组件目录。\n")
            return false
        }
        return ok
    }

    /** 安装/修复所选本地目录内的 Android SDK 平台与构建工具。 */
    fun installAndroidSdkFromLocalDir(srcRoot: File, onOutput: (String) -> Unit = {}): Boolean {
        if (!srcRoot.isDirectory) return false
        val sdkSrc = when {
            File(srcRoot, "platforms").isDirectory -> srcRoot
            File(srcRoot, "android-sdk/platforms").isDirectory -> File(srcRoot, "android-sdk")
            else -> return false
        }
        val sdkDest = File(runtime.home, "android-sdk")
        return try {
            sdkDest.parentFile?.mkdirs()
            sdkSrc.copyRecursively(sdkDest, overwrite = true)
            app.getSharedPreferences("githubk", Context.MODE_PRIVATE)
                .edit().putString("sdk_home", sdkDest.absolutePath).apply()
            true
        } catch (_: Throwable) { false }
    }

    /**
     * Install Dart/Flutter from the same offline toolchain bundle used by JDK/Gradle.
     * Supported bundle layout: dart-sdk/ and flutter-sdk/ (or flutter/).
     */
    fun ensureDartFlutterToolchain(onOutput: (String) -> Unit = {}): Boolean =
        downloader.installFlutterAndDart(onOutput)

    /** 一键安装完整 Android/Termux/Flutter 开发环境（在线优先，SDK 大件走网络）。 */
    fun installCompleteIdeEnvironment(onOutput: (String) -> Unit = {}): Boolean =
        downloader.installCompleteIdeEnvironment(onOutput)

    /**
     * 纯离线安装完整 IDE 环境（仅使用 APK 内置资源，绝不联网）。
     * 流程：内置 bootstrap runtime → assets/offline-toolchain 的 .deb
     * （JDK/Gradle/Git/Python/Node/Clang/CMake/Dart/Flutter 等全部组件）。
     * 若内置 deb 未完整覆盖，返回 false 并在日志中说明原因。
     */
    fun installOfflineFullEnvironment(onOutput: (String) -> Unit = {}): Boolean {
        onOutput("[OFFLINE] 开始纯离线安装（仅使用 APK 内置资源，不访问网络）…\n")
        if (!runtime.isInstalled()) {
            onOutput("[OFFLINE] 正在从内置资源安装 Termux 运行时…\n")
            try {
                runtime.install { p -> onOutput("[OFFLINE] runtime ${(p * 100).toInt()}%\n") }
            } catch (e: Throwable) {
                onOutput("[OFFLINE] 运行时安装失败：${e.message ?: e.javaClass.simpleName}\n")
                return false
            }
            if (!runtime.isInstalled()) {
                onOutput("[OFFLINE] 运行时仍未就绪\n")
                return false
            }
            onOutput("[OFFLINE] 运行时就绪 ✓\n")
        }
        // 预检可执行通道：华为/鸿蒙 ROM 会禁止 app 直接 exec 自身 data 目录，
        // 确认可执行通道：优先 proot 沙盒（无需 Shizuku），必要时走 Shizuku run-as 并自动弹授权。
        if (!runtime.ensureExecCapability(onOutput)) {
            onOutput("[OFFLINE] 中止安装：当前无可用可执行通道（proot 沙盒不可用且未获 Shizuku 授权）。\n")
            onOutput("[OFFLINE] 请启动 Shizuku（无线调试/ADB）并授权后重新离线安装。\n")
            return false
        }
        val packages = listOf(
            "openjdk-17", "openjdk-21", "git", "gradle", "python", "nodejs",
            "clang", "cmake", "ninja", "make", "pkg-config", "zip", "unzip",
            "tar", "xz-utils", "curl", "wget", "android-tools", "aapt2",
            "openssl-tool", "which", "findutils", "dart", "flutter"
        )
        onOutput("[OFFLINE] 内置组件共 ${packages.size} 个，开始解包安装…\n")
        val ok = try {
            runtime.installPackagesOffline(packages, onOutput)
        } catch (e: Throwable) {
            onOutput("[OFFLINE] 安装异常：${e.message ?: e.javaClass.simpleName}\n")
            false
        }
        if (ok) {
            onOutput("[OFFLINE] 内置离线环境安装完成 ✓\n")
            if (ensureBundledAndroidPlatform(onOutput)) {
                onOutput("[OFFLINE] Android 平台(android-34/android.jar)已就绪 ✓\n")
            }
            return true
        }
        onOutput("[OFFLINE] 内置离线包未完整覆盖所需组件，请检查离线包是否齐全\n")
        return false
    }

    /**
     * 幂等确保“完整离线构建环境”就绪（无需用户先进环境中心手动点安装）：
     *   runtime → JDK/Gradle/Git/Python/Node → aapt2/android-tools →
     *   Android 平台 android.jar → ARM64 aapt2 override → Dart/Flutter。
     * 全部基于 APK 内置 offline-toolchain 资源，不强制联网；组件已就绪时探测
     * 通过并快速返回，首次完整安装可能耗时数分钟且可能弹一次 Shizuku 授权。
     */
    fun ensureBuildEnvironmentReady(
        onOutput: (String) -> Unit = {},
        withFlutter: Boolean = true
    ): Boolean {
        val tag = "[BUILD-ENV] "
        // 1) runtime + 基础工具链（ensureIdeToolchain 内部对已就绪组件幂等跳过）
        val basicOk = try {
            ensureIdeToolchain(onOutput)
        } catch (e: Throwable) {
            onOutput("$tag 基础工具链自动安装异常：${e.message}\n"); false
        }
        if (!basicOk) onOutput("$tag 基础工具链安装未通过，继续尝试补齐剩余组件…\n")

        // 2) Android SDK 编译所需二进制（aapt2/d8/apksigner/zipalign/android-tools 等）
        if (!runtime.detectTool("aapt2").first) {
            onOutput("$tag aapt2 未安装/不可执行，从内置离线包补齐…\n")
            runCatching {
                runtime.installPackagesOffline(listOf("aapt2", "d8", "apksigner", "android-tools"), onOutput)
            }
            if (!runtime.detectTool("aapt2").first) {
                onOutput("$tag ⚠ aapt2 仍无法执行（依赖库缺失或架构不匹配）。\n")
                onOutput("$tag   离线构建 Android 将失败；可联网后在“环境中心→Android SDK”安装官方 Build-Tools，\n")
                onOutput("$tag   或由 AGP 从 Maven 仓库拉取官方 aapt2 后移除本机 override。\n")
            }
        }

        // 3) Android 平台 android.jar + ARM64 Gradle aapt2 override
        runCatching { ensureAndroidSdkLayout(onOutput) }
        runCatching { configureArm64BuildEnvironment(onOutput) }

        // 4) Dart / Flutter（termux deb 布局在 $PREFIX，登记后 environment() 才能注入）
        if (withFlutter) {
            if (dartHome() == null || flutterHome() == null) {
                onOutput("$tag Dart/Flutter 未就绪，从内置离线包安装…\n")
            }
            runCatching { ensureDartFlutterToolchain(onOutput) }
        }

        // 5) 校验汇总
        val java = javaHome()
        if (java == null || !File(java, "bin/java").isFile) {
            onOutput("$tag ✗ JDK 仍未就绪（javaHome=${java?.absolutePath ?: "null"}）。\n")
            onOutput("$tag   请在“环境中心→工具链”手动安装，或在设置中指定正确 JDK 路径。\n")
            return false
        }
        val sdk = sdkPath()
        onOutput("$tag JDK=${java.absolutePath}\n")
        onOutput("$tag Gradle=${gradleHome()?.absolutePath ?: "未配置"}\n")
        onOutput("$tag SDK=${sdk?.absolutePath ?: "(未配置)"}${if (sdk != null && !File(sdk, "platforms/android-34/android.jar").exists()) "（提示：缺少 platforms/android-34，编译 Android 项目会失败）" else ""}\n")
        onOutput("$tag Dart=${dartHome()?.absolutePath ?: "未配置"} Flutter=${flutterHome()?.absolutePath ?: "未配置"}\n")
        onOutput("$tag 构建环境就绪 ✓（如需联网下载 Gradle/Maven 依赖请保持网络可用）\n")
        return true
    }

    /**
     * 从 APK assets 复制内置 Android platform（android.jar，纯 Java 字节码，跨架构）
     * 到 SDK 的 platforms/android-34/，使离线环境在【无 arm64 sdkmanager】时也能编译
     * Android 项目。本版本 compileSdk=34，与 android-34 对齐。
     */
    fun ensureBundledAndroidPlatform(onOutput: (String) -> Unit = {}): Boolean {
        return try {
            val tmp = File(app.cacheDir, "bundled-android34.jar")
            runCatching {
                app.assets.open("android-platform/android-34/android.jar").use { input ->
                    FileOutputStream(tmp).use { input.copyTo(it) }
                }
            }.onFailure { e ->
                onOutput("[PLATFORM] 内置 android.jar 缺失：${e.message}\n")
                return false
            }
            val sdk = File(runtime.home, "android-sdk")
            val platform = File(sdk, "platforms/android-34")
            val destJar = File(platform, "android.jar")
            if (!destJar.exists() || destJar.length() != tmp.length()) {
                platform.mkdirs()
                FileOutputStream(destJar).use { out -> tmp.inputStream().use { it.copyTo(out) } }
            }
            tmp.delete()
            onOutput("[PLATFORM] android.jar → ${destJar.absolutePath}\n")
            true
        } catch (t: Throwable) {
            onOutput("[PLATFORM] 放置 Android 平台失败：${t.message}\n")
            false
        }
    }

    /**
     * 幂等补齐 Android SDK 目录结构，使 sdkPath() 能识别并让 AGP 找到平台/工具：
     *   $sdk/platforms/android-34/android.jar    <- 内置 asset
     *   $sdk/platform-tools/adb                  <- runtime/bin/adb
     *   $sdk/build-tools/34.0.0/{aapt2,apksigner,d8,zipalign} <- 拷贝自 runtime/bin
     *   $sdk/licenses/android-sdk-license
     * 同时把 sdk_home 写入偏好，保证 status()/build 都能定位。仅在缺失时复制一次，之后零开销。
     */
    fun ensureAndroidSdkLayout(onOutput: (String) -> Unit = {}): Boolean {
        return try {
            val sdk = File(runtime.home, "android-sdk").apply { mkdirs() }
            var ok = true

            // 1) platforms/android-34/android.jar（内置 asset，纯 Java 字节码）
            val platform = File(sdk, "platforms/android-34")
            val destJar = File(platform, "android.jar")
            try {
                if (!destJar.exists() || destJar.length() < 1024L * 1024) {
                    val tmp = File(app.cacheDir, "bundled-android34.jar")
                    if (tmp.exists()) tmp.delete()
                    app.assets.open("android-platform/android-34/android.jar").use { input ->
                        FileOutputStream(tmp).use { input.copyTo(it) }
                    }
                    platform.mkdirs()
                    FileOutputStream(destJar).use { out -> tmp.inputStream().use { it.copyTo(out) } }
                    tmp.delete()
                    onOutput("[SDK] android.jar -> ${destJar.absolutePath}\n")
                }
            } catch (e: Throwable) {
                ok = false
                onOutput("[SDK] android.jar 缺失: ${e.message}\n")
            }

            // 2) platform-tools/adb
            try {
                val pt = File(sdk, "platform-tools").apply { mkdirs() }
                val adb = File(pt, "adb")
                val srcAdb = File(runtime.prefix, "bin/adb")
                if (!adb.exists() && srcAdb.exists()) {
                    srcAdb.copyTo(adb, overwrite = true)
                    adb.setExecutable(true, false)
                    onOutput("[SDK] 已放置 platform-tools/adb\n")
                }
            } catch (_: Throwable) {}

            // 3) build-tools/34.0.0：链接 runtime/bin 中已验证可执行的工具
            try {
                val bt = File(sdk, "build-tools/34.0.0").apply { mkdirs() }
                listOf("aapt2", "apksigner", "d8", "zipalign").forEach { name ->
                    val src = File(runtime.prefix, "bin/$name")
                    val dst = File(bt, name)
                    if (src.exists() && !dst.exists()) {
                        src.copyTo(dst, overwrite = true)
                        dst.setExecutable(true, false)
                    }
                }
            } catch (_: Throwable) {}

            // 4) licenses（AGP 要求接受 Android SDK 许可）
            try {
                val lic = File(sdk, "licenses/android-sdk-license")
                lic.parentFile?.mkdirs()
                if (!lic.exists()) lic.writeText("8933bad161af4178b1185d1a37fbf41ea5269c55\n")
            } catch (_: Throwable) {}

            // 5) 记录 sdk_home
            try {
                app.getSharedPreferences("githubk", Context.MODE_PRIVATE)
                    .edit().putString("sdk_home", sdk.absolutePath).apply()
            } catch (_: Throwable) {}

            // 6) 校验能被 sdkPath 识别
            val detected = sdkPath("")
            if (detected != null) {
                onOutput("[SDK] 就绪: ${detected.absolutePath}\n")
                true
            } else {
                onOutput("[SDK] 仍未识别（platforms 缺失）\n")
                false
            }
        } catch (t: Throwable) {
            onOutput("[SDK] 补齐异常: ${t.message}\n")
            false
        }
    }

    /** 在线安装/修复 Android SDK Command-line Tools。 */
    fun installAndroidCommandLineTools(onOutput: (String) -> Unit = {}): Boolean =
        downloader.installAndroidCommandLineTools(onOutput)

    fun ensureAndroidSdkComponents(api: Int = 36, buildTools: String = "36.0.0", onOutput: (String) -> Unit = {}): Boolean {
        val sdk = sdkPath("")
        if (sdk == null) {
            onOutput("[SDK] 未找到 Android SDK，请在设置中指定 ANDROID_HOME。\n")
            return false
        }
        val sdkmanager = listOf(
            File(sdk, "cmdline-tools/latest/bin/sdkmanager"),
            File(sdk, "cmdline-tools/bin/sdkmanager"),
            File(sdk, "tools/bin/sdkmanager"),
            File(runtime.prefix, "bin/sdkmanager")
        ).firstOrNull { it.exists() }
        if (sdkmanager == null) {
            onOutput("[SDK] 未找到 sdkmanager，无法自动安装平台组件。\n")
            return false
        }
        val env = environment(sdk.absolutePath, "", "")
        val cmd = "yes | ${quote(sdkmanager.absolutePath)} --licenses >/dev/null 2>&1 || true; " +
            "${quote(sdkmanager.absolutePath)} \"platform-tools\" \"platforms;android-$api\" \"build-tools;$buildTools\""
        val result = ShizukuShell.execUserService(app, cmd, env, sdk.absolutePath, 30 * 60)
            ?: runtime.run(cmd)
        onOutput(result + "\n")
        return !result.startsWith("ERROR:") && !result.contains("Failed")
    }

    /**
     * 修复/生成项目的 Gradle Wrapper。
     * 若项目缺少 gradlew 或 gradle-wrapper.jar，使用已安装的 Gradle 重新生成。
     */
    fun repairGradleWrapper(projectPath: File, onOutput: (String) -> Unit = {}): Boolean {
        val project = projectPath
        if (!project.isDirectory) {
            onOutput("[WRAPPER] 项目路径不存在：${project.absolutePath}\n")
            return false
        }
        val gradleDir = gradleHome("")
        val gradleBin = gradleDir?.let { File(it, "bin/gradle") }
        if (gradleBin == null || !gradleBin.exists()) {
            onOutput("[WRAPPER] 未找到可用的 Gradle，无法生成 wrapper，请先安装/配置 Gradle。\n")
            return false
        }
        val env = environment("", "", gradleDir.absolutePath)
        val cmd = "cd ${quote(project.absolutePath)} && ${quote(gradleBin.absolutePath)} wrapper --gradle-version ${gradleVersionOf(gradleDir)} --no-daemon"
        val result = ShizukuShell.execUserService(app, cmd, env, project.absolutePath, 10 * 60)
            ?: runtime.run(cmd)
        onOutput(result + "\n")
        val wrapperJar = File(project, "gradle/wrapper/gradle-wrapper.jar")
        val wrapperScript = File(project, "gradlew")
        val ok = wrapperJar.exists() && wrapperScript.exists() &&
            !result.startsWith("ERROR:") && !result.contains("FAILED") && !result.contains("Failed")
        if (ok) {
            try { wrapperScript.setExecutable(true) } catch (_: Throwable) {}
        }
        return ok
    }

    private fun gradleVersionOf(gradleDir: File): String {
        val libDir = File(gradleDir, "lib")
        val jar = libDir.listFiles { f -> f.name.startsWith("gradle-core-api-") || f.name.startsWith("gradle-launcher-") }
            ?.firstOrNull()
        val fromJar = jar?.name?.let { Regex("(\\d+\\.\\d+(\\.\\d+)?)").find(it)?.value }
        return fromJar ?: "8.2.1"
    }

    /** 为 ARM64 手机写入 Gradle 用户级 aapt2 覆盖，避免 AGP 拉取 x86_64 aapt2。 */
    fun configureArm64BuildEnvironment(onOutput: (String) -> Unit = {}): Boolean {
        if (!runtime.detectTool("aapt2").first) {
            onOutput("[ARM64] Termux aapt2 不可执行，跳过 Gradle override（避免 AGP 使用损坏二进制）。\n")
            return false
        }
        val sdk = sdkPath("") ?: return false
        val aapt2 = File(runtime.prefix, "bin/aapt2")
        if (!aapt2.exists()) {
            onOutput("[ARM64] 未找到 Termux aapt2，请先安装完整环境。\n")
            return false
        }
        val gradleHome = File(runtime.home, "gradle-home").apply { mkdirs() }
        val props = File(gradleHome, "gradle.properties")
        val lines = if (props.exists()) props.readLines().toMutableList() else mutableListOf()
        lines.removeAll { it.trim().startsWith("android.aapt2FromMavenOverride=") }
        lines.add("android.aapt2FromMavenOverride=${aapt2.absolutePath}")
        lines.removeAll { it.trim().startsWith("org.gradle.java.home=") }
        javaHome("")?.let { lines.add("org.gradle.java.home=${it.absolutePath}") }
        props.writeText(lines.joinToString("\n") + "\n")
        onOutput("[ARM64] Gradle aapt2 override: ${aapt2.absolutePath}\n")
        return true
    }

    fun doctor(sdk: String = "", java: String = "", gradle: String = ""): String {
        val s = status(sdk, java, gradle)
        return buildString {
            appendLine("GitHubK Toolchain Doctor")
            appendLine("Runtime      : ${if (s.runtime) "OK" else "MISSING"}")
            appendLine("JDK          : ${if (s.java) "OK" else "MISSING"} ${s.javaVersion}")
            appendLine("Gradle       : ${if (s.gradle) "OK" else "MISSING"} ${s.gradleVersion}")
            appendLine("Git          : ${if (s.git) "OK" else "MISSING"}")
            appendLine("Android SDK  : ${if (s.sdk) "OK" else "MISSING"} ${s.sdkPath}")
            appendLine("ADB          : ${if (s.adb) "OK" else "MISSING"}")
            appendLine("Dart SDK     : ${if (s.dart) "OK" else "MISSING"} ${dartHome()?.absolutePath ?: ""}")
            appendLine("Flutter SDK  : ${if (s.flutter) "OK" else "MISSING"} ${flutterHome()?.absolutePath ?: ""}")
            appendLine("Ready        : ${if (s.ready) "YES" else "NO"}")
            appendLine("Execution    : ${if (ShizukuShell.isServiceAvailable() && ShizukuShell.hasPermission()) "Shizuku UserService" else "App sandbox fallback"}")
        }
    }

    private fun quote(v: String): String = "'" + v.replace("'", "'\\\"'\\\"'") + "'"

    /**
     * 纯 Java tar.gz 解压（当系统 tar 不可用时的回退方案）
     */
private fun extractTarGzJava(tarGzFile: File, destDir: File, onOutput: (String) -> Unit) {
        try {
            val fis = java.io.FileInputStream(tarGzFile)
            val gzis = java.util.zip.GZIPInputStream(fis)
            val bis = java.io.BufferedInputStream(gzis)
            val buf = ByteArray(512)
            while (true) {
                val n = bis.read(buf, 0, 512)
                if (n < 512) break
                val nameBytes = buf.copyOfRange(0, 100)
                val nameEnd = nameBytes.indexOf(0.toByte())
                val name = if (nameEnd >= 0) String(nameBytes, 0, nameEnd) else String(nameBytes).trim()
                if (name.isEmpty()) break
                val sizeStr = String(buf, 124, 11).trim().trimEnd(' ')
                val size = if (sizeStr.isEmpty()) 0L else sizeStr.toLong(8)
                val typeFlag = buf[156].toInt() and 0xFF
                val outFile = File(destDir, name)
                if (typeFlag == 53) {
                    outFile.mkdirs()
                } else if (typeFlag == 48 || typeFlag == 0) {
                    outFile.parentFile?.mkdirs()
                    val fos = java.io.FileOutputStream(outFile)
                    var remaining = size
                    val copyBuf = ByteArray(4096)
                    while (remaining > 0) {
                        val toRead = minOf(copyBuf.size.toLong(), remaining).toInt()
                        val read = bis.read(copyBuf, 0, toRead)
                        if (read < 0) break
                        fos.write(copyBuf, 0, read)
                        remaining -= read
                    }
                    fos.close()
                    val padding = ((512 - (size % 512)) % 512).toInt()
                    if (padding > 0) bis.read(ByteArray(padding), 0, padding)
                }
            }
            bis.close()
            fis.close()
            onOutput("[OFFLINE] Java tar done\n")
        } catch (e: Throwable) {
            onOutput("[OFFLINE] Java tar failed: ${e.message}\n")
        }
    }

}
