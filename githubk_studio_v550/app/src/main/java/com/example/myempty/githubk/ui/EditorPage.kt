package com.example.myempty.githubk.ui

import com.example.myempty.githubk.io.LocalFileExporter
import com.example.myempty.githubk.apk.ApkManager
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.webkit.WebView
import android.webkit.WebSettings
import android.widget.ImageView
import com.example.myempty.githubk.R
import com.example.myempty.githubk.core.Project
import com.example.myempty.githubk.editor.EditorTab
import com.example.myempty.githubk.editor.SyntaxHighlighter
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * GitHubK Studio 4.1 editor workspace.
 *
 * This page is intentionally the IDE page that opens after creating/opening a project.
 * It follows the mobile Android Code Studio style shown by the user:
 * - minimal top command bar (menu / project-file title / run / refresh)
 * - code editor occupies the main area
 * - project tree is an optional left drawer opened by the hamburger button
 * - build output/problems/logs are an expandable bottom sheet, not a permanent bottom nav
 * - no "IDE" item exists in the application's global bottom navigation
 */
class EditorPage(private val host: PageHost) {

    private val state = host.state
    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hlToken = AtomicInteger(0)
    private val compToken = AtomicInteger(0)
    private val treeToken = AtomicInteger(0)
    private var hlBusy = false
    private var hlQueued = false
    private var project: Project? = null
    private var rootView: View? = null

    private lateinit var treeDrawer: LinearLayout
    private lateinit var treeBox: LinearLayout
    private lateinit var tabsRow: LinearLayout
    private lateinit var lineNumbers: TextView
    private lateinit var codeEdit: EditText
    private lateinit var statusLine: TextView
    private lateinit var fileTitle: TextView
    private lateinit var toolSheet: LinearLayout
    private lateinit var toolBody: TextView
    private lateinit var sheetTitle: TextView
    private lateinit var completionScroll: HorizontalScrollView
    private lateinit var completionRow: LinearLayout

    private var completionVisible = false
    private var completionItems = mutableListOf<String>()
    private var rendering = false
    private lateinit var treeSearchInput: EditText
    private var treeVisible = true
    private var runAction: TextView? = null
    private val runPoller = object : Runnable {
        override fun run() {
            val b = state.buildManager.isBuilding()
            runAction?.let { tv ->
                tv.text = if (b) "■" else "▶"
                tv.setTextColor(if (b) StudioTheme.STOP_RED else StudioTheme.RUN_GREEN)
            }
            rootView?.postDelayed(this, 700)
        }
    }
    private var collapsedDirs = mutableSetOf<String>()
    private var treeQuery = ""
    private var lastGitStatus: Map<String, String> = emptyMap()
    private var matchSet: Set<String>? = null

    private lateinit var treePanel: LinearLayout
    private lateinit var treeResizeHandle: View
    private var treeWidthPx = 0
    private var dragStartX = 0f
    private var dragStartWidth = 0

    fun buildView(): View {
        val root = FrameLayout(host.context).apply { setBackgroundColor(StudioTheme.EDITOR_BG) }
        val main = LinearLayout(host.context).apply { orientation = LinearLayout.VERTICAL }
        // Android Code Studio style: every IDE action is reachable from the top area.
        main.addView(topToolbar(), LinearLayout.LayoutParams(-1, dp(88)))
        main.addView(tabsBar(), LinearLayout.LayoutParams(-1, dp(35)))
        main.addView(editorArea(), LinearLayout.LayoutParams(-1, 0, 1f))
        // Keep only a tiny status strip; no functional toolbar/search/actions are placed at the bottom.
        main.addView(statusBar(), LinearLayout.LayoutParams(-1, dp(25)))
        root.addView(main, FrameLayout.LayoutParams(-1, -1))

        toolSheet = buildToolSheet()
        val sheetLp = FrameLayout.LayoutParams(-1, dp(330), Gravity.BOTTOM)
        sheetLp.bottomMargin = dp(25)
        root.addView(toolSheet, sheetLp)
        toolSheet.visibility = View.GONE
        rootView = root
        runPoller.run()
        return root
    }

