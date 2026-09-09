package com.example.myempty.githubk.ui

import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.example.myempty.githubk.terminal.EnvManager
import com.example.myempty.githubk.terminal.ShizukuShell
import com.example.myempty.githubk.shizuku.ShizukuBridge
import com.example.myempty.githubk.shizuku.DhizukuBridge

/**
 * SettingsPage：GitHub Token、AI Provider、SDK 路径、构建历史。
 */
class SettingsPage(private val host: PageHost) {

    private val state = host.state
    private val envMgr = EnvManager(host.context)

    fun buildView(): View {
        val root = UiKit.vstack(host.context).apply {
            setPadding(UiKit.dp(host.context, 14), UiKit.dp(host.context, 12), UiKit.dp(host.context, 14), UiKit.dp(host.context, 8))
        }
        root.addView(UiKit.pageHeader(host.context, "设置", "令牌、AI 与工具链配置", null))
        root.addView(UiKit.spacer(host.context, 8))

        // GitHub Token
        root.addView(UiKit.hstack(host.context).apply {
            addView(UiKit.icon(host.context, com.example.myempty.githubk.R.drawable.ic_user, 16, ThemeManager.colors.primary))
            addView(UiKit.section(host.context, "GitHub 令牌"))
        })
        val tokenInput = UiKit.input(host.context, "GitHub Personal Access Token")
        tokenInput.setText(state.secure.get("github_token") ?: "")
        root.addView(tokenInput)
        root.addView(UiKit.spacer(host.context, 6))
        root.addView(UiKit.hstack(host.context).apply {
            addView(UiKit.button(host.context, "保存令牌") {
                val t = tokenInput.text.toString().trim()
                if (t.isBlank()) { host.toast("令牌不能为空"); return@button }
                state.secure.put("github_token", t)
                host.toast("已保存")
            })
        })
        root.addView(UiKit.spacer(host.context, 8))

        // AI 智能（卡片入口 → AiSettingsPage）
        root.addView(UiKit.section(host.context, "AI 智能"))
        root.addView(buildAiCard())
        root.addView(UiKit.spacer(host.context, 8))

        // 环境检测入口
        root.addView(UiKit.section(host.context, "开发环境"))
        val envStatusLabel = UiKit.label(host.context,
            "状态：检测中…\nJDK=… · Gradle=… · Git=…\nAndroid SDK=… · ADB=…\nDart=… · Flutter=…",
            color = com.example.myempty.githubk.R.color.warning, size = 11f)
        root.addView(envStatusLabel)
        // 真实检测含 gradle --version 等子进程启动，放到后台线程执行，避免阻塞 UI 渲染。
        state.toolchain.statusAsync(onResult = { s ->
            host.runUi {
                envStatusLabel.text =
                    "状态：" + (if (s.ready) "✓ Android 构建环境就绪" else "⚠ 环境尚未完整") +
                    "\nJDK=${if (s.java) "OK" else "缺失"} · Gradle=${if (s.gradle) "OK" else "缺失"} · Git=${if (s.git) "OK" else "缺失"}" +
                    "\nAndroid SDK=${if (s.sdk) "OK" else "缺失"} · ADB=${if (s.adb) "OK" else "缺失"}" +
                    "\nDart=${if (s.dart) "OK" else "缺失"} · Flutter=${if (s.flutter) "OK" else "缺失"}"
                envStatusLabel.setTextColor(if (s.ready) com.example.myempty.githubk.R.color.success else com.example.myempty.githubk.R.color.warning)
            }
        })
        root.addView(UiKit.spacer(host.context, 5))
        root.addView(UiKit.hstack(host.context).apply {
            addView(UiKit.button(host.context, "完整 IDE 环境中心") { host.pushPage(ToolchainPage(host).buildView()) })
            addView(UiKit.spacer(host.context, 8))
            addView(UiKit.ghostButton(host.context, "离线安装完整环境(内置)") {
                // 纯离线安装：使用 APK 内置 bootstrap + offline-toolchain deb，不访问网络。
                // 打开 IDE 环境中心并自动开始安装，完整进度展示在日志面板中。
                ToolchainPage.autoStartTask = "offline-full"
                host.pushPage(ToolchainPage(host).buildView())
            })
            addView(UiKit.spacer(host.context, 8))
            addView(UiKit.ghostButton(host.context, "刷新环境状态") {
                host.runUi { host.pushPage(ToolchainPage(host).buildView()) }
            })
            addView(UiKit.spacer(host.context, 8))
            addView(UiKit.ghostButton(host.context, "安装 Dart + Flutter SDK") {
                Thread {
                    val ok = state.toolchain.ensureDartFlutterToolchain { line -> host.runUi { host.toast(line.trim().take(220)) } }
                    host.runUi { host.toast(if (ok) "Dart/Flutter SDK 已就绪" else "未找到可用的 Dart/Flutter 安装包") }
                }.start()
            })
            addView(UiKit.spacer(host.context, 8))
            addView(UiKit.ghostButton(host.context, "环境诊断") {
                UiKit.dialog(host.context, "Toolchain Doctor", UiKit.label(host.context, "正在检测（含 gradle --version，请稍候）…"))
                Thread {
                    val report = state.toolchain.doctor(state.sdkHome(), state.javaHome(), state.gradleHome())
                    host.runUi {
                        UiKit.dialog(host.context, "Toolchain Doctor", UiKit.label(host.context, report))
                    }
                }.start()
            })
            addView(UiKit.spacer(host.context, 8))
            addView(UiKit.ghostButton(host.context, "SDK 组件检测/安装") {
                Thread {
                    val ok = state.toolchain.installAndroidCommandLineTools { line -> host.runUi { host.toast(line.trim().take(220)) } } && state.toolchain.ensureAndroidSdkComponents(36, "36.0.0") { line -> host.runUi { host.toast(line.trim().take(220)) } }
                    host.runUi { host.toast(if (ok) "Android SDK 组件已就绪" else "SDK 组件安装未完成") }
                }.start()
            })
            addView(UiKit.spacer(host.context, 8))
            addView(UiKit.ghostButton(host.context, "Gradle Wrapper 修复") {
                val project = state.workspace.projects().firstOrNull()
                if (project == null) { host.toast("暂无项目"); return@ghostButton }
                Thread {
                    val ok = state.toolchain.repairGradleWrapper(project.path) { line -> host.runUi { host.toast(line.trim().take(220)) } }
                    host.runUi { host.toast(if (ok) "Wrapper 已修复" else "Wrapper 修复失败，请先准备 Gradle") }
                }.start()
            })
            addView(UiKit.spacer(host.context, 8))
            addView(UiKit.ghostButton(host.context, "安装 Termux") { envMgr.openFdroidTermux() })
            addView(UiKit.spacer(host.context, 8))
            addView(UiKit.ghostButton(host.context, "重新显示首次引导") {
                state.secure.remove("first_launch")
                host.pushPage(FirstLaunchGuide(host).buildView())
            })
        })
        root.addView(UiKit.spacer(host.context, 8))

        // 工具链路径
        root.addView(UiKit.section(host.context, "工具链路径（可选）"))
        root.addView(pathInput("SDK 路径 (ANDROID_HOME)", "sdk_home"))
        root.addView(UiKit.spacer(host.context, 4))
        root.addView(pathInput("JDK 路径 (JAVA_HOME)", "java_home"))
        root.addView(UiKit.spacer(host.context, 4))
        root.addView(pathInput("Gradle 路径", "gradle_home"))
        root.addView(UiKit.spacer(host.context, 4))
        root.addView(pathInput("Dart SDK 路径", "dart_home"))
        root.addView(UiKit.spacer(host.context, 4))
        root.addView(pathInput("Flutter SDK 路径", "flutter_home"))
        root.addView(UiKit.spacer(host.context, 4))
        root.addView(pathInput("Flutter 可执行文件", "flutter_bin"))
        root.addView(UiKit.spacer(host.context, 8))

        // 构建历史
        root.addView(UiKit.section(host.context, "构建历史"))
        root.addView(UiKit.ghostButton(host.context, "查看构建历史") { showHistory() })
        root.addView(UiKit.spacer(host.context, 8))

        // Build Manager 4.4
        root.addView(UiKit.section(host.context, "Build Manager"))
        val currentBuild = state.buildManager.current()
        val queuedBuilds = state.buildManager.queued()
        root.addView(UiKit.label(host.context,
            if (currentBuild == null) "当前：空闲" else "当前：#${currentBuild.request.id} ${currentBuild.request.project.name} · ${currentBuild.state}" + "\n队列：${queuedBuilds.size}",
            color = if (currentBuild == null) com.example.myempty.githubk.R.color.muted else com.example.myempty.githubk.R.color.warning, size = 11f))
        root.addView(UiKit.hstack(host.context).apply {
            addView(UiKit.ghostButton(host.context, "停止当前构建") {
                if (state.buildManager.cancel()) host.toast("已请求停止构建") else host.toast("没有正在运行的构建")
            })
            addView(UiKit.spacer(host.context, 8))
            addView(UiKit.ghostButton(host.context, "清空构建队列") {
                host.toast("已清空 ${state.buildManager.clearQueue()} 个任务")
            })
        })
        root.addView(UiKit.spacer(host.context, 8))

        // Shizuku 权限中心
        root.addView(UiKit.section(host.context, "Shizuku 权限中心"))
        val shizukuAvailable = ShizukuShell.isServiceAvailable()
        val shizukuGranted = shizukuAvailable && ShizukuShell.hasPermission()
        root.addView(UiKit.label(
            host.context,
            when {
                shizukuGranted -> "● Shizuku 已连接 · 已授权"
                shizukuAvailable -> "● Shizuku 已连接 · 未授权"
                else -> "○ Shizuku/Dhizuku 未运行（应用仍可使用内置终端）"
            },
            color = when {
                shizukuGranted -> com.example.myempty.githubk.R.color.success
                shizukuAvailable -> com.example.myempty.githubk.R.color.warning
                else -> com.example.myempty.githubk.R.color.muted
            },
            size = 12f
        ))
        root.addView(UiKit.label(host.context, "Shizuku 已接入 V3 Binder + UserService；Dhizuku 使用独立官方 API。授权请求在后台执行，不阻塞界面。", color = com.example.myempty.githubk.R.color.muted, size = 11f))
        root.addView(UiKit.spacer(host.context, 5))
        root.addView(UiKit.hstack(host.context).apply {
            addView(UiKit.button(host.context, if (shizukuGranted) "已授权" else "请求 Shizuku 权限") {
                if (!ShizukuShell.isServiceAvailable()) {
                    host.toast("请先启动 Shizuku 或 Dhizuku")
                } else if (ShizukuShell.hasPermission()) {
                    host.toast("Shizuku 已授权")
                } else {
                    ShizukuShell.requestPermission(host.context) { granted -> host.runUi { host.toast(if (granted) "Shizuku 授权成功" else "未获得 Shizuku 授权") } }
                }
            })
            addView(UiKit.spacer(host.context, 8))
            addView(UiKit.ghostButton(host.context, "打开 Shizuku/Dhizuku") {
                try {
                    var launch = host.context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (launch == null) {
                        launch = host.context.packageManager.getLaunchIntentForPackage("com.rosan.dhizuku")
                    }
                    if (launch == null) host.toast("未安装 Shizuku 或 Dhizuku") else host.context.startActivity(launch)
                } catch (e: Throwable) { host.toast("无法打开：${e.message}") }
            })
            addView(UiKit.spacer(host.context, 8))
            addView(UiKit.ghostButton(host.context, "Shizuku/Dhizuku 诊断") {
                val sb = StringBuilder()
                sb.append("Shizuku/Dhizuku 诊断:\n")
                sb.append("  服务可用: ${ShizukuShell.isServiceAvailable()}\n")
                sb.append("  已就绪: ${ShizukuBridge.isReady()}\n")
                sb.append("  已授权: ${ShizukuShell.hasPermission()}\n")
                sb.append("  UID: ${ShizukuShell.getUid()}\n")
                sb.append("  版本: ${ShizukuBridge.getServerVersion()}\n")
                sb.append("  后端: ${ShizukuShell.backendLabel()}\n")
                sb.append("  Shizuku已装: ${ShizukuBridge.isShizukuInstalled(host.context)}\n")
                sb.append("  Dhizuku已装: ${ShizukuBridge.isDhizukuInstalled(host.context)}\n")
                sb.append("  活跃管理器: ${ShizukuBridge.getActiveManager()}\n")
                host.toast(sb.toString())
            })
        })
        // Dhizuku：使用官方 Dhizuku API，和 Shizuku shell 通道分离。
        root.addView(UiKit.section(host.context, "Dhizuku 授权中心"))
        val dhInstalled = ShizukuBridge.isDhizukuInstalled(host.context)
        val dhGranted = if (dhInstalled) DhizukuBridge.isPermissionGranted(host.context) else false
        root.addView(UiKit.label(host.context,
            when {
                dhGranted -> "● Dhizuku 已连接 · 已授权"
                dhInstalled -> "● Dhizuku 已安装 · 未授权/未激活"
                else -> "○ Dhizuku 未安装"
            },
            color = when {
                dhGranted -> com.example.myempty.githubk.R.color.success
                dhInstalled -> com.example.myempty.githubk.R.color.warning
                else -> com.example.myempty.githubk.R.color.muted
            }, size = 12f))
        root.addView(UiKit.hstack(host.context).apply {
            addView(UiKit.button(host.context, if (dhGranted) "已授权" else "请求 Dhizuku 权限") {
                if (!dhInstalled) host.toast("请先安装并激活 Dhizuku")
                else if (DhizukuBridge.isPermissionGranted(host.context)) host.toast("Dhizuku 已授权")
                else DhizukuBridge.requestPermission(host.context) { ok -> host.toast(if (ok) "Dhizuku 授权成功" else "Dhizuku 未授权") }
            })
            addView(UiKit.spacer(host.context, 8))
            addView(UiKit.ghostButton(host.context, "打开 Dhizuku") {
                if (!DhizukuBridge.openManager(host.context)) host.toast("未安装 Dhizuku")
            })
        })
        root.addView(UiKit.label(host.context, "Dhizuku 用于 DeviceOwner/ProfileOwner 能力；终端/构建的 shell 提权仍由 Shizuku UserService 负责。", color = com.example.myempty.githubk.R.color.muted, size = 11f))
        root.addView(UiKit.spacer(host.context, 8))

        // 多主题
        root.addView(UiKit.section(host.context, "主题"))
        root.addView(UiKit.label(host.context, "当前：${ThemeManager.current.label}", color = com.example.myempty.githubk.R.color.accent))
        root.addView(UiKit.spacer(host.context, 6))
        val themeScroll = android.widget.HorizontalScrollView(host.context).apply {
            isHorizontalScrollBarEnabled = false
        }
        val themeRow = UiKit.hstack(host.context)
        ThemeManager.ThemeType.entries.forEach { t ->
            themeRow.addView(UiKit.ghostButton(host.context, t.label) {
                state.saveTheme(t.id)
                host.toast("已切换主题：${t.label}")
                host.rebuildAll()
            })
            themeRow.addView(UiKit.spacer(host.context, 4))
        }
        themeScroll.addView(themeRow)
        root.addView(themeScroll)
        root.addView(UiKit.spacer(host.context, 8))

        // MCP 服务
        root.addView(UiKit.section(host.context, "MCP 服务"))
        root.addView(UiKit.label(host.context, "添加远程 MCP Server（JSON-RPC over HTTP）", color = com.example.myempty.githubk.R.color.muted))
        val mcpName = UiKit.input(host.context, "名称，如 my-server")
        val mcpUrl = UiKit.input(host.context, "URL，如 http://127.0.0.1:8788")
        root.addView(mcpName)
        root.addView(UiKit.spacer(host.context, 4))
        root.addView(mcpUrl)
        root.addView(UiKit.spacer(host.context, 4))
        root.addView(UiKit.hstack(host.context).apply {
            addView(UiKit.button(host.context, "添加 / 更新") {
                val n = mcpName.text.toString().trim()
                val u = mcpUrl.text.toString().trim()
                if (state.mcp.addServer(n, u)) host.toast("已保存 MCP Server") else host.toast("名称或 URL 不能为空")
            })
            addView(UiKit.spacer(host.context, 8))
            addView(UiKit.ghostButton(host.context, "列出") { showMcpList() })
        })
        root.addView(UiKit.spacer(host.context, 4))
        root.addView(UiKit.ghostButton(host.context, "启动本地 MCP Server（端口 8788）") {
            val started = state.mcp.isLocalRunning
            if (started) { host.toast("本地 MCP Server 已在运行"); return@ghostButton }
            state.mcp.startLocalServer(8788) { tool, args ->
                when (tool) {
                    "ping" -> "pong"
                    "list_projects" -> state.workspace.projects().joinToString("\n") { "${it.name} [${it.type}]" }
                    "build" -> {
                        val p = state.workspace.projects().firstOrNull()
                        if (p != null) state.buildManager.buildDetailed(p.path, p.type).render() else "ERROR: no project"
                    }
                    "git_status" -> {
                        val p = state.workspace.projects().firstOrNull()
                        if (p != null) state.git.status(p.path).joinToString("\n") { (f, s) -> "$s $f" } else "ERROR: no project"
                    }
                    else -> "OK (local stub): $tool"
                }
            }
            host.toast("本地 MCP Server 已启动 (8788)")
        })
        root.addView(UiKit.spacer(host.context, 8))

        val scroll = ScrollView(host.context)
        scroll.addView(root)
        return scroll
    }

