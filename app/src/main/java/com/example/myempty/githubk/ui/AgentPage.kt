package com.example.myempty.githubk.ui

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import com.example.myempty.githubk.core.Project
import java.io.File

/**
 * AgentPage v5.1-Redesign：AI 任务工作台（IDE 式、任务优先、Material3 简约）。
 *
 * 布局自上而下：
 *  1. 固定标题栏：状态灯 + 标题 + 运行任务名 + Problems/项目记忆/快速设置
 *  2. 单行全局信息条（点击打开「环境概览抽屉」）
 *  3. 可收起快速动作 Chip 栏
 *  4. AI 任务输入主编辑区（占用最大垂直空间）
 *  5. 可调节/可最小化日志面板
 *
 * 大量次要信息（MCP/Skill/开发环境/后台任务等）收敛到抽屉与菜单，不再常驻大卡片。
 */
class AgentPage(private val host: PageHost) : RefreshablePage {

    private val state = host.state

    // ---- 就地刷新所需的视图引用 ----
    private val infoValueLabels = mutableListOf<TextView>()
    private var quickWrap: LinearLayout? = null
    private var quickToggleArrow: TextView? = null
    private var quickToggleLabel: TextView? = null

    // ---- 视图引用 ----
    private lateinit var statusDot: TextView
    private lateinit var statusTaskLabel: TextView
    private lateinit var problemsBadge: TextView
    private lateinit var memoryDot: TextView
    private lateinit var taskInput: EditText
    private lateinit var runBtn: android.widget.Button
    private lateinit var logPanel: LinearLayout
    private lateinit var logScroll: ScrollView
    private lateinit var logContent: LinearLayout
    private lateinit var logCountLabel: TextView
    private var logExpanded = true
    private var logHeightDp = 220

    private var running = false
    private var selectedProject: String? = null
    private var quickExpanded = false
    private var logAutoScroll = true
    private var logLines = 0
    private var currentTaskName: String? = null
    private var cachedProblems = listOf<Problem>()

    private data class Problem(val file: String, val line: Int, val type: String, val text: String)

