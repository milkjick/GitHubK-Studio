package com.example.myempty.githubk.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import com.example.myempty.githubk.terminal.BuiltinRuntime
import java.io.File

/**
 * EnvManager v2（参考 AndroidCS IDE）：IDE 环境中心。
 * - SDK Manager：Android SDK 组件（platform-tools / build-tools / platforms）+ JDK 17/21 + Gradle + Flutter SDK
 * - 语言服务器：Java (jdtls) / Kotlin (kotlin-language-server) / Dart / Python / Bash 检测与安装命令
 * - 终端环境：Termux 检测、Ubuntu (proot-distro) 环境、Termux-X11 显示支持
 * - 工具链检测：git / jdk / gradle / python / node / flutter / dart
 */
data class EnvTool(
    val name: String,
    val cmd: String,
    val termuxPkg: String,
    val installed: Boolean,
    val version: String
)

/** SDK Manager 组件（参考 AndroidCS IDE）。 */
data class SdkComponent(
    val name: String,
    val desc: String,
    val detectCmd: String,
    val installCmd: String,
    val installed: Boolean = false,
    val version: String = ""
)

/** 语言服务器（LSP）。 */
data class LangServer(
    val name: String,
    val detectCmd: String,
    val installCmd: String,
    val installed: Boolean = false,
    val version: String = ""
)

class EnvManager(private val c: Context) {

    private val termuxBin = File("/data/data/com.termux/files/usr/bin")

    // ---------- 工具链检测 ----------

    /** 检测常见工具链。 */
    fun detect(): List<EnvTool> {
        val defs = listOf(
            Triple("Git", "git", "git"),
            Triple("OpenJDK", "java", "openjdk-17"),
            Triple("Gradle", "gradle", "gradle"),
            Triple("Python", "python3", "python"),
            Triple("Node.js", "node", "nodejs-lts"),
            Triple("Flutter", "flutter", "flutter"),
            Triple("Dart", "dart", "dart")
        )
        return defs.map { (name, cmd, pkg) ->
            val (installed, ver) = detectTool(cmd)
            EnvTool(name, cmd, pkg, installed, ver)
        }
    }

    /** 依次尝试：内置运行时 → Termux bin → PATH → 常见路径。 */
    private fun detectTool(cmd: String): Pair<Boolean, String> {
        // 内置运行时（应用私有目录，无需 Termux）
        val rt = BuiltinRuntime(c)
        if (rt.isInstalled()) {
            val res = rt.detectTool(cmd)
            if (res.first) return res
            // 内置 runtime 只是基础 shell，继续检查 Termux/系统环境，避免误报。
        }
        val candidates = listOf(
            File(termuxBin, cmd),
            File("/data/data/com.termux/files/usr/bin", cmd)
        )
        for (f in candidates) {
            if (f.exists()) {
                val ver = runCommand("${f.absolutePath} --version").take(120).replace('\n', ' ')
                return true to (ver.ifBlank { "已安装 (Termux)" })
            }
        }
        val pathVer = runCommand("command -v $cmd && $cmd --version 2>&1").take(120).replace('\n', ' ')
        if (pathVer.isNotBlank()) return true to pathVer
        return false to "未安装"
    }

    // ---------- SDK Manager（参考 AndroidCS IDE） ----------

