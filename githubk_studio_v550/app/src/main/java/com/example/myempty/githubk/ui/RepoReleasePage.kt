package com.example.myempty.githubk.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.example.myempty.githubk.R
import com.example.myempty.githubk.git.GitHubOpResult
import com.example.myempty.githubk.git.GitRepo
import com.example.myempty.githubk.git.gitHubErrorText
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * RepoReleasePage：发布 Release 全屏表单（v5.3 大文件分块上传）。
 *
 * - 版本号 / 标题 / 发布说明 / APK 文件（仅 .apk，GitHub Release 最大支持 2GB）
 * - 开关：预发布、覆盖同名版本
 * - 提交前确认弹窗；发布过程独立线程执行；错误按 HTTP 分类提示
 * - >100MB 自动切换分块上传（8MB/块、进度条、可取消、可续传）
 * - 选中即校验文件大小并显示；读取失败给出“权限失效”提示并可重选
 * - 成功后可复制下载链接 / 写入本地项目记忆备忘
 */
class RepoReleasePage(private val host: PageHost, private val repo: GitRepo) {

    private val state get() = host.state
    private val c get() = host.context
    private val api get() = state.gitHub

    // GitHub Release 单文件上限 2GB
    private val MAX_FILE = 2L * 1024L * 1024L * 1024L
    // 接近 2GB 提醒阈值（≥90%）
    private val NEAR_MAX = (MAX_FILE * 0.90).toLong()
    // 超过该大小自动切换分块上传（100MB）
    private val SIMPLE_LIMIT = 100L * 1024L * 1024L

    private lateinit var statusLine: TextView
    private lateinit var tagInput: EditText
    private lateinit var titleInput: EditText
    private lateinit var bodyInput: EditText
    private lateinit var fileStatus: TextView
    private lateinit var uploadArea: LinearLayout
    private lateinit var uploadBar: ProgressBar
    private lateinit var uploadText: TextView
    private var chosenFile: File? = null
    private var chosenName: String = ""
    private var chosenSize: Long = 0L
    private var prerelease = false
    private var overwrite = false
    private var busy = false
    private var prevAutoTitle: String = ""
    private lateinit var historyArea: LinearLayout
    private var historyLoaded = false

    // 大文件上传状态
    private var uploadCancelled = false
    private var cancelAction: (() -> Unit)? = null
    // 断点续传：保存已创建的 Release，避免重复创建
    private var pendingReleaseId: Long? = null
    private var pendingReleaseTag: String = ""
    private var pendingHtmlUrl: String = ""

    fun show() {
        host.pushPage(buildView())
        loadHistory()
    }