    // ================================================================
    //  主入口
    // ================================================================
    fun buildView(): View {
        val root = LinearLayout(host.context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiKit.themeColor(host.context, com.example.myempty.githubk.R.color.surface))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(buildHeader())
        root.addView(buildInfoBar())
        root.addView(buildQuickChips())
        root.addView(UiKit.divider(host.context))
        root.addView(buildInputArea(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(buildLogPanel())
        root.tag = this
        refreshHeader()
        return root
    }

    /** 就地刷新：更新状态灯/任务名/信息条/快速动作/Problems 徽标/记忆点，不重建页面（保留输入与运行态）。 */
    override fun refresh() {
        refreshHeader()
        updateInfoBar()
        renderQuickChips()
    }

    // ================================================================
    //  1. 固定标题栏
    // ================================================================
    private fun buildHeader(): View {
        val bar = UiKit.hstack(host.context).apply {
            setPadding(UiKit.dp(host.context, 14), UiKit.dp(host.context, 6), UiKit.dp(host.context, 10), UiKit.dp(host.context, 6))
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = UiKit.label(host.context, "●", color = com.example.myempty.githubk.R.color.success, size = 15f).apply {
            layoutParams = LinearLayout.LayoutParams(UiKit.dp(host.context, 20), UiKit.dp(host.context, 20))
            gravity = Gravity.CENTER
        }
        bar.addView(statusDot)
        bar.addView(UiKit.spacer(host.context, 8))
        bar.addView(UiKit.label(host.context, "AI 任务工作台", color = com.example.myempty.githubk.R.color.on_surface, size = 17f).apply {
            typeface = Typeface.DEFAULT_BOLD
        })
        bar.addView(UiKit.spacer(host.context, 8))
        statusTaskLabel = UiKit.label(host.context, "空闲", color = com.example.myempty.githubk.R.color.muted, size = 11.5f).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(statusTaskLabel)
        bar.addView(UiKit.spacer(host.context, 4))

        // Problems
        bar.addView(headerIconButton("🔔") { openProblems() })
        problemsBadge = UiKit.badge(host.context, "", com.example.myempty.githubk.R.color.error).apply {
            layoutParams = LinearLayout.LayoutParams(UiKit.dp(host.context, 18), UiKit.dp(host.context, 18))
            gravity = Gravity.CENTER
            textSize = 9f
            visibility = View.GONE
        }
        bar.addView(problemsBadge)
        bar.addView(UiKit.spacer(host.context, 6))

        // 项目记忆
        bar.addView(headerIconButton("📝") { openMemory() })
        memoryDot = UiKit.label(host.context, "", color = com.example.myempty.githubk.R.color.success, size = 8f).apply {
            layoutParams = LinearLayout.LayoutParams(UiKit.dp(host.context, 12), UiKit.dp(host.context, 12))
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        bar.addView(memoryDot)
        bar.addView(UiKit.spacer(host.context, 6))

        // 快速设置
        bar.addView(headerIconButton("⚙️") { showQuickSettings() })
        return bar
    }

    /** 头部小图标按钮。 */
    private fun headerIconButton(emoji: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(host.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(UiKit.dp(host.context, 8), UiKit.dp(host.context, 2), UiKit.dp(host.context, 8), UiKit.dp(host.context, 2))
            background = UiKit.rounded(host.context, UiKit.themeColor(host.context, com.example.myempty.githubk.R.color.surface_elevated), 12, UiKit.themeColor(host.context, com.example.myempty.githubk.R.color.divider), 1)
            setOnClickListener { onClick() }
            addView(UiKit.label(host.context, emoji, color = com.example.myempty.githubk.R.color.on_surface, size = 16f))
        }

    // ================================================================
    //  2. 单行全局信息条
    // ================================================================
    private fun buildInfoBar(): View {
        val row = UiKit.hstack(host.context).apply {
            setPadding(UiKit.dp(host.context, 12), UiKit.dp(host.context, 6), UiKit.dp(host.context, 12), UiKit.dp(host.context, 6))
            gravity = Gravity.CENTER_VERTICAL
            background = UiKit.rounded(host.context, UiKit.themeColor(host.context, com.example.myempty.githubk.R.color.surface_elevated), 14, UiKit.themeColor(host.context, com.example.myempty.githubk.R.color.divider), 1)
            setOnClickListener { openEnvOverview() }
        }
        infoValueLabels.clear()
        addInfoSeg(row, "🤖", 0)
        addInfoSeg(row, "📂", 1)
        addInfoSeg(row, "🔌", 2)
        addInfoSeg(row, "🧩", 3)
        row.addView(UiKit.label(host.context, " ▾", color = com.example.myempty.githubk.R.color.muted, size = 12f))
        updateInfoBar()
        return row
    }

    private fun addInfoSeg(row: LinearLayout, icon: String, idx: Int) {
        row.addView(UiKit.label(host.context, icon, color = com.example.myempty.githubk.R.color.muted, size = 12f))
        val v = UiKit.label(host.context, "", color = com.example.myempty.githubk.R.color.on_surface, size = 12f).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        infoValueLabels.add(v)
        row.addView(v)
        row.addView(UiKit.label(host.context, " │ ", color = com.example.myempty.githubk.R.color.divider, size = 12f))
    }

    private fun updateInfoBar() {
        val cfg = state.ai.config()
        val proj = selectedProject ?: state.workspace.projects().firstOrNull()?.name ?: "未选择项目"
        val mcpCount = runCatching { state.mcp.allEnabledServers().size }.getOrDefault(0)
        val skillCount = runCatching { state.skill.list().size }.getOrDefault(0)
        if (infoValueLabels.size >= 4) {
            infoValueLabels[0].text = " " + cfg.model.ifBlank { "未配模型" }.take(16)
            infoValueLabels[1].text = " " + proj.take(22)
            infoValueLabels[2].text = " " + if (mcpCount > 0) "MCP:$mcpCount" else "MCP:无连接"
            infoValueLabels[3].text = " " + "Skill:$skillCount"
        }
    }

    // ================================================================
    //  3. 可收起快速动作 Chip 栏
    // ================================================================
    private fun buildQuickChips(): View {
        val wrap = LinearLayout(host.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(host.context, 12), UiKit.dp(host.context, 4), UiKit.dp(host.context, 12), UiKit.dp(host.context, 4))
        }
        quickWrap = wrap
        val toggleRow = UiKit.hstack(host.context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                quickExpanded = !quickExpanded
                renderQuickChips()
            }
            val arrow = UiKit.label(host.context, if (quickExpanded) "▾" else "▸", color = com.example.myempty.githubk.R.color.primary, size = 14f)
            quickToggleArrow = arrow
            addView(arrow)
            addView(UiKit.spacer(host.context, 6))
            addView(UiKit.label(host.context, "⚡ 快速动作", color = com.example.myempty.githubk.R.color.primary, size = 13f).apply { typeface = Typeface.DEFAULT_BOLD })
            addView(UiKit.spacer(host.context, 6))
            val lbl = UiKit.label(host.context, if (quickExpanded) "收起" else "展开", color = com.example.myempty.githubk.R.color.muted, size = 11f)
            quickToggleLabel = lbl
            addView(lbl)
        }
        wrap.addView(toggleRow)
        renderQuickChips()
        return wrap
    }

    private fun renderQuickChips() {
        val wrap = quickWrap ?: return
        quickToggleArrow?.text = if (quickExpanded) "▾" else "▸"
        quickToggleLabel?.text = if (quickExpanded) "收起" else "展开"
        while (wrap.childCount > 1) wrap.removeViewAt(1)
        if (!quickExpanded) return
        val hScroll = HorizontalScrollView(host.context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, UiKit.dp(host.context, 6), 0, 0)
        }
        val inner = UiKit.hstack(host.context)
        val actions = listOf<Pair<String, () -> Unit>>(
            "新建项目" to { askNewProjectTask() },
            "构建 APK" to {
                if (!hasProject()) { host.toast("请先新建或选择项目"); return@to }
                startTask("构建当前项目${projectRef()}，如果失败请读取报错并修复后重试，成功后导出 APK 到 Downloads 目录")
            },
            "打包源码" to {
                if (!hasProject()) { host.toast("请先新建或选择项目"); return@to }
                startTask("将当前项目${projectRef()}的源码打包为 ZIP 并导出到 Downloads 目录")
            },
            "分析修复" to {
                if (!hasProject()) { host.toast("请先新建或选择项目"); return@to }
                startTask("检查当前项目${projectRef()}是否存在编译或明显代码问题，如果有请修复并重新构建验证")
            },
            "扫描待办" to { runProblemsScan() },
            "清理缓存" to { startTask("清理当前项目${projectRef()}的构建缓存（clean 构建），然后重新构建验证") }
        )
        actions.forEach { (name, act) ->
            inner.addView(UiKit.chip(host.context, name, false) { act() })
            inner.addView(UiKit.spacer(host.context, 6))
        }
        hScroll.addView(inner)
        wrap.addView(hScroll)
    }

    private fun hasProject(): Boolean = selectedProject != null || state.workspace.projects().isNotEmpty()
    private fun projectRef(): String {
        val name = selectedProject ?: state.workspace.projects().firstOrNull()?.name ?: ""
        return if (name.isNotEmpty()) "「$name」" else ""
    }

    // ================================================================
    //  4. AI 任务输入主编辑区
    // ================================================================
    @SuppressLint("ClickableViewAccessibility")
    private fun buildInputArea(): View {
        val box = UiKit.vstack(host.context).apply {
            setPadding(UiKit.dp(host.context, 12), UiKit.dp(host.context, 8), UiKit.dp(host.context, 12), UiKit.dp(host.context, 6))
        }
        val toolbar = UiKit.hstack(host.context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(toolbarAction("📋", "粘贴") { pasteClipboard() })
            addView(UiKit.spacer(host.context, 8))
            addView(toolbarAction("📂", "插入路径") { insertProjectPath() })
            addView(UiKit.spacer(host.context, 8))
            addView(toolbarAction("↗", "全屏") { openFullscreenEditor() })
            addView(UiKit.spacer(host.context, 8))
            addView(toolbarAction("⭐", "收藏") { showFavorites() })
        }
        box.addView(toolbar)

        taskInput = UiKit.textArea(host.context, "在此输入任务或粘贴日志、需求。").apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            gravity = Gravity.TOP or Gravity.START
        }
        box.addView(taskInput)

        val btnRow = UiKit.hstack(host.context).apply {
            setPadding(0, UiKit.dp(host.context, 6), 0, 0)
        }
        runBtn = UiKit.tonalButton(host.context, "▶ 开始执行") { startTask() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnRow.addView(runBtn)
        btnRow.addView(UiKit.spacer(host.context, 8))
        btnRow.addView(UiKit.ghostButton(host.context, "🗑️ 清空输入") { taskInput.setText("") })
        box.addView(btnRow)
        return box
    }

    private fun toolbarAction(emoji: String, label: String, onClick: () -> Unit): View =
        LinearLayout(host.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(UiKit.dp(host.context, 10), UiKit.dp(host.context, 2), UiKit.dp(host.context, 10), UiKit.dp(host.context, 2))
            background = UiKit.rounded(host.context, UiKit.themeColor(host.context, com.example.myempty.githubk.R.color.surface_elevated), 10, UiKit.themeColor(host.context, com.example.myempty.githubk.R.color.divider), 1)
            setOnClickListener { onClick() }
            addView(UiKit.label(host.context, emoji, color = com.example.myempty.githubk.R.color.on_surface, size = 15f))
        }

    private fun pasteClipboard() {
        try {
            val cm = host.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(host.context).toString()
                taskInput.setText(text)
                host.toast("已粘贴剪贴板内容")
            } else host.toast("剪贴板为空")
        } catch (e: Throwable) { host.toast("粘贴失败：${e.message}") }
    }

    private fun insertProjectPath() {
        val proj = currentProject() ?: run { host.toast("请先选择项目"); return }
        val inset = if (taskInput.text.toString().trim().isEmpty()) "" else "\n"
        taskInput.append(inset + proj.path.absolutePath)
        host.toast("已插入项目路径")
    }

    private fun currentProject(): Project? = selectedProject?.let { state.workspace.project(it) }
        ?: state.workspace.projects().firstOrNull()

    private fun openFullscreenEditor() {
        val temp = UiKit.textArea(host.context, taskInput.text.toString()).apply {
            minLines = 16
            gravity = Gravity.TOP or Gravity.START
        }
        val box = UiKit.vstack(host.context).apply {
            setPadding(UiKit.dp(host.context, 20), UiKit.dp(host.context, 12), UiKit.dp(host.context, 20), UiKit.dp(host.context, 12))
        }
        box.addView(UiKit.label(host.context, "全屏编辑任务指令", color = com.example.myempty.githubk.R.color.on_surface, size = 15f).apply { typeface = Typeface.DEFAULT_BOLD })
        box.addView(UiKit.spacer(host.context, 8))
        box.addView(temp, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(host.context, 340)))
        UiKit.dialog(host.context, "全屏编辑", box, onOk = {
            taskInput.setText(temp.text.toString())
            host.toast("已回填到输入框")
        }, onCancel = {})
    }

