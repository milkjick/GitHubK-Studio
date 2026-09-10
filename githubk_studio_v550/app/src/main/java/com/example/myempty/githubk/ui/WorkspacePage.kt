package com.example.myempty.githubk.ui

import com.example.myempty.githubk.apk.ApkManager
import android.app.AlertDialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.example.myempty.githubk.R
import com.example.myempty.githubk.core.Project
import com.example.myempty.githubk.core.ProjectTemplate
import com.example.myempty.githubk.git.GitRepo
import java.io.File
import android.widget.HorizontalScrollView
import com.example.myempty.githubk.io.LocalFileExporter

/**
 * GitHubK Studio 4.5 Workspace。
 *
 * 目标：把工作区做成真正的移动 IDE 项目中心：
 * - 项目搜索 / 刷新 / 新建 / 导入
 * - 打开 IDE、终端、构建、APK
 * - 删除已经完成的项目（带二次确认，且 WorkspaceManager 做根目录保护）
 * - 一键把整个项目发布到 GitHub，可新建仓库或选择已有仓库
 * - 发布时自动排除 .git / build / .gradle / .idea 等本地构建数据
 */
class WorkspacePage(private val host: PageHost) {

    private val state = host.state
    private lateinit var listBox: LinearLayout
    private lateinit var searchInput: EditText
    private var allProjects: List<Project> = emptyList()

    fun buildView(): View {
        val root = UiKit.vstack(host.context).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(header())
        root.addView(toolbar())
        root.addView(UiKit.spacer(host.context, 8))
        root.addView(UiKit.hstack(host.context).apply {
            searchInput = UiKit.input(host.context, "搜索工作区项目")
            searchInput.setSingleLine(true)
            searchInput.setOnEditorActionListener { _, _, _ -> refresh(); true }
            searchInput.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderFiltered() }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
            addView(searchInput, LinearLayout.LayoutParams(0, -2, 1f))
            addView(UiKit.spacer(host.context, 6))
            addView(UiKit.iconButton(host.context, R.drawable.ic_refresh, "刷新") { refresh() })
        })
        root.addView(UiKit.spacer(host.context, 8))