    private fun buildView(): View {
        val root = UiKit.vstack(c)
        root.setPadding(UiKit.dp(c, 14), UiKit.dp(c, 8), UiKit.dp(c, 14), UiKit.dp(c, 8))

        // 顶栏：返回 + 标题
        val top = UiKit.hstack(c).apply {
            addView(UiKit.ghostButton(c, "← 返回") { host.popPage() })
            addView(UiKit.spacer(c, 8))
            addView(UiKit.vstack(c).apply {
                addView(TextView(c).apply {
                    text = "发布 Release"
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
        root.addView(UiKit.spacer(c, 4))

        // Release 历史摘要 chips（点击可直接填入版本号）
        root.addView(UiKit.label(c, "近期 Releases（点击填入版本号）", color = R.color.muted, size = 11f))
        val hscroll = android.widget.HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
        }
        historyArea = UiKit.hstack(c).apply {
            setPadding(0, UiKit.dp(c, 4), 0, UiKit.dp(c, 6))
        }
        hscroll.addView(historyArea)
        root.addView(hscroll)
        root.addView(UiKit.spacer(c, 2))

        statusLine = TextView(c).apply {
            text = "填写版本信息，将创建 GitHub Release 并上传 APK。"
            textSize = 12.5f
            setTextColor(ThemeManager.colors.muted)
            setPadding(UiKit.dp(c, 4), UiKit.dp(c, 2), UiKit.dp(c, 4), UiKit.dp(c, 4))
        }
        root.addView(statusLine)

        // 大文件上传进度区域（默认隐藏）
        uploadArea = UiKit.vstack(c).apply {
            visibility = View.GONE
            background = UiKit.rounded(c, ThemeManager.colors.surfaceAlt, 14, ThemeManager.colors.divider, 1)
            setPadding(UiKit.dp(c, 14), UiKit.dp(c, 12), UiKit.dp(c, 14), UiKit.dp(c, 12))
        }
        uploadArea.addView(TextView(c).apply {
            text = "正在上传 APK（分块 8MB）…"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ThemeManager.colors.onSurface)
        })
        uploadArea.addView(UiKit.spacer(c, 8))
        uploadBar = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 10000
            progress = 0
        }
        uploadArea.addView(uploadBar)
        uploadArea.addView(UiKit.spacer(c, 6))
        uploadText = TextView(c).apply {
            text = "已上传 0.0 MB / 总 0.0 MB"
            textSize = 12.5f
            setTextColor(ThemeManager.colors.muted)
        }
        uploadArea.addView(uploadText)
        uploadArea.addView(UiKit.spacer(c, 8))
        uploadArea.addView(UiKit.ghostButton(c, "取消上传") { cancelUpload() })
        uploadArea.addView(UiKit.spacer(c, 2))
        root.addView(uploadArea)
        root.addView(UiKit.spacer(c, 4))

        val scroll = ScrollView(c).apply { isFillViewport = false }
        val content = UiKit.vstack(c).apply { setPadding(0, 0, 0, UiKit.dp(c, 12)) }

        // 版本号
        content.addView(sectionTitle("版本号（推荐以 v 开头，如 v0.7.8）"))
        tagInput = UiKit.input(c, "例如 v0.7.8")
        tagInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, cc: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, cc: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val tag = s.toString().trim()
                val desired = if (tag.isBlank()) "" else "Release $tag"
                val cur = titleInput.text.toString()
                if (cur.isBlank() || cur == prevAutoTitle) {
                    if (desired != cur) {
                        titleInput.setText(desired)
                        titleInput.setSelection(desired.length)
                    }
                }
                prevAutoTitle = desired
            }
        })
        content.addView(tagInput)
        content.addView(UiKit.spacer(c, 10))

        // 标题
        content.addView(sectionTitle("Release 标题（可选）"))
        titleInput = UiKit.input(c, "默认自动填充 Release {版本号}")
        content.addView(titleInput)
        content.addView(UiKit.spacer(c, 10))

        // 发布说明
        content.addView(sectionTitle("发布说明（可选 · 多行 · 可留空）"))
        bodyInput = UiKit.textArea(c, "填写变更日志 / 更新说明…")
        content.addView(bodyInput)
        content.addView(UiKit.spacer(c, 10))

        // APK 文件选择
        content.addView(sectionTitle("APK 文件（仅 .apk · GitHub Release 最大支持 2GB）"))
        fileStatus = TextView(c).apply {
            text = "尚未选择 APK"
            textSize = 12.5f
            setTextColor(ThemeManager.colors.muted)
            setPadding(0, 0, 0, UiKit.dp(c, 6))
        }
        content.addView(fileStatus)
        content.addView(UiKit.button(c, "选择 APK 文件") { pickApk() })
        content.addView(UiKit.spacer(c, 10))

        // 开关：预发布
        content.addView(sectionTitle("发布选项"))
        content.addView(UiKit.switchRow(
            c, "设为预发布版本", "勾选后标记为 prerelease（默认关闭）", initial = false
        ) { v -> prerelease = v })
        content.addView(UiKit.spacer(c, 6))
        content.addView(UiKit.switchRow(
            c, "覆盖同名版本", "若该版本标签已存在：先删除旧 Release 与标签，再重新发布（默认关闭）", initial = false
        ) { v -> overwrite = v })
        content.addView(UiKit.spacer(c, 14))

        content.addView(UiKit.button(c, "确认发布到 GitHub") { requestConfirm() })
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