    /** SDK 组件清单。 */
    fun sdkComponents(): List<SdkComponent> {
        val list = mutableListOf(
            SdkComponent(
                "Android SDK Platform-Tools",
                "adb / fastboot 等平台工具",
                "adb --version",
                "pkg install -y android-tools"
            ),
            SdkComponent(
                "Android SDK Build-Tools",
                "aapt2 / d8 / apksigner 等构建工具",
                "ls ${termuxBin.absolutePath}/aapt2 2>/dev/null || ls \$ANDROID_HOME/build-tools 2>/dev/null",
                "pkg install -y aapt aapt2 d8 apksigner" // Termux: build-tools 通过 android-tools/ecj 等提供
            ),
            SdkComponent(
                "Android SDK Platform (android-34)",
                "Android 34 平台库（编译 API 34 需要）",
                "ls \$ANDROID_HOME/platforms 2>/dev/null",
                "sdkmanager \"platforms;android-34\""
            ),
            SdkComponent(
                "JDK 17",
                "Java 开发套件（Gradle/AGP 8.x 需要）",
                "java -version 2>&1",
                "pkg install -y openjdk-17"
            ),
            SdkComponent(
                "JDK 21",
                "Java 21（可选，参考 AndroidCS IDE）",
                "ls ${termuxBin.absolutePath}/java21 2>/dev/null || (java -version 2>&1 | grep -o '21')",
                "pkg install -y openjdk-21"
            ),
            SdkComponent(
                "Gradle",
                "构建系统（AGP 8.x 需要 Gradle 8.x）",
                "gradle --version",
                "pkg install -y gradle"
            ),
            SdkComponent(
                "Flutter SDK",
                "Flutter 跨平台框架（含 Dart）",
                "flutter --version",
                "pkg install -y flutter"
            )
        )
        // 根据实际检测结果填充 installed/version
        return list.map { comp ->
            val (ok, ver) = detectTool(comp.detectCmd.split(" ")[0].trim())
            comp.copy(installed = ok, version = if (ok) ver.take(60) else "未安装")
        }
    }

    // ---------- 语言服务器（参考 AndroidCS IDE） ----------

    /** 语言服务器清单：检测 + 安装命令。 */
    fun langServers(): List<LangServer> {
        val defs = listOf(
            LangServer("Java Language Server", "jdtls --version", "pkg install -y jdtls"),
            LangServer("Kotlin Language Server", "kotlin-language-server --version", "pkg install -y kotlin-language-server"),
            LangServer("Dart Analysis Server", "dart analyze --version", "dart pub global activate dart_language_server"),
            LangServer("Python Language Server", "pylsp --version", "pip install python-lsp-server"),
            LangServer("Bash Language Server", "bash-language-server --version", "npm install -g bash-language-server"),
            LangServer("Clangd (C/C++)", "clangd --version", "pkg install -y clangd"),
            LangServer("TypeScript Language Server", "typescript-language-server --version", "npm install -g typescript-language-server")
        )
        return defs.map { ls ->
            val (ok, ver) = detectTool(ls.detectCmd.split(" ")[0].trim())
            ls.copy(installed = ok, version = if (ok) ver.take(50) else "未安装")
        }
    }

    // ---------- Termux / Ubuntu / X11 ----------

    /** Termux 是否已安装。 */
    fun isTermuxInstalled(): Boolean = try {
        c.packageManager.getPackageInfo("com.termux", 0)
        true
    } catch (_: Throwable) { false }

    /** Termux-X11 是否已安装。 */
    fun isTermuxX11Installed(): Boolean = try {
        c.packageManager.getPackageInfo("com.termux.x11", 0)
        true
    } catch (_: Throwable) { false }

    /** Ubuntu (proot-distro) 是否已安装。 */
    fun isUbuntuInstalled(): Boolean =
        File(termuxBin, "proot-distro").exists() &&
            runCommand("proot-distro list").contains("ubuntu")

