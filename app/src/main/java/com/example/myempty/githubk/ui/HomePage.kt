package com.example.myempty.githubk.ui

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.myempty.githubk.core.Project
import com.example.myempty.githubk.git.GitRepo
import java.io.File

/**
 * HomePage v3：参考 AndroidCS IDE 的"打开或创建您的下一个项目"布局。
 * - 大标题 + 快捷操作（新建项目 / 打开项目 / 克隆仓库 / 环境与终端）
 * - 最近项目列表（本地工作区）
 * - 工作区与 GitHub 快捷入口
 */
class HomePage(private val host: PageHost) {

    private val state = host.state
    private lateinit var recentBox: LinearLayout

    fun buildView(): View {
        val root = UiKit.vstack(host.context).apply {
            setPadding(UiKit.dp(host.context, 14), UiKit.dp(host.context, 10), UiKit.dp(host.context, 14), UiKit.dp(host.context, 8))
        }
        val scroll = ScrollView(host.context).apply {
            isFillViewport = true
            addView(root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        // 大标题
        root.addView(UiKit.hero(host.context, "打开或创建", "您的下一个项目"))

        // 快捷操作（2x2 网格）
        root.addView(quickGrid())
        root.addView(UiKit.spacer(host.context, 10))

        // 登录状态卡
        root.addView(UiKit.card(host.context, UiKit.hstack(host.context).apply {
            addView(UiKit.label(host.context, "工作区 ${state.workspace.projects().size} 个项目", color = com.example.myempty.githubk.R.color.on_surface).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(UiKit.ghostButton(host.context, "工作区") { host.switchTab(1) })
            addView(UiKit.spacer(host.context, 6))
            addView(UiKit.ghostButton(host.context, "GitHub") { host.pushPage(ReposPage(host).buildView()) })
        }))
        root.addView(UiKit.spacer(host.context, 10))

        // 最近项目
        root.addView(UiKit.section(host.context, "最近项目"))
        recentBox = UiKit.vstack(host.context)
        root.addView(recentBox)
        refreshRecent()

        return scroll
    }

    /** 2x2 快捷操作网格。 */
    private data class QuickAction(val iconRes: Int, val label: String, val sub: String, val act: () -> Unit)

    private fun quickGrid(): LinearLayout {
        val grid = UiKit.vstack(host.context)
        fun quickRow(cells: List<QuickAction>) {
            val row = UiKit.hstack(host.context)
            cells.forEach { (iconRes, label, sub, act) ->
                val cell = LinearLayout(host.context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = UiKit.roundedCard(host.context)
                    setPadding(UiKit.dp(host.context, 14), UiKit.dp(host.context, 14), UiKit.dp(host.context, 14), UiKit.dp(host.context, 14))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { act() }
                }
                cell.addView(UiKit.icon(host.context, iconRes, 22, ThemeManager.colors.primary))
                cell.addView(TextView(host.context).apply {
                    text = label
                    textSize = 14.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(ThemeManager.colors.onSurface)
                    setPadding(0, UiKit.dp(host.context, 6), 0, 0)
                })
                cell.addView(TextView(host.context).apply {
                    text = sub
                    textSize = 12f
                    setTextColor(ThemeManager.colors.muted)
                    setPadding(0, UiKit.dp(host.context, 3), 0, 0)
                })
                row.addView(cell)
                row.addView(UiKit.spacer(host.context, 8))
            }
            grid.addView(row)
            grid.addView(UiKit.spacer(host.context, 8))
        }
        quickRow(listOf(
            QuickAction(com.example.myempty.githubk.R.drawable.ic_code, "新建项目", "创建 Android/Flutter 项目") { showNewProject() },
            QuickAction(com.example.myempty.githubk.R.drawable.ic_folder, "打开项目", "从工作区打开") {
                val p = state.workspace.projects().firstOrNull()
                if (p == null) host.toast("暂无项目") else host.openEditor(p)
            }
        ))
        quickRow(listOf(
            QuickAction(com.example.myempty.githubk.R.drawable.ic_download, "克隆仓库", "从 GitHub 拉取") { host.pushPage(ReposPage(host).buildView()) },
            QuickAction(com.example.myempty.githubk.R.drawable.ic_terminal, "环境与终端", "安装 SDK/工具链") { host.openTerminal() }
        ))
        return grid
    }

    private fun showNewProject() {
        host.pushPage(NewProjectPage(host).buildView())
    }

    fun refreshRecent() {
        recentBox.removeAllViews()
        val projects = state.workspace.projects()
        if (projects.isEmpty()) {
            recentBox.addView(UiKit.card(host.context, UiKit.vstack(host.context).apply {
                addView(UiKit.label(host.context, "暂无项目", color = com.example.myempty.githubk.R.color.muted))
                addView(UiKit.spacer(host.context, 6))
                addView(UiKit.label(host.context, "可在「仓库」页克隆 GitHub 仓库，或在「新建项目」创建项目。", color = com.example.myempty.githubk.R.color.muted, size = 12f))
            }))
            return
        }
        projects.take(6).forEach { p ->
            val row = UiKit.hstack(host.context)
            row.addView(UiKit.avatar(host.context, p.name, if (p.type == "Flutter") com.example.myempty.githubk.R.color.accent else com.example.myempty.githubk.R.color.primary))
            row.addView(UiKit.spacer(host.context, 10))
            row.addView(UiKit.vstack(host.context).apply {
                addView(UiKit.label(host.context, p.name, color = com.example.myempty.githubk.R.color.on_surface, size = 14.5f))
                addView(UiKit.label(host.context, p.type, color = com.example.myempty.githubk.R.color.muted, size = 12f))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(UiKit.ghostButton(host.context, "打开") { host.openEditor(p) })
            recentBox.addView(UiKit.card(host.context, row))
            recentBox.addView(UiKit.spacer(host.context, 6))
        }
    }
}

/** UiKit 兼容：白底细边框圆角卡背景。 */
fun UiKit.roundedCard(c: android.content.Context): android.graphics.drawable.GradientDrawable =
    android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = UiKit.dp(c, 6).toFloat()
        setColor(ThemeManager.colors.surfaceElevated)
        setStroke(UiKit.dp(c, 1), ThemeManager.colors.divider)
    }