    private fun loadHistory() {
        if (historyLoaded) return
        historyLoaded = true
        Thread {
            val r = api.releasesOp(repo.owner, repo.name)
            host.runUi {
                if (!r.ok) return@runUi // 历史仅辅助展示，失败不打扰用户
                val arr = r.dataArray ?: return@runUi
                if (arr.length() == 0) {
                    historyArea.addView(TextView(c).apply {
                        text = "（暂无 Release 历史）"
                        textSize = 12f
                        setTextColor(ThemeManager.colors.muted)
                    })
                    return@runUi
                }
                val count = minOf(arr.length(), 6)
                for (i in 0 until count) {
                    val o = arr.optJSONObject(i) ?: continue
                    val tag = o.optString("tag_name")
                    if (tag.isBlank()) continue
                    val date = o.optString("published_at").take(10)
                    addHistoryChip(tag, date)
                }
            }
        }.start()
    }

    private fun addHistoryChip(tag: String, date: String) {
        val chip = TextView(c).apply {
            text = if (date.isBlank()) tag else "$tag · $date"
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(ThemeManager.colors.primary)
            background = UiKit.rounded(c, ThemeManager.colors.surfaceAlt, 20, ThemeManager.colors.divider, 1)
            setPadding(UiKit.dp(c, 10), UiKit.dp(c, 5), UiKit.dp(c, 10), UiKit.dp(c, 5))
            setOnClickListener {
                tagInput.setText(tag)
                tagInput.setSelection(tag.length)
            }
        }
        historyArea.addView(chip)
        historyArea.addView(UiKit.spacer(c, 6))
    }

    // ===================== 文件选择与校验 =====================

    private fun pickApk() {
        if (busy) return
        host.pickFile { uri ->
            if (uri == null) { host.toast("已取消选择"); return@pickFile }
            val name = state.workspace.queryDisplayName(uri) ?: "release.apk"
            if (!name.substringAfterLast('.').equals("apk", ignoreCase = true)) {
                host.toast("仅支持选择 APK 文件（.apk）")
                return@pickFile
            }
            // 清理旧缓存
            chosenFile?.delete()
            chosenFile = null
            chosenName = ""
            chosenSize = 0L

            // 立即获取大小做预校验（避免先读整个文件）
            val preSize = queryOpenableSize(uri)
            if (preSize > MAX_FILE) {
                rejectTooBig(name)
                return@pickFile
            }
            fileStatus.text = "已选择：$name（%.1f MB · 正在读取到本地缓存…）".format(if (preSize > 0) preSize / 1024.0 / 1024.0 else 0.0)
            fileStatus.setTextColor(ThemeManager.colors.muted)

            Thread {
                // 流式复制到私有缓存（8MB 块），避免上传期间 Uri 授权失效
                val outcome = copyToLocal(uri)
                host.runUi {
                    when {
                        outcome == null -> {
                            fileStatus.text = "⚠️ 读取失败：$name"
                            fileStatus.setTextColor(ThemeManager.colors.error)
                            host.toast("⚠️ Android 文档访问权限已失效，请重新选择该文件")
                        }
                        outcome.tooBig || outcome.size > MAX_FILE -> rejectTooBig(name)
                        else -> {
                            chosenFile = outcome.file
                            chosenName = name
                            chosenSize = outcome.size
                            val mb = outcome.size / 1024.0 / 1024.0
                            val mode = if (outcome.size >= SIMPLE_LIMIT) "· 将分块上传" else "· 简单上传"
                            val near = if (outcome.size >= NEAR_MAX) " · ⚠️ 接近 2GB 上限" else ""
                            fileStatus.text = "已选择：$name（%.1f MB%s%s）".format(mb, mode, near)
                            fileStatus.setTextColor(if (outcome.size >= NEAR_MAX) ThemeManager.colors.warning else ThemeManager.colors.success)
                        }
                    }
                }
            }.start()
        }
    }

    /** 通过 AssetFileDescriptor 快速获取文件大小；失败返回 -1（未知）。 */
    private fun queryOpenableSize(uri: Uri): Long = try {
        c.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    } catch (_: Exception) { -1L }

    private class CopyOutcome(val file: File?, val size: Long, val tooBig: Boolean = false)

