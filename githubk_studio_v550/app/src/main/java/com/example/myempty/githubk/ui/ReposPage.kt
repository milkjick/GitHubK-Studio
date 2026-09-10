package com.example.myempty.githubk.ui

import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.example.myempty.githubk.git.GitRepo
import java.io.File

/**
 * ReposPage v3：GitHub 官方客户端风格仓库页。
 * - 分段：热门 / 我的
 * - 搜索栏（按关键词搜索 GitHub 仓库）
 * - 仓库列表卡片（点击打开应用内详情）
 * - 克隆：ZIP 下载 + 解压（无 git 命令依赖）
 */
class ReposPage(private val host: PageHost) {

    private val state = host.state
    private lateinit var listBox: LinearLayout
    private var since = "daily"
    private var mode = "trending"

    fun buildView(): View {
        val root = UiKit.vstack(host.context).apply {
            setPadding(UiKit.dp(host.context, 14), UiKit.dp(host.context, 10), UiKit.dp(host.context, 14), UiKit.dp(host.context, 8))
        }
        root.addView(UiKit.pageHeader(host.context, "仓库", "浏览热门与我的 GitHub 仓库"))

        // 分段：热门 / 我的
        val segRow = UiKit.hstack(host.context)
        listOf("trending" to "热门", "mine" to "我的").forEach { (key, label) ->
            segRow.addView(UiKit.chip(host.context, label, mode == key) {
                mode = key
                if (key == "mine" && !state.hasToken()) { host.toast("请先在设置中配置 GitHub Token"); return@chip }
                load()
            })
            segRow.addView(UiKit.spacer(host.context, 6))
        }
        root.addView(segRow)
        root.addView(UiKit.spacer(host.context, 8))

        // 周期切换（热门模式）
        val periodRow = UiKit.hstack(host.context)
        listOf("daily" to "今日", "weekly" to "本周", "monthly" to "本月").forEach { (key, label) ->
            periodRow.addView(UiKit.ghostButton(host.context, label) {
                since = key
                if (mode == "trending") load()
            })
            periodRow.addView(UiKit.spacer(host.context, 6))
        }
        root.addView(periodRow)
        root.addView(UiKit.spacer(host.context, 8))

        // 搜索栏
        val searchBox = UiKit.hstack(host.context).apply {
            addView(UiKit.input(host.context, "搜索 GitHub 仓库").apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                tag = "search"
            })
            addView(UiKit.spacer(host.context, 8))
            addView(UiKit.button(host.context, "搜索") { doSearch() })
            addView(UiKit.spacer(host.context, 6))
            addView(UiKit.iconButton(host.context, com.example.myempty.githubk.R.drawable.ic_plus, "新建") { showCreateRepo() })
        }
        root.addView(searchBox)
        root.addView(UiKit.spacer(host.context, 8))

