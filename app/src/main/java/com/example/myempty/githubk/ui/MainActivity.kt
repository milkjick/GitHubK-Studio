package com.example.myempty.githubk.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.net.Uri
import android.app.PendingIntent
import android.os.Build
import java.util.concurrent.atomic.AtomicInteger
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.app.Activity
import com.example.myempty.githubk.R
import com.example.myempty.githubk.core.Project
import com.example.myempty.githubk.git.GitRepo
import com.example.myempty.githubk.terminal.ShizukuShell
import com.example.myempty.githubk.shizuku.ShizukuBridge

/**
 * PageHost：页面与 Activity 之间的回调契约。
 */
interface PageHost {
    val context: Context
    val state: AppState
    fun switchTab(index: Int)
    fun openEditor(project: Project)
    /** 全屏打开一个页面（入栈，back 返回）。 */
    fun pushPage(view: View)
    /** 关闭最上层全屏页面。 */
    fun popPage()
    fun openRepo(repo: GitRepo)
    fun openTerminal(dir: java.io.File? = null)
    /** 打开内置终端并自动执行初始命令（如环境安装脚本，不跳外部 Termux）。 */
    fun openTerminalRun(dir: java.io.File?, initialCommand: String?)
    /** 通过 Termux RUN_COMMAND 后台执行命令，输出回传到 onTermuxOutput（应用内置终端显示，不跳转 Termux UI）。 */
    fun runTermuxCommand(command: String)
    fun pickFile(onResult: (Uri?) -> Unit)
    fun pickFolder(onResult: (Uri?) -> Unit)
    /** 终端输出接收器：由内置终端页注册，接收 RUN_COMMAND 回传输出。 */
    var termuxOutputSink: ((String) -> Unit)?
    fun toast(msg: String)
    fun runUi(block: () -> Unit)
    /** 主题切换后重建全部页面。 */
    fun rebuildAll()
    /** 只重建当前全屏页（若页面实现 RefreshablePage则就地刷新），不回首页。 */
    fun rebuildCurrentPage()
}

/** 支持就地重建的全屏页。 */
interface RefreshablePage { fun refresh() }

/**
 * MainActivity v5：GitHub 官方客户端风格。
 * - 顶部 MD3 风格标题栏（当前页标题）
 * - 底部 4 Tab：首页 / 仓库 / AI / 设置
 * - 新建/打开项目后直接进入 EditorPage，不再把 IDE 作为全局底栏页面
 * - 终端、编辑器、仓库详情以全屏页压栈打开
 */
class MainActivity : Activity(), PageHost {

    override val context: Context get() = this
    override lateinit var state: AppState

    private lateinit var container: FrameLayout
    private lateinit var bottomNav: LinearLayout
    private lateinit var navBarWrap: LinearLayout
    private lateinit var topTitle: TextView
    private val pages = mutableListOf<View>()
    private val navButtons = mutableListOf<LinearLayout>()
    private var currentTab = 0
    private val overlayStack = mutableListOf<View>()
    private val tabDefs = listOf(
        Triple("首页", R.drawable.ic_home, 0),
        Triple("工作区", R.drawable.ic_folder, 1),
        Triple("AI", R.drawable.ic_ai, 2),
        Triple("设置", R.drawable.ic_settings, 3)
    )

    /** 内置终端输出接收器（Termux RUN_COMMAND 回传输出）。 */
    override var termuxOutputSink: ((String) -> Unit)? = null
    private val termuxExecutionId = AtomicInteger(1000)
    private companion object {
        const val REQ_FILE = 4101
        const val REQ_FOLDER = 4102
    }
    private var pendingFilePick: ((Uri?) -> Unit)? = null
    private var pendingFolderPick: ((Uri?) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = AppState(this)
        state.loadTheme()
        setContentView(R.layout.activity_main)
        container = findViewById(R.id.container)
        bottomNav = findViewById(R.id.bottom_nav)
        navBarWrap = bottomNav.parent as LinearLayout
        topTitle = findViewById(R.id.top_title)
        applyThemeColors()
        buildTopBar()
        buildBottomNav()
        buildPages()
        switchTab(0)
        // 首次启动引导已移除（用户可在设置中手动触发「重新显示首次引导」）
        // 如果用户之前未标记 first_launch，这里直接标记为已跳过，不再自动弹窗
        if (state.secure.get("first_launch").isNullOrBlank()) {
            state.secure.put("first_launch", "1")
        }

        // 远程/自动化入口：am start ... --es autoStartTask offline-full
        // 直接打开 IDE 环境中心并自动开始内置离线安装（无需手动点击）。
        val autoTask = intent?.getStringExtra("autoStartTask")
        if (!autoTask.isNullOrBlank()) {
            container.post {
                ToolchainPage.autoStartTask = autoTask
                pushPage(ToolchainPage(this).buildView())
            }
        }

        // Shizuku 不再作为硬编译依赖。终端会自动使用内置 bash / system sh。
        // 这样在 AIDE/Termux 环境中不会因为 Shizuku AAR 的资源转换失败而阻断整个 APK 构建。
        refreshShizukuTitle()
        // Shizuku may have been restarted while the activity was not alive.
        // Recover the Binder immediately instead of waiting for a manual click.
        ShizukuBridge.ensureBinder(this)
    }

