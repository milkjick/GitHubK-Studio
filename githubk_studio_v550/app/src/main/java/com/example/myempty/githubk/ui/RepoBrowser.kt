package com.example.myempty.githubk.ui

import java.io.File
import com.example.myempty.githubk.io.LocalFileExporter
import com.example.myempty.githubk.apk.ApkManager
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.myempty.githubk.R
import com.example.myempty.githubk.git.GitRepo
import com.example.myempty.githubk.git.GitRepoEntry

/**
 * RepoBrowser v5：GitHub 客户端风格仓库详情页（全屏）。
 * - 头部：头像 / 全名 / 描述 / 统计（★ 🍴 语言 分支）
 * - Tab：README / 源码（在线浏览，无需克隆）/ 分支 / Issues / 信息
 * - 克隆：ZIP 下载 + 解压（无 git 依赖，Android 可用）
 * - 全部信息应用内查看，不跳转浏览器
 */
class RepoBrowser(private val host: PageHost, private val repo: GitRepo) {

    private val api get() = host.state.gitHub
    private lateinit var body: LinearLayout
    private lateinit var readmeWeb: WebView
    private lateinit var sourceBox: LinearLayout
    private lateinit var branchBox: LinearLayout
    private lateinit var issueBox: LinearLayout
    private lateinit var infoBox: LinearLayout
    private var currentBranch: String = repo.defaultBranch
    private var starred = false