    private fun showMcpList() {
        val list = state.mcp.servers()
        val box = UiKit.vstack(host.context).apply {
            setPadding(UiKit.dp(host.context, 20), UiKit.dp(host.context, 10), UiKit.dp(host.context, 20), UiKit.dp(host.context, 10))
        }
        if (list.isEmpty()) {
            box.addView(UiKit.label(host.context, "暂无 MCP Server"))
        } else {
            list.forEach { s ->
                box.addView(UiKit.hstack(host.context).apply {
                    addView(UiKit.label(host.context, "${if (s.enabled) "●" else "○"} ${s.name} — ${s.url}", color = if (s.enabled) com.example.myempty.githubk.R.color.success else com.example.myempty.githubk.R.color.muted).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(UiKit.ghostButton(host.context, if (s.enabled) "停用" else "启用") {
                        state.mcp.toggleServer(s.name)
                        host.toast("已切换 ${s.name}")
                    })
                    addView(UiKit.spacer(host.context, 4))
                    addView(UiKit.ghostButton(host.context, "删除") {
                        state.mcp.removeServer(s.name)
                        host.toast("已删除 ${s.name}")
                    })
                })
                box.addView(UiKit.spacer(host.context, 6))
            }
        }
        UiKit.dialog(host.context, "MCP Server 列表", box)
    }

    /** 切换主题后立即刷新主界面背景（不需要重启即可看到主要变化）。 */
    private fun applyThemeToWindow() {
        val c = ThemeManager.colors
        (host.context as? android.app.Activity)?.window?.decorView?.setBackgroundColor(c.surface)
    }

    /** AI 智能卡片：状态摘要 + 打开 AI 配置中心 + 快速测试。 */
    private fun buildAiCard(): View {
        val cfg = state.ai.config()
        val keyOk = cfg.key.isNotBlank()
        val summary = UiKit.label(
            host.context,
            "服务商：${state.ai.providerLabel(cfg.provider.ifBlank { "compatible" })} · ${if (cfg.enabled) "● 已启用" else "○ 已停用"}" +
                "\n模型：${cfg.model.ifBlank { "未设置" }}" +
                "\nEndpoint：${cfg.endpoint.ifBlank { "未配置" }}" +
                "\nAPI Key：${if (keyOk) "已配置（加密存储）" else "未设置"}",
            color = if (cfg.enabled) com.example.myempty.githubk.R.color.on_surface else com.example.myempty.githubk.R.color.muted,
            size = 11.5f
        )
        val testLabel = UiKit.label(host.context, "点击“测试连接”验证当前配置是否可用", color = com.example.myempty.githubk.R.color.muted, size = 11f)
        val header = UiKit.hstack(host.context).apply {
            addView(UiKit.icon(host.context, com.example.myempty.githubk.R.drawable.ic_ai, 18, if (cfg.enabled) ThemeManager.colors.accent else ThemeManager.colors.muted))
            addView(UiKit.section(host.context, "AI 配置中心"))
            addView(UiKit.badge(host.context, if (cfg.enabled) "运行中" else "已停用", if (cfg.enabled) com.example.myempty.githubk.R.color.success else com.example.myempty.githubk.R.color.muted))
        }
        val card = UiKit.card(host.context, UiKit.vstack(host.context).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(UiKit.spacer(host.context, 2))
            addView(summary)
            addView(UiKit.spacer(host.context, 8))
            addView(UiKit.hstack(host.context).apply {
                addView(UiKit.button(host.context, "打开 AI 配置中心") { host.pushPage(AiSettingsPage(host).buildView()) })
                addView(UiKit.spacer(host.context, 8))
                addView(UiKit.ghostButton(host.context, "测试连接") {
                    testLabel.text = "测试中…"
                    testLabel.setTextColor(ThemeManager.colors.warning)
                    Thread {
                        val r = state.ai.test()
                        host.runUi {
                            testLabel.text = r
                            testLabel.setTextColor(if (r.startsWith("连接成功")) ThemeManager.colors.success else ThemeManager.colors.error)
                        }
                    }.start()
                })
            })
            addView(UiKit.spacer(host.context, 6))
            addView(testLabel)
        })
        return card
    }

