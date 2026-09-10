package com.example.myempty.githubk.ui

import com.example.myempty.githubk.apk.ApkManager
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.example.myempty.githubk.R
import com.example.myempty.githubk.git.GitBranch
import com.example.myempty.githubk.git.GitRepo
import com.example.myempty.githubk.io.LocalFileExporter
import java.io.File

/**
 * RepoManagePage v6：仓库管理全屏页（替代旧版系统 AlertDialog 文本列表）。
 *
 * 功能按区域组织，全部在页面内完成：
 *  - 仓库概览（头像 / 名称 / 描述 / 统计）
 *  - Star / Unstar / Fork（动态状态）
 *  - 分支管理：列表 + 创建 + 删除
 *  - 导入文件到仓库：ZIP / 项目文件夹（页内实时进度，不再用 Toast 刷屏）
 *  - 重命名仓库 / 打开编辑器 / 删除仓库（危险二次确认）
 *
 * 权限模型：只有仓库所有者（owner == 当前登录用户）能执行写操作，
 * 非所有者仅保留 Star / Unstar / Fork / 克隆（浏览类）能力。
 */
class RepoManagePage(private val host: PageHost, private val repo: GitRepo) {

    private val state = host.state
    private val api get() = state.gitHub
    private val c get() = host.context

    private lateinit var content: LinearLayout
    private lateinit var statusLine: TextView
    private lateinit var branchBox: LinearLayout
    private lateinit var branchInput: EditText

    private var starred = false
    private var isOwner = false
    private var ownerChecked = false
    private var busy = false
    private var currentDefaultBranch: String = repo.defaultBranch.ifBlank { "main" }

    fun show() = host.pushPage(buildView())

