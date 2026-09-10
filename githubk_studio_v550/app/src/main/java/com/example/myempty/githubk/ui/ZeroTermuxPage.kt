package com.example.myempty.githubk.ui

import android.app.AlertDialog
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.myempty.githubk.terminal.zerotermux.ZeroTermuxManager
import java.io.File

/** ZeroTermux feature center integrated with the native PTY terminal. */
class ZeroTermuxPage(private val host: PageHost) {
    private lateinit var body: LinearLayout
    private lateinit var info: TextView
    private val manager by lazy { ZeroTermuxManager(host.context, host.state.runtime) }

    fun buildView(): View {
        val root = UiKit.vstack(host.context).apply { setBackgroundColor(ThemeManager.colors.surface) }
        val bar = UiKit.hstack(host.context).apply {
            setPadding(UiKit.dp(host.context, 8), UiKit.dp(host.context, 6), UiKit.dp(host.context, 8), UiKit.dp(host.context, 6))
            setBackgroundColor(ThemeManager.colors.surfaceElevated)
        }
        bar.addView(UiKit.button(host.context, "←") { host.popPage() })
        bar.addView(UiKit.title(host.context, "ZeroTermux", 18f).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setPadding(UiKit.dp(host.context, 8), 0, 0, 0)
        })
        bar.addView(UiKit.button(host.context, "IDE") { host.pushPage(ToolchainPage(host).buildView()) })
        bar.addView(UiKit.button(host.context, "诊断") { showText("环境诊断", manager.diagnostics()) })
        root.addView(bar)

        val scroll = ScrollView(host.context)
        body = UiKit.vstack(host.context).apply {
            setPadding(UiKit.dp(host.context, 14), UiKit.dp(host.context, 12), UiKit.dp(host.context, 14), UiKit.dp(host.context, 24))
        }
        body.addView(UiKit.pageHeader(host.context, "ZeroTermux 功能中心", "原生 PTY + Termux runtime + ZeroTermux 扩展"))
        info = TextView(host.context).apply {
            text = "PREFIX: ${manager.prefix().absolutePath}\nHOME: ${manager.home().absolutePath}"
            textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(ThemeManager.colors.muted)
            setPadding(0, 0, 0, UiKit.dp(host.context, 10))
        }
        body.addView(info)

        manager.features().forEach { feature ->
            body.addView(featureCard(feature))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun featureCard(feature: ZeroTermuxManager.Feature): View {
        val box = UiKit.vstack(host.context).apply {
            background = UiKit.rounded(host.context, ThemeManager.colors.surfaceElevated, 14, ThemeManager.colors.divider, 1)
            setPadding(UiKit.dp(host.context, 12), UiKit.dp(host.context, 10), UiKit.dp(host.context, 12), UiKit.dp(host.context, 10))
            setOnClickListener { executeFeature(feature) }
        }
        box.addView(TextView(host.context).apply {
            text = feature.title; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(ThemeManager.colors.onSurface)
        })
        box.addView(TextView(host.context).apply {
            text = feature.description; textSize = 12f; setTextColor(ThemeManager.colors.muted)
            setPadding(0, UiKit.dp(host.context, 4), 0, 0)
        })
        if (!feature.command.isNullOrBlank()) {
            box.addView(TextView(host.context).apply {
                text = "$ ${feature.command}"; textSize = 10f; typeface = Typeface.MONOSPACE
                setTextColor(ThemeManager.colors.primary); setPadding(0, UiKit.dp(host.context, 6), 0, 0)
            })
        }
        val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = UiKit.dp(host.context, 8); box.layoutParams = lp
        return box
    }

    private fun executeFeature(feature: ZeroTermuxManager.Feature) {
        when (feature.id) {
            "pkg", "proot", "x11", "qemu", "storage", "tmux" -> host.openTerminalRun(manager.prefix(), feature.command)
            "mirror" -> chooseMirror()
            "diagnostics" -> showText(feature.title, manager.diagnostics())
            "plugins" -> showText(feature.title, manager.pluginDiagnostics())
            "backup" -> backup()
            "restore" -> restore()
        }
    }


    private fun chooseMirror() {
        val labels = manager.mirrors.map { it.title }.toTypedArray()
        AlertDialog.Builder(host.context).setTitle("选择 APT 软件源").setItems(labels) { _, which ->
            val mirror = manager.mirrors[which]
            Thread {
                val result = manager.switchMirror(mirror)
                host.runUi {
                    showText("软件源", if (result.isSuccess) result.getOrThrow() + "\n\n建议在终端执行 pkg update" else "切换失败：${result.exceptionOrNull()?.message}")
                }
            }.start()
        }.setNegativeButton("取消", null).show()
    }

    private fun backup() {
        Thread {
            val result = manager.backupInternal()
            host.runUi {
                if (result.isSuccess) {
                    val file = result.getOrThrow()
                    showText("备份完成", "${file.absolutePath}\n\n大小：${file.length() / 1024 / 1024} MB\n\n可在应用内部备份目录找到，也可以通过文件管理器导出。")
                } else showText("备份失败", result.exceptionOrNull()?.message ?: "unknown")
            }
        }.start()
    }

    private fun restore() {
        host.pickFile { uri ->
            if (uri == null) return@pickFile
            Thread {
                val result = manager.restoreFromUri(uri)
                host.runUi {
                    showText("恢复结果", if (result.isSuccess) "恢复完成。建议重新启动终端。" else "恢复失败：${result.exceptionOrNull()?.message}")
                }
            }.start()
        }
    }

    private fun showText(title: String, text: String) {
        AlertDialog.Builder(host.context).setTitle(title).setMessage(text)
            .setPositiveButton("打开终端") { _, _ -> host.openTerminal(manager.prefix()) }
            .setNegativeButton("关闭", null).show()
    }
}