    fun buildView(): View {
        val root = UiKit.vstack(host.context)
        // 顶栏
        root.addView(UiKit.hstack(host.context).apply {
            setPadding(UiKit.dp(host.context, 14), UiKit.dp(host.context, 8), UiKit.dp(host.context, 14), UiKit.dp(host.context, 8))
            addView(UiKit.ghostButton(host.context, "← 返回") { host.popPage() })
            addView(UiKit.spacer(host.context, 8))
            addView(UiKit.label(host.context, repo.fullName, color = com.example.myempty.githubk.R.color.on_surface, size = 16f).apply {
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        })
        body = UiKit.vstack(host.context)
        root.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        buildBody()
        return root
    }

    private fun buildBody() {
        body.removeAllViews()
        val scroll = ScrollView(host.context).apply { isFillViewport = true }
        val content = UiKit.vstack(host.context).apply {
            setPadding(UiKit.dp(host.context, 14), 0, UiKit.dp(host.context, 14), UiKit.dp(host.context, 10))
        }

        // 头部卡
        content.addView(UiKit.card(host.context, UiKit.vstack(host.context).apply {
            addView(UiKit.hstack(host.context).apply {
                addView(UiKit.avatar(host.context, repo.owner, com.example.myempty.githubk.R.color.primary))
                addView(UiKit.spacer(host.context, 10))
                addView(UiKit.vstack(host.context).apply {
                    addView(TextView(host.context).apply {
                        text = repo.name
                        textSize = 18f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(ThemeManager.colors.onSurface)
                    })
                    addView(TextView(host.context).apply {
                        text = "@${repo.owner}"
                        textSize = 12.5f
                        setTextColor(ThemeManager.colors.muted)
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(UiKit.ghostButton(host.context, "Star") { toggleStar() }.apply { tag = "starBtn" })
                addView(UiKit.spacer(host.context, 6))
                addView(UiKit.iconButton(host.context, com.example.myempty.githubk.R.drawable.ic_download, "克隆") { cloneRepo() })
            })
            if (repo.description.isNotBlank()) {
                addView(TextView(host.context).apply {
                    text = repo.description
                    textSize = 13.5f
                    setTextColor(ThemeManager.colors.onSurface)
                    setPadding(0, UiKit.dp(host.context, 8), 0, 0)
                })
            }
            addView(UiKit.hstack(host.context).apply {
                setPadding(0, UiKit.dp(host.context, 10), 0, 0)
                addView(UiKit.stat(host.context, "★", "${repo.stars}"))
                addView(UiKit.spacer(host.context, 12))
                addView(UiKit.stat(host.context, "⑂", "${repo.forks}"))
                if (repo.language.isNotBlank()) {
                    addView(UiKit.spacer(host.context, 12))
                    addView(TextView(host.context).apply {
                        text = "● ${repo.language}"
                        textSize = 12.5f
                        setTextColor(ThemeManager.colors.accent)
                    })
                }
                addView(UiKit.spacer(host.context, 8))
                addView(UiKit.label(host.context, "默认分支 ${repo.defaultBranch}", color = com.example.myempty.githubk.R.color.muted, size = 12f).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.END }
                })
            })
        }))
        content.addView(UiKit.spacer(host.context, 10))

        // Tab 行
        val tabs = mutableListOf<Pair<String, () -> Unit>>()
        val tabRow = UiKit.hstack(host.context)
        fun addTab(label: String, selected: Boolean, act: () -> Unit) {
            val btn = UiKit.chip(host.context, label, selected) { act() }
            tabRow.addView(btn)
            tabRow.addView(UiKit.spacer(host.context, 6))
            tabs.add(label to act)
        }
        addTab("README", true) { showTab(0) }
        addTab("源码", false) { showTab(1) }
        addTab("分支", false) { showTab(2) }
        addTab("Issues", false) { showTab(3) }
        addTab("信息", false) { showTab(4) }
        content.addView(tabRow)
        content.addView(UiKit.spacer(host.context, 8))

        // 内容容器
        val tabContent = UiKit.vstack(host.context)
        content.addView(tabContent)

        // README WebView
        readmeWeb = WebView(host.context).apply {
            setBackgroundColor(ThemeManager.colors.surface)
            webViewClient = object : WebViewClient() {}
            settings?.javaScriptEnabled = false
            settings?.textZoom = 100
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(host.context, 320))
        }
        tabContent.addView(readmeWeb)

        // 源码浏览
        sourceBox = UiKit.vstack(host.context)
        tabContent.addView(sourceBox)
        sourceBox.visibility = View.GONE

        // 分支
        branchBox = UiKit.vstack(host.context)
        tabContent.addView(branchBox)
        branchBox.visibility = View.GONE

        // Issues
        issueBox = UiKit.vstack(host.context)
        tabContent.addView(issueBox)
        issueBox.visibility = View.GONE

        // 信息
        infoBox = UiKit.vstack(host.context)
        tabContent.addView(infoBox)
        infoBox.visibility = View.GONE

        body.addView(scroll)
        scroll.addView(content)

        // 加载数据
        loadReadme()
        loadBranches()
        loadIssues()
        loadInfo()
        checkStar()
    }

    private fun showTab(i: Int) {
        readmeWeb.visibility = if (i == 0) View.VISIBLE else View.GONE
        sourceBox.visibility = if (i == 1) View.VISIBLE else View.GONE
        branchBox.visibility = if (i == 2) View.VISIBLE else View.GONE
        issueBox.visibility = if (i == 3) View.VISIBLE else View.GONE
        infoBox.visibility = if (i == 4) View.VISIBLE else View.GONE
        if (i == 1 && sourceBox.childCount == 0) loadSource("")
        if (i == 3 && issueBox.childCount == 0) loadIssues()
    }

    // ---------- README ----------

    private fun loadReadme() {
        Thread {
            val md = api.readme(repo.fullName, currentBranch)
            host.runUi {
                readmeWeb.loadDataWithBaseURL("https://github.com/", MarkdownRenderer.wrapHtml(md, repo.fullName, true), "text/html", "UTF-8", null)
            }
        }.start()
    }

    // ---------- 在线源码浏览 ----------

    private fun loadSource(path: String) {
        sourceBox.removeAllViews()
        sourceBox.addView(UiKit.hstack(host.context).apply {
            addView(UiKit.label(host.context, "路径：/${path.ifBlank { "" }}", color = com.example.myempty.githubk.R.color.muted, size = 12f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (path.isNotBlank()) {
                addView(UiKit.ghostButton(host.context, "上级") { loadSource(path.substringBeforeLast('/', "")) })
            }
        })
        sourceBox.addView(UiKit.spacer(host.context, 6))
        sourceBox.addView(UiKit.label(host.context, "正在加载目录...", color = com.example.myempty.githubk.R.color.muted, size = 12f))
        Thread {
            val entries = try { api.contents(repo.fullName, path, currentBranch) } catch (_: Throwable) { emptyList() }
            host.runUi {
                sourceBox.removeAllViews()
                sourceBox.addView(UiKit.hstack(host.context).apply {
                    addView(UiKit.label(host.context, "路径：/${path.ifBlank { "" }}", color = com.example.myempty.githubk.R.color.muted, size = 12f).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    if (path.isNotBlank()) {
                        addView(UiKit.ghostButton(host.context, "上级") { loadSource(path.substringBeforeLast('/', "")) })
                    }
                })
                sourceBox.addView(UiKit.spacer(host.context, 6))
                if (entries.isEmpty()) {
                    sourceBox.addView(UiKit.label(host.context, "目录为空或加载失败", color = com.example.myempty.githubk.R.color.muted))
                    return@runUi
                }
                entries.forEach { e ->
                    val row = UiKit.hstack(host.context).apply {
                        addView(UiKit.label(host.context,
                            if (e.type == "dir") "▸ ${e.name}" else "· ${e.name}",
                            color = if (e.type == "dir") com.example.myempty.githubk.R.color.primary else com.example.myempty.githubk.R.color.on_surface,
                            size = 13.5f).apply {
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        if (e.type == "dir") {
                            addView(UiKit.ghostButton(host.context, "打开") { loadSource(e.path) })
                        } else {
                            addView(UiKit.ghostButton(host.context, "查看") { viewFile(e) })
                            addView(UiKit.spacer(host.context, 4))
                            addView(UiKit.ghostButton(host.context, "下载") { downloadEntry(e) })
                        }
                    }
                    row.background = UiKit.roundedCard(host.context)
                    row.setPadding(UiKit.dp(host.context, 10), UiKit.dp(host.context, 8), UiKit.dp(host.context, 10), UiKit.dp(host.context, 8))
                    sourceBox.addView(row)
                    sourceBox.addView(UiKit.spacer(host.context, 4))
                }
            }
        }.start()
    }

    /** 下载 GitHub 仓库单个文件到手机本地 Download，下载完成后自动弹系统分享。 */
    private fun downloadEntry(e: GitRepoEntry) {
        if (e.type == "dir") return
        host.toast("下载 ${e.name} …")
        Thread {
            val encPath = e.path.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") }
            val url = e.downloadUrl.ifBlank {
                "https://raw.githubusercontent.com/${repo.fullName}/${currentBranch.trim('/')}/$encPath"
            }
            val tmp = File(host.context.cacheDir, "dl_${System.currentTimeMillis()}_${e.name}")
            val ok = api.downloadFile(url, tmp)
            if (!ok || tmp.length() == 0L) {
                runCatching { tmp.delete() }
                host.runUi { host.toast("下载失败：${e.name}") }
                return@Thread
            }
            val (exOk, where) = LocalFileExporter.exportToDownloads(host.context, tmp, e.name)
            val mgr = ApkManager(host.context)
            host.runUi {
                host.toast(if (exOk) "已下载到本地：$where" else "保存到本地失败：$where")
                if (exOk) runCatching { mgr.shareFile(tmp, "application/octet-stream", "分享 ${e.name}") }
                android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed({ runCatching { tmp.delete() } }, 60000)
            }
        }.start()
    }

    private fun viewFile(e: GitRepoEntry) {
        host.toast("加载 ${e.name} ...")
        Thread {
            val content = try { api.fileContent(repo.fullName, e.path, currentBranch) } catch (_: Throwable) { "" }
            host.runUi { openFileViewer(e, content) }
        }.start()
    }

    /** 全屏代码预览页：标题栏 + 高亮代码全屏展示 + 可选克隆到工作区编辑。 */
    private fun openFileViewer(e: GitRepoEntry, content: String) {
        val root = UiKit.vstack(host.context)
        root.setBackgroundColor(ThemeManager.colors.surface)
        // 标题栏
        val bar = UiKit.hstack(host.context).apply {
            setPadding(UiKit.dp(host.context, 8), UiKit.dp(host.context, 6), UiKit.dp(host.context, 8), UiKit.dp(host.context, 6))
            setBackgroundColor(ThemeManager.colors.surfaceAlt)
        }
        bar.addView(UiKit.ghostButton(host.context, "← 返回") { host.popPage() })
        bar.addView(UiKit.spacer(host.context, 4))
        bar.addView(UiKit.label(host.context, e.path, color = com.example.myempty.githubk.R.color.on_surface, size = 13f).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
        })
        bar.addView(UiKit.ghostButton(host.context, "克隆编辑") {
            host.popPage()
            cloneRepo()
        })
        root.addView(bar)
        // 代码区：全屏滚动，高亮
        val scroll = ScrollView(host.context)
        val tv = TextView(host.context).apply {
            textSize = 12.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(ThemeManager.colors.onSurface)
            setPadding(UiKit.dp(host.context, 12), UiKit.dp(host.context, 10), UiKit.dp(host.context, 12), UiKit.dp(host.context, 10))
        }
        val isCode = isCode(e.name)
        tv.text = if (isCode && content.isNotBlank()) {
            com.example.myempty.githubk.editor.SyntaxHighlighter.highlight(
                content, e.name.substringAfterLast('.', ""),
                com.example.myempty.githubk.editor.SyntaxHighlighter.lightPalette
            )
        } else content.ifBlank { "（二进制/空文件）" }
        scroll.addView(tv)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        // 底部状态
        root.addView(UiKit.label(
            host.context,
            "${repo.fullName} · ${currentBranch} · ${if (isCode) "代码" else "文本"} · ${content.length} 字符",
            color = com.example.myempty.githubk.R.color.muted, size = 11f
        ).apply {
            setBackgroundColor(ThemeManager.colors.surfaceAlt)
            setPadding(UiKit.dp(host.context, 12), UiKit.dp(host.context, 6), UiKit.dp(host.context, 12), UiKit.dp(host.context, 6))
        })
        host.pushPage(root)
    }

    // ---------- 分支 ----------

    private fun loadBranches() {
        Thread {
            val branches = try { api.branches(repo.fullName) } catch (_: Throwable) { emptyList() }
            host.runUi {
                branchBox.removeAllViews()
                branchBox.addView(UiKit.label(host.context, "共 ${branches.size} 个分支", color = com.example.myempty.githubk.R.color.muted, size = 12f))
                branchBox.addView(UiKit.spacer(host.context, 6))
                branches.forEach { b ->
                    val row = UiKit.hstack(host.context).apply {
                        addView(UiKit.label(host.context, "⎇ ${b.name}", color = com.example.myempty.githubk.R.color.on_surface, size = 14f).apply {
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        if (b.protected) addView(UiKit.badge(host.context, "protected"))
                        addView(UiKit.spacer(host.context, 6))
                        addView(UiKit.ghostButton(host.context, "切换") { currentBranch = b.name; loadReadme(); sourceBox.removeAllViews(); host.toast("已切换分支：${b.name}") })
                    }
                    row.background = UiKit.roundedCard(host.context)
                    row.setPadding(UiKit.dp(host.context, 10), UiKit.dp(host.context, 8), UiKit.dp(host.context, 10), UiKit.dp(host.context, 8))
                    branchBox.addView(row)
                    branchBox.addView(UiKit.spacer(host.context, 4))
                }
            }
        }.start()
    }

    // ---------- Issues ----------

    private fun loadIssues() {
        issueBox.removeAllViews()
        issueBox.addView(UiKit.label(host.context, "正在加载 Issues...", color = com.example.myempty.githubk.R.color.muted, size = 12f))
        Thread {
            val issues = try { api.issues(repo.fullName) } catch (_: Throwable) { emptyList() }
            host.runUi {
                issueBox.removeAllViews()
                issueBox.addView(UiKit.hstack(host.context).apply {
                    addView(UiKit.label(host.context, "共 ${issues.size} 个 open Issue", color = com.example.myempty.githubk.R.color.muted, size = 12f).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    if (host.state.hasToken()) {
                        addView(UiKit.ghostButton(host.context, "新建 Issue") { newIssue() })
                    }
                })
                issueBox.addView(UiKit.spacer(host.context, 6))
                issues.forEach { isu ->
                    val row = UiKit.vstack(host.context).apply {
                        addView(UiKit.hstack(host.context).apply {
                            addView(UiKit.label(host.context, "#${isu.number}", color = com.example.myempty.githubk.R.color.primary, size = 12f))
                            addView(UiKit.spacer(host.context, 6))
                            addView(UiKit.label(host.context, isu.title, color = com.example.myempty.githubk.R.color.on_surface, size = 14f).apply {
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            })
                        })
                        addView(UiKit.label(host.context, "by ${isu.user} · ${isu.createdAt.take(10)} · ${isu.comments} 评论",
                            color = com.example.myempty.githubk.R.color.muted, size = 12f))
                    }
                    row.background = UiKit.roundedCard(host.context)
                    row.setPadding(UiKit.dp(host.context, 10), UiKit.dp(host.context, 8), UiKit.dp(host.context, 10), UiKit.dp(host.context, 8))
                    issueBox.addView(row)
                    issueBox.addView(UiKit.spacer(host.context, 4))
                }
            }
        }.start()
    }

    private fun newIssue() {
        val title = UiKit.input(host.context, "Issue 标题")
        val body = UiKit.textArea(host.context, "Issue 内容（可选）")
        val box = UiKit.vstack(host.context).apply {
            addView(title)
            addView(UiKit.spacer(host.context, 8))
            addView(body)
        }
        UiKit.dialog(host.context, "新建 Issue", box, onOk = {
            val t = title.text.toString().trim()
            if (t.isEmpty()) { host.toast("请输入标题"); return@dialog }
            Thread {
                val ok = api.createIssue(repo.fullName, t, body.text.toString())
                host.runUi { host.toast(if (ok) "Issue 已创建" else "创建失败"); if (ok) loadIssues() }
            }.start()
        }, onCancel = {})
    }

    // ---------- 信息卡 ----------

    private fun loadInfo() {
        infoBox.removeAllViews()
        infoBox.addView(UiKit.label(host.context, "正在加载项目信息...", color = com.example.myempty.githubk.R.color.muted, size = 12f))
        Thread {
            val full = try { api.repoInfo(repo.fullName) } catch (_: Throwable) { null }
            host.runUi {
                infoBox.removeAllViews()
                if (full == null) {
                    infoBox.addView(UiKit.label(host.context, "信息加载失败", color = com.example.myempty.githubk.R.color.muted))
                    return@runUi
                }
                val rows = listOf(
                    "创建时间" to full.createdAt.take(10),
                    "最近推送" to full.pushedAt.take(10),
                    "Star" to "${full.stars}",
                    "Fork" to "${full.forks}",
                    "Watch" to "${full.watchers}",
                    "Open Issues" to "${full.openIssues}",
                    "License" to full.license.ifBlank { "无" },
                    "语言" to full.language.ifBlank { "Unknown" },
                    "默认分支" to full.defaultBranch,
                    "仓库大小" to formatSize(full.size * 1024),
                    "归档" to if (full.archived) "是" else "否"
                )
                rows.forEach { (k, v) ->
                    val row = UiKit.hstack(host.context).apply {
                        addView(UiKit.label(host.context, k, color = com.example.myempty.githubk.R.color.muted, size = 13f).apply {
                            layoutParams = LinearLayout.LayoutParams(UiKit.dp(host.context, 90), LinearLayout.LayoutParams.WRAP_CONTENT)
                        })
                        addView(UiKit.label(host.context, v, color = com.example.myempty.githubk.R.color.on_surface, size = 13f))
                    }
                    row.setPadding(0, UiKit.dp(host.context, 3), 0, UiKit.dp(host.context, 3))
                    infoBox.addView(row)
                }
                if (full.topics.isNotEmpty()) {
                    infoBox.addView(UiKit.spacer(host.context, 4))
                    infoBox.addView(UiKit.section(host.context, "Topics"))
                    val row = UiKit.hstack(host.context)
                    full.topics.take(10).forEach { t -> row.addView(UiKit.badge(host.context, t)) }
                    infoBox.addView(row)
                }
            }
        }.start()
    }

    // ---------- Star / 克隆 ----------

    private fun checkStar() {
        if (!host.state.hasToken()) return
        Thread {
            val s = api.isStarred(repo.fullName)
            host.runUi {
                starred = s
                val btn = body.findViewWithTag<View>("starBtn")
                (btn as? TextView)?.text = if (s) "★ Starred" else "Star"
            }
        }.start()
    }

    private fun toggleStar() {
        if (!host.state.hasToken()) { host.toast("请先配置 GitHub Token"); return }
        Thread {
            val ok = if (starred) api.unstar(repo.fullName) else api.star(repo.fullName)
            host.runUi {
                starred = !starred
                host.toast(if (ok) (if (starred) "已 Star" else "已取消 Star") else "操作失败")
            }
        }.start()
    }

    /**
     * 克隆：ZIP 下载 + 解压（无 git 命令依赖）。
     */
    private fun cloneRepo() {
        if (!host.state.hasToken()) { host.toast("请先配置 GitHub Token"); return }
        host.toast("正在下载 ${repo.name} ...")
        val zipFile = java.io.File(host.context.cacheDir, "${repo.name}_${System.currentTimeMillis()}.zip")
        Thread {
            val ok = api.downloadZip(repo.fullName, zipFile, currentBranch)
            host.runUi {
                if (!ok) {
                    host.toast("下载失败，请检查网络或仓库是否公开")
                    zipFile.delete()
                    return@runUi
                }
                val imported = host.state.workspace.importZip(zipFile, repo.name)
                zipFile.delete()
                if (imported) {
                    host.state.workspace.scan()
                    host.toast("克隆成功：${repo.name}")
                    host.state.workspace.project(repo.name)?.let { host.openEditor(it) }
                } else {
                    host.toast("解压失败，请重试")
                }
            }
        }.start()
    }

    private fun isCode(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf(
            "kt", "kts", "java", "dart", "xml", "json", "gradle", "groovy", "py", "js", "ts",
            "c", "cpp", "h", "hpp", "swift", "go", "rs", "rb", "php", "sh", "sql", "md",
            "yml", "yaml", "toml", "properties", "txt", "css", "html", "vue", "scala", "cs"
        )
    }

    private fun formatSize(s: Long): String = when {
        s >= 1_000_000 -> "%.1f MB".format(s / 1_000_000.0)
        s >= 1_000 -> "%.1f KB".format(s / 1_000.0)
        else -> "$s B"
    }

    /** 兼容旧调用：直接全屏显示。 */
    fun show() {
        host.pushPage(buildView())
    }
}
