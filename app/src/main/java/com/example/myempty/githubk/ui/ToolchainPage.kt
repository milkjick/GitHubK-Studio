package com.example.myempty.githubk.ui

import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.content.ClipData
import android.content.ClipboardManager
import java.io.File
import java.io.FileOutputStream
import android.net.Uri
import com.example.myempty.githubk.terminal.ToolchainStatus

/**
 * ToolchainPage v4.3 — 完整 IDE / SDK 管理器。
 *
 * 日志区修复（v4.3，无 AndroidX 依赖）：
 *  - 页面拆成“操作区(上滚) + 日志区(底部固定、独立滚动)”，两层 ScrollView 不再嵌套，
 *    日志手势完全独立，不会出现“滚不动/误滚外层”的问题；
 *  - 日志改为 100ms 节流 + 增量 append，不再逐行 setText 整个几百 KB 文本；
 *  - 文件持久化约 1.5s 节流一次，避免高频磁盘 IO 拖慢安装线程；
 *  - 自动滚动跟随：停留在底部时新日志自动滚到底；上翻查看历史时暂停跟随，
 *    也可点“滚到底部”恢复。
 */
class ToolchainPage(private val host: PageHost) {
    companion object {
        /** 外部入口（如设置页「离线安装完整环境(内置)」）设置：页面构建后自动执行的任务标识。 */
        @Volatile
        var autoStartTask: String? = null
    }

    private val state = host.state

    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var status: TextView

    private val logLock = Any()
    private val logBuffer = StringBuilder()
    private val uiText = StringBuilder()
    private var flushScheduled = false
    private var followBottom = true
    private var lastPersist = 0L

    private val maxLogChars = 300_000
    private val logFile: File get() = File(host.context.filesDir, "logs/toolchain.log")
    private val uiHandler = Handler(Looper.getMainLooper())
    private val flushRunnable = Runnable { flushUi() }