    private fun pathInput(hint: String, key: String): LinearLayout {
        val input = UiKit.input(host.context, hint)
        input.setText(state.secure.get(key) ?: "")
        val box = UiKit.hstack(host.context).apply {
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(UiKit.spacer(host.context, 6))
            addView(UiKit.ghostButton(host.context, "保存") {
                state.secure.put(key, input.text.toString().trim())
                host.toast("已保存 $key")
            })
        }
        return box
    }

    private fun showHistory() {
        val items = state.buildHistory.load()
        val box = UiKit.vstack(host.context).apply {
            setPadding(UiKit.dp(host.context, 24), UiKit.dp(host.context, 12), UiKit.dp(host.context, 24), UiKit.dp(host.context, 12))
        }
        if (items.isEmpty()) {
            box.addView(UiKit.label(host.context, "暂无构建历史"))
        } else {
            items.take(20).forEach { it: com.example.myempty.githubk.buildsys.BuildHistory.Entry ->
                box.addView(UiKit.hstack(host.context).apply {
                    addView(UiKit.label(
                        host.context,
                        (if (it.success) "✓ " else "✗ ") + it.project + " · " + it.time,
                        color = if (it.success) com.example.myempty.githubk.R.color.success else com.example.myempty.githubk.R.color.error
                    ).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
                })
                box.addView(UiKit.label(host.context, it.summary, color = com.example.myempty.githubk.R.color.muted))
                box.addView(UiKit.spacer(host.context, 6))
            }
        }
        UiKit.dialog(host.context, "构建历史", box)
    }
}