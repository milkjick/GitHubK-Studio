package com.example.myempty.githubk.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.example.myempty.githubk.R
import com.example.myempty.githubk.localai.BackendStatus
import com.example.myempty.githubk.localai.InferBackend
import com.example.myempty.githubk.localai.InferKind
import com.example.myempty.githubk.localai.InferTask
import com.example.myempty.githubk.localai.InferenceCatalog
import com.example.myempty.githubk.localai.InferenceComponent
import com.example.myempty.githubk.localai.InstallKind
import com.example.myempty.githubk.localai.InstallOutcome
import com.example.myempty.githubk.localai.LocalInferenceManager
import com.example.myempty.githubk.localai.LocalVisionEngine
import java.io.File

/**
 * v6.5 · 本地 AI 推理页（离线神经网络）
 *
 * 严格遵循 v6.4 确立的流程：检测 → 告知 → 确认 → 安装 → 校验 → 失败回滚 → 记账 → 运行。
 * 依赖全部安装在 App 私有目录 files/runtime 下，免 Root、不污染系统分区、可一键移除；
 * 图片始终在本机推理，不上传任何服务器。
 */
class LocalAiPage(private val host: PageHost) : RefreshablePage {

    private val ctx: Context get() = host.context
    private val engine: LocalVisionEngine by lazy { LocalVisionEngine(ctx) }
    private val mgr: LocalInferenceManager get() = engine.manager
    private val ui = Handler(Looper.getMainLooper())

    // ---- UI 引用 ----
    private var statusBox: TextView? = null
    private var resultBox: TextView? = null
    private var imageLabel: TextView? = null
    private var progressBar: ProgressBar? = null
    private var progressLabel: TextView? = null
    private var logView: TextView? = null
    private var logScroll: ScrollView? = null
    private var taskRow: LinearLayout? = null
    private val badges = LinkedHashMap<String, Pair<InferenceComponent, TextView>>()

    // ---- 运行状态 ----
    private val log = StringBuilder()
    private var busy = false
    private var picked: File? = null
    private var task: String = InferTask.AUTO

    // ================================================================ 视图

    fun buildView(): View {
        val root = UiKit.vstack(ctx).apply { setBackgroundColor(ThemeManager.colors.surface) }

        val bar = UiKit.hstack(ctx).apply {
            setBackgroundColor(ThemeManager.colors.surfaceElevated)
            setPadding(UiKit.dp(ctx, 8), UiKit.dp(ctx, 5), UiKit.dp(ctx, 8), UiKit.dp(ctx, 5))
        }
        bar.addView(UiKit.button(ctx, "‹") { host.popPage() })
        bar.addView(UiKit.title(ctx, "本地 AI", 18f).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setPadding(UiKit.dp(ctx, 8), 0, 0, 0)
        })
        bar.addView(UiKit.button(ctx, "↻") { refreshStatus() })
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(ctx)
        val body = UiKit.vstack(ctx).apply {
            setPadding(UiKit.dp(ctx, 14), UiKit.dp(ctx, 12), UiKit.dp(ctx, 14), UiKit.dp(ctx, 16))
        }
        body.addView(
            UiKit.pageHeader(
                ctx,
                "本地 AI 推理（离线）",
                "分类 / 目标检测 / OCR 全在本机运行 · 图片不出设备 · 免 Root"
            )
        )

        statusBox = UiKit.mono(ctx, "正在探测本机推理环境…", 12f).apply {
            setPadding(0, UiKit.dp(ctx, 6), 0, UiKit.dp(ctx, 8))
        }
        body.addView(statusBox, LinearLayout.LayoutParams(-1, -2))

        val quick = UiKit.hstack(ctx)
        quick.addView(UiKit.tonalButton(ctx, "一键准备 · 分类") { prepare(InferTask.CLASSIFY) })
        quick.addView(UiKit.spacer(ctx, 6))
        quick.addView(UiKit.tonalButton(ctx, "一键准备 · 检测") { prepare(InferTask.DETECT) })
        quick.addView(UiKit.spacer(ctx, 6))
        quick.addView(UiKit.ghostButton(ctx, "重新探测") { refreshStatus() })
        body.addView(quick, LinearLayout.LayoutParams(-1, -2))

        body.addView(UiKit.spacer(ctx, 10))
        body.addView(UiKit.section(ctx, "依赖与模型"))
        body.addView(UiKit.label(ctx, "可逐项安装 / 校验 / 移除，全部落盘于 App 私有目录，不影响系统。"))