    /** 生成 SDK Manager 安装脚本。 */
    fun sdkInstallScript(): String {
        val prefix = c.filesDir.absolutePath + "/runtime"
        val sb = StringBuilder()
        sb.append("# GitHubK Studio - SDK Manager 一键安装\n")
        sb.append("export PREFIX=$prefix\n")
        sb.append("export HOME=\$PREFIX/home\n")
        sb.append("export TMPDIR=\$PREFIX/tmp\n")
        sb.append("export PATH=\$PREFIX/bin:\$PREFIX/bin/applets:/system/bin:/system/xbin\n")
        sb.append("export LD_LIBRARY_PATH=\$PREFIX/lib\n")
        sb.append("export LANG=C.UTF-8\n")
        sb.append("if command -v pkg >/dev/null 2>&1; then\n")
        sb.append("  pkg update -y && pkg upgrade -y\n")
        sb.append("  pkg install -y git openjdk-17 gradle python nodejs-lts\n")
        sb.append("  # Android SDK 基础组件\n")
        sb.append("  pkg install -y android-tools aapt aapt2 d8 apksigner\n")
        sb.append("  # 语言服务器\n")
        sb.append("  pkg install -y jdtls kotlin-language-server clangd || true\n")
        sb.append("  pip install python-lsp-server 2>/dev/null || true\n")
        sb.append("else\n")
        sb.append("  echo '[错误] pkg 不可用，请先执行 install-env'\n")
        sb.append("fi\n")
        sb.append("echo '=== SDK Manager 安装完成 ==='\n")
        return sb.toString()
    }

    /** 生成 Ubuntu (proot-distro) + Termux-X11 环境脚本。 */
    fun ubuntuScript(): String {
        val sb = StringBuilder()
        sb.append("# GitHubK Studio - Ubuntu 环境 + Termux-X11 (参考 AndroidCS IDE)\n")
        sb.append("pkg update -y\n")
        sb.append("pkg install -y proot-distro x11-repo\n")
        sb.append("pkg install -y termux-x11-nightly pulseaudio 2>/dev/null || true\n")
        sb.append("proot-distro install ubuntu\n")
        sb.append("echo '=== Ubuntu 安装完成，启动：proot-distro login ubuntu ==='\n")
        sb.append("echo '=== X11 启动：termux-x11 :0 & 然后 proot-distro login ubuntu ==='\n")
        return sb.toString()
    }

    /** 生成环境安装脚本（根据后端类型自适应）。 */
    fun installScript(tools: List<String> = listOf("git", "openjdk-17", "gradle", "python", "nodejs-lts")): String {
        val packages = (tools + listOf("curl", "unzip", "aapt2", "aapt", "d8", "apksigner", "zipalign")).distinct()
        return buildString {
            appendLine("# GitHubK Studio - Android IDE 编译环境一键安装")
            appendLine("set -e")
            appendLine("pkg update -y")
            val core = packages.filterNot { it in setOf("aapt2", "aapt", "d8", "apksigner", "zipalign") }
            appendLine("pkg install -y ${core.joinToString(" ")}")
            appendLine("pkg install -y aapt2 aapt d8 apksigner zipalign || true")
            appendLine("export JAVA_HOME=\$(dirname \$(dirname \$(readlink -f \$(command -v java))))")
            appendLine("export ANDROID_HOME=\$HOME/android-sdk")
            appendLine("export ANDROID_SDK_ROOT=\$ANDROID_HOME")
            appendLine("mkdir -p \"\$ANDROID_HOME/platforms\" \"\$ANDROID_HOME/build-tools/34.0.0\"")
            appendLine("# Android SDK Platform 34：从 Google 官方 repository 元数据解析当前可用 archive")
            appendLine("if [ ! -f \"\$ANDROID_HOME/platforms/android-34/android.jar\" ]; then")
            appendLine("  python - <<'PY'")
            appendLine("import os, urllib.request, xml.etree.ElementTree as ET, zipfile, tempfile, shutil")
            appendLine("sdk=os.environ['ANDROID_HOME']; xml=urllib.request.urlopen('https://dl.google.com/android/repository/repository2-3.xml', timeout=30).read()")
            appendLine("root=ET.fromstring(xml); url=None")
            appendLine("for p in root.iter('remotePackage'):")
            appendLine("    if p.attrib.get('path') == 'platforms;android-34':")
            appendLine("        a=p.find('./archives/archive/complete/url'); url=a.text if a is not None else None; break")
            appendLine("if not url: raise SystemExit('Google SDK platform 34 archive not found')")
            appendLine("base='https://dl.google.com/android/repository/'; z=os.path.join(tempfile.gettempdir(),'android-34.zip')")
            appendLine("urllib.request.urlretrieve(base+url,z); t=tempfile.mkdtemp()")
            appendLine("zipfile.ZipFile(z).extractall(t); candidates=[]")
            appendLine("for r,d,fs in os.walk(t):")
            appendLine("    if 'android.jar' in fs: candidates.append(r)")
            appendLine("if not candidates: raise SystemExit('android.jar not found')")
            appendLine("dst=os.path.join(sdk,'platforms','android-34'); shutil.rmtree(dst,ignore_errors=True); shutil.copytree(candidates[0],dst); os.remove(z); shutil.rmtree(t,ignore_errors=True)")
            appendLine("PY")
            appendLine("fi")
            appendLine("# Termux 提供 Android ARM 原生构建工具；为 AGP 建立标准 SDK 路径")
            appendLine("for tool in aapt2 aapt d8 apksigner zipalign; do if command -v \$tool >/dev/null 2>&1; then ln -sf \$(command -v \$tool) \"\$ANDROID_HOME/build-tools/34.0.0/\$tool\"; fi; done")
            appendLine("export PATH=\$ANDROID_HOME/build-tools/34.0.0:\$ANDROID_HOME/platform-tools:\$PATH")
            appendLine("mkdir -p \$HOME/.gradle")
            appendLine("echo '=== GitHubK Studio IDE 环境完成 ==='")
            appendLine("git --version")
            appendLine("java -version 2>&1 | head -1")
            appendLine("gradle --version | head -5")
            appendLine("test -f \"\$ANDROID_HOME/platforms/android-34/android.jar\" && echo 'Android API 34: OK'")
        }
    }