    /**
     * 流式把 Uri 内容复制到私有缓存文件（8MB 块），带重试。
     * 返回 null 表示读取失败（授权失效或 IO 错误）；超过 2GB 返回 tooBig=true。
     */
    private fun copyToLocal(uri: Uri): CopyOutcome? {
        val dest = File(c.cacheDir, "release_${System.currentTimeMillis()}.apk")
        for (attempt in 0 until 3) {
            try {
                val ins = c.contentResolver.openInputStream(uri)
                    ?: throw IOException("无法打开数据流")
                dest.delete() // 重试时清掉半成品
                ins.use { input ->
                    FileOutputStream(dest).use { out ->
                        val buf = ByteArray(8 * 1024 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                            // 超限直接中止
                            if (total > MAX_FILE) {
                                dest.delete()
                                return CopyOutcome(null, total, true)
                            }
                        }
                        return CopyOutcome(dest, total)
                    }
                }
            } catch (e: IOException) {
                dest.delete()
                try { Thread.sleep(300L * (attempt + 1)) } catch (_: InterruptedException) {}
            } catch (e: Exception) {
                dest.delete()
                try { Thread.sleep(300L * (attempt + 1)) } catch (_: InterruptedException) {}
            }
        }
        return null
    }

    private fun rejectTooBig(name: String) {
        chosenFile = null
        chosenName = ""
        chosenSize = 0L
        fileStatus.text = "❌ 文件超出 GitHub Release 最大 2GB 限制，无法上传：$name"
        fileStatus.setTextColor(ThemeManager.colors.error)
    }

    // ===================== 发布流程 =====================

    private fun requestConfirm() {
        if (busy) return
        val tag = tagInput.text.toString().trim()
        if (tag.isBlank()) { host.toast("请输入版本号，推荐以 v 开头"); return }
        if (!tag.startsWith("v") && !tag.contains(".")) {
            host.toast("版本号格式建议：v0.7.8")
            return
        }
        if (chosenFile == null) { host.toast("请先选择 APK 文件"); return }
        val apkName = chosenName.ifBlank { chosenFile!!.name }

        val modeDesc = if (chosenSize >= SIMPLE_LIMIT) "分块上传（8MB/块 · 可取消 · 可续传）" else "简单上传"
        val box = UiKit.vstack(c).apply {
            setPadding(0, 0, 0, 0)
            addView(UiKit.hstack(c).apply {
                addView(UiKit.label(c, "仓库：", color = R.color.muted, size = 13.5f))
                addView(UiKit.label(c, repo.fullName, color = R.color.primary, size = 13.5f).apply {
                    typeface = Typeface.DEFAULT_BOLD
                })
            })
            addView(UiKit.spacer(c, 6))
            addView(keyValue("版本标签", tag))
            addView(UiKit.spacer(c, 6))
            addView(keyValue("预发布", if (prerelease) "是" else "否"))
            addView(UiKit.spacer(c, 6))
            addView(keyValue("APK 文件", apkName))
            addView(UiKit.spacer(c, 6))
            addView(keyValue("文件大小", "%.1f MB".format(chosenSize / 1024.0 / 1024.0)))
            addView(UiKit.spacer(c, 6))
            addView(keyValue("上传方式", modeDesc))
            if (overwrite) {
                addView(UiKit.spacer(c, 8))
                addView(UiKit.label(c, "⚠️ 覆盖开启：将移除该标签原有 Release 与附件！该变更直接作用于 GitHub 远程仓库。", color = R.color.warning, size = 12f))
            }
            addView(UiKit.spacer(c, 4))
            addView(UiKit.label(c, "发布成功后仅修改 GitHub 远程，不会改动本地工作区文件。", color = R.color.muted, size = 11f))
        }
        UiKit.confirmEx(c, "即将发布 Release", box, "确认发布", danger = false, onOk = { startPublish() }, onCancel = {})
    }

    private fun keyValue(k: String, v: String): LinearLayout = UiKit.hstack(c).apply {
        addView(UiKit.label(c, "$k：", color = R.color.muted, size = 13.5f))
        addView(UiKit.label(c, v, color = R.color.on_surface, size = 13.5f).apply {
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        })
    }