    override fun onResume() {
        super.onResume()
        ShizukuBridge.ensureBinder(this)
        window.decorView.postDelayed({
            ShizukuBridge.refreshPermission()
            refreshShizukuTitle()
        }, 350L)
        // Second attempt after 2s for slow Shizuku startup
        window.decorView.postDelayed({
            if (!ShizukuBridge.isReady()) {
                ShizukuBridge.ensureBinder(this)
                ShizukuBridge.refreshPermission()
                refreshShizukuTitle()
            }
        }, 2000L)
    }

    private fun refreshShizukuTitle() {
        topTitle.text = if (ShizukuShell.isServiceAvailable() && ShizukuShell.hasPermission()) {
            "GitHubK Studio ⚡"
        } else {
            "GitHubK Studio"
        }
    }

    private fun applyThemeColors() {
        val c = ThemeManager.colors
        window?.decorView?.setBackgroundColor(c.surface)
        bottomNav.setBackgroundColor(c.surface)
        navBarWrap.setBackgroundColor(c.surface)
        window.statusBarColor = c.surface
        window.navigationBarColor = c.surface
        val lightBars = ThemeManager.current in setOf(ThemeManager.ThemeType.LIGHT, ThemeManager.ThemeType.OCEAN, ThemeManager.ThemeType.FOREST, ThemeManager.ThemeType.SUNSET)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            var flags = window.decorView.systemUiVisibility
            flags = if (lightBars) flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    else flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = if (lightBars) flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                        else flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
            window.decorView.systemUiVisibility = flags
        }
    }

    private fun buildTopBar() {
        val bar = findViewById<LinearLayout>(R.id.top_bar)
        bar.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 8), UiKit.dp(this, 16), UiKit.dp(this, 8))
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(ThemeManager.colors.surface)
        // top_title 已在 XML 中作为 top_bar 的子视图，只更新属性，不重复 addView
        topTitle.text = "GitHubK Studio"
        topTitle.setTextColor(ThemeManager.colors.onSurface)
        topTitle.typeface = android.graphics.Typeface.DEFAULT_BOLD
        topTitle.textSize = 18f
        // 右上角不再显示“已登录/未登录”状态方块。
        while (bar.childCount > 1) bar.removeViewAt(bar.childCount - 1)
        bar.addView(TextView(this).apply {
            text = " v5.0.0"
            textSize = 11f
            setTextColor(ThemeManager.colors.muted)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
    }

    private fun buildBottomNav() {
        tabDefs.forEach { (label, icon, idx) ->
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, UiKit.dp(this@MainActivity, 54), 1f)
                setPadding(UiKit.dp(this@MainActivity, 2), UiKit.dp(this@MainActivity, 4), UiKit.dp(this@MainActivity, 2), UiKit.dp(this@MainActivity, 2))
                setOnClickListener { switchTab(idx) }
            }
            val iv = ImageView(this).apply {
                setImageResource(icon)
                layoutParams = LinearLayout.LayoutParams(UiKit.dp(this@MainActivity, 22), UiKit.dp(this@MainActivity, 22))
            }
            val tv = TextView(this).apply {
                text = label
                textSize = 10.5f
                gravity = Gravity.CENTER
                setTextColor(ThemeManager.colors.muted)
            }
            tab.addView(iv)
            tab.addView(tv)
            tab.background = UiKit.rounded(this@MainActivity, ThemeManager.colors.surface, 18)
            bottomNav.addView(tab)
            navButtons.add(tab)
        }
    }

    private fun buildPages() {
        pages.add(HomePage(this).buildView())       // 0 首页
        pages.add(WorkspacePage(this).buildView()) // 1 工作区
        pages.add(AgentPage(this).buildView())      // 2 AI
        pages.add(SettingsPage(this).buildView())   // 3 设置
    }

    override fun switchTab(index: Int) {
        if (index !in pages.indices) return
        currentTab = index
        while (overlayStack.isNotEmpty()) overlayStack.removeAt(overlayStack.size - 1)
        showView(pages[index])
        bottomNav.visibility = View.VISIBLE
        val titles = listOf("首页", "工作区", "AI 助手", "设置")
        topTitle.text = titles.getOrElse(index) { "GitHubK Studio" }
        navButtons.forEachIndexed { i, tab ->
            val selected = i == currentTab
            val iv = (tab.getChildAt(0) as? ImageView)
            val tv = (tab.getChildAt(1) as? TextView)
            tab.background = UiKit.rounded(this, if (selected) ThemeManager.colors.surfaceAlt else ThemeManager.colors.surface, 18)
            if (selected) {
                iv?.colorFilter = android.graphics.PorterDuffColorFilter(ThemeManager.colors.primary, android.graphics.PorterDuff.Mode.SRC_IN)
                tv?.setTextColor(ThemeManager.colors.primary)
                tv?.typeface = android.graphics.Typeface.DEFAULT_BOLD
            } else {
                iv?.colorFilter = android.graphics.PorterDuffColorFilter(ThemeManager.colors.muted, android.graphics.PorterDuff.Mode.SRC_IN)
                tv?.setTextColor(ThemeManager.colors.muted)
                tv?.typeface = android.graphics.Typeface.DEFAULT
            }
        }
    }

    override fun pushPage(view: View) {
        overlayStack.add(view)
        showView(view)
        // 全屏页（IDE/终端/仓库）隐藏底部导航栏，实现真正全屏
        bottomNav.visibility = View.GONE
    }

    override fun popPage() {
        if (overlayStack.isEmpty()) { switchTab(currentTab); return }
        overlayStack.removeAt(overlayStack.size - 1)
        if (overlayStack.isNotEmpty()) {
            showView(overlayStack.last())
            bottomNav.visibility = View.GONE
        } else {
            showView(pages[currentTab])
            bottomNav.visibility = View.VISIBLE
        }
    }

    private fun showView(v: View) {
        container.removeAllViews()
        container.addView(v)
    }

    override fun openEditor(project: Project) {
        val page = EditorPage(this)
        val view = page.buildView()   // 先构建视图，确保 lateinit 初始化
        page.setProject(project)
        pushPage(view)
    }

    override fun openRepo(repo: GitRepo) {
        pushPage(RepoBrowser(this, repo).buildView())
    }

    override fun openTerminal(dir: java.io.File?) {
        pushPage(TerminalPage(this, dir).buildView())
    }

    override fun openTerminalRun(dir: java.io.File?, initialCommand: String?) {
        pushPage(TerminalPage(this, dir, initialCommand).buildView())
    }

    /** 通过 Termux RUN_COMMAND 静默执行命令，输出回传应用内置终端（不跳转 Termux 界面）。 */
    override fun pickFile(onResult: (Uri?) -> Unit) {
        pendingFilePick = onResult
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, REQ_FILE)
    }

    override fun pickFolder(onResult: (Uri?) -> Unit) {
        pendingFolderPick = onResult
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQ_FOLDER)
    }

    @Deprecated("Use Activity Result API in new projects; kept intentionally for AIDE/offline compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = if (resultCode == RESULT_OK) data?.data else null
        when (requestCode) {
            REQ_FILE -> { pendingFilePick?.invoke(uri); pendingFilePick = null }
            REQ_FOLDER -> {
                if (uri != null) {
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    } catch (_: Throwable) {}
                }
                pendingFolderPick?.invoke(uri); pendingFolderPick = null
            }
        }
    }

    override fun runTermuxCommand(command: String) {
        try {
            val id = termuxExecutionId.incrementAndGet()
            val resultIntent = Intent(this, TermuxResultService::class.java).apply {
                putExtra("execution_id", id)
            }
            val flags = PendingIntent.FLAG_ONE_SHOT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getService(this, id, resultIntent, flags)
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending)
                putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "GitHubK Studio")
                putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION", "GitHubK Studio 后台编译环境安装")
            }
            startService(intent)
            toast("已提交到 Termux 后台执行")
        } catch (e: android.content.ActivityNotFoundException) {
            termuxOutputSink?.invoke("\n[Termux] 未找到 RUN_COMMAND 服务。请确认已安装 Termux，并允许本应用执行 Termux 命令。\n")
            toast("需要 Termux RUN_COMMAND 权限")
        } catch (e: Throwable) {
            termuxOutputSink?.invoke("\n[Termux] 执行不可用：${e.message}\n")
            toast("Termux 执行不可用：${e.message}")
        }
    }

    override fun toast(msg: String) = UiKit.toast(this, msg)

    override fun runUi(block: () -> Unit) = runOnUiThread(block)

    /** 就地刷新当前页：优先刷新顶部全屏页，其次刷新当前 Tab 页；均不支持时回退到全局重建。 */
    override fun rebuildCurrentPage() {
        if (overlayStack.isNotEmpty()) {
            val top = overlayStack.last()
            val rp = top.tag as? RefreshablePage
            if (rp != null) { rp.refresh(); return }
            rebuildAll(); return
        }
        val page = pages.getOrNull(currentTab)
        val rp = page?.tag as? RefreshablePage
        if (rp != null) { rp.refresh(); return }
        rebuildAll()
    }

    /** 主题切换后重建全部页面，保持当前 Tab。 */
    override fun rebuildAll() {
        val tab = currentTab
        applyThemeColors()
        buildTopBar()
        bottomNav.removeAllViews()
        navButtons.clear()
        buildBottomNav()
        pages.clear()
        buildPages()
        overlayStack.clear()
        switchTab(tab)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (overlayStack.isNotEmpty()) {
                popPage()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