        for ((groupName, comps) in InferenceCatalog.grouped()) {
            body.addView(UiKit.spacer(ctx, 8))
            body.addView(UiKit.label(ctx, groupName, color = R.color.primary, size = 12f))
            for (c in comps) {
                body.addView(UiKit.spacer(ctx, 6))
                body.addView(componentCard(c))
            }
        }

        body.addView(UiKit.spacer(ctx, 12))
        body.addView(UiKit.section(ctx, "运行识别"))
        body.addView(runCard())

        scroll.addView(body, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(logPanel(), LinearLayout.LayoutParams(-1, -2))

        refreshStatus()
        return root
    }

    override fun refresh() = refreshStatus()

    // ---------------- 依赖 / 模型卡片 ----------------

    private fun componentCard(c: InferenceComponent): View {
        val box = UiKit.vstack(ctx).apply {
            background = UiKit.rounded(
                ctx, ThemeManager.colors.surfaceElevated, 14, ThemeManager.colors.divider, 1
            )
            setPadding(UiKit.dp(ctx, 12), UiKit.dp(ctx, 10), UiKit.dp(ctx, 12), UiKit.dp(ctx, 10))
        }
        val head = UiKit.hstack(ctx)
        head.addView(TextView(ctx).apply {
            text = c.displayName
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ThemeManager.colors.onSurface)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        val badge = UiKit.badge(ctx, "未安装", R.color.muted)
        head.addView(badge)
        badges[c.id] = c to badge
        box.addView(head, LinearLayout.LayoutParams(-1, -2))

        box.addView(TextView(ctx).apply {
            text = buildString {
                append(kindLabel(c.kind)).append(" · ").append(InferBackend.label(c.backend))
                append(" · ").append(tasksLabel(c.tasks))
                if (c.sizeBytes > 0) append(" · ").append(c.sizeText)
                append(" · ").append(installKindLabel(c.installKind))
            }
            textSize = 11f
            setTextColor(ThemeManager.colors.muted)
            setPadding(0, UiKit.dp(ctx, 3), 0, 0)
        })
        box.addView(TextView(ctx).apply {
            text = c.purpose
            textSize = 11.5f
            setTextColor(ThemeManager.colors.muted)
            setPadding(0, UiKit.dp(ctx, 4), 0, UiKit.dp(ctx, 6))
        })

        val row = UiKit.hstack(ctx)
        row.addView(UiKit.tonalButton(ctx, "安装") { installWithConfirm(c, null) })
        row.addView(UiKit.spacer(ctx, 6))
        row.addView(UiKit.ghostButton(ctx, "校验") { verifyComponent(c) })
        row.addView(UiKit.spacer(ctx, 6))
        row.addView(UiKit.dangerButton(ctx, "移除") { removeWithConfirm(c) })
        box.addView(row, LinearLayout.LayoutParams(-1, -2))
        return box
    }

    // ---------------- 运行面板 ----------------

    private fun runCard(): View {
        val inner = UiKit.vstack(ctx)
        val box = UiKit.card(ctx, inner)

        val pick = UiKit.hstack(ctx)
        pick.addView(UiKit.tonalButton(ctx, "选择图片") { pickImage() })
        pick.addView(UiKit.spacer(ctx, 8))
        imageLabel = TextView(ctx).apply {
            text = "尚未选择图片"
            textSize = 11.5f
            setTextColor(ThemeManager.colors.muted)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        pick.addView(imageLabel)
        inner.addView(pick, LinearLayout.LayoutParams(-1, -2))

        inner.addView(TextView(ctx).apply {
            text = "识别任务"
            textSize = 12f
            setTextColor(ThemeManager.colors.onSurface)
            setPadding(0, UiKit.dp(ctx, 10), 0, UiKit.dp(ctx, 4))
        })
        taskRow = UiKit.hstack(ctx)
        inner.addView(taskRow, LinearLayout.LayoutParams(-1, -2))
        renderTaskChips()

        inner.addView(UiKit.spacer(ctx, 8))
        inner.addView(UiKit.button(ctx, "开始识别（本机推理）") { runInference() })
        inner.addView(UiKit.label(ctx, "首次使用请先点上方「一键准备」，装好依赖后再识别。"))

        resultBox = UiKit.mono(ctx, "结果将显示在这里。", 12f).apply {
            setPadding(0, UiKit.dp(ctx, 8), 0, 0)
        }
        inner.addView(resultBox, LinearLayout.LayoutParams(-1, -2))
        return box
    }

    private fun renderTaskChips() {
        val row = taskRow ?: return
        row.removeAllViews()
        val options = listOf(
            InferTask.AUTO to "自动",
            InferTask.CLASSIFY to "分类",
            InferTask.DETECT to "检测",
            InferTask.OCR to "OCR"
        )
        for ((id, text) in options) {
            row.addView(UiKit.chip(ctx, text, task == id) {
                task = id
                renderTaskChips()
            })
            row.addView(UiKit.spacer(ctx, 6))
        }
    }

    // ---------------- 日志面板 ----------------

    private fun logPanel(): View {
        val panel = UiKit.vstack(ctx).apply {
            setBackgroundColor(ThemeManager.colors.surfaceElevated)
            setPadding(UiKit.dp(ctx, 12), UiKit.dp(ctx, 8), UiKit.dp(ctx, 12), UiKit.dp(ctx, 10))
        }
        progressLabel = TextView(ctx).apply {
            text = "空闲"
            textSize = 11.5f
            setTextColor(ThemeManager.colors.muted)
        }
        panel.addView(progressLabel, LinearLayout.LayoutParams(-1, -2))

        progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        panel.addView(
            progressBar,
            LinearLayout.LayoutParams(-1, UiKit.dp(ctx, 6)).apply { topMargin = UiKit.dp(ctx, 4) }
        )

        val head = UiKit.hstack(ctx)
        head.addView(UiKit.label(ctx, "安装 / 运行日志", size = 11.5f).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        head.addView(UiKit.ghostButton(ctx, "清空") {
            log.setLength(0)
            logView?.text = ""
        })
        panel.addView(head, LinearLayout.LayoutParams(-1, -2))

        logScroll = ScrollView(ctx)
        logView = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10.5f
            setTextColor(ThemeManager.colors.onSurface)
            setPadding(UiKit.dp(ctx, 8), UiKit.dp(ctx, 6), UiKit.dp(ctx, 8), UiKit.dp(ctx, 6))
            text = ""
        }
        logScroll!!.addView(logView, LinearLayout.LayoutParams(-1, -2))
        panel.addView(logScroll, LinearLayout.LayoutParams(-1, UiKit.dp(ctx, 150)))
        return panel
    }

    // ================================================================ 环境探测

    private fun refreshStatus() {
        statusBox?.text = "正在探测本机推理环境…"
        Thread {
            val st = runCatching { mgr.probe(false) }.getOrNull()
            ui.post {
                statusBox?.text = if (st == null) "探测失败：内置运行时不可用" else statusText(st)
                for ((_, pair) in badges) {
                    val (comp, badge) = pair
                    val ok = runCatching { mgr.isInstalled(comp) }.getOrDefault(false)
                    badge.text = if (ok) "已安装" else "未安装"
                    badge.setTextColor(if (ok) ThemeManager.colors.success else ThemeManager.colors.muted)
                }
            }
        }.start()
    }

    private fun statusText(st: BackendStatus): String = buildString {
        append("内置运行时：").append(if (st.runtimeInstalled) "已安装" else "未安装")
        append("    proot 沙盒：").append(if (st.sandboxReady) "可用" else "不可用")
        if (!st.sandboxReady) {
            append("\n提示：请先到「设置 → 完整 IDE 环境中心」安装内置运行时，再回来装推理依赖。")
        }
        append("\npython：").append(st.pythonVersion ?: "—")
        append("    numpy：").append(st.numpyVersion ?: "—")
        append("    pillow：").append(st.pillowVersion ?: "—")
        append("\nonnxruntime：").append(st.onnxVersion ?: "—")
        append("    tflite：").append(st.tfliteVersion ?: "—")
        append("\ntesseract：").append(st.tesseractVersion ?: "—")
        if (st.tesseractLangs.isNotEmpty()) {
            append("（").append(st.tesseractLangs.joinToString(",")).append("）")
        }
    }

    // ================================================================ 安装 / 校验 / 移除

    private fun installWithConfirm(c: InferenceComponent, after: (() -> Unit)?) {
        if (busy) {
            host.toast("正在执行其他任务，请稍候…")
            return
        }
        val msg = buildString {
            append(c.displayName).append("\n\n")
            append("用途：").append(c.purpose).append('\n')
            append("类型：").append(kindLabel(c.kind))
            append(" · ").append(installKindLabel(c.installKind)).append('\n')
            if (c.sizeBytes > 0) append("大小：").append(c.sizeText).append('\n')
            c.urls().firstOrNull()?.let { append("来源：").append(it).append('\n') }
            if (c.license.isNotBlank()) append("许可：").append(c.license).append('\n')
            append("\n安装位置：App 私有目录 files/runtime（免 Root，可随时移除；失败自动回滚）。是否继续？")
        }
        UiKit.confirm(ctx, "确认安装 · ${c.displayName}", msg) { doInstall(c, after) }
    }

    private fun doInstall(c: InferenceComponent, after: (() -> Unit)?) {
        busy = true
        showProgress("安装 " + c.displayName, -1)
        appendLog("=== 安装 ${c.displayName}（${c.id}）===")
        Thread {
            val outcome = runCatching {
                mgr.install(
                    c,
                    onProgress = { p ->
                        ui.post {
                            onDownloadProgress(c.displayName, p.stage, p.doneText, p.percent, p.speedBytesPerSec)
                        }
                    },
                    onLine = { line -> appendLog(line) }
                )
            }.getOrElse { e ->
                InstallOutcome(
                    ok = false,
                    componentId = c.id,
                    displayName = c.displayName,
                    message = "${e.javaClass.simpleName}: ${e.message}"
                )
            }
            ui.post {
                busy = false
                hideProgress()
                if (outcome.ok) {
                    appendLog("✔ ${c.displayName} 安装完成")
                    host.toast("${c.displayName} 安装完成")
                } else if (outcome.rolledBack) {
                    appendLog("✘ ${c.displayName} 安装失败：${outcome.message}（已回滚，本机无残留）")
                    host.toast("安装失败，已回滚")
                } else {
                    appendLog("✘ ${c.displayName} 安装失败：${outcome.message}")
                    host.toast("安装失败")
                }
                refreshStatus()
                after?.invoke()
            }
        }.start()
    }

    private fun verifyComponent(c: InferenceComponent) {
        if (busy) {
            host.toast("正在执行其他任务，请稍候…")
            return
        }
        appendLog("=== 校验 ${c.displayName} ===")
        Thread {
            val res = runCatching { mgr.verify(c) }.getOrElse { false to (it.message ?: "校验异常") }
            ui.post {
                appendLog((if (res.first) "✔ " else "✘ ") + res.second)
                host.toast(if (res.first) "校验通过" else "校验未通过")
            }
        }.start()
    }

    private fun removeWithConfirm(c: InferenceComponent) {
        if (busy) {
            host.toast("正在执行其他任务，请稍候…")
            return
        }
        UiKit.confirm(
            ctx,
            "移除 · ${c.displayName}",
            "将从本机删除「${c.displayName}」的包 / 文件（不影响系统分区），之后可重新安装。是否继续？"
        ) {
            busy = true
            appendLog("=== 移除 ${c.displayName} ===")
            Thread {
                val o = runCatching { mgr.remove(c.id) }.getOrElse {
                    InstallOutcome(false, c.id, c.displayName, it.message ?: "移除异常")
                }
                ui.post {
                    busy = false
                    appendLog((if (o.ok) "✔ " else "✘ ") + o.message)
                    host.toast(o.message)
                    refreshStatus()
                }
            }.start()
        }
    }

    // ================================================================ 一键准备

    private fun prepare(taskWanted: String) {
        if (busy) {
            host.toast("正在执行其他任务，请稍候…")
            return
        }
        Thread {
            val plan = runCatching { mgr.planFor(taskWanted) }.getOrNull()
            ui.post {
                val p = plan
                if (p == null) {
                    host.toast("无法生成依赖方案，请先安装内置运行时")
                } else if (p.blockedReason != null) {
                    UiKit.dialog(ctx, "暂不可安装", UiKit.label(ctx, p.blockedReason ?: ""), null, null)
                } else if (p.ready) {
                    host.toast("${InferTask.label(taskWanted)} 依赖已就绪，可直接识别")
                } else {
                    UiKit.confirm(
                        ctx,
                        "准备「${InferTask.label(taskWanted)}」依赖",
                        p.describe() + "\n\n将按顺序安装以上 ${p.missing.size} 项，全部落盘在 App 私有目录（免 Root，可移除，失败自动回滚）。是否继续？"
                    ) {
                        installSequence(p.missing)
                    }
                }
            }
        }.start()
    }

    private fun installSequence(list: List<InferenceComponent>) {
        if (list.isEmpty()) {
            host.toast("没有需要安装的组件")
            return
        }
        busy = true
        showProgress("准备安装…", -1)
        appendLog("=== 批量安装 ${list.size} 项依赖 ===")
        Thread {
            var allOk = true
            for ((i, c) in list.withIndex()) {
                appendLog("--- [${i + 1}/${list.size}] ${c.displayName} ---")
                val o = runCatching {
                    mgr.install(
                        c,
                        onProgress = { p ->
                            val prefix = "[${i + 1}/${list.size}] "
                            ui.post {
                                onDownloadProgress(prefix + c.displayName, p.stage, p.doneText, p.percent, p.speedBytesPerSec)
                            }
                        },
                        onLine = { line -> appendLog(line) }
                    )
                }.getOrElse { e ->
                    InstallOutcome(false, c.id, c.displayName, "${e.javaClass.simpleName}: ${e.message}")
                }
                if (!o.ok) {
                    allOk = false
                    appendLog("✘ ${c.displayName}：${o.message}" + if (o.rolledBack) "（已回滚）" else "")
                    break
                }
                appendLog("✔ ${c.displayName} 完成")
            }
            ui.post {
                busy = false
                hideProgress()
                appendLog(if (allOk) "=== 全部依赖安装完成 ===" else "=== 安装中断，请查看上方日志 ===")
                host.toast(if (allOk) "依赖准备完成" else "依赖安装未完成")
                refreshStatus()
            }
        }.start()
    }

    // ================================================================ 运行识别

    private fun pickImage() {
        host.pickFile { uri ->
            if (uri == null) {
                host.toast("已取消选择")
            } else {
                val dest = File(ctx.cacheDir, "localai-pick.jpg")
                Thread {
                    val ok = runCatching { host.state.workspace.copyFileTo(uri, dest) }.getOrDefault(false)
                    ui.post {
                        if (ok && dest.isFile && dest.length() > 0L) {
                            picked = dest
                            imageLabel?.text = "已选：" + dest.name + "（" + InferenceComponent.formatSize(dest.length()) + "）"
                            resultBox?.text = "已就绪：" + dest.name + "\n点「开始识别」运行本机推理。"
                        } else {
                            host.toast("读取图片失败，请换一张试试")
                        }
                    }
                }.start()
            }
        }
    }

    private fun runInference() {
        val img = picked
        if (img == null || !img.isFile) {
            host.toast("请先选择一张图片")
            return
        }
        if (busy) {
            host.toast("正在执行其他任务，请稍候…")
            return
        }
        busy = true
        resultBox?.text = "识别中…（首次运行会稍慢）"
        val t = task
        Thread {
            val res = runCatching {
                engine.run(imagePath = img.absolutePath, task = t, topK = 5, conf = 0.25, iou = 0.45)
            }.getOrNull()
            ui.post {
                busy = false
                if (res == null) {
                    resultBox?.text = "识别异常：请查看下方日志"
                } else {
                    resultBox?.text = res.report()
                    if (res.log.isNotBlank()) appendLog(res.log)
                    if (!res.ok && res.hint.contains("安装")) {
                        appendLog("提示：可点上方「一键准备」安装缺失依赖。")
                    }
                }
            }
        }.start()
    }

    // ================================================================ 日志与进度

    private fun appendLog(line: String) {
        val l = line.trimEnd()
        if (l.isBlank()) return
        ui.post {
            log.append(l).append('\n')
            logView?.text = log.toString()
            logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun showProgress(title: String, percent: Int) {
        progressBar?.visibility = View.VISIBLE
        progressBar?.isIndeterminate = percent < 0
        if (percent >= 0) progressBar?.progress = percent
        progressLabel?.text = title
    }

    private fun hideProgress() {
        progressBar?.visibility = View.GONE
        progressBar?.isIndeterminate = false
        progressLabel?.text = "空闲"
    }

    private fun onDownloadProgress(name: String, stage: String, done: String, percent: Int, speed: Double) {
        progressBar?.visibility = View.VISIBLE
        if (percent >= 0) {
            progressBar?.isIndeterminate = false
            progressBar?.progress = percent
        }
        val speedText = if (speed > 0) "  " + String.format(java.util.Locale.US, "%.0fKB/s", speed / 1024.0) else ""
        progressLabel?.text = if (percent >= 0) {
            "$name · $stage $done ($percent%)$speedText"
        } else {
            "$name · $stage $done$speedText"
        }
    }

    // ================================================================ 文案

    private fun kindLabel(kind: String): String = when (kind) {
        InferKind.RUNTIME -> "运行时"
        InferKind.LIB -> "推理库"
        InferKind.MODEL -> "模型"
        InferKind.DATA -> "数据"
        InferKind.TOOL -> "工具"
        else -> kind
    }

    private fun tasksLabel(tasks: List<String>): String =
        if (tasks.isEmpty()) "通用" else tasks.joinToString("/") { InferTask.label(it) }

    private fun installKindLabel(k: String): String = when (k) {
        InstallKind.PKG -> "包管理器安装"
        InstallKind.FILE -> "单文件下载"
        InstallKind.ARCHIVE -> "压缩包解压"
        else -> k
    }
}