        val scroll = ScrollView(host.context).apply { isFillViewport = true }
        listBox = UiKit.vstack(host.context)
        scroll.addView(listBox)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        load()
        return root
    }

    private fun input(): EditText? =
        (listBox.parent as? View)?.rootView?.findViewWithTag<EditText>("search")

    private fun load() {
        listBox.removeAllViews()
        listBox.addView(UiKit.label(host.context, "加载中...", color = com.example.myempty.githubk.R.color.muted))
        Thread {
            val list = try {
                if (mode == "trending") state.gitHub.trending(since)
                else state.gitHub.myRepos()
            } catch (e: Throwable) { emptyList() }
            host.runUi { renderRepos(list) }
        }.start()
    }

    private fun doSearch() {
        val q = input()?.text?.toString()?.trim()
        if (q.isNullOrBlank()) { load(); return }
        listBox.removeAllViews()
        listBox.addView(UiKit.label(host.context, "搜索中...", color = com.example.myempty.githubk.R.color.muted))
        Thread {
            val list = try { state.gitHub.search(q) } catch (e: Throwable) { emptyList() }
            host.runUi { renderRepos(list) }
        }.start()
    }

    private fun renderRepos(list: List<GitRepo>) {
        listBox.removeAllViews()
        if (list.isEmpty()) {
            listBox.addView(UiKit.card(host.context, UiKit.label(host.context, "暂无仓库。请确认 Token 有效或网络正常。")))
            return
        }
        list.forEach { repo ->
            listBox.addView(UiKit.repoCard(
                host.context,
                name = repo.name,
                owner = repo.owner,
                desc = repo.description,
                language = repo.language,
                stars = repo.stars,
                forks = repo.forks,
                updatedAt = repo.updatedAt,
                avatarColorRes = com.example.myempty.githubk.R.color.primary,
                langColorRes = com.example.myempty.githubk.R.color.accent,
                onClick = { RepoBrowser(host, repo).show() },
                actions = { row ->
                    row.addView(UiKit.ghostButton(host.context, "详情") { RepoBrowser(host, repo).show() })
                    row.addView(UiKit.spacer(host.context, 6))
                    row.addView(UiKit.ghostButton(host.context, "克隆") { cloneRepo(repo) })
                    row.addView(UiKit.spacer(host.context, 6))
                    row.addView(UiKit.ghostButton(host.context, "管理") { showManage(repo) })
                }
            ))
            listBox.addView(UiKit.spacer(host.context, 8))
        }
    }

    private fun showCreateRepo() {
        if (!state.hasToken()) { host.toast("请先配置 GitHub Token"); return }
        var isPrivate = true
        val name = UiKit.input(host.context, "例如 my-awesome-app").apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val desc = UiKit.input(host.context, "描述（可选）").apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val privSwitch = android.widget.Switch(host.context).apply {
            text = "私有仓库"
            isChecked = true
            setOnCheckedChangeListener { _, checked -> isPrivate = checked }
        }
        val box = UiKit.vstack(host.context).apply {
            setPadding(0, 0, 0, 0)
            addView(UiKit.label(host.context, "仓库名称（必填）", color = com.example.myempty.githubk.R.color.muted, size = 12f))
            addView(UiKit.spacer(host.context, 4))
            addView(name)
            addView(UiKit.spacer(host.context, 10))
            addView(UiKit.label(host.context, "描述（可选）", color = com.example.myempty.githubk.R.color.muted, size = 12f))
            addView(UiKit.spacer(host.context, 4))
            addView(desc)
            addView(UiKit.spacer(host.context, 10))
            addView(privSwitch)
            addView(UiKit.spacer(host.context, 6))
            addView(UiKit.label(host.context, "创建后将自动初始化 README，并刷新到「我的」列表。", color = com.example.myempty.githubk.R.color.muted, size = 11f))
        }
        UiKit.dialog(host.context, "新建 GitHub 仓库", box, onOk = {
            val n = name.text.toString().trim()
            if (n.isBlank()) { host.toast("仓库名称不能为空"); return@dialog }
            val d = desc.text.toString().trim()
            host.toast("正在创建仓库…")
            Thread {
                try {
                    state.gitHub.createRepo(n, d, isPrivate)
                    host.runUi {
                        mode = "mine"
                        load()
                        host.toast("仓库已创建：$n")
                    }
                } catch (e: Throwable) {
                    host.runUi { host.toast("创建失败：${e.message}") }
                }
            }.start()
        })
    }

    /** 克隆：ZIP 下载 + 解压（无 git 命令依赖）。 */
    private fun cloneRepo(repo: GitRepo) {
        if (!state.hasToken()) { host.toast("请先配置 GitHub Token"); return }
        val dir = File(state.workspace.rootDir(), repo.name)
        if (dir.exists()) { host.toast("项目已存在：${repo.name}"); return }
        host.toast("正在下载 ${repo.name} ...")
        val zipFile = File(host.context.cacheDir, "${repo.name}_${System.currentTimeMillis()}.zip")
        Thread {
            val ok = state.gitHub.downloadZip(repo.fullName, zipFile)
            host.runUi {
                if (!ok) { host.toast("下载失败，请检查网络"); zipFile.delete(); return@runUi }
                val imported = state.workspace.importZip(zipFile, repo.name)
                zipFile.delete()
                if (imported) {
                    state.workspace.scan()
                    host.toast("克隆成功：${repo.name}")
                    state.workspace.project(repo.name)?.let { host.openEditor(it) }
                } else {
                    host.toast("解压失败，请重试")
                }
            }
        }.start()
    }

    /** 仓库管理：全屏管理页（Star/Fork/分支/导入/重命名/删除）。 */
    private fun showManage(repo: GitRepo) {
        RepoManagePage(host, repo).show()
    }

}