    private fun showFavorites() {
        val favs = loadFavorites()
        if (favs.isEmpty()) {
            UiKit.confirm(host.context, "收藏任务", "将当前输入收藏为常用指令？", onYes = {
                saveFavorite(taskInput.text.toString())
            })
            return
        }
        val items = favs.mapIndexed { i, f ->
            UiKit.SheetItem(
                icon = "★",
                title = f.take(50),
                subtitle = "${i + 1} · 点击填入输入框",
                colorRes = com.example.myempty.githubk.R.color.warning
            ) { taskInput.setText(f) }
        }
        UiKit.sheet(host.context, "收藏的任务指令", "点击填入输入框", items)
    }

    private fun loadFavorites(): List<String> {
        val f = File(host.context.filesDir, "ai_favorites.txt")
        return runCatching { if (f.isFile) f.readLines().filter { it.isNotBlank() } else emptyList() }.getOrDefault(emptyList())
    }

    private fun saveFavorite(text: String) {
        if (text.isBlank()) return
        val f = File(host.context.filesDir, "ai_favorites.txt")
        val cur = loadFavorites().toMutableList()
        if (cur.contains(text)) { host.toast("已存在该收藏"); return }
        cur.add(0, text)
        if (cur.size > 30) cur.removeAt(cur.size - 1)
        runCatching { f.writeText(cur.joinToString("\n")) }
        host.toast("已收藏当前任务")
    }

