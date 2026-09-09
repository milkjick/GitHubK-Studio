package com.example.myempty.githubk.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.example.myempty.githubk.R
import com.example.myempty.githubk.terminal.EnvManager
import com.example.myempty.githubk.terminal.EnvTool

/**
 * FirstLaunchGuide v2：首次启动自动引导安装开发环境（参考 Android CodeStudio IDE）。
 * - 检测本机工具链（Git / JDK / Gradle / Python / Node / Flutter / Dart）
 * - 缺失组件：一键自动安装 → 打开「应用内置终端」直接执行安装脚本（不跳转外部 Termux）
 * - 复制脚本 / 打开内置终端环境中心 / 跳过
 * - 标记 first_launch 完成，避免每次启动都出现
 */
class FirstLaunchGuide(private val host: PageHost) {

    private val state = host.state
    private lateinit var resultBox: LinearLayout
    private var tools: List<EnvTool> = emptyList()
    private var detecting = false

    fun buildView(): View {
        val root = UiKit.vstack(host.context).apply {
            setPadding(UiKit.dp(host.context, 16), UiKit.dp(host.context, 14), UiKit.dp(host.context, 16), UiKit.dp(host.context, 10))
        }

        // 页面头：IDE 欢迎（渐变胶囊）
        root.addView(UiKit.hstack(host.context).apply {
            addView(UiKit.icon(host.context, com.example.myempty.githubk.R.drawable.ic_terminal, 22, ThemeManager.colors.primary))
            addView(UiKit.spacer(host.context, 8))
            addView(UiKit.title(host.context, "欢迎使用 GitHubK Studio", 20f))
        })
        root.addView(UiKit.label(host.context, "首次启动：自动检测开发环境，缺失组件可直接在应用内置终端一键安装", color = R.color.muted))

        root.addView(UiKit.spacer(host.context, 10))
        root.addView(UiKit.section(host.context, "环境检测"))

        val scroll = ScrollView(host.context).apply { isFillViewport = true }
        resultBox = UiKit.vstack(host.context)
        scroll.addView(resultBox)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(UiKit.spacer(host.context, 8))
        root.addView(UiKit.button(host.context, "⚡ 自动安装（内置终端）") { autoInstall() })
        root.addView(UiKit.spacer(host.context, 6))
        root.addView(UiKit.hstack(host.context).apply {
            addView(UiKit.ghostButton(host.context, "重新检测") { detect() })
            addView(UiKit.spacer(host.context, 6))
            addView(UiKit.ghostButton(host.context, "打开内置终端") { finishAndOpenTerminal() })
            addView(UiKit.spacer(host.context, 6))
            addView(UiKit.ghostButton(host.context, "复制脚本") { copyScript() })
        })
        root.addView(UiKit.spacer(host.context, 6))
        root.addView(UiKit.ghostButton(host.context, "跳过（稍后在设置中安装）") { finish() })

        detect()
        return root
    }

    /** 一键自动安装：先确保内置运行时，然后安装工具链。 */
    private fun autoInstall() {
        val missing = tools.filter { !it.installed }.map { it.termuxPkg }
        if (missing.isEmpty()) { host.toast("环境已完整，无需安装"); return }
        markDone()
        state.secure.put("pending_ide_setup", "1")
        if (state.runtime.isInstalled()) {
            // 编译工具链通过 Termux RUN_COMMAND 安装，输出回传到当前终端。
            host.openTerminalRun(null, "setup-ide")
        } else {
            // 先初始化终端；安装完成后 TerminalPage 会继续 setup-ide。
            host.openTerminalRun(null, "install-env")
        }
    }

    private fun detect() {
        if (detecting) return
        detecting = true
        resultBox.removeAllViews()
        resultBox.addView(UiKit.label(host.context, "检测中...", color = R.color.muted))
        Thread {
            val list = EnvManager(host.context).detect()
            tools = list
            host.runUi {
                detecting = false
                render(list)
            }
        }.start()
    }

    private fun render(list: List<EnvTool>) {
        resultBox.removeAllViews()
        val installedCount = list.count { it.installed }
        resultBox.addView(UiKit.card(host.context, UiKit.vstack(host.context).apply {
            addView(UiKit.hstack(host.context).apply {
                addView(UiKit.label(
                    host.context,
                    "已安装 $installedCount/${list.size} 个组件",
                    color = if (installedCount == list.size) R.color.success else R.color.warning
                ).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
                addView(UiKit.badge(host.context, if (installedCount == list.size) "环境完整" else "需要安装"))
            })
            addView(UiKit.spacer(host.context, 6))
            // 进度条（简易）
            addView(LinearLayout(host.context).apply {
                orientation = LinearLayout.HORIZONTAL
                val width = host.context.resources.displayMetrics.widthPixels - UiKit.dp(host.context, 32)
                val filled = ((width * installedCount / list.size).coerceAtLeast(0))
                setBackgroundColor(ThemeManager.colors.surfaceAlt)
                addView(View(host.context).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = UiKit.dp(host.context, 3).toFloat()
                        setColor(ThemeManager.colors.primary)
                    }
                    layoutParams = LinearLayout.LayoutParams(filled, UiKit.dp(host.context, 6))
                })
            })
        }))
        resultBox.addView(UiKit.spacer(host.context, 6))
        list.forEach { t ->
            val row = UiKit.hstack(host.context).apply {
                addView(UiKit.label(
                    host.context,
                    if (t.installed) "✓ ${t.name}" else "✗ ${t.name}",
                    color = if (t.installed) R.color.success else R.color.error,
                    size = 13f
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
                addView(UiKit.label(
                    host.context,
                    if (t.installed) t.version.take(24) else "缺失 · ${t.termuxPkg}",
                    color = if (t.installed) R.color.muted else R.color.warning,
                    size = 11f
                ))
            }
            resultBox.addView(UiKit.card(host.context, row))
            resultBox.addView(UiKit.spacer(host.context, 4))
        }
        if (installedCount < list.size) {
            resultBox.addView(UiKit.spacer(host.context, 8))
            resultBox.addView(UiKit.label(
                host.context,
                "点击「自动安装」会初始化内置 POSIX 终端运行时。\n如需 Android/Flutter APK 编译，再进入「终端 → setup-ide」，通过 Termux RUN_COMMAND 安装 JDK、Gradle、Android SDK Platform 与 ARM 构建工具。",
                color = R.color.muted, size = 12f
            ))
        }
    }

    private fun copyScript() {
        val script = EnvManager(host.context).installScript(tools.filter { !it.installed }.map { it.termuxPkg })
        host.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)?.let { cm ->
            (cm as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("setup", script))
        }
        host.toast("安装脚本已复制")
    }

    private fun finishAndOpenTerminal() {
        markDone()
        host.openTerminal()
    }

    private fun finish() {
        markDone()
        host.popPage()
    }

    private fun markDone() {
        state.secure.put("first_launch", "1")
    }
}