        val scroll = ScrollView(host.context).apply { isFillViewport = true }
        listBox = UiKit.vstack(host.context)
        scroll.addView(listBox)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        refresh()
        return root
    }

    private fun header(): View = UiKit.hstack(host.context).apply {
        addView(UiKit.vstack(host.context).apply {
            addView(UiKit.title(host.context, "工作区", 22f))
            addView(UiKit.label(host.context, "项目 · 文件 · 构建 · GitHub 发布", color = R.color.muted, size = 11.5f))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(UiKit.iconButton(host.context, R.drawable.ic_code, "新建项目") {
            host.pushPage(NewProjectPage(host).buildView())
        })
        addView(UiKit.spacer(host.context, 4))
        addView(UiKit.iconButton(host.context, R.drawable.ic_folder, "打开工作区") {
            host.toast("工作区：${state.workspace.rootDir().absolutePath}")
        })
    }

    private fun toolbar(): View {
        val row = UiKit.hstack(host.context).apply {
            addView(UiKit.button(host.context, "＋ 新建") { host.pushPage(NewProjectPage(host).buildView()) })
            addView(UiKit.spacer(host.context, 6))
            addView(UiKit.ghostButton(host.context, "GitHub 仓库") { host.pushPage(ReposPage(host).buildView()) })
            addView(UiKit.spacer(host.context, 6))
            addView(UiKit.ghostButton(host.context, "导入 ZIP/文件") {
                host.pickFile { uri ->
                    if (uri == null) return@pickFile
                    host.toast("正在导入…")
                    Thread {
                        state.workspace.importFile(uri,
                            { host.runUi { host.toast("导入完成"); refresh() } },
                            { e -> host.runUi { host.toast("导入失败：${e.message ?: "未知错误"}") } }
                        )
                    }.start()
                }
            })
            addView(UiKit.spacer(host.context, 6))
            addView(UiKit.ghostButton(host.context, "导入文件夹") {
                host.pickFolder { uri ->
                    if (uri == null) return@pickFolder
                    host.toast("正在读取文件夹…")
                    Thread {
                        state.workspace.importTree(uri,
                            { host.runUi { host.toast("文件夹导入完成"); refresh() } },
                            { e -> host.runUi { host.toast("文件夹导入失败：${e.message ?: "未知错误"}") } }
                        )
                    }.start()
                }
            })
        }
        return HorizontalScrollView(host.context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    fun refresh() {
        allProjects = state.workspace.projects().sortedBy { it.name.lowercase() }
        renderFiltered()
    }

    private fun renderFiltered() {
        if (!::listBox.isInitialized) return
        listBox.removeAllViews()
        val q = if (::searchInput.isInitialized) searchInput.text.toString().trim() else ""
        val projects = if (q.isBlank()) allProjects else allProjects.filter {
            it.name.contains(q, true) || it.type.contains(q, true)
        }
        if (projects.isEmpty()) {
            listBox.addView(UiKit.card(host.context, UiKit.vstack(host.context).apply {
                addView(UiKit.title(host.context, if (allProjects.isEmpty()) "工作区是空的" else "没有匹配项目", 16f))
                addView(UiKit.spacer(host.context, 5))
                addView(UiKit.label(host.context,
                    if (allProjects.isEmpty()) "新建或导入一个项目后，这里会成为你的移动 IDE 工作区。" else "尝试搜索项目名称或类型。",
                    color = R.color.muted, size = 12f))
                if (allProjects.isEmpty()) {
                    addView(UiKit.spacer(host.context, 8))
                    addView(UiKit.hstack(host.context).apply {
                        addView(UiKit.button(host.context, "＋ 新建项目") { host.pushPage(NewProjectPage(host).buildView()) })
                        addView(UiKit.spacer(host.context, 5))
                        addView(UiKit.ghostButton(host.context, "导入项目(ZIP/文件夹)") {
                            host.pickFile { uri ->
                                if (uri == null) return@pickFile
                                host.toast("正在导入…")
                                Thread {
                                    state.workspace.importFile(uri,
                                        { host.runUi { host.toast("导入完成"); refresh() } },
                                        { e -> host.runUi { host.toast("导入失败：${e.message ?: "未知错误"}") } }
                                    )
                                }.start()
                            }
                        })
                    })
                }
            }))
            return
        }
        listBox.addView(UiKit.label(host.context, "${projects.size} 个项目", color = R.color.muted, size = 11f))
        listBox.addView(UiKit.spacer(host.context, 5))
        projects.forEach { p ->
            listBox.addView(projectCard(p))
            listBox.addView(UiKit.spacer(host.context, 8))
        }
    }

    private fun projectCard(p: Project): View {
        val content = UiKit.vstack(host.context)
        val title = UiKit.hstack(host.context).apply {
            addView(UiKit.avatar(host.context, p.name, if (p.type == "Flutter") R.color.accent else R.color.primary))
            addView(UiKit.spacer(host.context, 9))
            addView(UiKit.vstack(host.context).apply {
                addView(UiKit.title(host.context, p.name, 16f))
                addView(UiKit.label(host.context, p.type, color = R.color.accent, size = 11f))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(UiKit.ghostButton(host.context, "更多") { projectMenu(p) })
        }
        content.addView(title)
        content.addView(UiKit.spacer(host.context, 5))
        content.addView(UiKit.label(host.context, p.path.absolutePath, color = R.color.muted, size = 10.5f).apply { maxLines = 1 })
        content.addView(UiKit.spacer(host.context, 7))

        content.addView(UiKit.hstack(host.context).apply {
            addView(UiKit.button(host.context, "打开 IDE") { host.openEditor(p) })
            addView(UiKit.spacer(host.context, 5))
            addView(UiKit.ghostButton(host.context, "构建") { build(p) })
            addView(UiKit.spacer(host.context, 5))
            addView(UiKit.ghostButton(host.context, "终端") { host.openTerminal(p.path) })
            addView(UiKit.spacer(host.context, 5))
            addView(UiKit.ghostButton(host.context, "GitHub") { publishProject(p) })
        })
        content.addView(UiKit.spacer(host.context, 5))
        content.addView(UiKit.hstack(host.context).apply {
            addView(UiKit.ghostButton(host.context, "APK") { showApks(p) })
            addView(UiKit.spacer(host.context, 5))
            addView(UiKit.ghostButton(host.context, "下载") { exportProjectToLocal(p) })
            addView(UiKit.spacer(host.context, 5))
            addView(UiKit.ghostButton(host.context, "上传") { uploadProjectFile(p) })
            addView(UiKit.spacer(host.context, 5))
            addView(UiKit.ghostButton(host.context, "删除项目") { confirmDelete(p) })
        })
        return UiKit.card(host.context, content)
    }

    /**
     * 项目"更多"操作菜单 v2：底部卡片式分组菜单。
     * - 顶部项目名 + 路径 + 关闭
     * - 按「常用 / 构建产物 / 分享发布 / 危险操作」分组，图标 + 主副标题 + 波纹反馈
     */
    private fun projectMenu(p: Project) {
        val c = host.context
        val tc = ThemeManager.colors
        val dlg = android.app.Dialog(c)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val sheet = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.rounded(c, tc.surfaceElevated, 20)
        }
        // ---- 头部 ----
        sheet.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(10), dp(8))
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                addView(TextView(c).apply {
                    text = p.name
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(tc.onSurface)
                })
                addView(TextView(c).apply {
                    text = "${p.type}   ${p.path.absolutePath}"
                    textSize = 11f
                    setTextColor(tc.muted)
                    maxLines = 1
                })
            })
            addView(TextView(c).apply {
                text = "✕"
                textSize = 16f
                setTextColor(tc.muted)
                setPadding(dp(12), dp(8), dp(10), dp(8))
                setOnClickListener { dlg.dismiss() }
            })
        })
        // ---- 可滚动主体 ----
        val scroll = ScrollView(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, (c.resources.displayMetrics.heightPixels * 0.7).toInt())
        }
        val body = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(14))
        }
        scroll.addView(body)
        sheet.addView(scroll)
        dlg.setContentView(sheet)
        val win = dlg.window
        if (win != null) {
            win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            win.setLayout((c.resources.displayMetrics.widthPixels * 0.96).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            win.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        }

        // ---- 分组渲染 ----
        fun groupHeader(text: String) {
            body.addView(TextView(c).apply {
                this.text = text
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(tc.accent)
                setPadding(dp(4), dp(4), 0, dp(2))
            })
        }

        fun section(header: String, buildRows: LinearLayout.() -> Unit) {
            groupHeader(header)
            val card = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                background = UiKit.rounded(c, tc.surface, 14, tc.divider)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                buildRows()
            }
            body.addView(card)
            body.addView(UiKit.spacer(c, 10))
        }

        fun rowItem(container: LinearLayout, icon: String, title: String, sub: String, danger: Boolean = false, onClick: () -> Unit) {
            val mask = UiKit.rounded(c, android.graphics.Color.WHITE, 10)
            val ripple = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(
                    if (danger) 0x22FF5252.toInt() else 0x22FFFFFF.toInt()
                ), null, mask)
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ripple
                setPadding(dp(12), dp(11), dp(12), dp(11))
                setOnClickListener { dlg.dismiss(); onClick() }
            }
            row.addView(TextView(c).apply {
                text = icon
                textSize = 20f
                setPadding(0, 0, dp(12), 0)
            })
            row.addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                addView(TextView(c).apply {
                    text = title
                    textSize = 14.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (danger) 0xFFFF6B6B.toInt() else tc.onSurface)
                })
                if (sub.isNotBlank()) addView(TextView(c).apply {
                    text = sub
                    textSize = 11.5f
                    setTextColor(tc.muted)
                    maxLines = 1
                })
            })
            row.addView(TextView(c).apply {
                text = "›"
                textSize = 20f
                setTextColor(tc.muted)
            })
            container.addView(row)
        }

        section("常用") {
            rowItem(this, "🧭", "打开 IDE", "代码编辑器、构建与运行") { host.openEditor(p) }
            rowItem(this, "🖥️", "打开终端", "在项目目录打开 PTY 终端") { host.openTerminal(p.path) }
        }
        section("构建与产物") {
            rowItem(this, "🔨", "Debug 构建", if (p.type == "Flutter") "flutter build apk --debug" else "gradle assembleDebug") { build(p, "assembleDebug") }
            rowItem(this, "📦", "Release 构建", if (p.type == "Flutter") "flutter build apk --release" else "gradle assembleRelease") { build(p, "assembleRelease") }
            rowItem(this, "📱", "查看 APK", "查看已构建的 APK 并下载/分享") { showApks(p) }
        }
        section("分享与发布") {
            rowItem(this, "⬇️", "下载源码", "打包为 ZIP 保存到手机本地") { exportProjectToLocal(p) }
            rowItem(this, "⬆️", "上传文件到 GitHub", "把项目文件上传到已有仓库") { uploadProjectFile(p) }
            rowItem(this, "📤", "发布到 GitHub", "新建/选择仓库并上传全部源码") { publishProject(p) }
            rowItem(this, "🚀", "发布 Release", "为仓库创建 GitHub Release") { publishRelease(p) }
        }
        section("危险操作") {
            rowItem(this, "🗑️", "删除项目", "永久删除项目全部文件（不可恢复）", danger = true) { confirmDelete(p) }
        }
        dlg.show()
    }

    private fun confirmDelete(p: Project) {
        UiKit.confirm(host.context, "删除项目", "确认删除“${p.name}”及其全部文件？\n\n此操作不可恢复。") {
            if (state.workspace.deleteProject(p)) {
                host.toast("已删除：${p.name}")
                refresh()
            } else {
                host.toast("删除失败：项目路径不在工作区内或文件被占用")
            }
        }
    }

    /** 把工作区项目源码打包为 ZIP，并导出下载到手机本地 Download 目录。 */
    private fun exportProjectToLocal(p: Project) {
        val c = host.context
        val options = listOf(
            UiKit.SheetItem("📄", "仅源码", "不含 build 产物，体积更小，适合分享代码。", R.color.primary) {
                exportProjectZip(p, includeBuild = false)
            },
            UiKit.SheetItem("📦", "包含 build 产物", "体积更大，适合完整归档。", R.color.accent) {
                exportProjectZip(p, includeBuild = true)
            }
        )
        UiKit.groupedSheet(c, "下载 ${p.name} 到本地", "选择要打包进 ZIP 的内容范围", listOf("" to options))
    }

    /** 实际打包并导出；导出成功后自动弹出系统分享。 */
    private fun exportProjectZip(p: Project, includeBuild: Boolean) {
        host.toast("正在打包 ${p.name} …")
        Thread {
            val zip = runCatching { state.workspace.packageSource(p, includeBuild) }.getOrNull()
            if (zip == null || !zip.exists()) {
                host.runUi { host.toast("打包失败：${p.name}") }
                return@Thread
            }
            val label = if (includeBuild) "${p.name}_full_${System.currentTimeMillis()}.zip" else "${p.name}_source_${System.currentTimeMillis()}.zip"
            val (ok, where) = LocalFileExporter.exportToDownloads(host.context, zip, label)
            val mgr = ApkManager(host.context)
            host.runUi {
                host.toast(if (ok) "已下载到本地：$where" else "下载失败：$where")
                if (ok) {
                    runCatching { mgr.shareFile(zip, "application/zip", "分享 ${p.name}（${if (includeBuild) "含 build 产物" else "源码"}）") }
                }
                android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed({ runCatching { zip.delete() } }, 60000)
            }
        }.start()
    }

    private fun build(p: Project, task: String = if (p.type == "Flutter") "build apk --debug" else "assembleDebug") {
        state.buildManager.submit(
            project = p,
            task = task,
            sdkHome = state.sdkHome(),
            javaHome = state.javaHome(),
            gradleHome = state.gradleHome(),
            flutterBin = state.flutterBin()
        ) { result: com.example.myempty.githubk.buildsys.BuildResult ->
            host.runUi { host.toast(if (result.success) "${p.name} · $task 构建成功" else "${p.name} · $task 构建失败") }
        }
        host.toast("已加入构建队列：${p.name} · $task")
    }

    private fun showApks(p: Project) {
        val files = p.path.walkTopDown()
            .filter { it.isFile && it.extension.equals("apk", true) }
            .sortedByDescending { it.lastModified() }
            .take(20).toList()
        val box = UiKit.vstack(host.context).apply { setPadding(dp(18), dp(8), dp(18), dp(8)) }
        if (files.isEmpty()) box.addView(UiKit.label(host.context, "暂无 APK。先执行一次 Debug/Release 构建。", color = R.color.muted))
        files.forEach { f ->
            box.addView(UiKit.label(host.context, "${f.name}\n${f.absolutePath}", color = R.color.on_surface, size = 11f))
            box.addView(UiKit.spacer(host.context, 5))
        }
        UiKit.dialog(host.context, "APK 输出", box)
    }

    /** 上传 APK 或任意本地文件到个人 GitHub 仓库。 */
    private fun uploadProjectFile(p: Project) {
        if (!state.hasToken()) {
            host.toast("请先在设置中配置 GitHub Token")
            return
        }
        host.toast("正在读取 GitHub 仓库…")
        Thread {
            val repos = runCatching { state.gitHub.myRepos() }.getOrElse { emptyList() }
            host.runUi {
                if (repos.isEmpty()) {
                    host.toast("暂无可用仓库，请先到 GitHub 仓库页创建/接入")
                    return@runUi
                }
                val apks = p.path.walkTopDown()
                    .filter { it.isFile && it.extension.equals("apk", true) }
                    .sortedByDescending { it.lastModified() }
                    .take(30).toList()
                GitHubUploadHelper.chooseRepo(
                    host,
                    "上传 ${p.name} 的文件",
                    repos,
                    onCreate = null
                ) { repo -> GitHubUploadHelper.uploadOneFile(host, repo, apks) }
            }
        }.start()
    }

    /**
     * 整个项目直接发布到 GitHub，不再要求用户重新选择文件/文件夹。
     * 使用 Contents API，适合没有本地 git 命令的 AIDE/移动环境。
     */
    /** 发布 Release：拉取账号仓库后选择目标仓库，创建 GitHub Release 并上传 APK。 */
    private fun publishRelease(p: Project) {
        if (!state.hasToken()) {
            host.toast("请先在设置中配置 GitHub Token")
            return
        }
        host.toast("正在读取 GitHub 仓库…")
        Thread {
            val repos = runCatching { state.gitHub.myRepos() }.getOrElse { emptyList() }
            host.runUi {
                if (repos.isEmpty()) {
                    host.toast("暂无可用仓库，请先到 GitHub 仓库页创建/接入")
                    return@runUi
                }
                val apks = p.path.walkTopDown()
                    .filter { it.isFile && it.extension.equals("apk", true) }
                    .sortedByDescending { it.lastModified() }
                    .take(30).toList()
                GitHubUploadHelper.chooseRepo(
                    host,
                    "发布 ${p.name} 的 Release",
                    repos,
                    onCreate = null
                ) { repo -> GitHubUploadHelper.showCreateReleaseDialog(host, repo, apks) }
            }
        }.start()
    }

    private fun publishProject(p: Project) {
        if (!state.hasToken()) {
            host.toast("请先在设置中配置 GitHub Token")
            return
        }
        host.toast("正在读取 GitHub 仓库…")
        Thread {
            val repos = runCatching { state.gitHub.myRepos() }.getOrElse { emptyList() }
            host.runUi { showPublishPicker(p, repos) }
        }.start()
    }

    /**
     * 发布目标选择弹窗（自定义样式，替代旧版系统 AlertDialog 文本列表）。
     * 首行为“新建 GitHub 仓库”，下面为账号已有仓库。
     */
    private fun showPublishPicker(p: Project, repos: List<GitRepo>) {
        val holder = arrayOfNulls<android.app.Dialog>(1)
        val box = UiKit.vstack(host.context).apply { setPadding(0, 0, 0, 0) }
        box.addView(UiKit.label(host.context, "选择要发布到的 GitHub 仓库", color = R.color.muted, size = 12f))
        box.addView(UiKit.spacer(host.context, 8))
        box.addView(publishOptionRow("＋", "新建 GitHub 仓库", "以“${p.name}”为名创建并上传", accent = true) {
            holder[0]?.dismiss()
            createAndPublish(p)
        }, LinearLayout.LayoutParams(-1, -2))
        if (repos.isEmpty()) {
            box.addView(UiKit.spacer(host.context, 6))
            box.addView(UiKit.label(host.context, "当前账号还没有仓库，可直接使用上面的“新建 GitHub 仓库”。", color = R.color.muted, size = 11f))
        } else {
            repos.forEach { repo ->
                box.addView(UiKit.spacer(host.context, 6))
                box.addView(publishOptionRow(
                    if (repo.private) "🔒" else "🌐",
                    repo.fullName,
                    repo.description.ifBlank { "上传到 ${repo.defaultBranch.ifBlank { "main" }} 分支" },
                    accent = false
                ) {
                    holder[0]?.dismiss()
                    publishToRepo(p, repo)
                }, LinearLayout.LayoutParams(-1, -2))
            }
        }
        holder[0] = UiKit.dialog(host.context, "发布 ${p.name} 到 GitHub", box, onCancel = {})
    }

    /** 发布选择列表中的一行：图标 + 名称 + 副标题 + 右箭头。 */
    private fun publishOptionRow(icon: String, title: String, sub: String, accent: Boolean = false, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(host.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiKit.rounded(
                host.context,
                ThemeManager.colors.surface,
                12,
                if (accent) ThemeManager.colors.primary else ThemeManager.colors.divider,
                1
            )
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { onClick() }
        }
        row.addView(TextView(host.context).apply {
            text = icon
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(if (accent) android.graphics.Color.WHITE else ThemeManager.colors.onSurface)
            background = UiKit.rounded(host.context, if (accent) ThemeManager.colors.primary else ThemeManager.colors.surfaceAlt, 20)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
        })
        row.addView(UiKit.spacer(host.context, 10))
        row.addView(UiKit.vstack(host.context).apply {
            addView(TextView(host.context).apply {
                text = title
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (accent) ThemeManager.colors.primary else ThemeManager.colors.onSurface)
            })
            addView(TextView(host.context).apply {
                text = sub
                textSize = 11.5f
                maxLines = 2
                setTextColor(ThemeManager.colors.muted)
                setPadding(0, dp(1), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(host.context).apply {
            text = "›"
            textSize = 20f
            setTextColor(ThemeManager.colors.muted)
        })
        return row
    }

    private fun createAndPublish(p: Project) {
        val name = UiKit.input(host.context, "仓库名称").apply {
            setText(p.name)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val desc = UiKit.input(host.context, "仓库描述（可选）").apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        var privateRepo = true
        val box = UiKit.vstack(host.context).apply {
            setPadding(0, 0, 0, 0)
            addView(UiKit.label(host.context, "仓库名称（必填）", color = R.color.muted, size = 12f))
            addView(UiKit.spacer(host.context, 4))
            addView(name)
            addView(UiKit.spacer(host.context, 10))
            addView(UiKit.label(host.context, "仓库描述（可选）", color = R.color.muted, size = 12f))
            addView(UiKit.spacer(host.context, 4))
            addView(desc)
            addView(UiKit.spacer(host.context, 10))
            addView(android.widget.Switch(host.context).apply {
                text = "私有仓库"
                isChecked = true
                setOnCheckedChangeListener { _, checked -> privateRepo = checked }
            })
            addView(UiKit.spacer(host.context, 5))
            addView(UiKit.label(host.context, "创建后自动初始化 README，并立即上传项目文件。", color = R.color.muted, size = 11f))
        }
        UiKit.dialog(host.context, "新建 GitHub 仓库", box, onOk = {
            val n = name.text.toString().trim()
            if (n.isBlank()) { host.toast("仓库名称不能为空"); return@dialog }
            host.toast("正在创建仓库…")
            Thread {
                try {
                    val repo = state.gitHub.parseRepo(state.gitHub.createRepo(n, desc.text.toString().trim(), privateRepo))
                    if (repo == null) throw IllegalStateException("GitHub 返回的仓库信息无效")
                    publishToRepo(p, repo)
                } catch (e: Throwable) {
                    host.runUi { host.toast("创建仓库失败：${e.message ?: "未知错误"}") }
                }
            }.start()
        })
    }

    private fun publishToRepo(p: Project, repo: GitRepo) {
        val files = state.gitHub.collectUploadFiles(p.path)
        if (files.isEmpty()) { host.toast("项目没有可发布的源文件"); return }
        val progress = ProgressBar(host.context).apply { isIndeterminate = false; max = files.size; progress = 0 }
        val text = TextView(host.context).apply {
            text = "准备上传…"
            textSize = 12.5f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(4))
            setTextColor(ThemeManager.colors.onSurface)
        }
        val holder = arrayOfNulls<android.app.Dialog>(1)
        val box = UiKit.vstack(host.context).apply {
            setPadding(0, 0, 0, 0)
            addView(UiKit.hstack(host.context).apply {
                addView(UiKit.label(host.context, "目标仓库：", color = R.color.muted, size = 12f))
                addView(UiKit.label(host.context, repo.fullName, color = R.color.primary, size = 12.5f).apply {
                    typeface = Typeface.DEFAULT_BOLD
                })
            })
            addView(UiKit.spacer(host.context, 8))
            addView(text)
            addView(progress)
            addView(UiKit.spacer(host.context, 6))
            addView(UiKit.label(host.context, "自动排除 .git / build / .gradle / .idea · 可后台继续", color = R.color.muted, size = 11f))
            addView(UiKit.spacer(host.context, 10))
            addView(LinearLayout(host.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(UiKit.ghostButton(host.context, "后台继续") { holder[0]?.dismiss() })
            })
        }
        holder[0] = UiKit.dialog(host.context, "发布到 ${repo.fullName}", box)
        Thread {
            val result = runCatching {
                state.gitHub.uploadTree(repo.fullName, p.path, "", "Publish ${p.name} via GitHubK Studio", repo.defaultBranch) { done, total, path, ok ->
                    host.runUi {
                        progress.max = total
                        progress.progress = done
                        text.text = "${done}/${total}  ${if (ok) "✓" else "✗"}  ${path.take(72)}"
                    }
                }
            }
            host.runUi {
                holder[0]?.dismiss()
                result.onSuccess { uploaded ->
                    host.toast("GitHub 发布完成：$uploaded/${files.size} 个文件")
                }.onFailure { e -> host.toast("GitHub 发布失败：${e.message ?: "未知错误"}") }
            }
        }.start()
    }

    private fun dp(v: Int): Int = UiKit.dp(host.context, v)
}
