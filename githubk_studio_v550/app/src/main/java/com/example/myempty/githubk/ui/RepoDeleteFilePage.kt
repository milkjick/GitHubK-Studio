package com.example.myempty.githubk.ui

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.myempty.githubk.R
import com.example.myempty.githubk.git.GitHubOpResult
import com.example.myempty.githubk.git.GitRepo
import com.example.myempty.githubk.git.gitHubErrorText
import org.json.JSONArray

/**
 * RepoDeleteFilePage：删除远程文件 / 目录全屏页（v5.2）。
 *
 * - 分支选择（默认当前默认分支）
 * - 文件路径输入 + 智能大小写/扩展名匹配助手（读远程 tree）
 * - 多重路径逐条删除（同一分支，按顺序执行）
 * - 仅删除 GitHub 远程内容，不改动本地工作区文件
 */
class RepoDeleteFilePage(private val host: PageHost, private val repo: GitRepo) {

    private val state get() = host.state
    private val c get() = host.context
    private val api get() = state.gitHub

    private lateinit var statusLine: TextView
    private lateinit var branchInput: EditText
    private lateinit var pathInput: EditText
    private lateinit var matchArea: LinearLayout
    private lateinit var matchStatus: TextView
    private var busy = false

    private var cachedTree: JSONArray? = null
    private var cachedBranch: String? = null

    fun show() = host.pushPage(buildView())