    // ================================================================
    //  5. 可调节/可最小化日志面板
    // ================================================================
    @SuppressLint("ClickableViewAccessibility")
    private fun buildLogPanel(): View {
        logPanel = LinearLayout(host.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(host.context, 12), UiKit.dp(host.context, 4), UiKit.dp(host.context, 12), UiKit.dp(host.context, 8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                UiKit.dp(host.context, logHeightDp)
            )
        }
        // 拖拽分割条：向上拖动增大日志高度，向下减小
        val divider = View(host.context).apply {
            setBackgroundColor(UiKit.themeColor(host.context, com.example.myempty.githubk.R.color.divider))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(host.context, 5))
        }
        divider.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_MOVE) {
                val d = (event.y / host.context.resources.displayMetrics.density).toInt()
                logHeightDp = (logHeightDp - d).coerceIn(96, 460)
                logPanel.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    UiKit.dp(host.context, logHeightDp)
                )
                logPanel.requestLayout()
            }
            true
        }
        logPanel.addView(divider)

        val header = UiKit.hstack(host.context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(UiKit.label(host.context, "工具调用日志", color = com.example.myempty.githubk.R.color.on_surface, size = 13.5f).apply { typeface = Typeface.DEFAULT_BOLD })
            logCountLabel = UiKit.label(host.context, "", color = com.example.myempty.githubk.R.color.muted, size = 11f)
            addView(UiKit.spacer(host.context, 8))
            addView(logCountLabel)
            addView(UiKit.spacer(host.context, 0).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
            addView(panelIcon("🔍") { showLogSearch() })
            addView(panelIcon("⬇") { exportLog() })
            addView(panelIcon("⋮") { showLogMenu() })
        }
        logPanel.addView(header)

        logContent = UiKit.vstack(host.context)
        logScroll = ScrollView(host.context).apply {
            isFillViewport = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        logScroll.addView(logContent)
        logPanel.addView(logScroll)
        return logPanel
    }

    private fun panelIcon(emoji: String, onClick: () -> Unit): View =
        TextView(host.context).apply {
            text = emoji
            textSize = 15f
            setTextColor(UiKit.themeColor(host.context, com.example.myempty.githubk.R.color.muted))
            setPadding(UiKit.dp(host.context, 6), UiKit.dp(host.context, 2), UiKit.dp(host.context, 6), UiKit.dp(host.context, 2))
            setOnClickListener { onClick() }
        }

    private fun showLogMenu() {
        val pm = PopupMenu(host.context, logPanel)
        pm.menu.add(0, 1, 0, if (logExpanded) "最小化日志" else "展开日志")
        pm.menu.add(0, 2, 1, "清空日志")
        pm.menu.add(0, 3, 2, if (logAutoScroll) "自动滚动：开" else "自动滚动：关")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { logExpanded = !logExpanded; applyLogPanelHeight(); true }
                2 -> { logContent.removeAllViews(); logLines = 0; updateLogCount(); true }
                3 -> { logAutoScroll = !logAutoScroll; true }
                else -> false
            }
        }
        pm.show()
    }

    private fun showLogSearch() {
        val input = UiKit.input(host.context, "输入关键词查找日志")
        UiKit.dialog(host.context, "搜索日志", input, onOk = {
            val q = input.text.toString()
            if (q.isBlank()) return@dialog
            var found = false
            for (i in 0 until logContent.childCount) {
                val v = logContent.getChildAt(i)
                if (v is TextView && v.text.contains(q, ignoreCase = true)) {
                    host.toast("找到：${v.text}")
                    found = true
                    break
                }
            }
            if (!found) host.toast("未找到「$q」")
        }, onCancel = {})
    }

    private fun applyLogPanelHeight() {
        val h = if (logExpanded) logHeightDp else 44
        logPanel.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(host.context, h)
        )
        logPanel.requestLayout()
    }

    private fun exportLog() {
        val sb = StringBuilder()
        for (i in 0 until logContent.childCount) {
            val v = logContent.getChildAt(i)
            if (v is TextView) sb.append(v.text).append("\n")
        }
        try {
            val f = File(host.context.filesDir, "ai_log_${System.currentTimeMillis()}.txt")
            f.writeText(sb.toString())
            host.toast("已导出日志：${f.absolutePath}")
        } catch (e: Throwable) { host.toast("导出失败：${e.message}") }
    }

    // ================================================================
    //  快速设置（右上角 ⚙️）
    // ================================================================
    private fun showQuickSettings() {
        val pm = PopupMenu(host.context, buildView())
        val profiles = state.ai.profiles()
        val activeId = state.ai.activeId()
        pm.menu.add("AI 配置中心").setOnMenuItemClickListener { host.pushPage(AiSettingsPage(host).buildView()); true }
        pm.menu.add("当前模型：${state.ai.activeProfile().model.ifBlank { "未设置" }}").setEnabled(false)
        profiles.forEach { p ->
            pm.menu.add(if (p.id == activeId) "● ${p.name}" else "○ ${p.name}").setOnMenuItemClickListener {
                state.ai.setActive(p.id)
                host.toast("已切换模型：${p.name}")
                host.rebuildCurrentPage()
                true
            }
        }
        pm.menu.add("工程日志").setOnMenuItemClickListener { openMemory(); true }
        pm.menu.add("后台任务").setOnMenuItemClickListener { host.toast("后台任务队列已收敛到抽屉（可在设置查看日志）"); true }
        pm.show()
    }

    // ================================================================
    //  项目记忆
    // ================================================================
    private fun memoFile(project: Project): File {
        val f = File(File(project.path, ".studio"), "memory.json")
        runCatching {
            val gi = File(project.path, ".gitignore")
            if (gi.isFile && !gi.readText().contains(".studio")) gi.appendText("\n.studio/\n")
        }
        return f
    }

    private fun loadMemoryText(project: Project): String =
        runCatching { val f = memoFile(project); if (f.isFile) f.readText() else "" }.getOrDefault("")

    private fun saveMemoryText(project: Project, text: String) {
        runCatching {
            val f = memoFile(project)
            f.parentFile?.mkdirs()
            f.writeText(text)
        }
        host.runUi { host.rebuildCurrentPage() }
    }

    private fun openMemory() {
        val proj = currentProject() ?: run { host.toast("请先选择项目"); return }
        val cur = loadMemoryText(proj)
        if (cur.isBlank()) {
            val area = UiKit.textArea(host.context, "为「${proj.name}」记录项目记忆，例如：使用 Compose、第三方 API 基址、构建注意事项…").apply { minLines = 8 }
            val box = UiKit.vstack(host.context).apply {
                setPadding(UiKit.dp(host.context, 20), UiKit.dp(host.context, 12), UiKit.dp(host.context, 20), UiKit.dp(host.context, 12))
                addView(UiKit.label(host.context, "项目记忆会随任务自动注入给 AI 作为上下文。", color = com.example.myempty.githubk.R.color.muted, size = 11f))
                addView(UiKit.spacer(host.context, 8))
                addView(area)
            }
            UiKit.dialog(host.context, "项目记忆 · ${proj.name}", box, onOk = {
                val t = area.text.toString().trim()
                if (t.isNotBlank()) saveMemoryText(proj, t)
            }, onCancel = {})
            return
        }
        UiKit.confirm(host.context, "项目记忆 · ${proj.name}", cur.take(500), onYes = {
            val area = UiKit.textArea(host.context, cur).apply { minLines = 8 }
            val box = UiKit.vstack(host.context).apply {
                setPadding(UiKit.dp(host.context, 20), UiKit.dp(host.context, 12), UiKit.dp(host.context, 20), UiKit.dp(host.context, 12))
                addView(area)
            }
            UiKit.dialog(host.context, "编辑项目记忆", box, onOk = {
                saveMemoryText(proj, area.text.toString().trim())
            }, onCancel = {})
        })
    }

    // ================================================================
    //  Problems（问题抽屉）
    // ================================================================
    private fun openProblems() {
        if (cachedProblems.isEmpty()) {
            host.toast("暂无问题缓存，正在扫描…")
            runProblemsScan()
            return
        }
        val box = UiKit.vstack(host.context).apply {
            setPadding(UiKit.dp(host.context, 16), UiKit.dp(host.context, 8), UiKit.dp(host.context, 16), UiKit.dp(host.context, 12))
        }
        box.addView(UiKit.label(host.context, "共 ${cachedProblems.size} 处待办/问题（TODO/FIXME/HACK/XXX/BUG）", color = com.example.myempty.githubk.R.color.muted, size = 11f))
        box.addView(UiKit.spacer(host.context, 8))
        cachedProblems.take(100).forEach { p ->
            box.addView(UiKit.vstack(host.context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                background = UiKit.rounded(host.context, UiKit.themeColor(host.context, com.example.myempty.githubk.R.color.surface_elevated), 10, UiKit.themeColor(host.context, com.example.myempty.githubk.R.color.divider), 1)
                setPadding(UiKit.dp(host.context, 10), UiKit.dp(host.context, 6), UiKit.dp(host.context, 10), UiKit.dp(host.context, 6))
                setOnClickListener { jumpToProblem(p) }
                addView(UiKit.hstack(host.context).apply {
                    addView(UiKit.label(host.context, "[${p.type}]", color = com.example.myempty.githubk.R.color.warning, size = 11f).apply { typeface = Typeface.DEFAULT_BOLD })
                    addView(UiKit.spacer(host.context, 6))
                    addView(UiKit.label(host.context, p.file.substringAfterLast('/'), color = com.example.myempty.githubk.R.color.on_surface, size = 11.5f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(UiKit.spacer(host.context, 6))
                    addView(UiKit.label(host.context, ":${p.line}", color = com.example.myempty.githubk.R.color.muted, size = 10.5f))
                })
                addView(UiKit.label(host.context, p.text, color = com.example.myempty.githubk.R.color.muted, size = 11f).apply { setPadding(0, UiKit.dp(host.context, 3), 0, 0); maxLines = 2 })
                addView(UiKit.label(host.context, "点击跳转到源码行", color = com.example.myempty.githubk.R.color.accent, size = 10f).apply { setPadding(0, UiKit.dp(host.context, 2), 0, 0) })
            })
            box.addView(UiKit.spacer(host.context, 4))
        }
        val sc = ScrollView(host.context).apply { isFillViewport = true }
        sc.addView(box)
        UiKit.dialog(host.context, "Problems · 待办与问题", sc, onOk = {}, onCancel = {})
    }

    private fun jumpToProblem(p: Problem) {
        val proj = currentProject() ?: run { host.toast("请先选择项目"); return }
        host.openEditor(proj)
        host.toast("${p.file.substringAfterLast('/')}:${p.line} 【${p.type}】${p.text.take(40)}")
    }

    private fun runProblemsScan() {
        val proj = currentProject() ?: run { host.toast("请先选择项目"); return }
        host.toast("正在扫描「${proj.name}」…")
        Thread {
            val result = mutableListOf<Problem>()
            val re = Regex("\\b(TODO|FIXME|HACK|XXX|BUG)\\b[:：]?\\s*(.*)")
            val skipDirs = setOf("build", ".gradle", ".git", "node_modules", ".dart_tool", "gradle")
            val exts = setOf("kt", "java", "xml", "py", "dart", "js", "ts", "gradle", "kts", "md")
            fun walk(dir: File) {
                val children = dir.listFiles() ?: return
                for (child in children) {
                    if (child.isDirectory) {
                        if (child.name in skipDirs) continue
                        walk(child)
                    } else {
                        val ext = child.extension.lowercase()
                        if (ext !in exts) continue
                        if (child.length() > 1024 * 1024) continue
                        val lines = runCatching { child.readText(Charsets.UTF_8).lines() }.getOrDefault(emptyList())
                        lines.forEachIndexed { i, line ->
                            val m = re.find(line)
                            if (m != null) result.add(Problem(child.absolutePath, i + 1, m.groupValues[1].uppercase(), m.groupValues[2].trim().ifBlank { "待办标记" }))
                        }
                    }
                }
            }
            walk(proj.path)
            runCatching { host.runUi {
                cachedProblems = result
                host.rebuildCurrentPage()
                if (result.isEmpty()) host.toast("未发现待办/问题")
                else host.toast("发现 ${result.size} 处待办/问题")
            } }.getOrNull()
        }.start()
    }

    // ================================================================
    //  环境概览抽屉
    // ================================================================
    private fun openEnvOverview() {
        val content = UiKit.vstack(host.context).apply {
            setPadding(UiKit.dp(host.context, 16), UiKit.dp(host.context, 8), UiKit.dp(host.context, 16), UiKit.dp(host.context, 16))
        }
        val tabs = listOf("AI", "项目", "MCP", "Skill", "环境")
        var current = 0
        val tabRow = UiKit.hstack(host.context).apply { gravity = Gravity.CENTER_VERTICAL }
        fun render() {
            content.removeAllViews()
            tabRow.removeAllViews()
            tabs.forEachIndexed { i, t ->
                tabRow.addView(UiKit.chip(host.context, t, i == current) { current = i; render() })
                tabRow.addView(UiKit.spacer(host.context, 4))
            }
            content.addView(tabRow)
            content.addView(UiKit.spacer(host.context, 10))
            when (current) {
                0 -> renderAiTab(content)
                1 -> renderProjectTab(content)
                2 -> renderMcpTab(content)
                3 -> renderSkillTab(content)
                4 -> renderEnvTab(content)
            }
        }
        val sc = ScrollView(host.context).apply { isFillViewport = true }
        sc.addView(content)
        UiKit.dialog(host.context, "环境概览", sc, onOk = {}, onCancel = {})
        render()
    }

    private fun overviewRow(title: String, value: String): View =
        UiKit.hstack(host.context).apply {
            setPadding(0, UiKit.dp(host.context, 4), 0, UiKit.dp(host.context, 4))
            addView(UiKit.label(host.context, title, color = com.example.myempty.githubk.R.color.muted, size = 12.5f).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            addView(UiKit.label(host.context, value, color = com.example.myempty.githubk.R.color.on_surface, size = 12.5f).apply { maxLines = 2 })
        }

    private fun actionRow(text: String, onClick: () -> Unit): View =
        UiKit.ghostButton(host.context, text) { onClick() }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

    private fun renderAiTab(content: LinearLayout) {
        val cfg = state.ai.config()
        content.addView(overviewRow("服务商", state.ai.providerLabel(cfg.provider)))
        content.addView(overviewRow("模型", cfg.model.ifBlank { "未设模型" }))
        content.addView(overviewRow("状态", if (cfg.enabled) "已启用" else "已停用"))
        content.addView(overviewRow("API Key", if (cfg.key.isNotBlank()) "已配置" else "未配置"))
        content.addView(overviewRow("响应格式", if (cfg.responseApi) "OpenAI 兼容" else "原始流式"))
        content.addView(overviewRow("思维链", if (cfg.reasoning) "开启" else "关闭"))
        content.addView(actionRow("管理配置") { host.pushPage(AiSettingsPage(host).buildView()) })
        content.addView(actionRow("切换模型") { showAiPicker() })
    }

    private fun renderProjectTab(content: LinearLayout) {
        val projects = state.workspace.projects()
        content.addView(overviewRow("当前项目", selectedProject ?: projects.firstOrNull()?.name ?: "未选择"))
        content.addView(overviewRow("项目数", "${projects.size} 个"))
        if (projects.isEmpty()) {
            content.addView(overviewRow("提示", "请在工作区新建或导入项目"))
        } else {
            projects.take(10).forEach { p ->
                content.addView(actionRow("${p.name} [${p.type}]") { selectedProject = p.name; host.runUi { host.rebuildCurrentPage() } })
            }
        }
        content.addView(actionRow("打开工作区") { host.switchTab(1) })
    }

    private fun renderMcpTab(content: LinearLayout) {
        val servers = runCatching { state.mcp.allEnabledServers() }.getOrDefault(emptyList())
        content.addView(overviewRow("已启用服务", "${servers.size} 个"))
        if (servers.isEmpty()) {
            content.addView(overviewRow("提示", "MCP 无启用服务，可在设置中配置"))
        } else {
            servers.forEach { s ->
                content.addView(actionRow("🔌 ${s.name} · 测试") {
                    Thread {
                        try {
                            val tools = state.mcp.listTools(s)
                            host.runUi { host.toast("${s.name}: ${tools.size} 个工具") }
                        } catch (e: Throwable) {
                            host.runUi { host.toast("${s.name} 测试失败: ${e.message}") }
                        }
                    }.start()
                })
            }
        }
        content.addView(actionRow("管理 MCP 配置") { host.switchTab(3) })
    }

    private fun renderSkillTab(content: LinearLayout) {
        val skills = runCatching { state.skill.list() }.getOrDefault(emptyList())
        content.addView(overviewRow("工作流数", "${skills.size} 个"))
        if (skills.isEmpty()) {
            content.addView(overviewRow("提示", "暂无工作流，可在设置中添加"))
        } else {
            skills.take(12).forEach { s ->
                content.addView(actionRow("🧩 ${s.name} · 运行") { runSkill(s.id) })
            }
        }
        content.addView(actionRow("管理 Skill") { host.switchTab(3) })
    }

    private fun renderEnvTab(content: LinearLayout) {
        content.addView(UiKit.label(host.context, "开发环境检测中…", color = com.example.myempty.githubk.R.color.muted, size = 12f))
        refreshEnvStatusInto(content)
    }

    private fun refreshEnvStatusInto(content: LinearLayout) {
        state.toolchain.statusAsync(onResult = { s ->
            host.runUi {
                content.removeAllViews()
                content.addView(envTile("JDK", s.java, s.javaVersion))
                content.addView(envTile("Gradle", s.gradle, s.gradleVersion))
                content.addView(envTile("Android SDK", s.sdk, s.sdkPath.ifBlank { "" }))
                content.addView(envTile("Git", s.git, ""))
                content.addView(envTile("Dart", s.dart, ""))
                content.addView(envTile("Flutter", s.flutter, ""))
                content.addView(overviewRow("总体", if (s.ready) "就绪" else "不完整，可点击下方修复"))
                content.addView(actionRow("分析并修复环境") { host.switchTab(3) })
            }
        })
    }

    private fun envTile(name: String, ok: Boolean, ver: String): View =
        UiKit.hstack(host.context).apply {
            setPadding(0, UiKit.dp(host.context, 4), 0, UiKit.dp(host.context, 4))
            addView(UiKit.label(host.context, if (ok) "✅" else "❌", color = if (ok) com.example.myempty.githubk.R.color.success else com.example.myempty.githubk.R.color.error, size = 12f))
            addView(UiKit.spacer(host.context, 6))
            addView(UiKit.label(host.context, name, color = com.example.myempty.githubk.R.color.on_surface, size = 12.5f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(UiKit.label(host.context, ver, color = com.example.myempty.githubk.R.color.muted, size = 11.5f).apply { maxLines = 1 })
        }

    // ================================================================
    //  状态刷新
    // ================================================================
    private fun refreshHeader() {
        val proj = currentProject()
        val dotColor = when {
            running -> com.example.myempty.githubk.R.color.primary
            cachedProblems.isNotEmpty() -> com.example.myempty.githubk.R.color.warning
            else -> com.example.myempty.githubk.R.color.success
        }
        statusDot.setTextColor(UiKit.themeColor(host.context, dotColor))
        statusTaskLabel.text = if (running) "运行中：${currentTaskName ?: "任务"}" else if (cachedProblems.isNotEmpty()) "${cachedProblems.size} 处待办" else "空闲"
        statusTaskLabel.setTextColor(UiKit.themeColor(host.context, if (running) com.example.myempty.githubk.R.color.primary else com.example.myempty.githubk.R.color.muted))
        // Problems 徽标
        if (cachedProblems.isNotEmpty()) {
            problemsBadge.visibility = View.VISIBLE
            problemsBadge.text = if (cachedProblems.size > 99) "99+" else "${cachedProblems.size}"
        } else problemsBadge.visibility = View.GONE
        // 记忆点
        memoryDot.visibility = if (proj != null && loadMemoryText(proj).isNotBlank()) View.VISIBLE else View.GONE
        updateLogCount()
    }

    private fun updateLogCount() {
        if (::logCountLabel.isInitialized) {
            logCountLabel.text = "$logLines 行${if (running) " · 运行中" else ""}"
        }
    }

    // ================================================================
    //  日志输出
    // ================================================================
    private fun appendLog(text: String, color: Int = com.example.myempty.githubk.R.color.on_surface) {
        host.runUi {
            logContent.addView(UiKit.mono(host.context, text).apply {
                setTextColor(UiKit.themeColor(host.context, color))
                textSize = 11f
            })
            logContent.addView(UiKit.spacer(host.context, 4))
            logLines++
            updateLogCount()
            if (logAutoScroll) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ================================================================
    //  任务执行
    // ================================================================
    private fun startTask(presetTask: String? = null) {
        if (running) { host.toast("Agent 正在运行中"); return }
        if (presetTask != null) taskInput.setText(presetTask)
        var task = (presetTask ?: taskInput.text.toString()).trim()
        if (task.isBlank()) { host.toast("请输入任务"); return }
        val cfg0 = state.ai.config()
        if (!cfg0.enabled) { host.toast("AI 已停用，请先到 AI 配置中心开启"); return }
        if (cfg0.key.isBlank() && !state.ai.keyOptional(cfg0.provider)) { host.toast("请先配置 AI API Key"); return }
        val project: Project? = selectedProject?.let { state.workspace.project(it) }
            ?: state.workspace.projects().firstOrNull()

        // 项目记忆注入（若存在）
        project?.let {
            val mem = loadMemoryText(it)
            if (mem.isNotBlank()) task = "【项目记忆】$mem\n\n$task"
        }

        running = true
        currentTaskName = task.take(30)
        runBtn?.isEnabled = false
        appendLog("开始执行任务：$task", com.example.myempty.githubk.R.color.accent)
        appendLog("   可用工具：文件读写/构建/Git/MCP(${state.mcp.allEnabledServers().size} 服务)/Skill(${state.skill.list().size} 个)", com.example.myempty.githubk.R.color.muted)
        refreshHeader()

        Thread {
            val result = state.agent.run(task, project) { line ->
                appendLog(line, com.example.myempty.githubk.R.color.on_surface)
            }
            // 记忆自动保存：若任务/结果含有「记住xxx」则写入项目记忆
            rememberHeuristic(task, result.finalMessage)
            host.runUi {
                appendLog("═══ 完成（${result.iterations} 轮，写入 ${result.filesChanged} 个文件）═══", com.example.myempty.githubk.R.color.success)
                appendLog(result.finalMessage.take(2000), com.example.myempty.githubk.R.color.on_surface)
                running = false
                currentTaskName = null
                runBtn?.isEnabled = true
                refreshHeader()
            }
        }.start()
    }

    private fun rememberHeuristic(task: String, final: String) {
        val proj = currentProject() ?: return
        val re = Regex("记住[:：]?\\s*([^\\n]{2,})")
        val both = task + "\n" + final
        val m = re.find(both) ?: return
        try {
            val cur = loadMemoryText(proj)
            val mem = if (cur.isBlank()) m.groupValues[1].trim() else cur + "\n" + m.groupValues[1].trim()
            saveMemoryText(proj, mem)
        } catch (e: Throwable) { /* ignore */ }
    }

    // ================================================================
    //  选择项目 / 新建任务 / Skill / 模型
    // ================================================================
    private fun chooseProject() {
        val projects = state.workspace.projects()
        if (projects.isEmpty()) { host.toast("请先在工作区新建或导入项目"); return }
        val box = UiKit.vstack(host.context).apply {
            setPadding(UiKit.dp(host.context, 24), UiKit.dp(host.context, 12), UiKit.dp(host.context, 24), UiKit.dp(host.context, 12))
        }
        projects.forEach { p ->
            box.addView(UiKit.ghostButton(host.context, "${p.name} [${p.type}]") {
                selectedProject = p.name
                host.runUi { host.rebuildCurrentPage() }
            })
            box.addView(UiKit.spacer(host.context, 6))
        }
        UiKit.dialog(host.context, "选择目标项目", box)
    }

    private fun askNewProjectTask() {
        val box = UiKit.vstack(host.context).apply {
            setPadding(UiKit.dp(host.context, 20), UiKit.dp(host.context, 10), UiKit.dp(host.context, 20), UiKit.dp(host.context, 10))
        }
        val nameInput = UiKit.input(host.context, "项目名称，如 CounterApp")
        var type = "Android"
        box.addView(UiKit.label(host.context, "项目名称", color = com.example.myempty.githubk.R.color.on_surface))
        box.addView(UiKit.spacer(host.context, 4))
        box.addView(nameInput)
        box.addView(UiKit.spacer(host.context, 10))
        box.addView(UiKit.label(host.context, "项目类型", color = com.example.myempty.githubk.R.color.on_surface))
        box.addView(UiKit.spacer(host.context, 4))
        val typeRow = UiKit.hstack(host.context)
        listOf("Android", "Flutter", "空项目").forEach { t ->
            typeRow.addView(UiKit.chip(host.context, t, type == t) { type = t })
            typeRow.addView(UiKit.spacer(host.context, 6))
        }
        box.addView(typeRow)
        box.addView(UiKit.spacer(host.context, 8))
        box.addView(UiKit.label(host.context, "描述你想要的功能（可选），AI 会在新建后直接实现并构建：", color = com.example.myempty.githubk.R.color.muted, size = 11f))
        box.addView(UiKit.spacer(host.context, 4))
        val goalInput = UiKit.textArea(host.context, "例如：一个带加减按钮和计数显示的主页面")
        box.addView(goalInput)

        UiKit.dialog(host.context, "新建并构建项目", box, onOk = {
            val name = nameInput.text.toString().trim().ifBlank { "NewProject" }
            val goal = goalInput.text.toString().trim()
            val task = buildString {
                append("创建一个名为 $name 的 $type 项目")
                if (goal.isNotBlank()) append("，实现以下功能：$goal")
                append("。完成后构建项目，若失败请读取错误信息修复后重新构建，直到构建成功，最后导出 APK 到 Downloads 目录。")
            }
            startTask(task)
        }, onCancel = {})
    }

    private fun runSkill(sid: String) {
        val project = currentProject() ?: run { host.toast("请先选择项目"); return }
        if (running) { host.toast("Agent 正在运行中"); return }
        running = true
        runBtn?.isEnabled = false
        currentTaskName = "Skill: $sid"
        appendLog("Run Skill: $sid @ ${project.name}", com.example.myempty.githubk.R.color.accent)
        refreshHeader()
        Thread {
            val result = state.skill.execute(sid, project) { line ->
                appendLog(line, com.example.myempty.githubk.R.color.on_surface)
            }
            host.runUi {
                appendLog(result.take(2000), com.example.myempty.githubk.R.color.success)
                running = false
                currentTaskName = null
                runBtn?.isEnabled = true
                refreshHeader()
            }
        }.start()
    }

    private fun showAiPicker() {
        val profiles = state.ai.profiles()
        val activeId = state.ai.activeId()
        val items = profiles.map { p ->
            UiKit.SheetItem(
                icon = if (p.id == activeId) "●" else "○",
                title = "${p.name} · ${state.ai.providerLabel(p.provider)}",
                subtitle = p.model.ifBlank { "未设模型" } + if (p.id == activeId) "（当前）" else "",
                colorRes = if (p.id == activeId) com.example.myempty.githubk.R.color.primary else com.example.myempty.githubk.R.color.muted
            ) {
                state.ai.setActive(p.id)
                host.toast("已切换模型：${p.name}")
                host.runUi { host.rebuildCurrentPage() }
            }
        }
        UiKit.sheet(host.context, "切换 AI 模型", "点击切换，配置见下方「管理配置」", items)
    }
}