    private fun startPublish() {
        if (busy) return
        busy = true
        val tag = tagInput.text.toString().trim()
        val title = titleInput.text.toString().trim()
        val body = bodyInput.text.toString().trim()
        setBusy("正在发布 $tag …")

        // 断点续传：若上次同一 tag 已创建 Release 且上传中断，直接复用，不再重建
        val resume = pendingReleaseId != null && pendingReleaseTag == tag

        Thread {
            if (!resume) {
                val rel = createRelease(tag, title, body)
                if (!rel.ok) {
                    host.runUi { failure(rel) }
                    return@Thread
                }
                val rid = rel.data?.optLong("id") ?: 0L
                pendingReleaseId = rid
                pendingReleaseTag = tag
                pendingHtmlUrl = rel.data?.optString("html_url").orEmpty()
            }
            val rid = pendingReleaseId ?: 0L
            if (rid <= 0L) {
                host.runUi {
                    statusLine.text = "❌ 未能获取 Release id"
                    statusLine.setTextColor(ThemeManager.colors.error)
                    busy = false
                }
                return@Thread
            }
            // 上传 APK 资产
            val asset = chosenFile!!
            val size = asset.length()
            val useChunked = size >= SIMPLE_LIMIT

            host.runUi {
                if (useChunked) showUploadPanel(size) else setBusy("正在上传 APK（简单上传）…")
            }
            val upResult: GitHubOpResult = if (useChunked) {
                api.uploadReleaseAssetAdvanced(
                    repo.owner, repo.name, rid, asset, asset.name.substringAfterLast('/'),
                    onProgress = { sent, tot ->
                        host.runUi { updateUploadProgress(sent, tot) }
                    },
                    isCancelled = { uploadCancelled },
                    registerCancel = { fn -> cancelAction = fn }
                )
            } else {
                api.uploadReleaseAssetOp(repo.owner, repo.name, rid, asset, asset.name.substringAfterLast('/'))
            }

            host.runUi {
                if (useChunked) hideUploadPanel()
                if (uploadCancelled) {
                    uploadCancelled = false
                    cancelAction = null
                    statusLine.text = "已取消上传（Release 已创建，附件未上传）。可再次点击“确认发布到 GitHub”续传。"
                    statusLine.setTextColor(ThemeManager.colors.warning)
                    busy = false
                    return@runUi
                }
                cancelAction = null
                if (upResult.ok) {
                    val htmlUrl = pendingHtmlUrl.ifBlank { relHtmlUrl() }
                    // 成功后清空续传状态，避免同名再次误用
                    pendingReleaseId = null
                    pendingReleaseTag = ""
                    pendingHtmlUrl = ""
                    showSuccess(tag, htmlUrl, asset.name)
                } else {
                    val msg = gitHubErrorText(upResult, "上传失败，请稍后重试")
                    statusLine.text = "❌ $msg"
                    statusLine.setTextColor(ThemeManager.colors.error)
                    host.toast("❌ 上传失败：$msg")
                }
                busy = false
            }
        }.start()
    }

    /** 创建 Release（含同名检查/覆盖逻辑）。 */
    private fun createRelease(tag: String, title: String, body: String): GitHubOpResult {
        // 检查同名版本
        val exist = api.findReleaseByTag(repo.owner, repo.name, tag)
        if (!exist.ok) return exist
        val found = exist.data
        if (!overwrite && found != null) {
            return GitHubOpResult.fail(422, "tag already exists")
        }
        // 覆盖：先删除旧 Release，再删除 Git 标签
        if (overwrite && found != null) {
            val del = api.deleteReleaseOp(repo.owner, repo.name, found.optLong("id"))
            if (!del.ok) return del
            val delTag = api.deleteTagOp(repo.fullName, tag)
            if (!delTag.ok) return delTag
        }
        val releaseTitle = title.ifBlank { "Release $tag" }
        return api.createReleaseOp(
            repo.owner, repo.name, tag,
            name = releaseTitle, body = body.ifBlank { null },
            prerelease = prerelease,
            targetCommitish = repo.defaultBranch.ifBlank { "main" }
        )
    }

    private fun relHtmlUrl(): String = buildString {
        append("https://github.com/").append(repo.fullName).append("/releases/tag/").append(pendingReleaseTag)
    }