    fun buildView(): View {
        val root = UiKit.vstack(host.context).apply { setBackgroundColor(ThemeManager.colors.surface) }
        val bar = UiKit.hstack(host.context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UiKit.dp(host.context, 8), UiKit.dp(host.context, 5), UiKit.dp(host.context, 8), UiKit.dp(host.context, 5))
            setBackgroundColor(ThemeManager.colors.surfaceElevated)
        }
        bar.addView(UiKit.button(host.context, "‹") { host.popPage() })
        bar.addView(UiKit.title(host.context, "IDE Environment", 18f).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setPadding(UiKit.dp(host.context, 8), 0, 0, 0)
        })
        bar.addView(UiKit.button(host.context, "↻") { forceRefreshStatus() })
        root.addView(bar)

        // ============ 上区：操作按钮（自身可滚动） ============
        val scroll = ScrollView(host.context)
        val body = UiKit.vstack(host.context).apply {
            setPadding(UiKit.dp(host.context, 14), UiKit.dp(host.context, 12), UiKit.dp(host.context, 14), UiKit.dp(host.context, 16))
        }
        body.addView(UiKit.pageHeader(host.context, "完整 IDE 编译环境", "Termux/ZeroTermux 风格运行时 + Android SDK + Flutter + Dart"))
        status = TextView(host.context).apply {
            textSize = 12f; setTextColor(ThemeManager.colors.onSurface)
            setPadding(0, UiKit.dp(host.context, 6), 0, UiKit.dp(host.context, 10))
        }
        body.addView(status)

        body.addView(action("一键安装完整环境", "JDK 17 · Gradle · Git · Clang/CMake/Ninja · Android SDK · ARM64 ADB/aapt2 · Flutter · Dart") {
            runInstall { state.toolchain.installCompleteIdeEnvironment(it) }
        })
        body.addView(action("只安装 Android 编译环境", "Command-line Tools + API 34/35/36 + Build Tools + ARM64 aapt2/ADB") {
            runInstall {
                state.toolchain.installAndroidCommandLineTools(it) &&
                    state.toolchain.ensureAndroidSdkComponents(36, "36.0.0", it) &&
                    state.toolchain.configureArm64BuildEnvironment(it)
            }
        })
        body.addView(action("安装 Flutter + Dart", "ARM64 使用 Termux 维护的 Flutter 包；Dart 与 Flutter 版本绑定") {
            runInstall { state.toolchain.ensureDartFlutterToolchain(it) }
        })
        body.addView(action("修复 ARM64 Gradle 构建", "将 Termux 原生 aapt2 写入 Gradle 用户环境，避免 AGP 下载 x86_64 aapt2") {
            runInstall { state.toolchain.configureArm64BuildEnvironment(it) }
        })
        body.addView(action("Gradle Wrapper 修复", "为当前工作区项目生成可用 gradlew；若 /storage noexec，终端会自动使用系统 Gradle") {
            val p = state.workspace.projects().firstOrNull()
            if (p == null) host.toast("暂无工作区项目") else runInstall { state.toolchain.repairGradleWrapper(p.path, it) }
        })
        body.addView(UiKit.spacer(host.context, 4))
        body.addView(UiKit.section(host.context, "内置离线包安装 · 无需网络"))
        body.addView(action("离线安装完整环境（内置 APK 包）", "纯离线：使用 APK 内置 bootstrap + offline-toolchain deb（JDK/Gradle/Git/Python/Node/Clang/CMake/Dart/Flutter 等），不访问网络") {
            runInstall { state.toolchain.installOfflineFullEnvironment(it) }
        })
        body.addView(UiKit.spacer(host.context, 4))
        body.addView(UiKit.section(host.context, "本地离线导入 · 无需网络"))
        body.addView(action("导入本地离线环境目录", "选择已解压的目录：可含 runtime（bin/bash）+ jdk/ + gradle/ + android-sdk/ + git/ + dart-sdk/ + flutter/；也支持直接选单个 JDK/Gradle/Android SDK 目录") {
            host.pickFolder { uri -> if (uri != null) importLocalDir(uri) else host.toast("已取消选择") }
        })
        body.addView(action("安装本地 runtime ZIP", "选择本机保存的 Termux bootstrap-aarch64.zip，离线安装 bash/apt/pkg/git 基础运行时") {
            host.pickFile { uri -> if (uri != null) importRuntimeZip(uri) else host.toast("已取消选择") }
        })
        body.addView(UiKit.spacer(host.context, 4))
        body.addView(UiKit.section(host.context, "Termux 终端 · pkg/apt 包管理器"))
        body.addView(action("更新 Termux 软件源（apt update）", "官方 packages-cf 源 + 清华/中科大/北外多镜像自动切换；首次安装后自动后台执行一次") {
            runInstall { state.runtime.updateTermuxRepositories(it, force = true) }
        })
        body.addView(action("打开终端执行 pkg install", "在纯 Termux 终端内用原生 pkg/apt 安装 git/python/node/vim 等任意包") {
            host.openTerminal()
        })
        body.addView(UiKit.spacer(host.context, 4))
        body.addView(UiKit.ghostButton(host.context, "Toolchain Doctor") {
            replaceLog("正在检测（含 gradle --version 等子进程），请稍候…\n")
            Thread {
                val report = state.toolchain.doctor(state.sdkHome(), state.javaHome(), state.gradleHome())
                uiHandler.post { replaceLog(report) }
            }.start()
        })
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // ============ 下区：日志面板（固定、独立滚动） ============
        val panel = UiKit.vstack(host.context).apply {
            setBackgroundColor(ThemeManager.colors.surfaceAlt)
            setPadding(UiKit.dp(host.context, 0), UiKit.dp(host.context, 8), UiKit.dp(host.context, 0), UiKit.dp(host.context, 8))
        }
        panel.addView(LinearLayout(host.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UiKit.dp(host.context, 12), 0, UiKit.dp(host.context, 12), UiKit.dp(host.context, 6))
            addView(TextView(host.context).apply {
                text = "安装日志"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(ThemeManager.colors.onSurface)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            addView(UiKit.ghostButton(host.context, "滚到底") {
                followBottom = true
                logScroll.postDelayed({ logScroll.fullScroll(View.FOCUS_DOWN) }, 60)
            })
            addView(UiKit.spacer(host.context, 4))
            addView(UiKit.ghostButton(host.context, "复制") {
                val cm = host.context.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("GitHubK Studio Toolchain Log", uiText.toString().ifBlank { log.text.toString() }))
                host.toast("日志已复制")
            })
            addView(UiKit.spacer(host.context, 4))
            addView(UiKit.ghostButton(host.context, "清空") { clearLogUi() })
        })
        logScroll = ScrollView(host.context).apply {
            setBackgroundColor(ThemeManager.colors.surface)
            setOnScrollChangeListener { view, _, _, _, _ ->
                val content = (view as? ScrollView)?.getChildAt(0)
                val range = if (content != null) content.height - view.height else 0
                followBottom = view.scrollY >= range - UiKit.dp(host.context, 48)
            }
        }
        log = TextView(host.context).apply {
            typeface = Typeface.MONOSPACE; textSize = 10.5f
            setTextColor(ThemeManager.colors.onSurface)
            setPadding(UiKit.dp(host.context, 10), UiKit.dp(host.context, 8), UiKit.dp(host.context, 10), UiKit.dp(host.context, 8))
            text = ""
        }
        logScroll.addView(log, LinearLayout.LayoutParams(-1, -2))
        panel.addView(logScroll, LinearLayout.LayoutParams(-1, UiKit.dp(host.context, 300)))
        val openTerminal = UiKit.ghostButton(host.context, "打开终端") { host.openTerminal() }
        panel.addView(openTerminal)
        (openTerminal.layoutParams as? LinearLayout.LayoutParams)?.apply {
            topMargin = UiKit.dp(host.context, 8); bottomMargin = UiKit.dp(host.context, 2)
        }
        root.addView(panel, LinearLayout.LayoutParams(-1, -2))

        // 载入上次日志
        val persisted = loadPersistedLog()
        if (persisted.isNotEmpty()) {
            log.text = persisted
            uiText.append(persisted)
            logScroll.postDelayed({ logScroll.fullScroll(View.FOCUS_DOWN) }, 80)
        }
        refreshStatus()
        // 外部入口（如设置页「离线安装完整环境(内置)」）设置的自动任务：进入页面后立即执行
        val task = autoStartTask
        autoStartTask = null
        if (task != null) {
            logScroll.post {
                when (task) {
                    "offline-full" -> runInstall { state.toolchain.installOfflineFullEnvironment(it) }
                    else -> {}
                }
            }
        }
        return root
    }

    private fun action(title: String, desc: String, action: () -> Unit): View {
        val card = UiKit.vstack(host.context).apply {
            background = UiKit.rounded(host.context, ThemeManager.colors.surfaceElevated, 14, ThemeManager.colors.divider, 1)
            setPadding(UiKit.dp(host.context, 12), UiKit.dp(host.context, 11), UiKit.dp(host.context, 12), UiKit.dp(host.context, 11))
            setOnClickListener { action() }
        }
        card.addView(TextView(host.context).apply {
            text = title; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(ThemeManager.colors.onSurface)
        })
        card.addView(TextView(host.context).apply {
            text = desc; textSize = 11.5f; setTextColor(ThemeManager.colors.muted)
            setPadding(0, UiKit.dp(host.context, 4), 0, 0)
        })
        card.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = UiKit.dp(host.context, 8) }
        return card
    }

    private fun importLocalDir(uri: Uri) {
        val dest = File(host.context.filesDir, "local-toolchain-import")
        replaceLog("准备导入本地离线环境目录…\n")
        Thread {
            try {
                runCatching { dest.deleteRecursively() }
                dest.mkdirs()
                appendLog("正在复制所选目录（大目录可能需要几分钟），请勿关闭页面…\n")
                flushNow()
                if (!state.workspace.copyTreeTo(uri, dest)) {
                    appendLog("[LOCAL] 目录复制失败或内容为空\n")
                    flushNow()
                    host.runUi { forceRefreshStatus() }
                    return@Thread
                }
                appendLog("[LOCAL] 复制完成，开始识别并安装组件…\n")
                flushNow()
                val ok = state.toolchain.installFromLocalOfflineDir(dest) { line -> appendLog(line) }
                appendLog("\n${if (ok) "===== 本地离线导入完成 =====" else "===== 导入结束（部分失败，请查看上方日志） ====="}\n")
                flushNow()
                host.runUi {
                    forceRefreshStatus()
                    if (ok) host.toast("离线环境导入完成") else host.toast("离线导入未完全成功，详见日志")
                }
                runCatching { dest.deleteRecursively() }
            } catch (t: Throwable) {
                appendLog("\nERROR: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}\n")
                flushNow()
                host.runUi { forceRefreshStatus() }
            }
        }.start()
    }

    private fun importRuntimeZip(uri: Uri) {
        val zip = File(host.context.cacheDir, "bootstrap-local.zip")
        replaceLog("准备安装本地 runtime ZIP…\n")
        Thread {
            try {
                appendLog("正在复制 bootstrap ZIP…\n")
                flushNow()
                if (!state.workspace.copyFileTo(uri, zip)) {
                    appendLog("[LOCAL] ZIP 复制失败\n")
                    flushNow(); host.runUi { forceRefreshStatus() }; return@Thread
                }
                appendLog("ZIP 大小：${"%.1f".format(zip.length() / 1024.0 / 1024.0)} MB\n")
                appendLog("正在解压并修复内置运行时（ELF 解释器 / shebang / 权限）…\n")
                flushNow()
                val ok = state.toolchain.installRuntimeFromLocalZip(zip) { }
                appendLog(if (ok) "\n===== 本地 runtime 安装完成 =====" else "\n===== 本地 runtime 安装失败 =====")
                appendLog("\n")
                flushNow()
                host.runUi {
                    forceRefreshStatus()
                    host.toast(if (ok) "本地 runtime 安装完成" else "本地 runtime 安装失败，请查看日志")
                }
                zip.delete()
            } catch (t: Throwable) {
                appendLog("\nERROR: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}\n")
                flushNow(); host.runUi { forceRefreshStatus() }
            }
        }.start()
    }

    private fun runInstall(block: (append: (String) -> Unit) -> Boolean) {
        replaceLog("开始…\n")
        Thread {
            val ok = try {
                block { line -> appendLog(line) }
            } catch (t: Throwable) {
                appendLog("\nERROR: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}\n")
                false
            }
            appendLog("\n${if (ok) "===== 完成 =====" else "===== 未完全成功 ====="}\n")
            flushNow()
            host.runUi { forceRefreshStatus() }
        }.start()
    }

    // ---------------- 日志缓冲（线程安全 + 节流） ----------------

    private fun loadPersistedLog(): String {
        val text = runCatching { if (logFile.isFile) logFile.readText() else "" }.getOrDefault("")
        synchronized(logLock) {
            logBuffer.setLength(0)
            logBuffer.append(text.takeLast(maxLogChars))
        }
        return logBuffer.toString()
    }

    /** 安装线程可随时调用；先入缓冲，主线程 100ms 节流批量刷新。 */
    private fun appendLog(line: String) {
        // 过滤 proot/linker 反复刷屏的无害警告，避免日志面板被无用行占满
        if (line.contains("WARNING: linker") || line.contains("failed to find generated linker configuration")) return
        synchronized(logLock) {
            logBuffer.append(line)
            if (logBuffer.length > maxLogChars) {
                logBuffer.delete(0, logBuffer.length - maxLogChars)
            }
        }
        scheduleFlush(100)
    }

    private fun scheduleFlush(delayMs: Long) {
        if (flushScheduled) return
        flushScheduled = true
        uiHandler.postDelayed(flushRunnable, delayMs)
    }

    /** 线程安全：安装/导入线程可能调用本方法强制刷新，但 TextView 只能由主线程触碰。
     *  非主线程调用时转投主线程 Handler 执行，避免 CalledFromWrongThreadException 崩溃。 */
    private fun flushNow() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            uiHandler.removeCallbacks(flushRunnable)
            flushScheduled = false
            uiHandler.post { flushUi() }
            return
        }
        uiHandler.removeCallbacks(flushRunnable)
        flushScheduled = false
        flushUi()
    }

    /** 主线程：增量刷新 TextView，并在跟随模式下滚到底部。 */
    private fun flushUi() {
        flushScheduled = false
        val snapshot: String = synchronized(logLock) { logBuffer.toString() }
        if (snapshot.isEmpty()) return
        if (snapshot.length < uiText.length) {
            // 发生裁剪：整段重设
            uiText.setLength(0); uiText.append(snapshot)
            log.text = snapshot
        } else if (snapshot.length > uiText.length) {
            val seg = snapshot.substring(uiText.length)
            uiText.append(seg)
            log.append(seg)
            if (followBottom) {
                logScroll.postDelayed({ logScroll.fullScroll(View.FOCUS_DOWN) }, 60)
            }
        }
        maybePersist()
    }

    private fun maybePersist() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPersist < 1500) return
        lastPersist = now
        persistNow()
    }

    private fun persistNow() {
        runCatching {
            logFile.parentFile?.mkdirs()
            FileOutputStream(logFile).use { it.write(uiText.toString().toByteArray(Charsets.UTF_8)) }
        }
    }

    private fun replaceLog(text: String) {
        host.runUi {
            clearLogUi(updateText = text)
            followBottom = true
        }
    }

    private fun clearLogUi(updateText: String = "") {
        synchronized(logLock) {
            logBuffer.setLength(0)
            logBuffer.append(updateText.takeLast(maxLogChars))
        }
        uiText.setLength(0); uiText.append(updateText.takeLast(maxLogChars))
        log.text = updateText.takeLast(maxLogChars)
        runCatching { logFile.delete() }
        lastPersist = 0L
        if (updateText.isNotEmpty()) {
            logScroll.postDelayed({ logScroll.fullScroll(View.FOCUS_DOWN) }, 60)
        }
    }

    /** 清除 3s/4s 缓存后强制重新检测（手动刷新、安装/导入完成后）。 */
    private fun forceRefreshStatus() {
        state.toolchain.invalidateStatusCache()
        refreshStatus()
    }

    private fun refreshStatus() {
        // 工具检测（bash/java/gradle/git 子进程）耗时，移到后台线程，
        // 主线程只负责渲染结果，避免进入环境中心时卡住 UI。
        state.toolchain.statusAsync(onResult = { s ->
            host.runUi {
                status.text = buildString {
                    append(if (s.ready) "● 环境就绪" else "○ 环境未完整")
                    append("\nJDK: ${if (s.java) "✓" else "×"}   Gradle: ${if (s.gradle) "✓" else "×"}   Git: ${if (s.git) "✓" else "×"}")
                    append("\nAndroid SDK: ${if (s.sdk) "✓" else "×"}   ADB: ${if (s.adb) "✓" else "×"}")
                    append("\nFlutter: ${if (s.flutter) "✓" else "×"}   Dart: ${if (s.dart) "✓" else "×"}")
                    append("\nSDK: ${s.sdkPath.ifBlank { "未配置" }}")
                    // Termux 终端环境（与 IDE 构建环境相互独立展示）
                    val rt = state.runtime
                    val pkgOk = rt.termuxPkgReady()
                    val listsOk = rt.termuxAptListsReady()
                    append("\n终端 pkg/apt: ${if (pkgOk) "✓ 就绪" else "× 未就绪"}   软件源: ${if (listsOk) "✓ 已拉取" else "○ 待 apt update"}")
                    append("\n（IDE 编译环境与终端登录会话的 PATH 相互隔离）")
                }
            }
        })
    }
}