    // ------------------------------------------------------------------
    // 顶层结构
    // ------------------------------------------------------------------
    private fun buildView(): View {
        val root = UiKit.vstack(c)
        root.setPadding(UiKit.dp(c, 14), UiKit.dp(c, 8), UiKit.dp(c, 14), UiKit.dp(c, 8))

        // 顶栏：返回 + 标题
        val top = UiKit.hstack(c).apply {
            addView(UiKit.ghostButton(c, "← 返回") { host.popPage() })
            addView(UiKit.spacer(c, 8))
            addView(UiKit.vstack(c).apply {
                addView(TextView(c).apply {
                    text = "仓库管理"
                    textSize = 18f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(ThemeManager.colors.onSurface)
                })
                addView(TextView(c).apply {
                    text = repo.fullName
                    textSize = 12f
                    setTextColor(ThemeManager.colors.muted)
                    setPadding(0, UiKit.dp(c, 1), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(UiKit.ghostButton(c, "刷新") { host.pushPage(RepoManagePage(host, repo).buildView()) })
        }
        root.addView(top)
        root.addView(UiKit.spacer(c, 8))

        // 状态行
        statusLine = TextView(c).apply {
            text = "正在加载仓库信息…"
            textSize = 12.5f
            setTextColor(ThemeManager.colors.muted)
            setPadding(UiKit.dp(c, 4), UiKit.dp(c, 2), UiKit.dp(c, 4), UiKit.dp(c, 6))
        }
        root.addView(statusLine)

        val scroll = ScrollView(c).apply { isFillViewport = false }
        content = UiKit.vstack(c).apply {
            setPadding(0, 0, 0, UiKit.dp(c, 12))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // 异步初始化：读取详细信息、star 状态、owner、分支
        Thread {
            val fresh = api.repoInfo(repo.fullName) ?: repo
            val star = api.isStarred(repo.fullName)
            val login = api.myLogin()
            val defBr = api.defaultBranch(repo.fullName)
            host.runUi {
                starred = star
                currentDefaultBranch = defBr.ifBlank { "main" }
                isOwner = login.isNotBlank() && login.equals(fresh.owner, ignoreCase = true)
                ownerChecked = true
                renderHeader(fresh)
                renderRelationCard(fresh)
                renderBranchCard(fresh)
                renderImportCard(fresh)
                renderDeleteRemoteFileRow(fresh)
                renderSettingsCard(fresh)
                statusLine.text = if (isOwner) "所有者模式：可管理该仓库" else "只读模式：可 Star / Fork / 克隆"
                statusLine.setTextColor(if (isOwner) ThemeManager.colors.success else ThemeManager.colors.muted)
            }
        }.start()
        return root
    }

    private fun setStatus(msg: String) {
        statusLine.text = msg
        statusLine.setTextColor(ThemeManager.colors.muted)
    }

    private fun ensureToken(): Boolean {
        if (state.hasToken()) return true
        host.toast("请先在设置中配置 GitHub Token")
        return false
    }

    private fun formatNum(n: Int): String = when {
        n >= 1000 -> "%.1fk".format(n / 1000.0)
        else -> "$n"
    }

    // ------------------------------------------------------------------
    // 通用小组件
    // ------------------------------------------------------------------
    private fun sectionTitle(text: String): TextView = TextView(c).apply {
        this.text = text
        textSize = 13f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(ThemeManager.colors.muted)
        setPadding(0, UiKit.dp(c, 14), 0, UiKit.dp(c, 6))
    }

    /** 现代列表行：左侧圆底图标 + 标题/副标题 + 右箭头。 */
    private fun actionRow(
        iconRes: Int,
        iconTint: Int,
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiKit.rounded(c, ThemeManager.colors.surfaceElevated, 16)
            setPadding(UiKit.dp(c, 12), UiKit.dp(c, 10), UiKit.dp(c, 12), UiKit.dp(c, 10))
            setOnClickListener { onClick() }
        }
        val iconBox = LinearLayout(c).apply {
            background = UiKit.rounded(c, iconTint, 12)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(UiKit.dp(c, 40), UiKit.dp(c, 40))
        }
        iconBox.addView(UiKit.icon(c, iconRes, 22, android.graphics.Color.WHITE))
        row.addView(iconBox)
        row.addView(UiKit.spacer(c, 10))
        val textCol = UiKit.vstack(c).apply {
            addView(TextView(c).apply {
                text = title
                textSize = 14.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(ThemeManager.colors.onSurface)
            })
            addView(TextView(c).apply {
                text = subtitle
                textSize = 12f
                setTextColor(ThemeManager.colors.muted)
                setPadding(0, UiKit.dp(c, 2), 0, 0)
            })
        }
        row.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(c).apply {
            text = "›"
            textSize = 20f
            setTextColor(ThemeManager.colors.muted)
        })
        return row
    }

    // ------------------------------------------------------------------
    // 分区 0：仓库概览
    // ------------------------------------------------------------------
    private fun renderHeader(fresh: GitRepo) {
        content.addView(sectionTitle("仓库概览"))
        content.addView(UiKit.card(c, UiKit.vstack(c).apply {
            addView(UiKit.hstack(c).apply {
                addView(UiKit.avatar(c, fresh.owner, R.color.primary))
                addView(UiKit.spacer(c, 10))
                addView(UiKit.vstack(c).apply {
                    addView(TextView(c).apply {
                        text = fresh.name
                        textSize = 17f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(ThemeManager.colors.onSurface)
                    })
                    addView(TextView(c).apply {
                        text = "@${fresh.owner}"
                        textSize = 12.5f
                        setTextColor(ThemeManager.colors.muted)
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(UiKit.badge(c, if (fresh.private) "私有" else "公开", R.color.accent))
            })
            if (fresh.description.isNotBlank()) {
                addView(TextView(c).apply {
                    text = fresh.description
                    textSize = 13.5f
                    setTextColor(ThemeManager.colors.onSurface)
                    setPadding(0, UiKit.dp(c, 8), 0, 0)
                })
            }
            addView(UiKit.hstack(c).apply {
                setPadding(0, UiKit.dp(c, 10), 0, 0)
                addView(UiKit.stat(c, "★", formatNum(fresh.stars)))
                addView(UiKit.spacer(c, 10))
                addView(UiKit.stat(c, "⑂", formatNum(fresh.forks)))
                if (fresh.language.isNotBlank()) {
                    addView(UiKit.spacer(c, 10))
                    addView(UiKit.stat(c, "●", fresh.language, R.color.accent))
                }
            })
            addView(UiKit.hstack(c).apply {
                setPadding(0, UiKit.dp(c, 8), 0, 0)
                addView(UiKit.badge(c, "默认分支 $currentDefaultBranch"))
                addView(UiKit.spacer(c, 6))
                if (fresh.archived) addView(UiKit.badge(c, "已归档", R.color.warning))
                if (fresh.parentFullName.isNotBlank()) addView(UiKit.badge(c, "fork of ${fresh.parentFullName}", R.color.muted))
            })
        }))
    }

    // ------------------------------------------------------------------
    // 分区 1：Star / Fork
    // ------------------------------------------------------------------
    private fun renderRelationCard(fresh: GitRepo) {
        content.addView(sectionTitle("关系操作"))
        content.addView(actionRow(
            R.drawable.ic_star,
            ThemeManager.colors.warning,
            if (starred) "取消 Star" else "Star 仓库",
            if (starred) "你已收藏此仓库，点击取消收藏" else "收藏此仓库，方便后续快速访问",
        ) { toggleStar(fresh) })
        content.addView(UiKit.spacer(c, 8))
        content.addView(actionRow(
            R.drawable.ic_fork,
            ThemeManager.colors.accent,
            "Fork 仓库",
            "复制到你的账号下，之后可自由修改",
        ) { doFork(fresh) })
    }

    private fun toggleStar(fresh: GitRepo) {
        if (!ensureToken()) return
        if (busy) return
        busy = true
        setStatus(if (starred) "正在取消 Star…" else "正在 Star…")
        Thread {
            val ok = if (starred) api.unstar(fresh.fullName) else api.star(fresh.fullName)
            host.runUi {
                busy = false
                if (ok) {
                    starred = !starred
                    setStatus(if (starred) "已收藏：${fresh.fullName}" else "已取消收藏")
                    host.pushPage(RepoManagePage(host, fresh).buildView())
                } else {
                    setStatus("操作失败，请检查 Token 是否有效")
                }
            }
        }.start()
    }

    private fun doFork(fresh: GitRepo) {
        if (!ensureToken()) return
        if (busy) return
        busy = true
        setStatus("正在 Fork…")
        Thread {
            val r = api.fork(fresh.fullName)
            host.runUi {
                busy = false
                if (r == "fork failed") setStatus("Fork 失败：可能已 Fork 过或无权限")
                else {
                    setStatus("Fork 成功：$r")
                    host.toast("Fork 成功：$r")
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // 局部刷新：操作成功后清空重渲染，避免页面反复压栈
    // ------------------------------------------------------------------
    private fun refresh(fresh: GitRepo) {
        content.removeAllViews()
        Thread {
            val star = api.isStarred(fresh.fullName)
            val defBr = api.defaultBranch(fresh.fullName)
            host.runUi {
                starred = star
                currentDefaultBranch = defBr.ifBlank { "main" }
                renderHeader(fresh)
                renderRelationCard(fresh)
                renderBranchCard(fresh)
                renderImportCard(fresh)
                renderDeleteRemoteFileRow(fresh)
                renderSettingsCard(fresh)
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // 分区 2：分支管理
    // ------------------------------------------------------------------
    private fun renderBranchCard(fresh: GitRepo) {
        content.addView(sectionTitle("分支管理"))
        val card = UiKit.vstack(c).apply {
            background = UiKit.rounded(c, ThemeManager.colors.surface, 16)
            setPadding(UiKit.dp(c, 12), UiKit.dp(c, 12), UiKit.dp(c, 12), UiKit.dp(c, 12))
        }
        if (ownerChecked && isOwner) {
            // 新建分支（仅 owner）
            val row = UiKit.hstack(c)
            branchInput = UiKit.input(c, "新分支名（基于 $currentDefaultBranch）").apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(branchInput)
            row.addView(UiKit.spacer(c, 6))
            row.addView(UiKit.button(c, "创建") {
                val name = branchInput.text.toString().trim()
                if (name.isBlank()) { host.toast("请输入分支名"); return@button }
                if (busy) return@button
                busy = true
                setStatus("正在创建分支 $name…")
                Thread {
                    val ok = api.createBranch(fresh.fullName, name, currentDefaultBranch)
                    host.runUi {
                        busy = false
                        setStatus(if (ok) "分支 $name 已创建" else "创建失败：请检查名称或权限")
                        if (ok) refresh(fresh)
                    }
                }.start()
            })
            card.addView(row)
            card.addView(UiKit.spacer(c, 4))
        } else if (ownerChecked && !isOwner) {
            card.addView(UiKit.label(c, "仅仓库所有者可创建 / 删除分支", color = R.color.muted, size = 12.5f))
        }

        branchBox = UiKit.vstack(c)
        card.addView(branchBox)
        content.addView(card)

        // 异步加载分支列表
        Thread {
            val branches = try { api.branches(fresh.fullName) } catch (_: Throwable) { emptyList() }
            host.runUi {
                branchBox.removeAllViews()
                if (branches.isEmpty()) {
                    branchBox.addView(UiKit.label(c, "无分支或加载失败", color = R.color.muted))
                    return@runUi
                }
                branchBox.addView(TextView(c).apply {
                    text = "共 ${branches.size} 个分支 · 默认 ${currentDefaultBranch}"
                    textSize = 12f
                    setTextColor(ThemeManager.colors.muted)
                    setPadding(0, UiKit.dp(c, 4), 0, UiKit.dp(c, 4))
                })
                branches.forEach { br ->
                    val row = UiKit.hstack(c).apply {
                        background = UiKit.rounded(c, ThemeManager.colors.surfaceAlt, 12)
                        setPadding(UiKit.dp(c, 10), UiKit.dp(c, 7), UiKit.dp(c, 10), UiKit.dp(c, 7))
                    }
                    val isDef = br.name == currentDefaultBranch
                    row.addView(TextView(c).apply {
                        text = if (isDef) "✓ " else "· "
                        setTextColor(if (isDef) ThemeManager.colors.success else ThemeManager.colors.muted)
                    })
                    row.addView(UiKit.label(c, br.name, color = if (isDef) R.color.success else R.color.on_surface, size = 13.5f).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    })
                    if (isDef) {
                        row.addView(UiKit.badge(c, "默认"))
                    } else if (isOwner) {
                        row.addView(UiKit.ghostButton(c, "删除") { confirmDeleteBranch(fresh, br) })
                    }
                    branchBox.addView(row)
                    branchBox.addView(UiKit.spacer(c, 5))
                }
            }
        }.start()
    }

    private fun confirmDeleteBranch(fresh: GitRepo, br: GitBranch) {
        if (busy) return
        val box = UiKit.vstack(c).apply {
            addView(UiKit.label(c, "确定删除分支 ${br.name}？此操作不可撤销。", color = R.color.on_surface))
        }
        UiKit.dialog(c, "删除分支", box, onOk = {
            busy = true
            setStatus("正在删除分支 ${br.name}…")
            Thread {
                val ok = api.deleteBranch(fresh.fullName, br.name)
                host.runUi {
                    busy = false
                    setStatus(if (ok) "分支 ${br.name} 已删除" else "删除失败：默认分支不可删或权限不足")
                    if (ok) refresh(fresh)
                }
            }.start()
        }, onCancel = {})
    }

    // ------------------------------------------------------------------
    // 分区 3：导入文件到仓库
    // ------------------------------------------------------------------
    private fun renderImportCard(fresh: GitRepo) {
        content.addView(sectionTitle("导入 / 上传文件"))
        val card = UiKit.vstack(c).apply {
            background = UiKit.rounded(c, ThemeManager.colors.surface, 16)
            setPadding(UiKit.dp(c, 12), UiKit.dp(c, 12), UiKit.dp(c, 12), UiKit.dp(c, 12))
        }
        card.addView(actionRow(
            R.drawable.ic_upload, ThemeManager.colors.accent, "上传 APK / 任意文件",
            if (isOwner) "选择本地 APK 或任意文件，写入仓库指定路径（单文件 ≤ 100MB）" else "仅仓库所有者可上传",
        ) { if (isOwner) uploadSingleFileToRepo(fresh) else host.toast("仅仓库所有者可上传") })
        card.addView(UiKit.spacer(c, 8))
        card.addView(actionRow(
            R.drawable.ic_upload, ThemeManager.colors.accent, "发布 Release",
            if (isOwner) "创建 GitHub Release 并上传 APK（完整表单 · 预发布 / 覆盖旧版本）" else "仅仓库所有者可发布",
        ) { if (isOwner) RepoReleasePage(host, repo).show() else host.toast("仅仓库所有者可发布") })
        card.addView(UiKit.spacer(c, 8))
        card.addView(actionRow(
            R.drawable.ic_upload, ThemeManager.colors.primary, "从 ZIP 导入",
            if (isOwner) "选择 zip 压缩包，自动解压并替换同名文件" else "仅仓库所有者可导入",
        ) { if (isOwner) importZipToRepo(fresh) else host.toast("仅仓库所有者可导入") })
        card.addView(UiKit.spacer(c, 8))
        card.addView(actionRow(
            R.drawable.ic_folder_open, ThemeManager.colors.primary, "从项目文件夹导入",
            if (isOwner) "选择工作区项目，整目录上传并替换同名文件" else "仅仓库所有者可导入",
        ) { if (isOwner) importFolderToRepo(fresh) else host.toast("仅仓库所有者可导入") })

        // 页内进度
        card.addView(UiKit.spacer(c, 6))
        val pbar = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        val ptext = TextView(c).apply {
            text = ""
            textSize = 12f
            setTextColor(ThemeManager.colors.muted)
            visibility = View.GONE
            setPadding(0, UiKit.dp(c, 3), 0, 0)
        }
        card.addView(pbar)
        card.addView(ptext)
        content.addView(card)
        importProgressBar = pbar
        importProgressText = ptext
    }

    private var importProgressBar: ProgressBar? = null
    private var importProgressText: TextView? = null

    private fun uploadSingleFileToRepo(fresh: GitRepo) {
        if (!isOwner) {
            host.toast("仅仓库所有者可上传")
            return
        }
        if (!ensureToken()) return
        GitHubUploadHelper.uploadOneFile(host, fresh, null)
    }

    private fun importZipToRepo(fresh: GitRepo) {
        if (!ensureToken()) return
        if (busy) return
        host.pickFile { uri ->
            if (uri == null) { setStatus("未选择文件"); return@pickFile }
            busy = true
            setStatus("正在读取 ZIP…")
            Thread {
                try {
                    val tmpZip = File(c.cacheDir, "import_${System.currentTimeMillis()}.zip")
                    c.contentResolver.openInputStream(uri)?.use { input ->
                        java.io.FileOutputStream(tmpZip).use { out -> input.copyTo(out) }
                    } ?: throw java.io.IOException("无法读取文件")
                    val n = api.uploadZipToRepo(fresh.fullName, tmpZip, "", null) { d, t, p, ok ->
                        showImportProgress(d, t, p, ok)
                    }
                    tmpZip.delete()
                    host.runUi {
                        busy = false
                        setStatus(if (n > 0) "导入完成：成功上传 $n 个文件" else "导入失败：未上传任何文件")
                        importProgressText?.text = if (n > 0) "成功上传 $n 个文件" else "没有可上传的文件"
                    }
                } catch (e: Throwable) {
                    host.runUi { busy = false; setStatus("导入失败：${e.message}") }
                }
            }.start()
        }
    }

    private fun importFolderToRepo(fresh: GitRepo) {
        if (!ensureToken()) return
        val projects = state.workspace.projects()
        if (projects.isEmpty()) { host.toast("工作区无项目，请先克隆或新建"); return }
        val names = projects.map { it.name }.toTypedArray()
        android.app.AlertDialog.Builder(c)
            .setTitle("选择要导入的项目")
            .setItems(names) { _, which ->
                val proj = projects[which]
                if (busy) return@setItems
                busy = true
                setStatus("正在上传项目 ${proj.name}…")
                Thread {
                    val n = api.uploadTree(fresh.fullName, proj.path, "", "Import folder via GitHubK Studio", null) { d, t, p, ok ->
                        showImportProgress(d, t, p, ok)
                    }
                    host.runUi {
                        busy = false
                        setStatus(if (n > 0) "导入完成：成功上传 $n 个文件" else "导入失败：未上传任何文件")
                    }
                }.start()
            }
            .show()
    }

    private fun showImportProgress(done: Int, total: Int, path: String, ok: Boolean) {
        host.runUi {
            importProgressBar?.visibility = View.VISIBLE
            importProgressText?.visibility = View.VISIBLE
            if (total > 0) importProgressBar?.progress = (done * 100 / total)
            importProgressText?.text = "[$done/$total] ${if (ok) "✓" else "✗"} $path"
        }
    }

    /** 删除远程文件（危险，独立红框行，位于导入区与仓库设置之间）。 */
    private fun renderDeleteRemoteFileRow(fresh: GitRepo) {
        content.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = UiKit.dp(c, 10)
            }
            background = UiKit.rounded(c, ThemeManager.colors.surface, 16, ThemeManager.colors.error, 1)
            setPadding(UiKit.dp(c, 12), UiKit.dp(c, 10), UiKit.dp(c, 12), UiKit.dp(c, 10))
            setOnClickListener { if (isOwner) RepoDeleteFilePage(host, fresh).show() else host.toast("仅仓库所有者可删除") }
            val iconBox = LinearLayout(c).apply {
                background = UiKit.rounded(c, ThemeManager.colors.error, 12)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(UiKit.dp(c, 40), UiKit.dp(c, 40))
            }
            iconBox.addView(UiKit.icon(c, R.drawable.ic_delete, 22, android.graphics.Color.WHITE))
            addView(iconBox)
            addView(UiKit.spacer(c, 10))
            addView(UiKit.vstack(c).apply {
                addView(TextView(c).apply {
                    text = "删除远程文件"
                    textSize = 14.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(ThemeManager.colors.error)
                })
                addView(TextView(c).apply {
                    text = if (isOwner) "按路径删除远程文件 / 目录内容（自动大小写匹配提示）" else "仅仓库所有者可删除"
                    textSize = 12f
                    setTextColor(ThemeManager.colors.muted)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
    }

    // ------------------------------------------------------------------
    // 分区 4：仓库设置
    // ------------------------------------------------------------------
    private fun renderSettingsCard(fresh: GitRepo) {
        content.addView(sectionTitle("仓库设置"))

        // 打开编辑器 / 克隆
        val local = state.workspace.project(fresh.name)
        content.addView(actionRow(
            R.drawable.ic_code, ThemeManager.colors.success,
            if (local != null) "打开编辑器" else "克隆到工作区",
            if (local != null) "本地已存在项目，点击在 IDE 中打开" else "下载仓库源码并解压到本地项目",
        ) { if (local != null) host.openEditor(local) else cloneRepo(fresh) })
        // 下载源码 ZIP 到本地（保存到手机 Download 目录，不自动解压为项目）
        content.addView(actionRow(
            R.drawable.ic_download, ThemeManager.colors.accent,
            "下载源码 ZIP",
            "把 ${fresh.fullName} 的源码 ZIP 保存到本地 Downloads，不自动解压",
        ) { downloadZipOnly(fresh) })
        content.addView(UiKit.spacer(c, 8))

        content.addView(UiKit.spacer(c, 8))

        // 更改仓库状态（仅 owner）：公开/私有 切换 + 描述编辑
        content.addView(actionRow(
            R.drawable.ic_edit, ThemeManager.colors.warning, "更改仓库状态",
            if (isOwner) "切换公开 / 私有，或编辑仓库描述（仅所有者可改）" else "仅仓库所有者可修改状态",
        ) { if (isOwner) showChangeState(fresh) else host.toast("仅仓库所有者可修改状态") })
        content.addView(UiKit.spacer(c, 8))

        // 重命名（仅 owner）
        content.addView(actionRow(
            R.drawable.ic_edit, ThemeManager.colors.primary, "重命名仓库",
            if (isOwner) "修改仓库名称" else "仅仓库所有者可重命名",
        ) { if (isOwner) showRename(fresh) else host.toast("仅仓库所有者可重命名") })
        content.addView(UiKit.spacer(c, 8))

        // 删除（仅 owner，危险）
        content.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiKit.rounded(c, ThemeManager.colors.surface, 16, ThemeManager.colors.error, 1)
            setPadding(UiKit.dp(c, 12), UiKit.dp(c, 10), UiKit.dp(c, 12), UiKit.dp(c, 10))
            setOnClickListener { if (isOwner) showDelete(fresh) else host.toast("仅仓库所有者可删除") }
            val iconBox = LinearLayout(c).apply {
                background = UiKit.rounded(c, ThemeManager.colors.error, 12)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(UiKit.dp(c, 40), UiKit.dp(c, 40))
            }
            iconBox.addView(UiKit.icon(c, R.drawable.ic_delete, 22, android.graphics.Color.WHITE))
            addView(iconBox)
            addView(UiKit.spacer(c, 10))
            addView(UiKit.vstack(c).apply {
                addView(TextView(c).apply {
                    text = "删除仓库"
                    textSize = 14.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(ThemeManager.colors.error)
                })
                addView(TextView(c).apply {
                    text = if (isOwner) "永久删除 ${fresh.fullName}，不可恢复" else "仅仓库所有者可删除"
                    textSize = 12f
                    setTextColor(ThemeManager.colors.muted)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
    }

    private fun cloneRepo(fresh: GitRepo) {
        if (!ensureToken()) return
        if (busy) return
        val dir = File(state.workspace.rootDir(), fresh.name)
        if (dir.exists()) { host.toast("项目已存在：${fresh.name}"); return }
        busy = true
        setStatus("正在下载 ${fresh.name} …")
        val zipFile = File(c.cacheDir, "${fresh.name}_${System.currentTimeMillis()}.zip")
        Thread {
            val ok = api.downloadZip(fresh.fullName, zipFile, currentDefaultBranch)
            host.runUi {
                busy = false
                if (!ok) {
                    zipFile.delete()
                    setStatus("下载失败，请检查网络或仓库是否公开")
                    return@runUi
                }
                val imported = state.workspace.importZip(zipFile, fresh.name)
                zipFile.delete()
                if (imported) {
                    state.workspace.scan()
                    setStatus("克隆成功：${fresh.name}")
                    state.workspace.project(fresh.name)?.let { host.openEditor(it) }
                } else {
                    setStatus("解压失败，请重试")
                }
            }
        }.start()
    }

    /** 下载仓库源码 ZIP 并导出到手机本地 Download 目录（不自动解压为项目）。 */
    private fun downloadZipOnly(fresh: GitRepo) {
        if (!ensureToken()) return
        if (busy) return
        val tmpZip = File(c.cacheDir, "${fresh.name}_${System.currentTimeMillis()}.zip")
        busy = true
        setStatus("正在下载 ${fresh.fullName} ZIP …")
        Thread {
            val ok = api.downloadZip(fresh.fullName, tmpZip, currentDefaultBranch)
            if (ok && tmpZip.length() > 0L) {
                val (exOk, where) = LocalFileExporter.exportToDownloads(c, tmpZip, "${fresh.name}_source.zip")
                host.runUi {
                    busy = false
                    if (exOk) {
                        setStatus("已保存到本地：Download/GitHubK Studio/${fresh.name}_source.zip")
                        host.toast("已下载到本地，正在弹出分享…")
                        runCatching { ApkManager(c).shareFile(tmpZip, "application/zip", "分享 ${fresh.name} 源码") }
                        android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed({ runCatching { tmpZip.delete() } }, 60000)
                    } else {
                        tmpZip.delete()
                        setStatus("保存失败：$where")
                    }
                }
            } else {
                tmpZip.delete()
                host.runUi {
                    busy = false
                    setStatus("下载失败：请检查网络或仓库是否公开")
                }
            }
        }.start()
    }

    /**
     * 更改仓库状态：切换公开/私有、编辑描述。
     */
    private fun showChangeState(fresh: GitRepo) {
        if (busy) return
        val box = UiKit.vstack(c).apply {
            setPadding(UiKit.dp(c, 20), UiKit.dp(c, 10), UiKit.dp(c, 20), UiKit.dp(c, 10))
        }
        val descInput = UiKit.textArea(c, "仓库描述").apply {
            setText(fresh.description)
        }
        box.addView(UiKit.label(c, "可见性", color = R.color.muted, size = 12.5f))
        var currentPrivate = fresh.private
        val privSwitch = UiKit.switchRow(
            c,
            if (currentPrivate) "仓库为私有" else "仓库为公开",
            if (currentPrivate) "仅你可见（关闭改为公开仓库）" else "所有人可见（打开设为私有仓库）",
            initial = currentPrivate,
        ) { v -> currentPrivate = v }
        box.addView(privSwitch)
        box.addView(UiKit.spacer(c, 10))
        box.addView(UiKit.label(c, "描述", color = R.color.muted, size = 12.5f))
        box.addView(descInput)
        box.addView(UiKit.spacer(c, 6))
        box.addView(UiKit.label(c, "切换可见性/编辑描述需要仓库拥有者权限及正确的 Token。",
            color = R.color.muted, size = 11f))
        UiKit.dialog(c, "更改仓库状态", box, onOk = {
            val newDesc = descInput.text.toString().trim()
            if (newDesc == fresh.description && currentPrivate == fresh.private) {
                host.toast("未做任何修改")
                return@dialog
            }
            busy = true
            setStatus("正在更新仓库状态…")
            Thread {
                val ok = api.updateRepo(
                    fresh.fullName,
                    isPrivate = if (currentPrivate != fresh.private) currentPrivate else null,
                    description = if (newDesc != fresh.description) newDesc else null
                )
                host.runUi {
                    busy = false
                    setStatus(if (ok) "仓库状态已更新" else "更新失败：请检查 Token / 权限")
                    if (ok) refresh(fresh)
                }
            }.start()
        }, onCancel = {})
    }

    private fun showRename(fresh: GitRepo) {
        val box = UiKit.vstack(c).apply {
            setPadding(UiKit.dp(c, 20), UiKit.dp(c, 10), UiKit.dp(c, 20), UiKit.dp(c, 10))
        }
        val nameInput = UiKit.input(c, "新仓库名称").apply { setText(fresh.name) }
        box.addView(nameInput)
        UiKit.dialog(c, "重命名仓库", box, onOk = {
            val newName = nameInput.text.toString().trim()
            if (newName.isBlank() || newName == fresh.name) { host.toast("名称未改变"); return@dialog }
            busy = true
            setStatus("正在重命名…")
            Thread {
                val ok = api.renameRepo(fresh.fullName, newName)
                host.runUi {
                    busy = false
                    setStatus(if (ok) "仓库已重命名为 $newName" else "重命名失败：请检查名称或权限")
                }
            }.start()
        }, onCancel = {})
    }

    /** 仓库全名规范化：去除零宽空白/首尾空格，并把各种横线连字符统一为普通减号 -。 */
    private fun normalizeRepoName(raw: String): String {
        var s = raw
        // 零宽空白 / BOM / 软连字符
        for (zw in listOf("\u200B", "\u200C", "\u200D", "\uFEFF", "\u00AD")) {
            s = s.replace(zw, "")
        }
        // 横线/连字符统一为普通减号：
        // ‐ U+2010 | ‑ U+2011 非断连短横 | ‒ U+2012 | – U+2013 | — U+2014 全角横 | ― U+2015 | − U+2212 | － U+FF0D 全角减号
        val dashChars = "\u2010\u2011\u2012\u2013\u2014\u2015\u2212\uFF0D"
        val sb = StringBuilder(s.length)
        for (ch in s) {
            sb.append(if (dashChars.indexOf(ch) >= 0) '-' else ch)
        }
        return sb.toString().trim()
    }

    private fun showDelete(fresh: GitRepo) {
        val box = UiKit.vstack(c).apply {
            setPadding(UiKit.dp(c, 20), UiKit.dp(c, 10), UiKit.dp(c, 20), UiKit.dp(c, 10))
        }
        box.addView(UiKit.label(c, "请输入仓库全名（owner/name）确认删除：", color = R.color.on_surface))
        val confirmInput = UiKit.input(c, fresh.fullName)
        box.addView(confirmInput)
        UiKit.dialog(c, "危险操作：删除仓库", box, onOk = {
            // 规范化后再比较：全角横/非断连短横/普通减号统一为 -，忽略首尾空格与零宽空白
            val typed = normalizeRepoName(confirmInput.text.toString())
            val target = normalizeRepoName(fresh.fullName)
            val formatOk = typed.contains('/') && typed.count { it == '/' } == 1 &&
                typed.substringBefore('/').isNotBlank() && typed.substringAfter('/').isNotBlank()
            if (typed != target || !formatOk) { host.toast("输入不匹配，已取消"); return@dialog }
            busy = true
            setStatus("正在删除仓库…")
            Thread {
                val ok = api.deleteRepo(fresh.fullName)
                host.runUi {
                    busy = false
                    if (ok) {
                        host.toast("仓库已删除")
                        host.popPage()  // 返回上一页（仓库列表）
                    } else {
                        setStatus("删除失败：需要 delete_repo 权限，请检查 Token")
                    }
                }
            }.start()
        }, onCancel = {})
    }
}