    /** 通过 Termux RUN_COMMAND 执行命令（需在 Termux 设置开启「允许外部应用执行」）。 */
    fun launchTermux(command: String): Boolean = try {
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        c.startService(intent)
        true
    } catch (_: Throwable) {
        openTermux().also { if (!it) copyToClipboard(command) }
    }

    /** 打开 Termux 主界面。 */
    fun openTermux(): Boolean = try {
        val intent = c.packageManager.getLaunchIntentForPackage("com.termux")
        if (intent == null) false else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(intent)
            true
        }
    } catch (_: Throwable) { false }

    /** 引导到 F-Droid Termux 下载页。 */
    fun openFdroidTermux(): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://f-droid.org/packages/com.termux/"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(intent)
        true
    } catch (_: Throwable) { false }

    /** Termux bash 是否可作为内置终端后端（无需跳转 Termux UI）。 */
    fun termuxBashPath(): String? {
        val f = File("/data/data/com.termux/files/usr/bin/bash")
        return if (f.exists()) f.absolutePath else null
    }

    /** 复制文本到剪贴板。 */
    fun copyToClipboard(text: String) {
        try {
            val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("GitHubK", text))
        } catch (_: Throwable) {}
    }

    fun termuxHome(): File = File("/data/data/com.termux/files/home")

    private fun runCommand(cmdLine: String): String = try {
        // 优先使用 Termux bash 作为执行后端（与应用内置终端一致）
        val termuxBash = File("/data/data/com.termux/files/usr/bin/bash")
        val exec = if (termuxBash.exists()) {
            ProcessBuilder(termuxBash.absolutePath, "-c", cmdLine)
        } else {
            ProcessBuilder("/system/bin/sh", "-c", cmdLine)
        }
        if (termuxBash.exists()) {
            exec.environment()["PATH"] =
                "/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets:/system/bin:/system/xbin"
            exec.environment()["PREFIX"] = "/data/data/com.termux/files/usr"
            exec.environment()["HOME"] = "/data/data/com.termux/files/home"
        }
        val p = exec.redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        out.trim()
    } catch (_: Throwable) { "" }
}
