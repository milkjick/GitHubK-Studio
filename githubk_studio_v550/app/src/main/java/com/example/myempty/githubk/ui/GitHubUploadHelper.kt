package com.example.myempty.githubk.ui

import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.example.myempty.githubk.R
import com.example.myempty.githubk.git.GitRepo
import java.io.File

/**
 * 单文件上传助手（Contents API）：
 * - 选择任意本地文件（SAF），可输入目标目录/文件名，如 apk/release.apk；
 * - 可选传入项目 APK 列表，直接选择构建产物上传；
 * - GitHub Contents API 单文件上限 100MB（写满 Base64 前本地字节数 ≤ 100MB）。
 */
object GitHubUploadHelper {

    /** 从账号已有仓库中选择一个作为上传目标（若需要“新建仓库”请传 onCreate）。 */
    fun chooseRepo(
        host: PageHost,
        title: String,
        repos: List<GitRepo>,
        onCreate: (() -> Unit)? = null,
        onPick: (GitRepo) -> Unit
    ) {
        val holder = arrayOfNulls<android.app.Dialog>(1)
        val box = UiKit.vstack(host.context).apply { setPadding(0, 0, 0, 0) }
        box.addView(UiKit.label(host.context, "选择要上传到的 GitHub 仓库", color = R.color.muted, size = 12f))
        box.addView(UiKit.spacer(host.context, 8))
        if (onCreate != null) {
            box.addView(repoRow(host, "＋", "新建 GitHub 仓库", "创建仓库后自动打开上传", accent = true) {
                holder[0]?.dismiss(); onCreate()
            }, LinearLayout.LayoutParams(-1, -2))
            box.addView(UiKit.spacer(host.context, 4))
        }
        if (repos.isEmpty()) {
            box.addView(UiKit.spacer(host.context, 6))
            box.addView(UiKit.label(
                host.context,
                if (onCreate == null) "当前账号还没有仓库，请先到 GitHub 仓库页创建。" else "当前账号还没有仓库，可直接新建。",
                color = R.color.muted, size = 11f
            ))
        } else {
            repos.forEach { repo ->
                box.addView(UiKit.spacer(host.context, 6))
                box.addView(repoRow(
                    host,
                    if (repo.private) "🔒" else "🌐",
                    repo.fullName,
                    repo.description.ifBlank { "上传到 ${repo.defaultBranch.ifBlank { "main" }} 分支" },
                    accent = false
                ) { holder[0]?.dismiss(); onPick(repo) }, LinearLayout.LayoutParams(-1, -2))
            }
        }
        holder[0] = UiKit.dialog(host.context, title, box)
    }