    // ===================== 上传进度 UI =====================

    private fun showUploadPanel(totalBytes: Long) {
        uploadArea.visibility = View.VISIBLE
        uploadBar.progress = 0
        uploadText.text = "已上传 0.0 MB / 总 %.1f MB".format(totalBytes / 1024.0 / 1024.0)
        uploadCancelled = false
        cancelAction = null
    }

    private fun updateUploadProgress(sent: Long, total: Long) {
        if (total > 0) {
            uploadBar.progress = ((sent * 10000L) / total).toInt()
        }
        uploadText.text = "已上传 %.1f MB / 总 %.1f MB".format(sent / 1024.0 / 1024.0, total / 1024.0 / 1024.0)
    }

    private fun cancelUpload() {
        if (!uploadCancelled) {
            uploadCancelled = true
            uploadText.text = "正在取消…"
            cancelAction?.invoke()
        }
    }

    private fun hideUploadPanel() {
        uploadArea.visibility = View.GONE
    }

    private fun failure(rel: GitHubOpResult) {
        val msg = if (!rel.ok && rel.httpCode == 422 && !overwrite) {
            "该版本标签已存在，请修改版本号或开启覆盖选项"
        } else {
            gitHubErrorText(rel, "发布失败，请稍后重试")
        }
        statusLine.text = "❌ $msg"
        statusLine.setTextColor(ThemeManager.colors.error)
        host.toast("❌ 发布失败：$msg")
        busy = false
    }

    private fun showSuccess(tag: String, htmlUrl: String, apkName: String) {
        statusLine.text = "✅ Release 发布完成！$tag"
        statusLine.setTextColor(ThemeManager.colors.success)
        val box = UiKit.vstack(c).apply {
            setPadding(0, 0, 0, 0)
            addView(UiKit.label(c, "✅ Release 发布完成！$tag", color = R.color.success, size = 15f).apply {
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(UiKit.spacer(c, 4))
            addView(UiKit.label(c, "附件：${apkName}", color = R.color.muted, size = 12f))
            addView(UiKit.spacer(c, 8))
            addView(TextView(c).apply {
                text = htmlUrl
                textSize = 12.5f
                typeface = Typeface.MONOSPACE
                setTextColor(ThemeManager.colors.primary)
                background = UiKit.rounded(c, ThemeManager.colors.surfaceAlt, 10)
                setPadding(UiKit.dp(c, 10), UiKit.dp(c, 8), UiKit.dp(c, 10), UiKit.dp(c, 8))
            })
            addView(UiKit.spacer(c, 6))
            addView(UiKit.label(c, "可点击下方“复制下载链接”分享给他人。", color = R.color.muted, size = 11f))
        }
        val row = UiKit.hstack(c).apply { setPadding(0, UiKit.dp(c, 8), 0, 0) }
        row.addView(UiKit.ghostButton(c, "复制链接") {
            copyText(htmlUrl)
            host.toast("下载链接已复制")
        })
        row.addView(UiKit.spacer(c, 6))
        row.addView(UiKit.tonalButton(c, "记入项目记忆") {
            val proj = state.workspace.project(repo.name)
            if (ProjectMemoryNotes.appendIfProjectExists(proj, "已发布 Release $tag，附件 ${apkName}")) {
                host.toast("已写入项目记忆（.studio/memory.json）")
            } else {
                host.toast("本地工作区没有同名项目，未写入项目记忆")
            }
        })
        val dlgHolder = arrayOfNulls<android.app.Dialog>(1)
        val dlg = UiKit.dialog(c, "发布成功", UiKit.vstack(c).apply {
            addView(box)
            addView(row)
            addView(UiKit.spacer(c, 4))
            addView(UiKit.button(c, "完成") { dlgHolder[0]?.dismiss() })
        })
        dlgHolder[0] = dlg
    }

    private fun copyText(text: String) {
        runCatching {
            val cm = c.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("release", text))
        }
    }

    private fun setBusy(msg: String) {
        statusLine.text = msg
        statusLine.setTextColor(ThemeManager.colors.muted)
    }
}