    private fun buildView(): View {
        val root = UiKit.vstack(c)
        root.setPadding(UiKit.dp(c, 14), UiKit.dp(c, 8), UiKit.dp(c, 14), UiKit.dp(c, 8))

        val top = UiKit.hstack(c).apply {
            addView(UiKit.ghostButton(c, "← 返回") { host.popPage() })
            addView(UiKit.spacer(c, 8))
            addView(UiKit.vstack(c).apply {
                addView(TextView(c).apply {
                    text = "删除远程文件"
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(ThemeManager.colors.onSurface)
                })
                addView(TextView(c).apply {
                    text = repo.fullName
                    textSize = 12f
                    setTextColor(ThemeManager.colors.muted)
                    setPadding(0, UiKit.dp(c, 1), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(top)
        root.addView(UiKit.spacer(c, 6))

        statusLine = TextView(c).apply {
            text = "删除操作将直接提交到 GitHub 远程，不会改动本地工作区文件。"
            textSize = 12.5f
            setTextColor(ThemeManager.colors.muted)
            setPadding(UiKit.dp(c, 4), UiKit.dp(c, 2), UiKit.dp(c, 4), UiKit.dp(c, 4))
        }
        root.addView(statusLine)

        val scroll = ScrollView(c).apply { isFillViewport = false }
        val content = UiKit.vstack(c).apply { setPadding(0, 0, 0, UiKit.dp(c, 12)) }

        content.addView(sectionTitle("删除分支"))
        branchInput = UiKit.input(c, "分支名称")
        branchInput.setText(repo.defaultBranch.ifBlank { "main" })
        content.addView(branchInput)
        content.addView(UiKit.spacer(c, 10))

        content.addView(sectionTitle("远程路径（支持多个，每行一个）"))
        pathInput = UiKit.textArea(c, "例如：README.md 或 app/src/main/AndroidManifest.xml\n支持一次输入多行，逐条删除")
        content.addView(pathInput)
        content.addView(UiKit.label(
            c,
            "注意：GitHub 区分大小写。输入 readme 不会删除 README.md；可点下方“匹配远程路径”自动补全为远程实际路径。",
            color = R.color.muted, size = 11f
        ))
        content.addView(UiKit.spacer(c, 6))

        // 匹配助手
        content.addView(UiKit.tonalButton(c, "匹配远程路径（大小写 / 扩展名）") { probeRemotePaths() })
        content.addView(UiKit.spacer(c, 4))
        matchStatus = TextView(c).apply {
            text = ""
            textSize = 12f
            setTextColor(ThemeManager.colors.muted)
        }
        content.addView(matchStatus)
        matchArea = UiKit.vstack(c).apply { setPadding(0, 0, 0, 0) }
        content.addView(matchArea)
        content.addView(UiKit.spacer(c, 10))

        content.addView(sectionTitle("删除范围"))
        content.addView(UiKit.label(
            c, "删除单文件 = 移除该文件；删除目录 = 需要逐条列出其内文件（Contents API 不支持整目录删除）。",
            color = R.color.muted, size = 11f
        ))
        content.addView(UiKit.spacer(c, 12))

        content.addView(UiKit.dangerButton(c, "删除所选路径") { requestConfirm() })
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun sectionTitle(text: String): TextView = TextView(c).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(ThemeManager.colors.muted)
        setPadding(0, UiKit.dp(c, 12), 0, UiKit.dp(c, 6))
    }

    // ------------------------------------------------------------------
    // 路径智能匹配（读远程 tree）
    // ------------------------------------------------------------------
    private fun probeRemotePaths() {
        if (busy) return
        val typed = pathInput.text.toString().trim()
        val branch = branchInput.text.toString().trim()
        if (branch.isBlank()) { host.toast("请填写分支"); return }
        matchStatus.text = "正在读取远程文件树…"
        matchArea.removeAllViews()
        val fullName = repo.fullName
        Thread {
            var tree: JSONArray? = null
            var treeErr = ""
            if (cachedBranch == branch && cachedTree != null) {
                tree = cachedTree
            } else {
                val r = api.listTreeOp(fullName, branch)
                if (!r.ok) {
                    treeErr = gitHubErrorText(r, "无法读取远程文件树")
                } else {
                    cachedTree = r.data?.optJSONArray("tree")
                    cachedBranch = branch
                    tree = cachedTree
                }
            }
            val paths = ArrayList<String>()
            if (tree != null) {
                for (i in 0 until tree.length()) {
                    val o = tree.optJSONObject(i) ?: continue
                    val p = o.optString("path")
                    if (p.isNotBlank()) paths.add(p)
                }
            }
            host.runUi {
                if (treeErr.isNotBlank()) {
                    matchStatus.text = "❌ $treeErr"
                    matchStatus.setTextColor(ThemeManager.colors.error)
                    return@runUi
                }
                if (typed.isBlank()) {
                    matchStatus.text = "请输入路径后再点匹配。"
                    matchStatus.setTextColor(ThemeManager.colors.muted)
                    return@runUi
                }
                val hits = suggestCandidates(paths, typed)
                if (hits.isEmpty()) {
                    matchStatus.text = "远程中未找到与“$typed”相近的路径。"
                    matchStatus.setTextColor(ThemeManager.colors.muted)
                    return@runUi
                }
                matchStatus.text = "匹配到 ${hits.size} 个远程实际路径（点击使用）："
                matchStatus.setTextColor(ThemeManager.colors.onSurface)
                hits.take(8).forEach { hit ->
                    val row = LinearLayout(c).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = UiKit.rounded(c, ThemeManager.colors.surfaceAlt, 10)
                        setPadding(UiKit.dp(c, 10), UiKit.dp(c, 6), UiKit.dp(c, 10), UiKit.dp(c, 6))
                        setOnClickListener {
                            pathInput.setText(hit)
                            pathInput.setSelection(hit.length)
                        }
                    }
                    row.addView(UiKit.label(c, "📄 ", color = R.color.primary, size = 12f))
                    row.addView(UiKit.label(c, hit, color = R.color.on_surface, size = 12.5f).apply {
                        typeface = Typeface.MONOSPACE
                    })
                    matchArea.addView(row)
                    matchArea.addView(UiKit.spacer(c, 3))
                }
            }
        }.start()
    }

    private fun suggestCandidates(paths: List<String>, typed: String): List<String> {
        val t = typed.trim('/').lowercase()
        if (t.isBlank()) return emptyList()
        val seen = LinkedHashSet<String>()
        // 1) 大小写不敏感完全匹配（且与实际不同，说明大小写需要自动扩展）
        paths.forEach { p ->
            if (p.lowercase() == t) {
                if (p != typed.trim('/')) seen.add(p)
            }
        }
        // 2) 以输入为目录前缀
        paths.forEach { p ->
            if (p.lowercase().startsWith("$t/")) seen.add(p)
        }
        // 3) 同目录下同名不同扩展名 / 输入无扩展名时文件名前缀
        val dir = t.substringBeforeLast('/')
        paths.forEach { p ->
            val pl = p.lowercase()
            if (dir.isEmpty()) {
                if (pl.startsWith("$t.") || pl.startsWith(t)) seen.add(p)
            } else if (pl.startsWith("$dir/")) {
                val pName = p.substringAfterLast('/')
                val typedName = t.substringAfterLast('/')
                if (pName.lowercase().startsWith(typedName.lowercase()) || pName.lowercase().contains(typedName.lowercase())) {
                    seen.add(p)
                }
            }
        }
        return seen.toList().sorted()
    }

    // ------------------------------------------------------------------
    // 删除
    // ------------------------------------------------------------------
    private fun requestConfirm() {
        if (busy) return
        val branch = branchInput.text.toString().trim()
        if (branch.isBlank()) { host.toast("请填写分支"); return }
        val targets = pathInput.text.toString().trim().lines()
            .map { it.trim().trim('/') }
            .filter { it.isNotBlank() }
        if (targets.isEmpty()) { host.toast("请输入要删除的远程路径"); return }
        var confirmInput: EditText? = null
        val box = UiKit.vstack(c).apply {
            addView(UiKit.label(c, "分支：$branch", color = R.color.on_surface, size = 13f).apply {
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(UiKit.spacer(c, 6))
            targets.forEach {
                addView(UiKit.label(c, "🗑 $it", color = R.color.on_surface, size = 13f).apply {
                    typeface = Typeface.MONOSPACE
                })
            }
            addView(UiKit.spacer(c, 10))
            addView(UiKit.label(c, "请输入仓库全名以确认：${repo.fullName}", color = R.color.warning, size = 13f))
            confirmInput = UiKit.input(c, repo.fullName)
            addView(confirmInput!!)
        }
        UiKit.confirmEx(
            c, "确认删除远程文件？", box, "确认提交删除", danger = true,
            onOk = {
                val text = confirmInput?.text?.toString()?.trim()
                if (text != repo.fullName) {
                    host.toast("仓库全名输入不匹配，已取消")
                    return@confirmEx
                }
                startDelete(branch, targets)
            },
            onCancel = {}
        )
    }

    private fun startDelete(branch: String, targets: List<String>) {
        busy = true
        statusLine.text = "正在删除 ${targets.size} 个路径…"
        statusLine.setTextColor(ThemeManager.colors.muted)
        Thread {
            val okPaths = ArrayList<String>()
            val failMessages = ArrayList<String>()
            targets.forEach { path ->
                val r = api.deleteFileOp(repo.fullName, path, "Delete $path via GitHubK Studio", branch)
                if (r.ok) okPaths.add(path) else {
                    failMessages.add("$path：" + gitHubErrorText(r, "删除失败"))
                }
            }
            host.runUi {
                busy = false
                if (okPaths.isEmpty()) {
                    val msg = failMessages.joinToString("\n")
                    statusLine.text = "❌ 删除失败"
                    statusLine.setTextColor(ThemeManager.colors.error)
                    host.toast("删除失败：${msg.take(160)}")
                } else if (failMessages.isEmpty()) {
                    statusLine.text = "✅ 已提交删除 ${okPaths.size} 个远程路径"
                    statusLine.setTextColor(ThemeManager.colors.success)
                    showDeleteSuccess(okPaths, branch)
                } else {
                    statusLine.text = "⚠️ 部分成功：${okPaths.size} 成功，${failMessages.size} 失败"
                    statusLine.setTextColor(ThemeManager.colors.warning)
                    host.toast("部分路径删除失败：${failMessages.first().take(120)}")
                }
            }
        }.start()
    }

    private fun showDeleteSuccess(okPaths: List<String>, branch: String) {
        val box = UiKit.vstack(c).apply {
            addView(UiKit.label(c, "✅ 已提交删除 ${okPaths.size} 个远程路径", color = R.color.success, size = 15f).apply {
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(UiKit.spacer(c, 4))
            okPaths.take(6).forEach { addView(UiKit.label(c, "🗑 $it", color = R.color.on_surface, size = 12.5f).apply { typeface = Typeface.MONOSPACE }) }
            addView(UiKit.spacer(c, 8))
            addView(UiKit.label(c, "删除的是 GitHub 远程文件，本地工作区不受影响。", color = R.color.muted, size = 11.5f))
        }
        val holder = arrayOfNulls<android.app.Dialog>(1)
        val row = UiKit.hstack(c).apply { setPadding(0, UiKit.dp(c, 8), 0, 0) }
        row.addView(UiKit.ghostButton(c, "记入项目记忆") {
            val note = "远程删除 $branch 分支文件：${okPaths.joinToString(", ")}"
            val proj = state.workspace.project(repo.name)
            if (ProjectMemoryNotes.appendIfProjectExists(proj, note)) {
                host.toast("已写入项目记忆（.studio/memory.json）")
            } else {
                host.toast("本地工作区没有同名项目，未写入项目记忆")
            }
        })
        row.addView(UiKit.spacer(c, 6))
        row.addView(UiKit.button(c, "完成") { holder[0]?.dismiss() })
        val dlg = UiKit.dialog(c, "删除完成", UiKit.vstack(c).apply {
            addView(box)
            addView(row)
        })
        holder[0] = dlg
    }
}