    /** 打开“上传单个文件到指定仓库”对话框。 */
    fun uploadOneFile(
        host: PageHost,
        repo: GitRepo,
        projectApks: List<File>? = null
    ) {
        val pathInput = UiKit.input(host.context, "目标路径，如 apk/release.apk").apply {
            setSingleLine(true)
        }
        val status = TextView(host.context).apply {
            text = "尚未选择文件"
            textSize = 12f
            setTextColor(ThemeManager.colors.muted)
            setPadding(0, UiKit.dp(host.context, 2), 0, UiKit.dp(host.context, 4))
        }
        val pickRow = LinearLayout(host.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        var chosenUri: Uri? = null
        var chosenFile: File? = null

        fun setSelection(label: String) {
            status.text = label
            status.setTextColor(ThemeManager.colors.onSurface)
        }

        pickRow.addView(UiKit.ghostButton(host.context, "选择本地文件") {
            host.pickFile { uri ->
                if (uri != null) {
                    chosenUri = uri
                    chosenFile = null
                    val name = host.state.workspace.queryDisplayName(uri) ?: "upload_${System.currentTimeMillis()}"
                    setSelection("已选择：$name")
                    pathInput.setText(name)
                } else {
                    host.toast("已取消选择")
                }
            }
        })
        if (!projectApks.isNullOrEmpty()) {
            pickRow.addView(UiKit.spacer(host.context, 6))
            pickRow.addView(UiKit.ghostButton(host.context, "选项目 APK") {
                val names = projectApks.map { it.name }
                android.app.AlertDialog.Builder(host.context)
                    .setTitle("选择 APK")
                    .setItems(names.toTypedArray()) { _, which ->
                        chosenFile = projectApks[which]
                        chosenUri = null
                        val f = projectApks[which]
                        setSelection("已选择：${f.name} (${"%.1f".format(f.length() / 1024.0 / 1024.0)} MB)")
                        pathInput.setText("apk/${f.name}")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            })
        }
        val hint = TextView(host.context).apply {
            text = "GitHub Contents API 单文件上限 100MB；路径可含目录，GitHub 会自动创建。"
            textSize = 10.5f
            setTextColor(ThemeManager.colors.muted)
            setPadding(0, UiKit.dp(host.context, 6), 0, 0)
        }
        val start = UiKit.button(host.context, "开始上传") {
            val path = pathInput.text.toString().trim().trim('/')
            if (path.isBlank() || path.split('/').any { it == "." || it == ".." }) {
                host.toast("请输入有效的目标路径（不能为 . 或 ..）")
                return@button
            }
            if (chosenUri == null && chosenFile == null) {
                host.toast("请先选择文件")
                return@button
            }
            val progress = ProgressBar(host.context).apply { isIndeterminate = true }
            val ptext = TextView(host.context).apply {
                text = "正在上传 $path …"
                textSize = 12.5f
                typeface = Typeface.MONOSPACE
                setPadding(0, UiKit.dp(host.context, 4), 0, UiKit.dp(host.context, 4))
                setTextColor(ThemeManager.colors.onSurface)
            }
            val holder2 = arrayOfNulls<android.app.Dialog>(1)
            val pbox = UiKit.vstack(host.context).apply {
                setPadding(0, 0, 0, 0)
                addView(UiKit.label(host.context, "目标仓库：${repo.fullName}", color = R.color.muted, size = 12f))
                addView(ptext)
                addView(progress)
                addView(UiKit.spacer(host.context, 6))
                addView(UiKit.label(host.context, "可直接切到后台，上传会继续完成。", color = R.color.muted, size = 11f))
            }
            holder2[0] = UiKit.dialog(host.context, "上传中", pbox)
            Thread {
                val result = runCatching {
                    val bytes = if (chosenUri != null) {
                        val uri = chosenUri!!
                        host.context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    } else {
                        chosenFile!!.readBytes()
                    } ?: throw IllegalStateException("无法读取所选文件")
                    if (bytes.isEmpty()) throw IllegalStateException("所选文件为空")
                    if (bytes.size > 100L * 1024L * 1024L) throw IllegalStateException("文件超过 GitHub Contents API 的 100MB 上限")
                    val ok = host.state.gitHub.upsertFile(
                        repo.fullName, path, bytes,
                        "Upload $path via GitHubK Studio",
                        repo.defaultBranch.ifBlank { "main" }
                    )
                    if (!ok) throw IllegalStateException("GitHub 返回失败（请检查 token 权限与文件大小）")
                }
                host.runUi {
                    holder2[0]?.dismiss()
                    result.onSuccess {
                        host.toast("上传成功：$path")
                    }.onFailure { e ->
                        host.toast("上传失败：${e.message ?: "未知错误"}")
                    }
                }
            }.start()
        }
        (start.layoutParams as? LinearLayout.LayoutParams)?.apply { topMargin = UiKit.dp(host.context, 8) }

        val box = UiKit.vstack(host.context).apply {
            setPadding(0, 0, 0, 0)
            addView(UiKit.hstack(host.context).apply {
                addView(UiKit.label(host.context, "仓库：", color = R.color.muted, size = 12f))
                addView(UiKit.label(host.context, repo.fullName, color = R.color.primary, size = 12.5f).apply {
                    typeface = Typeface.DEFAULT_BOLD
                })
            })
            addView(UiKit.spacer(host.context, 8))
            addView(status)
            addView(pickRow)
            addView(UiKit.spacer(host.context, 10))
            addView(UiKit.label(host.context, "目标路径（可含目录）", color = R.color.muted, size = 12f))
            addView(UiKit.spacer(host.context, 4))
            addView(pathInput)
            addView(hint)
            addView(start)
        }
        UiKit.dialog(host.context, "上传到 ${repo.fullName}", box)
    }

    private fun repoRow(
        host: PageHost,
        icon: String,
        title: String,
        sub: String,
        accent: Boolean,
        onClick: () -> Unit
    ): LinearLayout {
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
            setPadding(UiKit.dp(host.context, 10), UiKit.dp(host.context, 8), UiKit.dp(host.context, 10), UiKit.dp(host.context, 8))
            setOnClickListener { onClick() }
        }
        row.addView(TextView(host.context).apply {
            text = icon
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(if (accent) android.graphics.Color.WHITE else ThemeManager.colors.onSurface)
            background = UiKit.rounded(host.context, if (accent) ThemeManager.colors.primary else ThemeManager.colors.surfaceAlt, 20)
            layoutParams = LinearLayout.LayoutParams(UiKit.dp(host.context, 34), UiKit.dp(host.context, 34))
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
                setPadding(0, UiKit.dp(host.context, 1), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(host.context).apply {
            text = "›"
            textSize = 20f
            setTextColor(ThemeManager.colors.muted)
        })
        return row
    }

    /** 发布 Release：创建 GitHub Release 并上传 APK 资产。支持覆盖（删除同标签旧 Release 后重建）。 */
    fun showCreateReleaseDialog(
        host: PageHost,
        repo: GitRepo,
        projectApks: List<File>? = null,
        defaultTag: String? = null
    ) {
        val c = host.context
        val tagInput = UiKit.input(c, "版本标签，如 v1.0.0")
        tagInput.setSingleLine(true)
        if (!defaultTag.isNullOrBlank()) tagInput.setText(defaultTag)
        val titleInput = UiKit.input(c, "Release 标题（可选）")
        titleInput.setSingleLine(true)
        val bodyInput = UiKit.input(c, "Release 描述（可选）", singleLine = false)
        val status = TextView(c).apply {
            text = "尚未选择 APK"
            textSize = 12f
            setTextColor(ThemeManager.colors.muted)
            setPadding(0, UiKit.dp(c, 2), 0, UiKit.dp(c, 4))
        }
        val pickRow = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        var chosenFile: File? = null
        var chosenUri: Uri? = null
        fun setSelection(label: String, f: File?, u: Uri?) {
            chosenFile = f
            chosenUri = u
            status.text = label
            status.setTextColor(ThemeManager.colors.onSurface)
        }
        pickRow.addView(UiKit.ghostButton(c, "选本地文件") {
            host.pickFile { uri ->
                if (uri != null) {
                    val name = host.state.workspace.queryDisplayName(uri) ?: "release.apk"
                    setSelection("已选择：$name", null, uri)
                } else host.toast("已取消选择")
            }
        })
        if (!projectApks.isNullOrEmpty()) {
            pickRow.addView(UiKit.spacer(c, 6))
            pickRow.addView(UiKit.ghostButton(c, "选项目 APK") {
                val names = projectApks.map { it.name }
                android.app.AlertDialog.Builder(c)
                    .setTitle("选择 APK")
                    .setItems(names.toTypedArray()) { _, which ->
                        val f = projectApks[which]
                        setSelection("已选择：${f.name} (${"%.1f".format(f.length() / 1024.0 / 1024.0)} MB)", f, null)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            })
        }
        val hint = TextView(c).apply {
            text = "发布后可在仓库 Releases 页面下载 APK；重复使用同标签会先询问覆盖。"
            textSize = 10.5f
            setTextColor(ThemeManager.colors.muted)
            setPadding(0, UiKit.dp(c, 6), 0, 0)
        }
        val start = UiKit.button(c, "创建 Release 并上传 APK") {
            val tag = tagInput.text.toString().trim()
            if (tag.isEmpty()) { host.toast("请输入版本标签"); return@button }
            if (chosenFile == null && chosenUri == null) { host.toast("请先选择 APK"); return@button }

            val progress = ProgressBar(c).apply { isIndeterminate = true }
            val ptext = TextView(c).apply {
                text = "正在发布 $tag …"
                textSize = 12.5f
                typeface = Typeface.MONOSPACE
                setPadding(0, UiKit.dp(c, 4), 0, UiKit.dp(c, 4))
                setTextColor(ThemeManager.colors.onSurface)
            }
            val holder2 = arrayOfNulls<android.app.Dialog>(1)
            val pbox = UiKit.vstack(c).apply {
                addView(UiKit.label(c, "目标仓库：${repo.fullName}", color = R.color.muted, size = 12f))
                addView(ptext)
                addView(progress)
                addView(UiKit.spacer(c, 6))
                addView(UiKit.label(c, "可直接切到后台，上传会继续完成。", color = R.color.muted, size = 11f))
            }
            holder2[0] = UiKit.dialog(c, "发布中", pbox, onCancel = {})
            fun releaseInBackground(overwriteExisting: Boolean, existingReleaseId: Long? = null) {
                Thread {
                    val result = runCatching {
                        val api = host.state.gitHub
                        if (overwriteExisting && existingReleaseId != null) {
                            if (!api.deleteRelease(repo.owner, repo.name, existingReleaseId)) {
                                throw IllegalStateException("删除旧 Release 失败（token 需 repo 权限）")
                            }
                        }
                        val title = titleInput.text.toString().trim()
                        val body = bodyInput.text.toString().trim()
                        val rel = api.createRelease(
                            repo.owner, repo.name, tag,
                            name = title.ifBlank { tag },
                            body = body
                        ) ?: throw IllegalStateException("创建 Release 失败（请检查 token 权限与标签是否冲突）")
                        val releaseId = rel.getLong("id")
                        val assetFile = chosenFile ?: run {
                            val uri = chosenUri!!
                            val bytes = c.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                ?: throw IllegalStateException("无法读取所选文件")
                            if (bytes.isEmpty()) throw IllegalStateException("所选文件为空")
                            val tmp = java.io.File(c.cacheDir, "release_${System.currentTimeMillis()}.apk")
                            tmp.writeBytes(bytes)
                            tmp
                        }
                        if (assetFile.length() <= 0) throw IllegalStateException("所选 APK 为空")
                        val asset = api.uploadReleaseAsset(
                            repo.owner, repo.name, releaseId, assetFile,
                            assetFile.name.substringAfterLast('/')
                        ) ?: throw IllegalStateException("上传 APK 失败（可能超过 GitHub 限制或网络异常）")
                        assetFile.delete()
                        "发布成功：${rel.optString("html_url")}"
                    }
                    host.runUi {
                        holder2[0]?.dismiss()
                        result.onSuccess { host.toast(it) }.onFailure { e ->
                            host.toast("发布失败：${e.message ?: "未知错误"}")
                        }
                    }
                }.start()
            }
            Thread {
                val existingId = runCatching {
                    val rels = host.state.gitHub.getReleases(repo.owner, repo.name)
                    if (rels == null) null else {
                        var found: Long? = null
                        for (i in 0 until rels.length()) {
                            val o = rels.optJSONObject(i) ?: continue
                            if (o.optString("tag_name") == tag) { found = o.optLong("id"); break }
                        }
                        found
                    }
                }.getOrNull()
                host.runUi {
                    if (existingId != null) {
                        UiKit.confirm(c, "标签已存在", "版本标签 $tag 已发布过。覆盖会先删除旧 Release 再重建，是否继续？") {
                            releaseInBackground(true, existingId)
                        }
                    } else {
                        releaseInBackground(false)
                    }
                }
            }.start()
        }
        (start.layoutParams as? LinearLayout.LayoutParams)?.apply { topMargin = UiKit.dp(c, 8) }

        val box = UiKit.vstack(c).apply {
            addView(UiKit.hstack(c).apply {
                addView(UiKit.label(c, "仓库：", color = R.color.muted, size = 12f))
                addView(UiKit.label(c, repo.fullName, color = R.color.primary, size = 12.5f).apply {
                    typeface = Typeface.DEFAULT_BOLD
                })
            })
            addView(UiKit.spacer(c, 8))
            addView(UiKit.label(c, "版本标签", color = R.color.muted, size = 12f))
            addView(UiKit.spacer(c, 4))
            addView(tagInput)
            addView(UiKit.spacer(c, 8))
            addView(UiKit.label(c, "Release 标题（默认用标签）", color = R.color.muted, size = 12f))
            addView(UiKit.spacer(c, 4))
            addView(titleInput)
            addView(UiKit.spacer(c, 8))
            addView(UiKit.label(c, "描述（可选）", color = R.color.muted, size = 12f))
            addView(UiKit.spacer(c, 4))
            addView(bodyInput)
            addView(UiKit.spacer(c, 10))
            addView(status)
            addView(pickRow)
            addView(hint)
            addView(start)
        }
        UiKit.dialog(c, "发布 Release · ${repo.name}", box, onCancel = {})
    }
}