    private fun topToolbar(): View {
        val wrap = LinearLayout(host.context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(StudioTheme.TOOLBAR_BG)
        }
        // ---- Android Studio 主工具条：☰ + 项目名 · 右侧紧凑图标 ----
        val row = LinearLayout(host.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(2), dp(4), 0)
        }
        row.addView(iconButton("☰", "项目") { toggleTree() }, LinearLayout.LayoutParams(dp(42), dp(44)))
        val titleBox = LinearLayout(host.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), 0, dp(4), 0)
        }
        fileTitle = TextView(host.context).apply {
            text = project?.name ?: "项目"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTextColor(StudioTheme.TEXT_BRIGHT)
            tag = "fileName"
        }
        titleBox.addView(fileTitle)
        titleBox.addView(TextView(host.context).apply {
            text = "Code Editor"
            textSize = 8.5f
            setTextColor(StudioTheme.TEXT_MUTED)
        })
        row.addView(titleBox, LinearLayout.LayoutParams(0, -1, 1f))
        row.addView(iconButton("⌕", "搜索", tint = StudioTheme.ACCENT_BLUE) { searchDialog() }, LinearLayout.LayoutParams(dp(40), dp(44)))
        runAction = iconButton("▶", "运行/构建", tint = StudioTheme.RUN_GREEN) {
            if (state.buildManager.isBuilding()) {
                state.buildManager.cancel()
                updateStatusLine("正在停止构建…")
            } else buildProject()
        }
        row.addView(runAction!!, LinearLayout.LayoutParams(dp(40), dp(44)))
        row.addView(iconButton("▣", "保存", tint = StudioTheme.TEXT_BRIGHT) { saveActive() }, LinearLayout.LayoutParams(dp(40), dp(44)))
        row.addView(iconButton("⋮", "更多操作") { showProjectActions() }, LinearLayout.LayoutParams(dp(40), dp(44)))
        wrap.addView(row, LinearLayout.LayoutParams(-1, dp(47)))

        // ---- Android Studio 工具窗口标签栏（横向滚动，整洁 tab） ----
        val toolScroll = HorizontalScrollView(host.context).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(StudioTheme.STATUSBAR_BG)
        }
        val toolRow = LinearLayout(host.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        fun toolTab(label: String, symbol: String, tint: Int = StudioTheme.TEXT_PRIMARY, click: () -> Unit) {
            val b = TextView(host.context).apply {
                text = "$symbol  $label"
                textSize = 10.5f
                gravity = Gravity.CENTER
                setTextColor(tint)
                setPadding(dp(11), dp(6), dp(11), dp(6))
                background = UiKit.rounded(host.context, StudioTheme.TAB_BG, 8, StudioTheme.DIVIDER, 1)
                setOnClickListener { click() }
            }
            toolRow.addView(b, LinearLayout.LayoutParams(-2, dp(32)).apply { rightMargin = dp(4) })
        }
        toolTab("Build", "▤") { showToolSheet("BUILD OUTPUT") }
        toolTab("Problems", "☷", StudioTheme.WARNING_YELLOW) { showProblemsSheet() }
        toolTab("Live Preview", "▣") { showLivePreview() }
        toolTab("Terminal", "⌁", StudioTheme.ACCENT_BLUE) { host.openTerminal(project?.path) }
        toolTab("Git", "⑂") { gitActions() }
        toolTab("环境", "⚙") { host.pushPage(ToolchainPage(host).buildView()) }
        toolScroll.addView(toolRow)
        wrap.addView(toolScroll, LinearLayout.LayoutParams(-1, dp(40)))
        wrap.addView(View(host.context).apply { setBackgroundColor(StudioTheme.TOOLBAR_BORDER) }, LinearLayout.LayoutParams(-1, dp(1)))
        return wrap
    }

    private fun iconButton(symbol: String, contentDescription: String, tint: Int = StudioTheme.TEXT_BRIGHT, action: () -> Unit): TextView =
        TextView(host.context).apply {
            text = symbol
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(tint)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { action() }
            this.contentDescription = contentDescription
        }

    private fun tabsBar(): View {
        val wrap = LinearLayout(host.context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = HorizontalScrollView(host.context).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(StudioTheme.TAB_BG)
        }
        tabsRow = LinearLayout(host.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scroll.addView(tabsRow)
        wrap.addView(scroll, LinearLayout.LayoutParams(-1, dp(34)))
        wrap.addView(View(host.context).apply { setBackgroundColor(StudioTheme.TOOLBAR_BORDER) }, LinearLayout.LayoutParams(-1, dp(1)))
        return wrap
    }

    private fun editorArea(): View {
        val wrap = LinearLayout(host.context).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(host.context).apply { orientation = LinearLayout.HORIZONTAL }

        treeWidthPx = host.context.getSharedPreferences("githubk", android.content.Context.MODE_PRIVATE)
            .getInt("editor_tree_width_px", dp(235))

        treeDrawer = LinearLayout(host.context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = if (prefs.getBoolean("editor_tree_open", true)) View.VISIBLE else View.GONE
            setBackgroundColor(StudioTheme.SIDEBAR_BG)
        }
        treePanel = LinearLayout(host.context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(StudioTheme.SIDEBAR_BG)
        }
        val treeHeader = LinearLayout(host.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(5), dp(5), dp(5))
            setBackgroundColor(StudioTheme.SIDEBAR_HEADER_BG)
        }
        treeHeader.addView(TextView(host.context).apply {
            text = "PROJECT"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(StudioTheme.TEXT_MUTED)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        treeHeader.addView(iconButton("◀", "折叠文件树") { collapseTree() }, LinearLayout.LayoutParams(dp(30), dp(34)))
        treeHeader.addView(iconButton("＋", "新建文件") { newFileDialog() }, LinearLayout.LayoutParams(dp(32), dp(34)))
        treeHeader.addView(iconButton("□", "新建文件夹") { newFolderDialog() }, LinearLayout.LayoutParams(dp(32), dp(34)))
        treeHeader.addView(iconButton("↻", "刷新文件树") { refresh() }, LinearLayout.LayoutParams(dp(32), dp(34)))
        treePanel.addView(treeHeader)
        treePanel.addView(treeSearchRow())
        treePanel.addView(View(host.context).apply { setBackgroundColor(StudioTheme.TOOLBAR_BORDER) }, LinearLayout.LayoutParams(-1, dp(1)))
        val treeScroll = ScrollView(host.context).apply {
            isFillViewport = true
            setBackgroundColor(StudioTheme.SIDEBAR_BG)
        }
        treeBox = UiKit.vstack(host.context).apply { setPadding(dp(3), dp(5), dp(3), dp(12)) }
        treeScroll.addView(treeBox)
        treePanel.addView(treeScroll, LinearLayout.LayoutParams(treeWidthPx, 0, 1f))
        treeDrawer.addView(treePanel)
        treeResizeHandle = View(host.context).apply {
            setBackgroundColor(StudioTheme.TOOLBAR_BORDER)
            setOnTouchListener { _, ev -> handleTreeResize(ev); true }
        }
        treeDrawer.addView(treeResizeHandle, LinearLayout.LayoutParams(dp(9), -1))
        row.addView(treeDrawer)

        lineNumbers = TextView(host.context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            gravity = Gravity.TOP or Gravity.END
            setTextColor(StudioTheme.GUTTER_TEXT)
            setBackgroundColor(StudioTheme.GUTTER_BG)
            setPadding(dp(4), dp(7), dp(7), dp(7))
            minWidth = dp(38)
        }
        row.addView(lineNumbers)

        codeEdit = EditText(host.context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            gravity = Gravity.TOP or Gravity.START
            setTextColor(StudioTheme.TEXT_PRIMARY)
            setHintTextColor(StudioTheme.TEXT_MUTED)
            setBackgroundColor(StudioTheme.EDITOR_BG)
            highlightColor = StudioTheme.SELECTION_BG
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
            isHorizontalScrollBarEnabled = true
            setPadding(dp(6), dp(7), dp(10), dp(7))
            setSingleLine(false)
        }
        codeEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val tab = state.editor.active() ?: return
                if (!rendering && s != null) {
                    tab.content = s.toString()
                    tab.dirty = true
                    updateLineNumbers()
                    renderTabs()
                    applyHighlight(tab)
                    updateCompletion()
                    updateCursorStatus()
                    updateFileName()
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        codeEdit.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_TAB -> {
                    if (completionVisible && completionItems.isNotEmpty()) insertCompletion(completionItems.first())
                    else codeEdit.text.insert(codeEdit.selectionStart.coerceAtLeast(0), "    ")
                    true
                }
                KeyEvent.KEYCODE_ENTER -> {
                    hideCompletion()
                    val sel = codeEdit.selectionStart.coerceAtLeast(0)
                    val text = codeEdit.text.toString()
                    val lineStart = text.lastIndexOf('\n', (sel - 1).coerceAtLeast(0)) + 1
                    val prefix = text.substring(lineStart, sel).takeWhile { it == ' ' || it == '\t' }
                    val previousStart = text.lastIndexOf('\n', (lineStart - 1).coerceAtLeast(0)) + 1
                    val previous = if (lineStart <= text.length) text.substring(previousStart, lineStart).trim() else ""
                    val extra = if (previous.endsWith("{") || previous.endsWith("(") || previous.endsWith("[")) "    " else ""
                    codeEdit.text.insert(sel, "\n$prefix$extra")
                    true
                }
                else -> false
            }
        }
        row.addView(codeEdit, LinearLayout.LayoutParams(0, -1, 1f))
        wrap.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))

        completionScroll = HorizontalScrollView(host.context).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            setBackgroundColor(StudioTheme.TOOLBAR_BG)
        }
        completionRow = LinearLayout(host.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(5), dp(3), dp(5), dp(3))
        }
        completionScroll.addView(completionRow)
        wrap.addView(completionScroll, LinearLayout.LayoutParams(-1, dp(34)))
        return wrap
    }

    private fun livePreviewBar(): View = LinearLayout(host.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(4), 0)
        setBackgroundColor(StudioTheme.STATUSBAR_BG)
        addView(TextView(host.context).apply {
            text = "Live Preview"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(StudioTheme.TEXT_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { gravity = Gravity.CENTER_VERTICAL }
            setOnClickListener { showLivePreview() }
        })
        addView(iconButton("☾", "预览主题") { host.toast("预览主题：${ThemeManager.current.label}") }, LinearLayout.LayoutParams(dp(42), -1))
        addView(iconButton("▣", "设备预览") { showLivePreview() }, LinearLayout.LayoutParams(dp(42), -1))
        addView(iconButton("☷", "Problems") { showProblemsSheet() }, LinearLayout.LayoutParams(dp(42), -1))
        addView(iconButton("↻", "刷新预览") { showLivePreview() }, LinearLayout.LayoutParams(dp(42), -1))
    }

    private fun showLivePreview() {
        val tab = state.editor.active() ?: run { host.toast("没有打开文件"); return }
        val name = tab.file.name.lowercase()
        val raw = tab.content
        val html = when {
            name.endsWith(".html") || name.endsWith(".htm") -> raw
            name.endsWith(".md") || name.endsWith(".markdown") -> MarkdownRenderer.wrapHtml(raw, "file:///", ThemeManager.current == ThemeManager.ThemeType.LIGHT)
            name.endsWith(".xml") -> "<pre>${raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")}</pre>"
            else -> "<pre>${raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")}</pre>"
        }
        val web = WebView(host.context).apply {
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            loadDataWithBaseURL("file:///", html, "text/html", "UTF-8", null)
        }
        AlertDialog.Builder(host.context).setTitle("Live Preview · ${tab.file.name}")
            .setView(web).setPositiveButton("关闭", null).show()
    }

    private fun showProblemsSheet() {
        if (::toolSheet.isInitialized) {
            sheetTitle.text = "PROBLEMS"
            toolBody.text = buildString {
                append("Problems Report\n\n")
                append("File: ${state.editor.active()?.file?.path ?: "-"}\n")
                append("TODO/FIXME markers and build diagnostics are shown here.\n")
                append("\nRun a build to refresh diagnostics.\n")
            }
            toolSheet.visibility = View.VISIBLE
        }
    }

    private fun statusBar(): View = LinearLayout(host.context).apply {
        orientation = LinearLayout.VERTICAL
        addView(View(host.context).apply { setBackgroundColor(StudioTheme.TOOLBAR_BORDER) }, LinearLayout.LayoutParams(-1, dp(1)))
        addView(LinearLayout(host.context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            setBackgroundColor(StudioTheme.STATUSBAR_BG)
            statusLine = TextView(host.context).apply {
                text = "就绪"
                textSize = 10.5f
                setTextColor(StudioTheme.TEXT_MUTED)
                maxLines = 1
            }
            addView(statusLine, LinearLayout.LayoutParams(0, -1, 1f))
            addView(TextView(host.context).apply {
                text = "UTF-8"
                textSize = 10f
                setTextColor(StudioTheme.TEXT_MUTED)
            })
        }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun buildToolSheet(): LinearLayout {
        val box = LinearLayout(host.context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(StudioTheme.SIDEBAR_BG)
            elevation = dp(10).toFloat()
        }
        box.addView(View(host.context).apply { setBackgroundColor(StudioTheme.TAB_ACTIVE_INDICATOR) }, LinearLayout.LayoutParams(-1, dp(2)))
        // Android Studio 工具窗：顶部可拖动条（参考图 "Drag to move"）
        var dragStartY = 0f
        var dragStartH = 0
        val dragHandle = LinearLayout(host.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(StudioTheme.TOOLBAR_BG)
            setPadding(0, dp(3), 0, dp(3))
            addView(TextView(host.context).apply {
                text = "▔▔▔"
                textSize = 10f
                setTextColor(StudioTheme.TEXT_MUTED)
            })
            setOnTouchListener { _, ev ->
                if (::toolSheet.isInitialized) {
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { dragStartH = toolSheet.height; dragStartY = ev.rawY }
                        MotionEvent.ACTION_MOVE -> {
                            val maxH = host.context.resources.displayMetrics.heightPixels - dp(60)
                            val nh = (dragStartH - (ev.rawY - dragStartY).toInt()).coerceIn(dp(150), maxH)
                            val lp = toolSheet.layoutParams
                            if (lp is FrameLayout.LayoutParams) {
                                lp.height = nh
                                toolSheet.layoutParams = lp
                                toolSheet.requestLayout()
                            }
                        }
                    }
                }
                true
            }
        }
        box.addView(dragHandle, LinearLayout.LayoutParams(-1, dp(22)))
        val header = LinearLayout(host.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(8), dp(7))
            setBackgroundColor(StudioTheme.TOOLBAR_BG)
        }
        sheetTitle = TextView(host.context).apply {
            text = "BUILD OUTPUT"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(StudioTheme.TEXT_BRIGHT)
        }
        header.addView(sheetTitle, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(iconButton("■", "停止构建", tint = StudioTheme.STOP_RED) {
            if (state.buildManager.isBuilding()) {
                state.buildManager.cancel()
                toolBody.text = (toolBody.text?.toString() ?: "") + "\n\n[BUILD] 已请求停止。"
                updateStatusLine("正在停止构建…")
            } else {
                host.toast("当前没有正在运行的构建")
            }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(iconButton("×", "关闭输出") { hideToolSheet() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        box.addView(header)

        val tabButtons = mutableListOf<TextView>()
        val tabs = LinearLayout(host.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(StudioTheme.TOOLBAR_BG)
        }
        listOf("输出", "问题", "应用日志", "IDE 日志").forEachIndexed { idx, label ->
            val b = TextView(host.context).apply {
                text = label
                textSize = 11.5f
                gravity = Gravity.CENTER
                setTextColor(if (idx == 0) StudioTheme.TEXT_BRIGHT else StudioTheme.TEXT_MUTED)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener {
                    tabButtons.forEachIndexed { i, tb -> tb.setTextColor(if (tb == this) StudioTheme.TEXT_BRIGHT else StudioTheme.TEXT_MUTED) }
                    sheetTitle.text = label.uppercase()
                    if (label == "问题") showProblemsBody()
                    else if (label == "应用日志") toolBody.text = "应用日志\n${state.workspace.projects().size} 个项目已加载。"
                    else if (label == "IDE 日志") toolBody.text = "IDE 日志\nEditor / Search / Build 会话日志。"
                    else toolBody.text = "Ready"
                }
            }
            tabButtons += b
            tabs.addView(b, LinearLayout.LayoutParams(0, dp(34), 1f))
        }
        box.addView(tabs)
        box.addView(View(host.context).apply { setBackgroundColor(StudioTheme.TOOLBAR_BORDER) }, LinearLayout.LayoutParams(-1, dp(1)))
        toolBody = TextView(host.context).apply {
            text = "Ready"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(StudioTheme.TEXT_PRIMARY)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setMovementMethod(android.text.method.ScrollingMovementMethod.getInstance())
            setTextIsSelectable(true)
        }
        box.addView(ScrollView(host.context).apply {
            setBackgroundColor(StudioTheme.EDITOR_BG)
            addView(toolBody)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        // Android Studio style: IDEProcess memory strip at panel bottom
        val memLabel = TextView(host.context).apply {
            textSize = 10f
            setTextColor(StudioTheme.TEXT_MUTED)
        }
        val memBar = ProgressBar(host.context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(StudioTheme.RUN_GREEN)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(StudioTheme.TOOLBAR_BORDER)
        }
        val footer = LinearLayout(host.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setBackgroundColor(StudioTheme.STATUSBAR_BG)
            addView(TextView(host.context).apply {
                text = "IDEProcess"
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(StudioTheme.ACCENT_BLUE)
                setPadding(0, 0, dp(8), 0)
            })
            addView(TextView(host.context).apply {
                text = "IDE"
                textSize = 10f
                setTextColor(StudioTheme.TEXT_MUTED)
                setPadding(0, 0, dp(8), 0)
            })
            addView(memBar, LinearLayout.LayoutParams(0, dp(6), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
            addView(memLabel, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        }
        val memUpdater = object : Runnable {
            override fun run() {
                if (toolSheet.visibility == View.VISIBLE) {
                    val rt = Runtime.getRuntime()
                    val used = (rt.totalMemory() - rt.freeMemory()) / 1048576L
                    val max = rt.maxMemory() / 1048576L
                    memBar.max = max.toInt().coerceAtLeast(1)
                    memBar.progress = used.toInt()
                    memLabel.text = "$used MB / $max MB"
                }
                footer.postDelayed(this, 2000)
            }
        }
        footer.post(memUpdater)
        box.addView(footer, LinearLayout.LayoutParams(-1, dp(26)))
        return box
    }

    private fun roundAction(symbol: String, tint: Int = StudioTheme.TEXT_BRIGHT, bg: Int = StudioTheme.TOOLBAR_BG, action: () -> Unit): View = TextView(host.context).apply {
        text = symbol
        textSize = 21f
        gravity = Gravity.CENTER
        setTextColor(tint)
        background = UiKit.rounded(host.context, bg, 27, StudioTheme.DIVIDER, 1)
        elevation = dp(5).toFloat()
        setOnClickListener { action() }
    }

    private fun spacer(px: Int): View = View(host.context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(px), dp(px))
    }

    private fun dp(v: Int): Int = UiKit.dp(host.context, v)

    private val prefs: android.content.SharedPreferences by lazy {
        host.context.getSharedPreferences("githubk", android.content.Context.MODE_PRIVATE)
    }

    private companion object {
        const val TREE_ROW_LIMIT = 4000
    }

    private fun treeSearchRow(): View {
        val row = LinearLayout(host.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(4), dp(5), dp(4))
            setBackgroundColor(StudioTheme.SIDEBAR_BG)
        }
        treeSearchInput = EditText(host.context).apply {
            hint = "⌕ 搜索文件名…"
            textSize = 11f
            setTextColor(StudioTheme.TEXT_PRIMARY)
            setHintTextColor(StudioTheme.TEXT_MUTED)
            setBackgroundColor(StudioTheme.EDITOR_BG)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            maxLines = 1
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    treeQuery = (s?.toString() ?: "").trim()
                    rebuildTree()
                }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        row.addView(treeSearchInput, LinearLayout.LayoutParams(0, dp(34), 1f))
        val clear = TextView(host.context).apply {
            text = "×"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(StudioTheme.TEXT_MUTED)
            setOnClickListener {
                if (treeQuery.isNotBlank()) {
                    treeSearchInput.setText("")
                } else {
                    host.toast("输入文件名关键字筛选文件树")
                }
            }
        }
        row.addView(clear, LinearLayout.LayoutParams(dp(34), dp(34)))
        return row
    }

    private fun toggleTree() {
        if (treeVisible) {
            collapseTree()
        } else {
            treeVisible = true
            if (::treeDrawer.isInitialized) treeDrawer.visibility = View.VISIBLE
            prefs.edit().putBoolean("editor_tree_open", true).apply()
            refresh()
        }
    }

    private fun collapseTree() {
        treeVisible = false
        if (::treeDrawer.isInitialized) treeDrawer.visibility = View.GONE
        prefs.edit().putBoolean("editor_tree_open", false).apply()
    }

    /** 拖动文件树与编辑区之间的把手调整面板宽度。 */
    private fun handleTreeResize(ev: MotionEvent) {
        if (!::treePanel.isInitialized || !::treeResizeHandle.isInitialized) return
        try {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = ev.rawX
                    dragStartWidth = treePanel.layoutParams?.width ?: treeWidthPx
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = (ev.rawX - dragStartX).toInt()
                    val maxW = (host.context.resources.displayMetrics.widthPixels * 0.72f).toInt()
                    val w = (dragStartWidth + delta).coerceIn(dp(130), maxW)
                    val lp = treePanel.layoutParams as LinearLayout.LayoutParams
                    lp.width = w
                    treePanel.layoutParams = lp
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    treeWidthPx = treePanel.layoutParams?.width ?: treeWidthPx
                    host.context.getSharedPreferences("githubk", android.content.Context.MODE_PRIVATE)
                        .edit().putInt("editor_tree_width_px", treeWidthPx).apply()
                }
            }
        } catch (_: Throwable) {}
    }

    private fun refreshEditor() {
        refresh()
        state.editor.active()?.let { renderEditor(it) }
        updateStatusLine("已刷新")
    }

    private fun setProjectAndOpenInternal(p: Project, file: File?) {
        project = p
        // 打开项目后默认展示左侧文件树（IDE 风格）；可用 ☰/◀ 折叠。
        if (::treeDrawer.isInitialized) {
            treeVisible = true
            treeDrawer.visibility = View.VISIBLE
        }
        while (state.editor.size > 0) state.editor.close(0)
        refresh()
        if (file != null && file.isFile) state.editor.open(file)
        renderTabs()
        state.editor.active()?.let { renderEditor(it) }
        updateFileName()
        updateCursorStatus()
        updateStatusLine("${p.name} · ${p.type}")
    }

    fun setProjectAndOpen(p: Project, file: File) = setProjectAndOpenInternal(p, file)

    fun setProject(p: Project) {
        val main = state.workspace.sourceFiles(p).firstOrNull {
            it.name in setOf("MainActivity.kt", "MainActivity.java", "main.dart", "AndroidManifest.xml", "build.gradle", "build.gradle.kts", "pubspec.yaml")
        }
        setProjectAndOpenInternal(p, main)
    }

    private fun refresh() {
        val p = project ?: return
        executor.execute {
            val st = runCatching {
                state.git.status(p.path).associate { (path, s) -> path.replace('\\', '/') to s }
            }.getOrDefault(emptyMap())
            mainHandler.post {
                lastGitStatus = st
                rebuildTree()
            }
        }
    }

    /** 仅重绘树（搜索框输入时调用，避免每个按键都跑 git status）。
     * 磁盘遍历与递归移入后台线程，避免大项目在主线程卡顿/ANR。 */
    private fun rebuildTree() {
        val p = project ?: return
        val q = treeQuery.trim()
        val search = q.isNotBlank()
        val status = lastGitStatus
        val token = treeToken.incrementAndGet()
        executor.execute {
            val matches = if (search) collectTreeMatches(p.path, q) else null
            val rows = collectTreeRows(p.path, p.path, status, matches)
            mainHandler.post {
                if (treeToken.get() != token) return@post
                matchSet = matches
                treeBox.removeAllViews()
                if (!p.path.exists()) {
                    treeBox.addView(UiKit.label(host.context, "项目目录不存在", color = R.color.error, size = 11f))
                    return@post
                }
                if (rows.isEmpty()) {
                    treeBox.addView(UiKit.label(host.context, if (search) "无匹配文件" else "无文件", color = R.color.muted, size = 11f))
                    return@post
                }
                rows.forEach { r ->
                    if (r.isDir) treeBox.addView(directoryRow(r.dir!!, r.rel, r.depth, r.collapsed))
                    else treeBox.addView(fileRow(r.file!!, r.rel, r.status, r.depth))
                }
                if (rows.size >= TREE_ROW_LIMIT) {
                    treeBox.addView(UiKit.label(host.context, "项目文件较多，仅显示前 $TREE_ROW_LIMIT 项", color = R.color.muted, size = 10.5f))
                }
            }
        }
    }

    private class TreeRow(
        val dir: File?,
        val file: File?,
        val rel: String,
        val depth: Int,
        val isDir: Boolean,
        val collapsed: Boolean,
        val status: String
    )

    /** 后台收集要展示的扁平目录树行（磁盘 I/O 与递归不占主线程）。 */
    private fun collectTreeRows(dir: File, projectRoot: File, statusMap: Map<String, String>, matches: Set<String>?): List<TreeRow> {
        val out = mutableListOf<TreeRow>()
        val search = matches != null
        var count = 0
        fun visit(d: File, depth: Int) {
            if (count >= TREE_ROW_LIMIT) return
            val children = d.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })) ?: return
            for (child in children) {
                if (count >= TREE_ROW_LIMIT) break
                if (isIgnoredTreeName(child.name)) continue
                val rel = child.relativeTo(projectRoot).path.replace('\\', '/')
                if (child.isDirectory) {
                    val key = runCatching { child.canonicalPath }.getOrElse { child.absolutePath }
                    if (search && key !in matches!!) continue
                    val collapsed = key in collapsedDirs && !search
                    out.add(TreeRow(child, null, rel, depth, true, collapsed, ""))
                    count++
                    if (!collapsed) visit(child, depth + 1)
                } else if (child.isFile && shouldShowInTree(child)) {
                    if (search) {
                        val key = runCatching { child.canonicalPath }.getOrElse { child.absolutePath }
                        if (key !in matches!!) continue
                    }
                    out.add(TreeRow(null, child, rel, depth, false, false, statusMap[rel].orEmpty()))
                    count++
                }
            }
        }
        visit(dir, 0)
        return out
    }

    /** 收集文件名/相对路径命中项及其所有祖先目录。 */
    private fun collectTreeMatches(root: File, q: String): Set<String> {
        val out = mutableSetOf<String>()
        val rootKey = runCatching { root.canonicalPath }.getOrElse { root.absolutePath }
        fun addAncestors(file: File) {
            var cur: File? = file.parentFile
            while (cur != null) {
                val node = cur
                val key = runCatching { node.canonicalPath }.getOrElse { node.absolutePath }
                if (!key.startsWith(rootKey)) break
                out += key
                if (key == rootKey) break
                cur = cur.parentFile
            }
        }
        fun visit(dir: File) {
            dir.listFiles()?.forEach { child ->
                if (isIgnoredTreeName(child.name)) return@forEach
                if (child.isDirectory) visit(child)
                else if (child.isFile && shouldShowInTree(child)) {
                    val rel = child.relativeTo(root).path.replace('\\', '/')
                    val key = runCatching { child.canonicalPath }.getOrElse { child.absolutePath }
                    if (child.name.contains(q, true) || rel.contains(q, true)) {
                        out += key
                        addAncestors(child)
                    }
                }
            }
        }
        visit(root)
        return out
    }

    private fun renderTree(dir: File, projectRoot: File, statusMap: Map<String, String>) {
        val children = dir.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })) ?: return
        val searching = matchSet != null
        children.forEach { child ->
            if (isIgnoredTreeName(child.name)) return@forEach
            val rel = child.relativeTo(projectRoot).path.replace('\\', '/')
            val depth = rel.count { it == '/' }
            if (child.isDirectory) {
                val key = runCatching { child.canonicalPath }.getOrElse { child.absolutePath }
                if (searching && key !in matchSet!!) return@forEach
                // 搜索模式下强制展开以展示命中文件；普通模式遵守折叠状态
                val collapsed = key in collapsedDirs && !searching
                treeBox.addView(directoryRow(child, rel, depth, collapsed))
                if (!collapsed) renderTree(child, projectRoot, statusMap)
            } else if (child.isFile && shouldShowInTree(child)) {
                if (searching) {
                    val key = runCatching { child.canonicalPath }.getOrElse { child.absolutePath }
                    if (key !in matchSet!!) return@forEach
                }
                treeBox.addView(fileRow(child, rel, statusMap[rel].orEmpty(), depth))
            }
        }
    }

    private fun isIgnoredTreeName(name: String): Boolean =
        name == ".git" || name == "build" || name == ".gradle" || name == ".idea" ||
            name == ".DS_Store" || name == "node_modules" || name == "local.properties"

    private fun shouldShowInTree(file: File): Boolean {
        if (isIgnoredTreeName(file.name)) return false
        if (file.length() > 2L * 1024L * 1024L) return false
        val ext = file.extension.lowercase()
        if (ext in treeTextExt) return true
        if (ext in treeBinaryExt && file.length() <= 1024L * 1024L) return true
        return file.name.lowercase() in setOf("gradlew", "makefile", "dockerfile", "readme", "license", "changelog")
    }

    private val treeTextExt = setOf("kt", "kts", "java", "dart", "xml", "gradle", "properties", "yaml", "yml", "json", "md", "txt", "js", "ts", "html", "css", "c", "h", "cpp", "py", "sh", "bat", "toml", "ini", "conf", "env")
    private val treeBinaryExt = setOf("png", "jpg", "jpeg", "webp", "gif", "svg", "ico", "jar", "aar", "apk", "zip", "db", "ttf")

    private fun directoryRow(dir: File, rel: String, depth: Int, collapsed: Boolean): View = LinearLayout(host.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6 + depth * 12), dp(6), dp(4), dp(6))
        addView(TextView(host.context).apply {
            text = if (collapsed) "▸" else "▾"
            textSize = 12f
            setTextColor(StudioTheme.TEXT_MUTED)
            minWidth = dp(18)
        })
        addView(TextView(host.context).apply {
            text = "📁 ${dir.name}"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(StudioTheme.TEXT_BRIGHT)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        setOnClickListener {
            val key = runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }
            if (!collapsedDirs.add(key)) collapsedDirs.remove(key)
            rebuildTree()
        }
        setOnLongClickListener {
            showDirMenu(dir, rel)
            true
        }
    }

    private fun showDirMenu(dir: File, rel: String) {
        val groups = listOf(
            "Path Actions" to listOf(
                "复制完整路径" to { copyPath("完整路径", dir.absolutePath) },
                "复制相对路径" to { copyPath("相对路径", rel) }
            ),
            "Folder Actions" to listOf(
                "展开/折叠" to {
                    val key = runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }
                    if (!collapsedDirs.add(key)) collapsedDirs.remove(key)
                    rebuildTree()
                },
                "在此新建文件" to { newFileDialog(dir) },
                "在此新建文件夹" to { newFolderDialog(dir) },
                "重命名" to { renameFileDialog(dir) }
            ),
            "Danger Zone" to listOf(
                "删除文件夹" to { confirmDeleteDir(dir, rel) }
            )
        )
        showGroupedMenu("📁 ${dir.name}", groups)
    }

    private fun confirmDeleteDir(dir: File, rel: String) {
        val p = project ?: return
        if (dir.canonicalFile == p.path.canonicalFile) {
            host.toast("不能删除项目根目录"); return
        }
        val count = runCatching { dir.walkTopDown().count { it.isFile } }.getOrDefault(0)
        AlertDialog.Builder(host.context)
            .setTitle("删除文件夹")
            .setMessage("将永久删除目录 ${rel}（含 $count 个文件）？\n\n此操作不可撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                val abs = runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }
                val base = runCatching { p.path.canonicalPath }.getOrElse { p.path.absolutePath }
                if (!abs.startsWith(base + File.separator)) { host.toast("路径越界，已阻止"); return@setPositiveButton }
                val ok = dir.deleteRecursively()
                if (ok) {
                    state.editor.closeWhere { tab ->
                        runCatching { tab.file.canonicalPath }.getOrElse { tab.file.absolutePath }.startsWith(abs)
                    }
                    refresh()
                    state.editor.active()?.let { renderEditor(it) }
                    renderTabs()
                    host.toast("已删除目录 ${dir.name}")
                } else host.toast("删除失败")
            }.show()
    }

    private fun typeLabel(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "gradle", "kts" -> "GRADLE"
            "properties" -> "PROPERTIES"
            "md", "markdown" -> "MD"
            "bat" -> "BAT"
            "kt" -> "KOTLIN"
            "java" -> "JAVA"
            "dart" -> "DART"
            "xml" -> "XML"
            "json" -> "JSON"
            "yaml", "yml" -> "YAML"
            "js", "ts" -> "JS"
            "py" -> "PY"
            "sh" -> "SHELL"
            "html", "css" -> "WEB"
            "apk" -> "APK"
            "zip" -> "ZIP"
            else -> if (ext.isNotEmpty()) ext.uppercase() else "FILE"
        }
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /** Android Studio 风格分组上下文菜单（参考截图：Path Actions / File Actions / Danger Zone）。 */
    private fun showGroupedMenu(title: String, groups: List<Pair<String, List<Pair<String, () -> Unit>>>>) {
        val sheetGroups = groups.map { (g, items) ->
            g to items.map { (label, action) ->
                UiKit.SheetItem(
                    icon = menuIconFor(label),
                    title = label,
                    subtitle = null,
                    colorRes = if (g.contains("danger", true) || label.startsWith("\u5220\u9664")) com.example.myempty.githubk.R.color.error else com.example.myempty.githubk.R.color.on_surface,
                    onClick = action
                )
            }
        }
        UiKit.groupedSheet(host.context, title, "\u9009\u62e9\u5bf9\u201c$title\u201d\u6267\u884c\u7684\u64cd\u4f5c", sheetGroups)
    }

    private fun menuIconFor(label: String): String = when {
        label.contains("\u590d\u5236\u5b8c\u6574") -> "📋"
        label.contains("\u590d\u5236\u76f8\u5bf9") -> "📎"
        label.contains("\u5c55\u5f00") -> "🔀"
        label.contains("\u65b0\u5efa\u6587\u4ef6") -> "\u2795"
        label.contains("\u65b0\u5efa\u6587\u4ef6\u5939") -> "📁"
        label.contains("\u91cd\u547d\u540d") -> "\u270f\ufe0f"
        label.contains("\u6253\u5f00") -> "👁"
        label.contains("\u4e0b\u8f7d") -> "\u2b07\ufe0f"
        label.contains("\u5220\u9664") -> "🗑"
        else -> "•"
    }

    private fun statusColor(st: String): Int = when (st) {
        "A" -> StudioTheme.RUN_GREEN
        "M" -> StudioTheme.WARNING_YELLOW
        "D", "U" -> StudioTheme.ERROR_RED
        else -> StudioTheme.TEXT_MUTED
    }

    private fun fileRow(f: File, rel: String, st: String, depth: Int): View = LinearLayout(host.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6 + depth * 12), dp(6), dp(4), dp(6))
        addView(TextView(host.context).apply {
            text = if (st.isBlank()) "  " else st
            textSize = 10f
            setTextColor(statusColor(st))
            minWidth = dp(16)
        })
        addView(TextView(host.context).apply {
            text = StudioTheme.fileIconGlyph(f.name)
            textSize = 11f
            setTextColor(StudioTheme.fileIconColor(f.name))
        })
        addView(TextView(host.context).apply {
            text = " ${f.name}"
            textSize = 11f
            setTextColor(if (st.isBlank()) StudioTheme.TEXT_PRIMARY else statusColor(st))
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        if (f.isFile) {
            // Android Studio 风格：类型标签 + 文件大小（灰字靠右）
            addView(TextView(host.context).apply {
                text = "${typeLabel(f.name)}  ${fmtSize(f.length())}"
                textSize = 9f
                gravity = Gravity.END
                setTextColor(StudioTheme.TEXT_MUTED)
                maxLines = 1
            })
            addView(TextView(host.context).apply {
                text = "⬇"
                textSize = 12f
                setTextColor(StudioTheme.ACCENT_BLUE)
                setPadding(dp(6), 0, dp(2), 0)
                setOnClickListener { exportFile(f) }
            })
        }
        setOnClickListener { openFile(f) }
        setOnLongClickListener {
            showFileMenu(f, rel)
            true
        }
    }

    /** 把工作区单个文件导出到手机本地 Download，导出成功后自动弹系统分享。 */
    private fun exportFile(f: File) {
        if (!f.isFile) return
        host.toast("正在导出 ${f.name} …")
        Thread {
            val (ok, where) = LocalFileExporter.exportToDownloads(host.context, f, f.name)
            val mgr = ApkManager(host.context)
            host.runUi {
                host.toast(if (ok) "已下载到本地：$where" else "导出失败：$where")
                if (ok) {
                    val mime = if (f.extension.equals("zip", true)) "application/zip" else "application/octet-stream"
                    runCatching { mgr.shareFile(f, mime, "分享 ${f.name}") }
                }
            }
        }.start()
    }

    private fun showFileMenu(f: File, rel: String) {
        val p = project ?: return
        val baseKey = runCatching { p.path.canonicalPath }.getOrElse { p.path.absolutePath }
        val fileKey = runCatching { f.canonicalPath }.getOrElse { f.absolutePath }
        if (!fileKey.startsWith(baseKey + File.separator)) { host.toast("路径越界"); return }
        val groups = listOf(
            "Path Actions" to listOf(
                "复制完整路径" to { copyPath("完整路径", f.absolutePath) },
                "复制相对路径" to { copyPath("相对路径", rel) }
            ),
            "File Actions" to listOf(
                "打开" to { openFile(f) },
                "下载到本地" to { exportFile(f) },
                "重命名" to { renameFileDialog(f) }
            ),
            "Danger Zone" to listOf(
                "删除文件" to { confirmDeleteFile(f, rel) }
            )
        )
        showGroupedMenu("${StudioTheme.fileIconGlyph(f.name)} ${f.name}", groups)
    }

    private fun copyPath(label: String, text: String) {
        val cm = host.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (cm == null) { host.toast("无法访问剪贴板"); return }
        cm.setPrimaryClip(ClipData.newPlainText("GitHubK path", text))
        host.toast("已复制$label")
    }

    private fun renameFileDialog(f: File) {
        val p = project ?: return
        val oldName = f.name
        val input = EditText(host.context).apply { setText(oldName); setSingleLine(true); selectAll() }
        AlertDialog.Builder(host.context)
            .setTitle(if (f.isDirectory) "重命名文件夹" else "重命名文件")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("重命名") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName.contains('/') || newName.contains('\\') || newName == "." || newName == ".." || newName == oldName) {
                    host.toast("名称无效"); return@setPositiveButton
                }
                val targetRel = runCatching {
                    File(f.parentFile, newName).relativeTo(p.path).path.replace('\\', '/')
                }.getOrNull()
                val target = if (targetRel == null) null else state.workspace.resolveSafe(p, targetRel)
                if (target == null) { host.toast("路径越界，已阻止"); return@setPositiveButton }
                if (target.exists()) { host.toast("同名文件/目录已存在"); return@setPositiveButton }
                // 先落盘已打开且未保存的内容，避免重命名丢失
                state.editor.all().filter { it.file.absolutePath == f.absolutePath }
                    .filter { it.dirty && f.isFile }
                    .forEach { runCatching { f.writeText(it.content) } }
                if (!f.renameTo(target)) { host.toast("重命名失败"); return@setPositiveButton }
                val oldAbs = f.absolutePath
                state.editor.closeWhere { it.file.absolutePath == oldAbs }
                refresh()
                if (target.isFile && target.extension.lowercase() in treeTextExt) {
                    openFile(target)
                } else {
                    state.editor.active()?.let { renderEditor(it) }
                    renderTabs()
                    updateStatusLine("已重命名为 ${target.name}")
                }
                host.toast("已重命名")
            }.show()
    }

    private fun confirmDeleteFile(f: File, rel: String) {
        val p = project ?: return
        AlertDialog.Builder(host.context)
            .setTitle("删除文件")
            .setMessage("确定删除 ${f.name}？\n\n${rel}")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                if (state.workspace.resolveSafe(p, rel)?.delete() == true) {
                    state.editor.closeByPath(f.absolutePath)
                    refresh()
                    state.editor.active()?.let { renderEditor(it) }
                    renderTabs()
                    host.toast("已删除 ${f.name}")
                } else host.toast("删除失败")
            }.show()
    }

    private fun openFile(file: File) {
        if (!file.isFile) return
        if (file.extension.lowercase() in treeBinaryExt) {
            host.toast("二进制文件：可长按复制路径/重命名/删除")
            return
        }
        val tab = state.editor.open(file)
        renderTabs()
        renderEditor(tab)
        updateCursorStatus()
        updateFileName()
        updateStatusLine("打开 ${file.name}")
        if (treeVisible) toggleTree()
    }

    private fun renderTabs() {
        tabsRow.removeAllViews()
        state.editor.all().forEachIndexed { i, tab ->
            val active = i == state.editor.activeIndex
            val chip = LinearLayout(host.context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(if (active) StudioTheme.TAB_ACTIVE_BG else StudioTheme.TAB_BG)
            }
            val row = LinearLayout(host.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(6), dp(8), dp(6))
            }
            row.addView(TextView(host.context).apply {
                text = StudioTheme.fileIconGlyph(tab.file.name)
                textSize = 12f
                setTextColor(StudioTheme.fileIconColor(tab.file.name))
            })
            row.addView(spacer(5))
            row.addView(TextView(host.context).apply {
                text = tab.name
                textSize = 11.5f
                setTextColor(if (active) StudioTheme.TEXT_BRIGHT else StudioTheme.TEXT_MUTED)
                maxLines = 1
            })
            row.addView(spacer(7))
            row.addView(TextView(host.context).apply {
                text = if (tab.dirty) "●" else "×"
                textSize = if (tab.dirty) 8f else 12f
                setTextColor(if (tab.dirty) StudioTheme.MODIFIED_DOT else StudioTheme.TEXT_MUTED)
                setPadding(dp(3), 0, dp(3), 0)
                setOnClickListener {
                    state.editor.close(i)
                    renderTabs()
                    state.editor.active()?.let { renderEditor(it) }
                }
            })
            chip.addView(row)
            // Active-tab underline, matching Android Studio's blue tab indicator
            chip.addView(View(host.context).apply {
                setBackgroundColor(if (active) StudioTheme.TAB_ACTIVE_INDICATOR else Color.TRANSPARENT)
            }, LinearLayout.LayoutParams(-1, dp(2)))
            chip.setOnClickListener {
                state.editor.activeIndex = i
                renderTabs()
                renderEditor(tab)
            }
            tabsRow.addView(chip)
        }
    }

    private fun renderEditor(tab: EditorTab) {
        rendering = true
        codeEdit.setText(tab.content)
        codeEdit.setSelection(tab.content.length.coerceAtMost(codeEdit.text.length))
        rendering = false
        updateLineNumbers()
        updateCursorStatus()
        updateFileName()
        scheduleHighlight(tab)
    }

    /** 异步高亮：先把纯文本显示出来，再后台计算语法高亮回填，避免大文件主线程卡顿。
     * 用 busy/queued 节流：连续输入时只保留最后一次高亮，避免后台任务堆积。 */
    private fun scheduleHighlight(tab: EditorTab) {
        if (rendering) return
        if (hlBusy) { hlQueued = true; return }
        hlBusy = true
        val token = hlToken.incrementAndGet()
        val content = tab.content
        val name = tab.file.name
        executor.execute {
            val hl = SyntaxHighlighter.highlight(content, name, SyntaxHighlighter.studioPalette)
            mainHandler.post {
                hlBusy = false
                val active = state.editor.active()
                if (hlToken.get() != token || active !== tab) {
                    if (hlQueued && active != null) { hlQueued = false; scheduleHighlight(active) }
                    return@post
                }
                val start = codeEdit.selectionStart.coerceAtLeast(0)
                val end = codeEdit.selectionEnd.coerceAtLeast(0)
                rendering = true
                codeEdit.setText(hl)
                try { codeEdit.setSelection(start.coerceAtMost(codeEdit.text.length), end.coerceAtMost(codeEdit.text.length)) } catch (_: Throwable) {}
                rendering = false
                updateLineNumbers()
                if (hlQueued && active != null) { hlQueued = false; scheduleHighlight(active) }
            }
        }
    }

    private fun applyHighlight(tab: EditorTab) {
        if (rendering) return
        scheduleHighlight(tab)
    }

    private fun updateLineNumbers() {
        val count = (codeEdit.text.toString().count { it == '\n' } + 1).coerceAtLeast(1)
        lineNumbers.text = (1..count).joinToString("\n")
    }

    private fun updateFileName() {
        val tab = state.editor.active()
        fileTitle.text = if (tab == null) project?.name ?: "项目" else (if (tab.dirty) "● " else "") + tab.file.name
    }

    private fun updateStatusLine(message: String) { statusLine.text = message }

    private fun updateCursorStatus() {
        val tab = state.editor.active() ?: return
        val sel = codeEdit.selectionStart.coerceAtLeast(0)
        val text = codeEdit.text.toString()
        val line = text.substring(0, sel.coerceAtMost(text.length)).count { it == '\n' } + 1
        val lineStart = text.lastIndexOf('\n', (sel - 1).coerceAtLeast(0)) + 1
        val col = sel - lineStart + 1
        statusLine.text = "Ln $line, Col $col${if (tab.dirty) " · Modified" else ""}"
    }

    private fun updateCompletion() {
        val tab = state.editor.active() ?: return
        val sel = codeEdit.selectionStart
        if (sel < 0) return
        val text = codeEdit.text.toString()
        var start = sel
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
        val prefix = text.substring(start, sel)
        val keywords = when {
            tab.file.name.endsWith(".kt") -> kotlinKeywords()
            tab.file.name.endsWith(".java") -> javaKeywords()
            tab.file.name.endsWith(".xml") -> xmlKeywords()
            tab.file.name.endsWith(".gradle") || tab.file.name.endsWith(".kts") -> gradleKeywords()
            tab.file.name.endsWith(".dart") -> dartKeywords()
            else -> kotlinKeywords()
        }
        val content = tab.content
        val token = compToken.incrementAndGet()
        executor.execute {
            val symbols = if (content.length <= 300_000) collectSymbols(content) else emptyList()
            val all = (symbols.asSequence() + keywords.asSequence()).distinct().toList()
            val filtered = (if (prefix.isBlank()) all else all.filter { it.startsWith(prefix, true) }).take(12)
            mainHandler.post {
                if (compToken.get() != token) return@post
                if (state.editor.active() !== tab) return@post
                showCompletion(filtered)
            }
        }
    }

    private fun collectSymbols(text: String): List<String> {
        val out = mutableListOf<String>()
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            if (c.isLetter() || c == '_') {
                val s = i
                while (i < n && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                out.add(text.substring(s, i))
            } else i++
        }
        return out
    }

    private fun showCompletion(filtered: List<String>) {
        if (filtered.isEmpty()) { hideCompletion(); return }
        completionItems = filtered.toMutableList()
        completionRow.removeAllViews()
        filtered.forEachIndexed { index, word ->
            completionRow.addView(TextView(host.context).apply {
                this.text = word
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(if (index == 0) Color.WHITE else StudioTheme.TEXT_PRIMARY)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = UiKit.rounded(host.context, if (index == 0) StudioTheme.ACCENT_BLUE else StudioTheme.SIDEBAR_BG, 4, StudioTheme.DIVIDER, 1)
                setOnClickListener { insertCompletion(word) }
            })
            completionRow.addView(spacer(3))
        }
        completionScroll.visibility = View.VISIBLE
        completionVisible = true
    }

    private fun hideCompletion() {
        completionVisible = false
        completionItems.clear()
        if (::completionScroll.isInitialized) completionScroll.visibility = View.GONE
    }

    private fun insertCompletion(word: String) {
        val sel = codeEdit.selectionStart
        if (sel < 0) return
        val text = codeEdit.text.toString()
        var start = sel
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
        rendering = true
        codeEdit.text.replace(start, sel, word)
        codeEdit.setSelection(start + word.length)
        rendering = false
        hideCompletion()
    }

    private fun saveActive() {
        val tab = state.editor.active() ?: return
        try {
            tab.file.writeText(tab.content)
            tab.dirty = false
            renderTabs()
            updateFileName()
            updateStatusLine("已保存 ${tab.file.name}")
        } catch (e: Throwable) { updateStatusLine("保存失败: ${e.message}") }
    }

    private fun searchDialog() {
        val p = project ?: return
        val input = EditText(host.context).apply {
            hint = "搜索代码、文件名或文本"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val result = TextView(host.context).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(ThemeManager.colors.onSurface)
            setPadding(0, dp(8), 0, 0)
        }
        val box = LinearLayout(host.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(6))
            addView(input)
            addView(ScrollView(host.context).apply { addView(result) }, LinearLayout.LayoutParams(-1, dp(300)))
        }
        val dialog = AlertDialog.Builder(host.context).setTitle("搜索 Everywhere").setView(box).setNegativeButton("关闭", null).setPositiveButton("搜索", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val q = input.text.toString().trim()
                if (q.isBlank()) return@setOnClickListener
                executor.execute {
                    val hits = mutableListOf<String>()
                    p.path.walkTopDown()
                        .filter { it.isFile && it.length() <= 2L * 1024L * 1024L && it.name != ".git" }
                        .take(3000)
                        .forEach { f ->
                            if (f.name.contains(q, true)) hits += "FILE ${f.relativeTo(p.path).path}"
                            if (f.extension.lowercase() in sourceExt) runCatching {
                                f.useLines(Charsets.UTF_8) { lines ->
                                    lines.forEachIndexed { n, line ->
                                        if (line.contains(q, true) && hits.size < 300) hits += "${f.relativeTo(p.path).path}:${n + 1}: ${line.trim().take(180)}"
                                    }
                                }
                            }
                        }
                    host.runUi {
                        result.text = if (hits.isEmpty()) "未找到：$q" else hits.joinToString("\n")
                        updateStatusLine("搜索完成：${hits.size} 个结果")
                    }
                }
            }
        }
        dialog.show()
    }

    private fun gotoDialog() {
        val input = EditText(host.context).apply {
            hint = "行号，例如 120"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        AlertDialog.Builder(host.context).setTitle("跳转到行").setView(input).setNegativeButton("取消", null).setPositiveButton("跳转") { _, _ ->
            val line = input.text.toString().toIntOrNull() ?: return@setPositiveButton
            val text = codeEdit.text.toString()
            var pos = 0
            repeat((line - 1).coerceAtLeast(0)) { val n = text.indexOf('\n', pos); if (n >= 0) pos = n + 1 }
            codeEdit.requestFocus()
            codeEdit.setSelection(pos.coerceAtMost(text.length))
            updateCursorStatus()
        }.show()
    }

    private fun showProjectActions() {
        val labels = arrayOf("保存文件", "跳转到行", "新建文件", "新建文件夹", "重命名当前文件", "项目文件树", "打开终端", "IDE 环境中心", "Debug 构建", "Release 构建", "Clean", "构建队列", "APK", "Git")
        AlertDialog.Builder(host.context).setTitle(project?.name ?: "项目").setItems(labels) { _, which ->
            when (which) {
                0 -> saveActive()
                1 -> gotoDialog()
                2 -> newFileDialog()
                3 -> newFolderDialog()
                4 -> state.editor.active()?.file?.let { renameFileDialog(it) } ?: host.toast("没有打开的文件")
                5 -> toggleTree()
                6 -> host.openTerminal(project?.path)
                7 -> host.pushPage(ToolchainPage(host).buildView())
                8 -> queueBuild("assembleDebug")
                9 -> queueBuild("assembleRelease")
                10 -> queueBuild("clean")
                11 -> showBuildQueue()
                12 -> showApkFiles()
                13 -> gitActions()
            }
        }.show()
    }

    private fun newFileDialog(baseDir: File? = null) {
        val p = project ?: return
        val dirRel = if (baseDir == null || baseDir == p.path) "" else runCatching {
            baseDir.relativeTo(p.path).path.replace('\\', '/')
        }.getOrDefault("")
        val input = EditText(host.context).apply {
            hint = if (dirRel.isBlank()) "例如 src/main/java/Test.kt" else "位于 $dirRel/ 下的文件名（可含多级）"
            setSingleLine(true)
        }
        AlertDialog.Builder(host.context).setTitle("新建文件" + if (dirRel.isBlank()) "" else " · $dirRel")
            .setView(input).setNegativeButton("取消", null).setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim().trimStart('/')
                if (name.isBlank()) { host.toast("名称不能为空"); return@setPositiveButton }
                val rel = if (dirRel.isBlank() || name.startsWith("$dirRel/")) name else "$dirRel/$name"
                if (rel.split('/').any { it == ".." || it.isBlank() }) { host.toast("路径无效"); return@setPositiveButton }
                val target = state.workspace.resolveSafe(p, rel)
                if (target == null) { host.toast("路径越界，已阻止"); return@setPositiveButton }
                try {
                    target.parentFile?.mkdirs()
                    if (!target.exists()) target.createNewFile()
                    if (target.extension.lowercase() in treeTextExt) openFile(target)
                    else { refresh(); renderTabs(); host.toast("已创建 ${target.name}") }
                } catch (e: Throwable) { host.toast("创建失败：${e.message}") }
            }.show()
    }

    private fun newFolderDialog(baseDir: File? = null) {
        val p = project ?: return
        val dirRel = if (baseDir == null || baseDir == p.path) "" else runCatching {
            baseDir.relativeTo(p.path).path.replace('\\', '/')
        }.getOrDefault("")
        val input = EditText(host.context).apply {
            hint = if (dirRel.isBlank()) "例如 src/main/java/com/example" else "在 $dirRel/ 下创建的文件夹名"
            setSingleLine(true)
        }
        AlertDialog.Builder(host.context)
            .setTitle("新建文件夹" + if (dirRel.isBlank()) "" else " · $dirRel")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim().trim('/')
                if (name.isBlank()) { host.toast("名称不能为空"); return@setPositiveButton }
                val rel = if (dirRel.isBlank() || name.startsWith("$dirRel/")) name else "$dirRel/$name"
                if (rel.split('/').any { it == ".." || it.isBlank() }) { host.toast("路径无效"); return@setPositiveButton }
                val target = state.workspace.resolveSafe(p, rel)
                if (target == null) { host.toast("路径越界，已阻止"); return@setPositiveButton }
                if (target.exists()) { host.toast("同名文件/目录已存在"); return@setPositiveButton }
                if (target.mkdirs()) { refresh(); host.toast("已创建：$rel") }
                else host.toast("文件夹创建失败")
            }.show()
    }

    private fun queueBuild(task: String) {
        val p = project ?: return
        showToolSheet("BUILD OUTPUT")
        toolBody.text = "[BUILD-MANAGER] $task 已加入队列：${p.name}\n等待执行…"
        state.buildManager.submit(
            project = p, task = task, sdkHome = state.sdkHome(), javaHome = state.javaHome(),
            gradleHome = state.gradleHome(), flutterBin = state.flutterBin()
        ) { result: com.example.myempty.githubk.buildsys.BuildResult ->
            host.runUi {
                toolBody.text = result.render()
                updateStatusLine(if (result.success) "$task 完成" else "$task 失败")
            }
        }
    }

    private fun showBuildQueue() {
        val box = LinearLayout(host.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val current = state.buildManager.current()
        box.addView(TextView(host.context).apply {
            text = if (current == null) "当前：空闲" else "当前：#${current.request.id} ${current.request.project.name} / ${current.request.task} / ${current.state}"
            textSize = 13f
            setTextColor(ThemeManager.colors.onSurface)
        })
        state.buildManager.queued().forEach { item: com.example.myempty.githubk.buildsys.BuildManager.BuildItem ->
            box.addView(TextView(host.context).apply {
                text = "#${item.request.id} ${item.request.project.name} / ${item.request.task} / ${item.state}"
                textSize = 12f
                setTextColor(ThemeManager.colors.muted)
            })
        }
        box.addView(UiKit.spacer(host.context, 8))
        box.addView(UiKit.hstack(host.context).apply {
            addView(UiKit.button(host.context, "停止当前") { state.buildManager.cancel(); host.toast("已请求停止") })
            addView(UiKit.spacer(host.context, 8))
            addView(UiKit.ghostButton(host.context, "清空队列") { host.toast("已清空 ${state.buildManager.clearQueue()} 个任务") })
        })
        UiKit.dialog(host.context, "Build Queue", box)
    }

    private fun showApkFiles() {
        val p = project ?: return
        val files = p.path.walkTopDown().filter { it.isFile && it.extension.equals("apk", true) }.sortedByDescending { it.lastModified() }.take(20).toList()
        val box = LinearLayout(host.context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12)) }
        if (files.isEmpty()) box.addView(TextView(host.context).apply { text = "暂无 APK" })
        files.forEach { f -> box.addView(TextView(host.context).apply { text = "${f.name}\n${f.absolutePath}"; textSize = 12f; setTextColor(ThemeManager.colors.onSurface); setPadding(0, dp(5), 0, dp(5)) }) }
        UiKit.dialog(host.context, "APK 输出", box)
    }

    private fun buildProject() {
        val p = project ?: return
        showToolSheet("BUILD OUTPUT")
        toolBody.text = "Running tasks: :app:assembleDebug\n> Configure project :app\n\n正在构建 ${p.name}..."
        state.buildManager.submit(
            project = p,
            task = if (p.type == "Flutter") "build apk --debug" else "assembleDebug",
            sdkHome = state.sdkHome(),
            javaHome = state.javaHome(),
            gradleHome = state.gradleHome(),
            flutterBin = state.flutterBin()
        ) { result: com.example.myempty.githubk.buildsys.BuildResult ->
            host.runUi {
                toolBody.text = result.render()
                updateStatusLine(if (result.success) "构建完成" else "构建失败")
            }
        }
        toolBody.text = "[BUILD-MANAGER] 已加入构建队列：${p.name}\n等待执行…"
    }

    private fun showProblemsBody() {
        val p = project ?: run { toolBody.text = "未选择项目"; return }
        toolBody.text = "正在扫描项目静态问题…"
        executor.execute {
            val activeName = state.editor.active()?.file?.name
            val byFile = LinkedHashMap<String, MutableList<String>>()
            state.workspace.sourceFiles(p).forEach { f ->
                val rel = f.relativeTo(p.path).path
                runCatching {
                    f.useLines { lines -> lines.forEachIndexed { i, line ->
                        val m = if (line.contains("FIXME", true)) "FIXME" else if (line.contains("TODO", true)) "TODO" else null
                        if (m != null) byFile.getOrPut(rel) { mutableListOf() }.add("${i + 1}: $m — ${line.trim().take(90)}")
                    } }
                }
            }
            val total = byFile.values.sumOf { it.size }
            val sb = StringBuilder()
            sb.append("Problems — ${total} 个问题（TODO / FIXME）\n")
            sb.append("File: ${activeName ?: "-"}\n")
            sb.append("──────────────────────────\n")
            if (total == 0) sb.append("✓ 未发现 TODO/FIXME 标记\n")
            else byFile.forEach { (rel, items) ->
                sb.append("\n${rel}\n")
                items.take(40).forEach { sb.append("  ${it}\n") }
            }
            host.runUi { toolBody.text = sb.toString() }
        }
    }

    private fun showToolSheet(title: String) {
        sheetTitle.text = title
        toolSheet.visibility = View.VISIBLE
    }

    private fun hideToolSheet() { toolSheet.visibility = View.GONE }

    private fun gitActions() { updateStatusLine("Git：${project?.name ?: "未选择项目"}") }

    private fun kotlinKeywords() = listOf("abstract","as","break","class","continue","do","else","enum","final","for","fun","if","import","in","interface","is","null","object","open","override","private","protected","public","return","sealed","super","this","throw","true","false","try","typealias","val","var","when","while","package","data","companion")
    private fun javaKeywords() = listOf("abstract","assert","boolean","break","byte","case","catch","char","class","const","continue","default","do","double","else","enum","extends","final","finally","float","for","if","implements","import","instanceof","int","interface","long","native","new","package","private","protected","public","return","short","static","super","switch","synchronized","this","throw","throws","try","void","volatile","while")
    private fun xmlKeywords() = listOf("android:layout_width","android:layout_height","android:text","android:id","android:orientation","android:padding","android:background","android:gravity","android:layout_gravity","android:visibility","android:src","xmlns:android","xmlns:app","android:textSize","android:textColor","android:inputType","android:hint")
    private fun gradleKeywords() = listOf("plugins","id","version","apply","plugin","android","compileSdk","defaultConfig","minSdk","targetSdk","versionCode","versionName","buildTypes","debug","release","dependencies","implementation","api","compileOnly","runtimeOnly","testImplementation","repositories","mavenCentral","google","maven","url")
    private fun dartKeywords() = listOf("abstract","as","assert","async","await","break","case","catch","class","const","continue","default","do","else","enum","extends","extension","factory","false","final","finally","for","Function","if","implements","import","in","interface","is","late","new","null","on","operator","part","required","rethrow","return","static","super","switch","sync","this","throw","true","try","typedef","var","void","while","with","yield")

    private val sourceExt = setOf("kt","kts","java","dart","xml","gradle","properties","yaml","yml","json","md","txt","js","ts","html","css","c","h","cpp","py")
}
