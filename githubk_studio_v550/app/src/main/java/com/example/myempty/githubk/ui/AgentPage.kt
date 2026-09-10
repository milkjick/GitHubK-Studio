package com.example.myempty.githubk.ui

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import com.example.myempty.githubk.R
import com.example.myempty.githubk.ai.AgentChatStore
import com.example.myempty.githubk.ai.AgentEvent
import com.example.myempty.githubk.ai.AgentResult
import com.example.myempty.githubk.ai.AgentRuntime
import com.example.myempty.githubk.ai.ChatMessage
import com.example.myempty.githubk.ai.ChatMsg
import com.example.myempty.githubk.ai.ChatSession
import com.example.myempty.githubk.core.Project
import com.example.myempty.githubk.core.AgentJob
import com.example.myempty.githubk.core.AgentTask
import com.example.myempty.githubk.core.AgentTaskStore
import com.example.myempty.githubk.core.AuditLog
import com.example.myempty.githubk.core.ProjectKnowledge
import com.example.myempty.githubk.core.ScheduleStore
import com.example.myempty.githubk.core.ExportLogStore
import com.example.myempty.githubk.ai.McpManager
import com.example.myempty.githubk.ai.McpServerInfo
import java.io.File

/**
 * AgentPage v6.0：对话式 AI 工作台（聊天工作台重构版）。
 *
 * 结构：
 *  头部：标题 + 状态灯 + 会话菜单 + 设置
 *  状态条：当前项目 / 模型 / token 占用 / 压缩开关
 *  消息区：四类气泡（用户 / AI / 工具执行卡 / 系统通知），自动滚动，长按复制
 *  快捷指令 Chip 条 + 输入区（多行输入 + 上传图片 + 发送）
 *
 * 能力：多会话（按项目隔离持久化）、事件流实时渲染、上下文自动总结压缩、
 *      项目记忆、图片视觉分析、对话导出、任务停止、AI 配置跳转。
 */
class AgentPage(private val host: PageHost) : RefreshablePage {

    private val state = host.state
    private val ctx: Context = host.context
    private val dp = { v: Int -> UiKit.dp(ctx, v) }

    // ---- 会话状态 ----
    private var activeSession: ChatSession? = null
    private var running = false
    private var stopRequested = false
    /** 任务运行期间用户新发送的消息，排队等当前任务完成后自动执行，避免“上传附件后无法发送”。 */
    private var pendingSend: (() -> Unit)? = null
    private var currentThread: Thread? = null
    private var runningProject: Project? = null
    private val liveToolViews = mutableMapOf<Int, ToolCardHolder>()

    // v6.0：AI 构建进度可视化 —— 运行中进度面板（indeterminate 进度条 + 实时日志）
    private var runLogPanel: LinearLayout? = null
    private var runLogTitle: TextView? = null
    private var runLogText: TextView? = null
    private var runLogBar: ProgressBar? = null
    private var runLogBuf = StringBuilder()
    private var runLogName = ""
    private var lastTask = ""
    private var liveFileCount = 0
    private var liveAiRound = 0

    // ---- 设置项（SecureStore） ----
    private val sec = state.secure
    private var autoCompress: Boolean
        get() = sec.get("agent_auto_compress")?.toBoolean() ?: true
        set(v) { sec.put("agent_auto_compress", v.toString()) }
    private var showToolArgs: Boolean
        get() = sec.get("agent_show_tool_args")?.toBoolean() ?: true
        set(v) { sec.put("agent_show_tool_args", v.toString()) }
    private var confirmRisky: Boolean
        get() = sec.get("agent_confirm_risky")?.toBoolean() ?: true
        set(v) { sec.put("agent_confirm_risky", v.toString()) }
    private var typingAnim: Boolean
        get() = sec.get("agent_typing_anim")?.toBoolean() ?: true
        set(v) { sec.put("agent_typing_anim", v.toString()) }
    private var autoRollback: Boolean
        get() = sec.get("agent_auto_rollback")?.toBoolean() ?: true
        set(v) { sec.put("agent_auto_rollback", v.toString()) }
    private var confirmReq: Boolean
        get() = sec.get("agent_need_confirm")?.toBoolean() ?: true
        set(v) { sec.put("agent_need_confirm", v.toString()) }
    private var lightTidy: Boolean
        get() = sec.get("agent_light_tidy")?.toBoolean() ?: true
        set(v) { sec.put("agent_light_tidy", v.toString()) }
    private var lightModelId: String?
        get() = sec.get("agent_light_model")
        set(v) { if (v.isNullOrBlank()) sec.put("agent_light_model", "") else sec.put("agent_light_model", v) }
    private var collapseTools: Boolean
        get() = sec.get("agent_collapse_tools")?.toBoolean() ?: false
        set(v) { sec.put("agent_collapse_tools", v.toString()) }
    private var tagFilter: String = ""
    private var scheduleTimer: java.util.Timer? = null

    /** 当前打开的“面板级”弹窗：再开新面板前自动关闭旧的，避免弹窗层层叠加无法管理。 */
    private var activePanel: android.app.Dialog? = null

    /** 面板级弹窗统一入口：自动关闭上一个面板，避免层叠；返回创建的 dialog。 */
    private fun showPanel(title: String, content: View, onOk: (() -> Unit)? = null): android.app.Dialog {
        try { activePanel?.dismiss() } catch (_: Throwable) {}
        activePanel = null
        val d = UiKit.dialog(ctx, title, content, onOk = onOk)
        activePanel = d
        d.setOnDismissListener { if (activePanel === d) activePanel = null }
        return d
    }

    private val compressBudgetTokens = 16000
    private val compressThresholdTokens = 10400

    // ---- 视图引用 ----
    private lateinit var statusDot: View
    private lateinit var sessionTitleLabel: TextView
    private lateinit var headerSub: TextView
    private lateinit var infoProject: TextView
    private lateinit var infoModel: TextView
    private lateinit var infoTokens: TextView
    private lateinit var tokenFill: View
    private lateinit var msgScroll: ScrollView
    private lateinit var msgList: LinearLayout
    private lateinit var chatInput: EditText
    private lateinit var sendBtn: android.widget.Button
    private lateinit var attachBar: LinearLayout
    private lateinit var attachScroll: ScrollView
    private lateinit var quickRow: LinearLayout

    /** 通用附件（图片/压缩包/APK/文本等），发送时注入任务或走视觉解析。 */
    data class AttachmentInfo(
        val name: String,
        val rel: String,        // 项目内相对路径 .studio/attachments/xxx 或绝对路径（无项目时）
        val size: Long,
        val kind: String,       // image / zip / apk / text / other
        val dataUrl: String = "" // 图片专用 base64（视觉解析）
    )
    private val pendingAttachments = mutableListOf<AttachmentInfo>()

    // ================================================================
    //  主入口
    // ================================================================
    fun buildView(): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiKit.themeColor(ctx, R.color.surface))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        root.addView(buildHeader())
        root.addView(buildStatusBar())
        msgScroll = ScrollView(ctx).apply { isFillViewport = true }
        msgList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        msgScroll.addView(msgList)
        root.addView(msgScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(UiKit.divider(ctx))
        root.addView(buildComposer())
        refreshRunButton()
        root.tag = this
        ensureSession()
        refresh()
        applyAgentSettings()
        startScheduleTimer()
        return root
    }

    /** 就地刷新。 */
    override fun refresh() {
        ensureSession()
        refreshHeader()
        rebuildMessageList()
        updateStatusBar()
    }

    // ================================================================
    //  v6.0 运行时接线 / 审批 / 定时
    // ================================================================
    /** 将本页设置同步到 AgentRuntime（高风险审批 / 自动回滚 / 轻量模型 / 联网整理）。 */
    private fun applyAgentSettings() {
        val agent = state.agent
        agent.riskConfirm = confirmRisky
        agent.autoRollback = autoRollback
        agent.lightProfileId = lightModelId?.ifBlank { null }
        agent.webLightRouter = if (lightTidy) { txt ->
            runCatching {
                state.ai.summarizeFor(
                    lightModelId?.ifBlank { null },
                    txt.take(7000),
                    prompt = "把以下联网搜索结果整理为简洁中文要点，仅保留结论、关键命令/链接，去掉重复与无关广告噪音。"
                )
            }.getOrElse { "" }
        } else null
        agent.onApprovalRequest = { seq, tool, argstr, reason ->
            host.runUi { showApprovalDialog(seq, tool, argstr, reason) }
        }
    }

    private fun startScheduleTimer() {
        scheduleTimer?.cancel()
        scheduleTimer = java.util.Timer().apply {
            schedule(object : java.util.TimerTask() {
                override fun run() {
                    if (running) return
                    val jobs = ScheduleStore.due(ctx)
                    jobs.forEach { job ->
                        val proj = state.workspace.projects().firstOrNull { it.name == job.projectKey }
                            ?: state.workspace.projects().firstOrNull()
                        host.runUi {
                            if (proj == null) { ScheduleStore.touch(ctx, job.id); return@runUi }
                            addSystem("定时任务触发：${job.name}（${job.action}）")
                            when (job.action) {
                                "scan" -> Thread { fallbackScan(proj) }.start()
                                "export" -> {
                                    val f = ProjectKnowledge.exportAbility(proj)
                                    ScheduleStore.touch(ctx, job.id)
                                    addSystem("定时导出能力包：${f.name}（${f.length() / 1024} KB）")
                                }
                                else -> ScheduleStore.touch(ctx, job.id)
                            }
                        }
                    }
                }
            }, 45_000, 45_000)
        }
    }

    private fun fallbackScan(proj: Project) {
        val result = mutableListOf<String>()
        val re = Regex("\\b(TODO|FIXME|BUG)\\b")
        val skip = setOf("build", ".gradle", ".git", "node_modules", "gradle")
        fun walk(d: File) {
            val cs = d.listFiles() ?: return
            for (c in cs) {
                if (c.isDirectory) { if (c.name !in skip) walk(c) }
                else if (c.extension.lowercase() in setOf("kt", "java", "dart", "py", "js", "ts", "md")) {
                    if (c.length() < 800_000) {
                        c.readText(Charsets.UTF_8).lines().forEachIndexed { i, l ->
                            if (re.containsMatchIn(l)) result.add("${c.name}:${i + 1} ${l.trim().take(40)}")
                        }
                    }
                }
            }
        }
        walk(proj.path)
        host.runUi {
            if (result.isEmpty()) addSystem("定时扫描「${proj.name}」：未发现待办")
            else addSystem("定时扫描「${proj.name}」：发现 ${result.size} 处待办")
            ScheduleStore.list(ctx).firstOrNull()?.let { ScheduleStore.touch(ctx, it.id) }
        }
    }

    /** 高风险操作审批弹窗（Agent 线程回调，UI 线程展示）。 */
    private fun showApprovalDialog(seq: Int, tool: String, argstr: String, reason: String) {
        val box = UiKit.vstack(ctx).apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }
        box.addView(UiKit.label(ctx, "工具：$tool", color = R.color.primary, size = 14f).apply { typeface = Typeface.DEFAULT_BOLD })
        box.addView(UiKit.spacer(ctx, 4))
        box.addView(UiKit.label(ctx, "原因：$reason", color = R.color.on_surface, size = 12.5f))
        if (argstr.isNotBlank()) {
            box.addView(UiKit.spacer(ctx, 4))
            box.addView(UiKit.label(ctx, "参数：${argstr.take(200)}", color = R.color.muted, size = 11.5f))
        }
        box.addView(UiKit.spacer(ctx, 6))
        box.addView(UiKit.label(ctx, "是否允许 Agent 执行此高风险操作？", color = R.color.muted, size = 11.5f))
        val row = UiKit.hstack(ctx).apply { setPadding(0, dp(8), 0, 0) }
        var dlg: android.app.Dialog? = null
        row.addView(UiKit.ghostButton(ctx, "拒绝") {
            state.agent.submitApproval(seq, false)
            dlg?.setOnDismissListener(null); dlg?.dismiss()
        })
        row.addView(UiKit.spacer(ctx, 8))
        row.addView(UiKit.button(ctx, "允许") {
            state.agent.submitApproval(seq, true)
            dlg?.setOnDismissListener(null); dlg?.dismiss()
        })
        box.addView(row)
        dlg = UiKit.dialog(ctx, "安全审批", box)
        dlg.setOnDismissListener { state.agent.submitApproval(seq, false) }
    }

    // ================================================================
    //  任务看板
    // ================================================================
    private fun openTaskBoard() {
        val key = projectKey()
        val all = AgentTaskStore.list(ctx, key)
        val box = UiKit.vstack(ctx).apply { setPadding(dp(4), dp(0), dp(4), dp(4)) }
        val chipRow = UiKit.hstack(ctx).apply { setPadding(0, dp(0), 0, dp(6)) }
        listOf("全部", "待办", "进行中", "已完成").forEach { c ->
            chipRow.addView(UiKit.chip(ctx, c, (if (c == "全部") tagFilter.isBlank() else tagFilter == c)) {
                tagFilter = if (c == "全部") "" else c
                openTaskBoard()
            })
            chipRow.addView(UiKit.spacer(ctx, 5))
        }
        box.addView(chipRow)
        val tasks = if (tagFilter.isBlank()) all else all.filter { it.state == tagFilter }
        if (all.isEmpty()) {
            box.addView(UiKit.label(ctx, "暂无任务。发送任务后会自动创建并持久化，可在此恢复继续执行。", color = R.color.muted, size = 12f))
        } else {
            tasks.take(40).forEach { t -> box.addView(taskRow(t)) }
        }
        showPanel("任务看板 · ${currentProject()?.name ?: "全局"}", box)
    }

    private fun taskRow(t: AgentTask): LinearLayout {
        val color = when (t.state) { AgentTask.RUN -> R.color.primary; AgentTask.DONE -> R.color.success; else -> R.color.warning }
        val row = UiKit.vstack(ctx).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = UiKit.rounded(ctx, UiKit.themeColor(ctx, R.color.surface_elevated), 12, UiKit.themeColor(ctx, R.color.divider), 1)
        }
        val top = UiKit.hstack(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(UiKit.label(ctx, t.state, color = color, size = 11f).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), dp(2), dp(6), dp(2))
        })
        top.addView(UiKit.spacer(ctx, 6))
        top.addView(UiKit.label(ctx, t.title.ifBlank { "未命名任务" }, color = R.color.on_surface, size = 13.5f).apply {
            typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(top)
        row.addView(UiKit.spacer(ctx, 3))
        row.addView(UiKit.label(ctx, "写入 ${t.filesChanged} 文件 · ${t.iterations} 轮 · ${t.note.take(60)}", color = R.color.muted, size = 10.5f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        val acts = UiKit.hstack(ctx).apply { setPadding(0, dp(5), 0, 0) }
        if (t.state != AgentTask.DONE) {
            acts.addView(UiKit.ghostButton(ctx, "继续") {
                val s = ensureSession()
                AgentChatStore.addMsg(s, "USER", "继续之前任务：${t.taskText}")
                saveNow(); rebuildMessageList(); sendTaskConfirmed("继续之前任务：${t.taskText}")
            })
            acts.addView(UiKit.spacer(ctx, 6))
            acts.addView(UiKit.ghostButton(ctx, "完成") {
                val upd = t.copy(state = AgentTask.DONE, updatedAt = System.currentTimeMillis())
                AgentTaskStore.upsert(ctx, upd)
                host.toast("已标记完成"); openTaskBoard()
            })
        }
        acts.addView(UiKit.spacer(ctx, 6))
        acts.addView(UiKit.ghostButton(ctx, "删除") {
            AgentTaskStore.delete(ctx, projectKey(), t.id)
            host.toast("已删除"); openTaskBoard()
        })
        row.addView(acts)
        return row
    }

    // ================================================================
    //  会话生命周期
    // ================================================================
    private fun projectKey(): String = AgentChatStore.safeProjectKey(ctx, runningProject?.name ?: selectedProjectName())
    private fun selectedProjectName(): String {
        return currentProject()?.name ?: ""
    }

    private fun currentProject(): Project? = state.workspace.projects().firstOrNull()

    private fun ensureSession(): ChatSession {
        val key = projectKey()
        val id = AgentChatStore.activeSessionId(ctx, key)
        val session = if (id.isBlank()) null else AgentChatStore.loadSession(ctx, key, id)
        return if (session != null) {
            activeSession = session
            session
        } else {
            val s = ChatSession(
                "s" + System.currentTimeMillis(),
                key,
                "新对话",
                System.currentTimeMillis(),
                System.currentTimeMillis()
            )
            AgentChatStore.saveSession(ctx, s)
            AgentChatStore.setActiveSession(ctx, key, s.id)
            activeSession = s
            s
        }
    }

    private fun switchSession(id: String) {
        if (running) { host.toast("Agent 运行中，请先停止"); return }
        saveNow()
        val key = projectKey()
        val existing = AgentChatStore.loadSession(ctx, key, id)
        val s = if (existing != null) existing else {
            ChatSession(id, key, "新对话", System.currentTimeMillis(), System.currentTimeMillis()).also { AgentChatStore.saveSession(ctx, it) }
        }
        activeSession = s
        AgentChatStore.setActiveSession(ctx, key, s.id)
        refresh()
        if (existing != null) host.toast("已切换：${s.title.ifBlank { "对话" }}")
    }

    private fun saveNow() {
        activeSession?.let { AgentChatStore.saveSession(ctx, it) }
    }

    // ================================================================
    //  1. 头部
    // ================================================================
    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    private fun buildHeader(): View {
        val bar = UiKit.hstack(ctx).apply {
            setPadding(dp(12), dp(8), dp(10), dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }
        // 品牌 logo：渐变圆角块 + AI 字标
        bar.addView(TextView(ctx).apply {
            text = "AI"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            background = UiKit.gradientBg(ctx, UiKit.themeColor(ctx, R.color.gradient_start), UiKit.themeColor(ctx, R.color.gradient_end), 11)
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        bar.addView(UiKit.spacer(ctx, 10))
        val titleCol = UiKit.vstack(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        sessionTitleLabel = UiKit.label(ctx, "AI 工作台", color = R.color.on_surface, size = 16f).apply {
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titleCol.addView(sessionTitleLabel)
        val subRow = UiKit.hstack(ctx).apply { setPadding(0, dp(3), 0, 0) }
        statusDot = View(ctx).apply { background = UiKit.rounded(ctx, UiKit.themeColor(ctx, R.color.success), 4) }
        subRow.addView(statusDot, LinearLayout.LayoutParams(dp(7), dp(7)))
        subRow.addView(UiKit.spacer(ctx, 5))
        headerSub = UiKit.label(ctx, "新对话", color = R.color.muted, size = 11f).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        subRow.addView(headerSub)
        titleCol.addView(subRow)
        bar.addView(titleCol)
        bar.addView(UiKit.spacer(ctx, 6))
        bar.addView(headerIconButton(R.drawable.ic_plus) { switchSession(newSessionId()); host.toast("已新建对话") })
        bar.addView(UiKit.spacer(ctx, 8))
        bar.addView(headerIconButton(R.drawable.ic_history) { openSessionManager() })
        bar.addView(UiKit.spacer(ctx, 8))
        bar.addView(headerIconButton(R.drawable.ic_settings) { showQuickSettings() })
        return bar
    }

    private fun headerIconButton(iconRes: Int, onClick: () -> Unit): View =
        LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            background = UiKit.rounded(ctx, UiKit.themeColor(ctx, R.color.surface_elevated), 11, UiKit.themeColor(ctx, R.color.divider), 1)
            setOnClickListener { onClick() }
            addView(UiKit.icon(ctx, iconRes, 18, UiKit.themeColor(ctx, R.color.muted)))
        }.apply { layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)) }

    // ================================================================
    //  2. 状态条
    // ================================================================
    private fun buildStatusBar(): View {
        val wrap = UiKit.vstack(ctx).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = UiKit.rounded(ctx, UiKit.themeColor(ctx, R.color.surface_elevated), 16, UiKit.themeColor(ctx, R.color.divider), 1)
        }
        val row = UiKit.hstack(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(UiKit.icon(ctx, R.drawable.ic_folder, 15, UiKit.themeColor(ctx, R.color.muted)))
        row.addView(UiKit.spacer(ctx, 6))
        infoProject = UiKit.label(ctx, "未选择项目", color = R.color.on_surface, size = 12.5f).apply {
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(infoProject)
        row.addView(UiKit.spacer(ctx, 8))
        infoModel = UiKit.label(ctx, "模型未配置", color = R.color.muted, size = 11.5f).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            background = UiKit.rounded(ctx, UiKit.themeColor(ctx, R.color.surface_alt), 10)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            // 便捷：点状态条模型名即可快速切换 AI 模型
            setOnClickListener { showAiPicker() }
        }
        row.addView(infoModel)
        wrap.addView(row)
        val tr = UiKit.hstack(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        tr.addView(UiKit.label(ctx, "上下文", color = R.color.muted, size = 10.5f))
        tr.addView(UiKit.spacer(ctx, 8))
        val track = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = UiKit.rounded(ctx, UiKit.themeColor(ctx, R.color.surface), 3, UiKit.themeColor(ctx, R.color.divider), 1)
            layoutParams = LinearLayout.LayoutParams(0, dp(6), 1f)
        }
        tokenFill = View(ctx).apply { background = UiKit.rounded(ctx, UiKit.themeColor(ctx, R.color.success), 3) }
        track.addView(tokenFill, LinearLayout.LayoutParams(0, dp(6), 0.05f))
        tr.addView(track)
        tr.addView(UiKit.spacer(ctx, 8))
        infoTokens = UiKit.label(ctx, "0%", color = R.color.muted, size = 10.5f)
        tr.addView(infoTokens)
        wrap.addView(tr)
        return wrap
    }

    private fun refreshHeader() {
        val s = activeSession
        val runningNow = running
        statusDot.background = UiKit.rounded(
            ctx,
            if (runningNow) UiKit.themeColor(ctx, R.color.primary) else UiKit.themeColor(ctx, R.color.success), 4
        )
        headerSub.text = buildString {
            if (runningNow) append("执行中 · ")
            append(s?.title ?: "新对话")
        }
        headerSub.setTextColor(UiKit.themeColor(ctx, if (runningNow) R.color.primary else R.color.muted))
        sessionTitleLabel.text = "AI 工作台"
    }

    private fun updateStatusBar() {
        val s = activeSession ?: return
        val proj = currentProject()
        infoProject.text = proj?.name ?: "未选择项目"
        val cfg = state.ai.config()
        infoModel.text = cfg.model.ifBlank { "模型未配置" }
        val tokens = AgentChatStore.estimateSessionTokens(s)
        s.tokenEstimate = tokens
        val pct = (tokens * 100.0 / compressBudgetTokens).toInt().coerceIn(0, 999)
        infoTokens.text = "${(tokens / 1000.0).let { String.format("%.1f", it) }}k / ${compressBudgetTokens / 1000}k · ${pct}%"
        infoTokens.setTextColor(UiKit.themeColor(ctx, if (pct > 70) R.color.warning else R.color.muted))
        val weight = (pct.coerceIn(0, 100) / 100f).coerceAtLeast(0.05f)
        tokenFill.layoutParams = LinearLayout.LayoutParams(0, dp(6), weight)
        tokenFill.background = UiKit.rounded(
            ctx,
            if (pct > 90) UiKit.themeColor(ctx, R.color.error)
            else if (pct > 70) UiKit.themeColor(ctx, R.color.warning)
            else UiKit.themeColor(ctx, R.color.success), 3
        )
        tokenFill.requestLayout()
    }

    // ================================================================
    //  3. 消息渲染
    // ================================================================
    private fun rebuildMessageList() {
        val s = activeSession ?: return
        msgList.removeAllViews()
        liveToolViews.clear()
        liveFileCount = 0
        liveAiRound = 0
        runLogPanel = null
        runLogTitle = null
        runLogText = null
        runLogBar = null
        runLogBuf.setLength(0)
        if (s.msgs.isEmpty()) {
            msgList.addView(emptyWelcome())
        }
        s.msgs.forEach { m -> msgList.addView(buildMsgView(m)) }
        scrollToBottom()
    }

    private fun aiAvatarView(sizeDp: Int): TextView = TextView(ctx).apply {
        text = "AI"
        textSize = (sizeDp * 0.34f).coerceAtLeast(9f)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(android.graphics.Color.WHITE)
        gravity = Gravity.CENTER
        background = UiKit.gradientBg(ctx, UiKit.themeColor(ctx, R.color.gradient_start), UiKit.themeColor(ctx, R.color.gradient_end), 9)
    }.apply { layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)) }

    private fun emptyWelcome(): View {
        val wrap = UiKit.vstack(ctx).apply {
            setPadding(dp(8), dp(30), dp(8), dp(16))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        wrap.addView(UiKit.label(ctx, "暂无对话，输入需求即可开始", color = R.color.muted, size = 12.5f).apply {
            gravity = Gravity.CENTER
        })
        return wrap
    }

    private fun scrollToBottom() {
        msgScroll.post { msgScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** 按消息类型构建气泡视图。 */
    private fun buildMsgView(m: ChatMsg): View = when (m.kind) {
        "USER" -> userBubble(m)
        "AI" -> aiBubble(m)
        "TOOL" -> storedToolCard(m)
        else -> systemBubble(m)
    }

    private fun bubbleContainer(m: ChatMsg, content: View, alignEnd: Boolean): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (alignEnd) Gravity.END else Gravity.START
            setPadding(0, dp(3), 0, dp(3))
            addView(content)
        }

    private fun userBubble(m: ChatMsg): View {
        val text = UiKit.label(ctx, m.text.ifBlank { "…" }, color = R.color.on_surface, size = 14f).apply {
            maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.82f).toInt()
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = UiKit.rounded(ctx, withAlpha(UiKit.themeColor(ctx, R.color.primary), 0x16), 16)
        }
        val header = UiKit.label(ctx, "你 · ${time(m.ts)}", color = R.color.muted, size = 10f)
        val col = UiKit.vstack(ctx).apply {
            gravity = Gravity.END
            addView(header)
            addView(UiKit.spacer(ctx, 3))
            addView(text)
        }
        attachBubbleMenu(col, m)
        return col
    }

    private fun aiBubble(m: ChatMsg, anim: Boolean = false): View {
        val bubble = UiKit.label(ctx, m.text.ifBlank { "…" }, color = R.color.on_surface, size = 14f).apply {
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = UiKit.rounded(ctx, UiKit.themeColor(ctx, R.color.surface_elevated), 16, UiKit.themeColor(ctx, R.color.divider), 1)
        }
        // v6.0：文字动画开关——仅对「新增」的 AI 回复逐字展示；历史重建不触发
        if (anim && typingAnim && m.text.isNotBlank() && m.text.length > 1) {
            val full = m.text
            bubble.text = ""
            val step = maxOf(1, full.length / 220)   // 长文本自动提速，单条动画约 4 秒内完成
            val handler = Handler(Looper.getMainLooper())
            val animTask = object : Runnable {
                var pos = 0
                override fun run() {
                    pos = minOf(full.length, pos + step)
                    bubble.text = full.substring(0, pos)
                    if (pos < full.length) handler.postDelayed(this, 18L) else bubble.text = full
                }
            }
            handler.post(animTask)
        }
        val col = UiKit.vstack(ctx)
        col.addView(UiKit.label(ctx, time(m.ts), color = R.color.muted, size = 10f))
        col.addView(UiKit.spacer(ctx, 3))
        col.addView(bubble, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val row = UiKit.hstack(ctx).apply { gravity = Gravity.TOP }
        row.addView(aiAvatarView(28))
        row.addView(UiKit.spacer(ctx, 8))
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        attachBubbleMenu(row, m)
        return row
    }

    /** 气泡长按菜单：复制 / 删除该消息。 */
    private fun attachBubbleMenu(v: View, m: ChatMsg) {
        v.setOnLongClickListener {
            val pm = PopupMenu(ctx, v)
            pm.menu.add("复制文本")
            pm.menu.add("删除此条")
            pm.setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "复制文本" -> {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("msg", m.text))
                        host.toast("已复制")
                        true
                    }
                    "删除此条" -> {
                        val s = activeSession
                        if (s != null) { AgentChatStore.removeMsg(s, m.id); saveNow(); rebuildMessageList(); updateStatusBar() }
                        true
                    }
                    else -> false
                }
            }
            pm.show()
            true
        }
    }

    private fun systemBubble(m: ChatMsg): View {
        val text = UiKit.label(ctx, m.text, color = R.color.muted, size = 11.5f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = UiKit.rounded(ctx, UiKit.themeColor(ctx, R.color.surface_alt), 10)
        }
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
            addView(text)
        }
        return wrap
    }

    private class ToolCardHolder {
        var ok = true
        var running = false
        var name = ""
        var args = ""
        var output = ""
        var collapsed = false
        lateinit var view: LinearLayout
        lateinit var statusDot: View
        lateinit var statusText: TextView
        lateinit var argsText: TextView
        lateinit var outputText: TextView
        lateinit var chevron: TextView
    }

    private fun toolStatusColor(h: ToolCardHolder): Int = UiKit.themeColor(
        ctx,
        when {
            h.running -> R.color.primary
            h.ok -> R.color.success
            else -> R.color.error
        }
    )

    private fun buildToolCardViews(h: ToolCardHolder): LinearLayout {
        // 标题行：状态点 + 工具名 + 状态标签
        val titleRow = UiKit.hstack(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        h.statusDot = View(ctx).apply { background = UiKit.rounded(ctx, toolStatusColor(h), 4) }
        titleRow.addView(h.statusDot, LinearLayout.LayoutParams(dp(8), dp(8)))
        titleRow.addView(UiKit.spacer(ctx, 8))
        titleRow.addView(
            UiKit.label(ctx, h.name.ifBlank { "工具" }, color = R.color.on_surface, size = 12.5f).apply {
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        titleRow.addView(UiKit.spacer(ctx, 8))
        h.statusText = toolStatusPill(h)
        titleRow.addView(h.statusText)
        titleRow.addView(UiKit.spacer(ctx, 4))
        h.chevron = UiKit.label(ctx, "▾", color = R.color.muted, size = 11f)
        titleRow.addView(h.chevron)
        // 参数摘要（运行中/有参数时显示）
        h.argsText = UiKit.label(ctx, h.args, color = R.color.muted, size = 10.5f).apply {
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(dp(2), dp(5), dp(2), 0)
            visibility = if (h.args.isBlank()) View.GONE else View.VISIBLE
        }
        // 输出区
        h.outputText = UiKit.label(ctx, "", color = R.color.muted, size = 11f).apply {
            typeface = Typeface.MONOSPACE
            background = UiKit.rounded(ctx, UiKit.themeColor(ctx, R.color.surface), 8, UiKit.themeColor(ctx, R.color.divider), 1)
            setPadding(dp(9), dp(7), dp(9), dp(7))
        }
        val card = UiKit.vstack(ctx).apply {
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = UiKit.rounded(
                ctx, UiKit.themeColor(ctx, R.color.surface_alt), 12,
                if (h.ok || h.running) null else UiKit.themeColor(ctx, R.color.error), 1
            )
            addView(titleRow)
            addView(h.argsText)
            addView(h.outputText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        card.setOnClickListener {
            h.collapsed = !h.collapsed
            renderToolHolder(h)
        }
        h.view = card
        return card
    }

    private fun toolStatusPill(h: ToolCardHolder): TextView {
        val c = toolStatusColor(h)
        val txt = if (h.running) "执行中" else if (h.ok) "完成" else "失败"
        return UiKit.label(ctx, txt, color = R.color.on_surface, size = 10.5f).apply {
            setTextColor(c)
            typeface = Typeface.DEFAULT_BOLD
            background = UiKit.rounded(ctx, withAlpha(c, if (h.running) 0x20 else 0x12), 9)
            setPadding(dp(9), dp(2), dp(9), dp(2))
        }
    }

    /** 从会话记录中渲染历史工具卡。 */
    private fun storedToolCard(m: ChatMsg): View {
        val h = ToolCardHolder()
        h.name = m.toolName
        h.args = m.toolArgs
        h.ok = m.toolOk
        h.output = m.text
        h.running = false
        h.collapsed = false
        val card = buildToolCardViews(h)
        renderToolHolder(h)
        return card
    }

    private fun newToolCard(seq: Int, name: String, args: String, ok: Boolean, output: String, running: Boolean): ToolCardHolder {
        val h = ToolCardHolder()
        h.name = name
        h.args = args
        h.ok = ok
        h.output = output
        h.running = running
        // v6.0：工具面板起始折叠开关——新建工具卡默认按开关折叠（点击卡片可展开/收起）
        h.collapsed = collapseTools
        buildToolCardViews(h)
        renderToolHolder(h)
        liveToolViews[seq] = h
        return h
    }

    private fun renderToolHolder(h: ToolCardHolder) {
        val c = toolStatusColor(h)
        h.statusDot.background = UiKit.rounded(ctx, c, 4)
        h.statusText.text = if (h.running) "执行中" else if (h.ok) "完成" else "失败"
        h.statusText.setTextColor(c)
        h.statusText.background = UiKit.rounded(ctx, withAlpha(c, if (h.running) 0x20 else 0x12), 9)
        h.chevron.text = if (h.collapsed) "▸" else "▾"
        val showArgs = h.running && h.args.isNotBlank() && showToolArgs && !h.collapsed
        h.argsText.visibility = if (showArgs) View.VISIBLE else View.GONE
        val head = when {
            h.output.isBlank() && h.ok -> "(无输出)"
            h.output.isBlank() -> "(等待输出…)"
            h.output.length > 800 -> h.output.take(800) + "\n…(点击查看完整内容)"
            else -> h.output
        }
        h.outputText.text = head
        h.outputText.visibility = if (h.collapsed) View.GONE else View.VISIBLE
        h.view.background = UiKit.rounded(
            ctx, UiKit.themeColor(ctx, R.color.surface_alt), 12,
            if (h.ok || h.running) null else UiKit.themeColor(ctx, R.color.error), 1
        )
    }

    private fun addSystem(text: String) {
        val s = activeSession ?: return
        AgentChatStore.addMsg(s, "SYSTEM", text)
        msgList.addView(systemBubble(s.msgs.last()))
        scrollToBottom()
    }

    // ================================================================
    //  v6.0：AI 构建进度可视化（indeterminate 进度条 + 实时日志）
    // ================================================================
    private fun isBuildTool(name: String): Boolean =
        name.lowercase() in setOf("build", "flutter_build", "gradle_build", "compile")

    /** 工具开始时创建/复用一个运行中进度面板，展示「工具名 + 进度条 + 实时日志」。 */
    private fun beginRunLog(name: String) {
        val s = activeSession ?: return
        runLogName = name
        runLogBuf.setLength(0)
        if (runLogPanel == null) {
            val card = UiKit.vstack(ctx).apply {
                setPadding(dp(16), dp(12), dp(16), dp(14))
                background = UiKit.rounded(ctx, UiKit.themeColor(ctx, R.color.surface_alt), 12)
            }
            // 标题行：工具名 + 状态
            val title = UiKit.label(ctx, "⌛ 正在 ${name} …", R.color.primary, 13f)
            runLogTitle = title
            card.addView(title)
            card.addView(UiKit.spacer(ctx, 10))
            // 进度条（indeterminate，表示“构建中”）
            val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                progressTintList = android.content.res.ColorStateList.valueOf(UiKit.themeColor(ctx, R.color.primary))
            }
            runLogBar = bar
            card.addView(bar)
            card.addView(UiKit.spacer(ctx, 10))
            // 实时日志（等宽，自动换行，浅色）
            val log = UiKit.mono(ctx, "", 12f).apply { maxLines = 8 }
            runLogText = log
            card.addView(log)
            runLogPanel = card
            msgList.addView(card)
        }
        runLogTitle?.text = "⌛ 正在 ${name} …"
        runLogTitle?.setTextColor(UiKit.themeColor(ctx, R.color.primary))
        runLogBar?.isIndeterminate = true
        runLogBar?.progressTintList = android.content.res.ColorStateList.valueOf(UiKit.themeColor(ctx, R.color.primary))
        runLogText?.text = ""
        scrollToBottom()
    }

    /** 每收到一行构建日志，追加到进度面板（限制长度，滚动到底部）。 */
    private fun appendRunLog(line: String) {
        if (runLogPanel == null) return
        if (runLogBuf.isNotEmpty()) runLogBuf.append("\n")
        runLogBuf.append(line)
        // 限制日志缓存，避免无限增长
        if (runLogBuf.length > 5000) {
            runLogBuf.delete(0, 1500)
            runLogText?.text = "…(已截断)…\n" + runLogBuf.toString()
        } else {
            runLogText?.text = runLogBuf.toString()
        }
        scrollToBottom()
    }

    /** 工具结束：进度条转为完成/失败，并附上最终输出。 */
    private fun endRunLog(ok: Boolean, output: String) {
        val panel = runLogPanel ?: return
        val status = if (ok) "完成" else "失败"
        val c = if (ok) UiKit.themeColor(ctx, R.color.success) else UiKit.themeColor(ctx, R.color.error)
        runLogTitle?.text = "${if (ok) "✅" else "❌"} ${runLogName} ${status}"
        runLogTitle?.setTextColor(c)
        runLogBar?.isIndeterminate = false
        runLogBar?.progress = 100
        runLogBar?.progressTintList = android.content.res.ColorStateList.valueOf(c)
        val tail = summarizeToolOutput(output)
        if (tail.isNotBlank() && tail != "完成") {
            if (runLogBuf.isNotEmpty()) runLogBuf.append("\n")
            runLogBuf.append("\n── ${status} ──\n").append(tail)
            runLogText?.text = runLogBuf.toString()
        }
        scrollToBottom()
    }

    /** 追加工具卡：seq>0 更新已有卡，否则新建。 */
    private fun addToolView(seq: Int, name: String, args: String, ok: Boolean, output: String, live: Boolean, running: Boolean = false) {
        val holder = liveToolViews[seq]
        if (holder != null) {
            holder.name = name
            holder.args = args
            holder.ok = ok
            holder.output = output
            holder.running = running
            // 保留用户手动展开/折叠状态，避免刷新时被打断
            renderToolHolder(holder)
        } else {
            val s = activeSession ?: return
            val card = newToolCard(seq, name, args, ok, output, running)
            msgList.addView(card.view)
            scrollToBottom()
        }
    }

    private fun time(ts: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))

    // ================================================================
    //  4. 快捷指令 + 输入区
    // ================================================================
    private fun buildComposer(): View {
        val box = UiKit.vstack(ctx).apply {
            setPadding(dp(10), dp(6), dp(10), dp(8))
            background = UiKit.rounded(ctx, UiKit.themeColor(ctx, R.color.surface_alt), 0)
        }
        // 快捷指令 chips（构建/分析/打包等快捷入口）
        val hScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        quickRow = UiKit.hstack(ctx)
        renderQuickChips()
        hScroll.addView(quickRow)
        box.addView(hScroll)
        box.addView(UiKit.spacer(ctx, 8))
        // 输入卡片
        val inputCard = UiKit.vstack(ctx).apply {
            background = UiKit.rounded(ctx, UiKit.themeColor(ctx, R.color.surface_elevated), 16, UiKit.themeColor(ctx, R.color.divider), 1)
            setPadding(dp(8), dp(4), dp(8), dp(6))
        }
        chatInput = EditText(ctx).apply {
            hint = "描述你的需求，例如：分析报错并修复、构建 APK、搜索某个 API 用法…"
            setHintTextColor(UiKit.themeColor(ctx, R.color.muted))
            setTextColor(UiKit.themeColor(ctx, R.color.on_surface))
            background = null
            setPadding(dp(8), dp(4), dp(8), dp(2))
            minLines = 2
            maxLines = 5
            textSize = 14f
            gravity = Gravity.TOP or Gravity.START
        }
        inputCard.addView(chatInput)
        // 附件容器：每个附件一行（文件名+大小+撤回按钮），可逐项删除；超过高度后内部滚动，避免遮挡输入框
        attachScroll = ScrollView(ctx).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        attachBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(4), dp(4), dp(4), dp(2))
        }
        attachScroll.addView(attachBar)
        inputCard.addView(attachScroll)
        val row = UiKit.hstack(ctx).apply {
            setPadding(dp(2), dp(4), dp(2), 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(composerIconAction(R.drawable.ic_plus) { pickAndAttachFiles() })
        row.addView(UiKit.spacer(ctx, 6))
        sendBtn = android.widget.Button(ctx).apply {
            text = "发送"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = UiKit.rounded(ctx, UiKit.themeColor(ctx, R.color.primary), 18)
            minHeight = 0
            setPadding(dp(18), 0, dp(18), 0)
            setOnClickListener { sendCurrent() }
        }
        row.addView(sendBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)))
        inputCard.addView(row)
        box.addView(inputCard)
        return box
    }

    /** 纯图标按钮（无文字）：用于附件上传等，满足“去图标去文字”的简洁输入区。 */
    private fun composerIconAction(iconRes: Int, onClick: () -> Unit): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            background = UiKit.rounded(ctx, UiKit.themeColor(ctx, R.color.surface), 9, UiKit.themeColor(ctx, R.color.divider), 1)
            setOnClickListener { onClick() }
            addView(UiKit.icon(ctx, iconRes, 18, UiKit.themeColor(ctx, R.color.primary)))
        }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)) }


    private fun renderQuickChips() {
        if (!::quickRow.isInitialized) return
        quickRow.removeAllViews()
        val actions = listOf<Pair<String, () -> Unit>>(
            "构建 APK" to {
                runOrToast("构建当前项目${projectRef()}并导出 APK 到 Downloads；如构建失败请分析报错修复后重试直至成功。")
            },
            "分析报错" to {
                runOrToast("分析当前项目${projectRef()}的报错（查看 build 输出与关键源码），找出问题并修复，然后重新构建验证。")
            },
            "清理缓存" to { runOrToast("对当前项目${projectRef()}执行 clean 后重新构建验证。") },
            "打包源码" to { runOrToast("将当前项目${projectRef()}的源码打包为 zip 并导出到 Downloads。") },
            "新建项目" to { askNewProjectTask() },
            "AI 逆向" to { showReverseSheet() },
            "能力" to { showCapabilitySheet() },
            "扫描问题" to { runProblemsScan() },
            "项目列表" to { chooseProject() },
            "Git 状态" to { runOrToast("查看当前项目${projectRef()}的 Git 状态与最近改动，简要汇报。") },
            "读取项目" to { runOrToast("先读取当前项目${projectRef()}的目录结构与关键文件，给出项目概况。") }
        )
        actions.forEach { (name, act) ->
            quickRow.addView(UiKit.chip(ctx, name, false) { act() })
            quickRow.addView(UiKit.spacer(ctx, 6))
        }
    }

    private fun runOrToast(task: String) {
        if (!hasProject()) { host.toast("请先在工作区新建或选择项目"); return }
        if (running) { host.toast("Agent 正在运行中"); return }
        sendTask(task)
    }

    private fun hasProject(): Boolean = state.workspace.projects().isNotEmpty()
    private fun projectRef(): String {
        val name = currentProject()?.name ?: ""
        return if (name.isNotEmpty()) "「$name」" else ""
    }

    private fun pasteClipboard() {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(ctx).toString()
                val cur = chatInput.text.toString()
                chatInput.setText(if (cur.isBlank()) text else cur + "\n" + text)
                host.toast("已粘贴")
            } else host.toast("剪贴板为空")
        } catch (e: Throwable) { host.toast("粘贴失败：${e.message}") }
    }

    // ================================================================
    //  5. 发送与执行
    // ================================================================
    private fun sendCurrent() {
        val text = chatInput.text.toString().trim()
        if (text.isBlank() && pendingAttachments.isEmpty()) { host.toast("请输入任务或上传附件"); return }
        chatInput.setText("")
        val images = pendingAttachments.filter { it.kind == "image" }
        val files = pendingAttachments.filter { it.kind != "image" }
        // 纯图片：走视觉解析；否则将附件清单注入任务文本，交由 Agent 读取/解压/分析。
        if (images.isNotEmpty() && files.isEmpty() && images.size == 1) {
            val img = images.first()
            if (img.dataUrl.isNotBlank()) {
                clearPendingAttachments()
                sendOrQueue { sendWithImage(text, img.dataUrl) }
                return
            }
        }
        val msg = buildTaskWithAttachments(text, images, files)
        clearPendingAttachments()
        if (msg.isBlank()) { host.toast("请输入任务或上传附件"); return }
        sendOrQueue { sendTask(msg) }
    }

    /** 空闲时直接发送；若正在运行则排队，等当前任务完成后自动执行。 */
    private fun sendOrQueue(fn: () -> Unit) {
        if (running) {
            pendingSend = fn
            addSystem("已排队：将在当前任务完成后自动发送这条消息。")
            saveNow()
            updateStatusBar()
            scrollToBottom()
            return
        }
        fn()
    }

    /** 任务结束后若有排队消息，稍作延迟后自动发送。 */
    private fun dispatchPending() {
        val p = pendingSend ?: return
        pendingSend = null
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching { p() }
            scrollToBottom()
        }, 150)
    }

    private fun buildTaskWithAttachments(text: String, images: List<AttachmentInfo>, files: List<AttachmentInfo>): String {
        if (images.isEmpty() && files.isEmpty()) return text
        val sb = StringBuilder()
        if (text.isNotBlank()) sb.append(text).append("\n\n")
        sb.append("本次随任务上传了 ${images.size + files.size} 个附件，存放于项目 .studio/attachments 目录：\n")
        images.forEach { sb.append("- 图片：").append(it.rel).append("\n") }
        files.forEach {
            sb.append("- ").append(kindLabel(it.kind)).append("：").append(it.rel).append("（").append(fmtSize(it.size)).append("）\n")
        }
        sb.append("请根据需要读取/解压/分析这些附件后再完成上面的任务：zip 可解压查看源码或资源；apk 可分析包结构与 manifest；文本可直接读取。")
        return sb.toString()
    }

    private fun kindLabel(kind: String): String = when (kind) {
        "zip" -> "压缩包"
        "apk" -> "APK"
        "text" -> "文本"
        else -> "文件"
    }

    private fun clearPendingAttachments() {
        pendingAttachments.clear()
        attachBar.removeAllViews()
        attachBar.visibility = View.GONE
        if (::attachScroll.isInitialized) attachScroll.visibility = View.GONE
    }

    private fun appendUserToUi() {
        val s = activeSession ?: return
        val last = s.msgs.lastOrNull() ?: return
        if (last.kind == "USER") msgList.addView(userBubble(last))
        scrollToBottom()
    }

    private fun hasAiReady(): Boolean {
        val cfg = state.ai.config()
        val reason = when {
            !cfg.enabled -> "AI 已停用，请到「AI 配置中心」开启总开关"
            cfg.key.isBlank() && !state.ai.keyOptional(cfg.provider) -> "尚未配置 AI API Key，请到「AI 配置中心」填写"
            else -> return true
        }
        host.toast(reason)
        runCatching {
            ensureSession()
            addSystem("AI 未就绪：$reason")
            saveNow()
            updateStatusBar()
            scrollToBottom()
        }
        return false
    }

    /** 发送文字任务：先自动压缩（如开启且超阈值），再启动 Agent 事件流执行。 */
    private fun sendTask(task: String) {
        if (!hasAiReady()) return
        if (running) { host.toast("Agent 正在运行中，可先停止"); return }
        if (confirmReq) {
            showReqConfirm(task)
            return
        }
        sendTaskConfirmed(task)
    }

    private fun sendTaskConfirmed(task: String) {
        if (!hasAiReady()) return
        if (running) { host.toast("Agent 正在运行中，可先停止"); return }
        val s = ensureSession()
        val project = state.workspace.projects().firstOrNull()
        // v6.0：发送前解析项目变量（{{key}} / ${key} → 项目变量值），使「变量」功能真正生效
        val resolved = if (project != null) ProjectKnowledge.resolveVariables(project, task) else task
        AgentChatStore.addMsg(s, "USER", resolved)
        if (s.title.isBlank() || s.title == "新对话") s.title = resolved.replace('\n', ' ').trim().take(24)
        saveNow()
        appendUserToUi()
        updateStatusBar()
        running = true
        // 任务运行期间始终保持输入框可用（仅限发送时提示“正在运行”），修复“附加文件后无法输入”的问题
        chatInput.isEnabled = true
        refreshRunButton()
        refreshHeader()
        if (autoCompress) {
            compressOldIfNeeded { runAgentNow(s, resolved, project) }
        } else {
            runAgentNow(s, resolved, project)
        }
    }

    /** 需求确认：启动前给用户看任务理解摘要，确认后才执行。 */
    private fun showReqConfirm(task: String) {
        val box = UiKit.vstack(ctx).apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }
        box.addView(UiKit.label(ctx, "我理解你的需求如下：", color = R.color.on_surface, size = 13f).apply { typeface = Typeface.DEFAULT_BOLD })
        box.addView(UiKit.spacer(ctx, 4))
        box.addView(UiKit.label(ctx, task.take(400), color = R.color.muted, size = 12.5f))
        box.addView(UiKit.spacer(ctx, 6))
        box.addView(UiKit.label(ctx, "将按此任务交由 Agent 执行（可随时停止；高风险操作会弹窗审批）。若理解有误，可修改后重新发送。", color = R.color.muted, size = 11f))
        val row = UiKit.hstack(ctx).apply { setPadding(0, dp(8), 0, 0) }
        var dlg: android.app.Dialog? = null
        row.addView(UiKit.ghostButton(ctx, "取消") { dlg?.dismiss() })
        row.addView(UiKit.spacer(ctx, 8))
        row.addView(UiKit.button(ctx, "确认并执行") { dlg?.dismiss(); sendTaskConfirmed(task) })
        box.addView(row)
        dlg = UiKit.dialog(ctx, "需求确认", box)
    }

    /** 启动 agent 线程。历史会去掉最后一条 USER（它作为 task 传参，避免重复）。 */
    private fun runAgentNow(s: ChatSession, task: String, project: Project?) {
        // 若在自动压缩等待期间用户点了“停止”，则不启动任务、直接恢复空闲界面
        if (stopRequested) {
            stopRequested = false
            running = false
            currentThread = null
            chatInput.isEnabled = true
            refreshRunButton()
            refreshHeader()
            updateStatusBar()
            addSystem("任务未启动（压缩等待期间已停止）。")
            dispatchPending()
            return
        }
        running = true
        stopRequested = false
        liveFileCount = 0
        liveAiRound = 0
        liveToolViews.clear()
        runningProject = project
        lastTask = task
        refreshRunButton()
        chatInput.isEnabled = true
        refreshHeader()
        addSystem("正在规划并执行任务…")
        updateStatusBar()

        val agent = state.agent
        agent.resetStopFlag()
        val tk = projectKey()
        var taskId: String? = null
        runCatching { taskId = AgentTaskStore.start(ctx, tk, project?.name ?: "全局", task).id }
        val history = AgentChatStore.chatHistory(s)
        val historyForModel = if (history.isNotEmpty() && history.last().content == task) history.dropLast(1) else history
        val summary = s.summary

        val thread = Thread {
            try {
                val result = agent.runConversation(
                    task = task,
                    project = project,
                    history = historyForModel,
                    summary = summary,
                    reverseMode = s.tag == "AI逆向",
                    onEvent = { ev -> host.runUi { handleRuntimeEvent(ev) } }
                )
                host.runUi {
                    running = false
                    currentThread = null
                    chatInput.isEnabled = true
                    refreshRunButton()
                    refreshHeader()
                    if (result.interrupted) {
                        addSystem("任务已停止。")
                        showContinueButton()
                    } else if (result.finalMessage.isNotBlank()) {
                        val last = s.msgs.lastOrNull()
                        if (last == null || last.kind != "AI" || last.text.trim() != result.finalMessage.trim()) {
                            AgentChatStore.addMsg(s, "AI", result.finalMessage.trim())
                            val ai = s.msgs.lastOrNull()
                            if (ai != null) msgList.addView(aiBubble(ai, anim = true))
                        }
                        addSystem("完成：${result.iterations} 轮 · 写入 ${result.filesChanged} 个文件")
                        scrollToBottom()
                    } else {
                        addSystem("任务完成：${result.iterations} 轮 · 写入 ${result.filesChanged} 个文件")
                    }
                    AgentTaskStore.finish(ctx, tk, taskId, result.success, result.iterations, result.filesChanged, if (result.interrupted) "已停止" else "完成")
                    runCatching { recordTaskOutcome(project, task, result) }
                    saveNow()
                    updateStatusBar()
                    dispatchPending()
                }
            } catch (e: Throwable) {
                host.runUi {
                    running = false
                    currentThread = null
                    chatInput.isEnabled = true
                    refreshRunButton()
                    refreshHeader()
                    AgentTaskStore.finish(ctx, tk, taskId, false, 0, 0, "执行异常")
                    addSystem("执行异常：${e.message}")
                    saveNow()
                    updateStatusBar()
                    dispatchPending()
                }
            }
        }
        currentThread = thread
        thread.start()
    }

    /** 任务完成后自动记录：变更日志 + 任务复盘（写入项目记忆），失败时留给回滚。 */
    private fun recordTaskOutcome(project: Project?, task: String, result: AgentResult) {
        if (project == null) return
        AuditLog.add(ctx, project.name, "task", "完成 ${result.iterations} 轮，写入 ${result.filesChanged} 文件", result.success)
        if (!result.success) {
            if (result.filesChanged > 0) {
                ProjectKnowledge.changelogAdd(project, task, state.workspace.changedFiles(project), "任务未完全成功（记录改动，供回滚参考）")
            }
            return
        }
        val changed = state.workspace.changedFiles(project)
        if (result.filesChanged > 0 && changed.isNotEmpty()) {
            ProjectKnowledge.changelogAdd(project, task, changed, "自动变更日志（${result.iterations} 轮）")
        }
        val points = buildList {
            add("完成 ${result.iterations} 轮工具迭代")
            if (result.filesChanged > 0) add("成功写入 ${result.filesChanged} 个文件")
            result.finalMessage.trim().take(300).takeIf { it.isNotBlank() }?.let { add(it) }
        }
        if (points.isNotEmpty()) {
            ProjectKnowledge.reviewAdd(project, task, "任务已完成，写入 ${result.filesChanged} 个文件", points)
        }
    }

    /** 若开启自动压缩且上下文超过阈值，先压缩旧消息（删除旧消息并写入摘要），完成后回调。 */
    private fun compressOldIfNeeded(onDone: () -> Unit) {
        val s = activeSession ?: run { onDone(); return }
        val pairs = s.msgs.filter { it.kind == "USER" || it.kind == "AI" }
        if (pairs.size <= 4 || AgentChatStore.estimateSessionTokens(s) <= compressThresholdTokens) {
            onDone()
            return
        }
        val keepCount = 4
        val consume = pairs.size - keepCount
        val textToSummarize = pairs.take(consume).joinToString("\n") { m ->
            (if (m.kind == "USER") "用户：" else "AI：") + m.text
        }.let { if (it.length > 40000) it.take(40000) + "\n…" else it }
        addSystem("上下文较长，正在总结 ${consume} 条早期消息…")
        Thread {
            try {
                val summary = state.ai.summarize(textToSummarize)
                host.runUi {
                    if (summary.isNotBlank()) {
                        var removed = 0
                        val iter = s.msgs.iterator()
                        while (iter.hasNext()) {
                            if (removed >= consume) break
                            val m = iter.next()
                            if (m.kind == "USER" || m.kind == "AI") { iter.remove(); removed++ }
                        }
                        AgentChatStore.applySummary(s, summary, removed)
                        saveNow()
                        rebuildMessageList()
                        addSystem("已自动总结 ${removed} 条早期消息，上下文已释放。")
                        updateStatusBar()
                    }
                    onDone()
                }
            } catch (e: Throwable) {
                host.runUi { addSystem("上下文总结失败，将按完整历史继续：${e.message}"); onDone() }
            }
        }.start()
    }

    private fun handleRuntimeEvent(ev: AgentEvent) {
        when (ev) {
            is AgentEvent.ToolStart -> {
                addToolView(ev.seq, ev.name, ev.args.take(300), true, "(stream)", live = true, running = true)
                if (isBuildTool(ev.name)) beginRunLog(ev.name)
            }
            is AgentEvent.ToolEnd -> {
                val nm = liveToolViews[ev.seq]?.name ?: ""
                addToolView(ev.seq, nm, "", ev.ok, summarizeToolOutput(ev.output), live = false, running = false)
                if (isBuildTool(nm)) endRunLog(ev.ok, ev.output)
                val s = activeSession
                if (s != null && !ev.output.startsWith("(stream)")) {
                    AgentChatStore.addMsg(s, "TOOL", summarizeToolOutput(ev.output), toolName = nm, toolOk = ev.ok)
                    saveNow()
                }
            }
            is AgentEvent.FileWrite -> {
                liveFileCount++
            }
            is AgentEvent.AiTurn -> {
                liveAiRound++
            }
            is AgentEvent.FinalText -> {
                val s = activeSession
                if (s != null && ev.text.isNotBlank()) {
                    AgentChatStore.addMsg(s, "AI", ev.text.trim())
                    saveNow()
                    msgList.addView(aiBubble(s.msgs.last(), anim = true))
                    scrollToBottom()
                }
            }
            is AgentEvent.Note -> {
                addSystem(ev.text)
                saveNow()
            }
            is AgentEvent.Log -> {
                appendRunLog(ev.text)
            }
        }
        updateStatusBar()
    }

    private fun summarizeToolOutput(o: String): String {
        if (o.isBlank()) return "完成"
        val clean = o.trim()
        return if (clean.length > 600) clean.take(600) + "\n…(输出较长已截断)" else clean
    }

    private fun stopRun() {
        if (!running) { host.toast("当前没有运行中的任务"); return }
        stopRequested = true
        state.agent.requestStop()
        addSystem("正在请求停止…（当前 AI 请求将立即中断）")
    }

    // ================================================================
    //  v6.0：任务可续跑（停止后保留上下文，一键继续）
    // ================================================================
    private fun showContinueButton() {
        if (lastTask.isBlank()) return
        val s = activeSession ?: return
        val btn = UiKit.chip(ctx, "▶ 继续上次任务", false) { continueTask() }
        val row = UiKit.hstack(ctx).apply {
            setPadding(dp(4), dp(4), dp(4), dp(10))
            addView(btn)
        }
        msgList.addView(row)
        scrollToBottom()
    }

    /** 基于当前会话历史与项目状态，用一条新指令让 Agent 从上次中断处继续。 */
    private fun continueTask() {
        if (!hasAiReady()) return
        if (running) { host.toast("Agent 正在运行中，可先停止"); return }
        val proj = runningProject
        val base = lastTask
        if (base.isBlank()) { host.toast("没有可继续的任务"); return }
        val s = ensureSession()
        val task = "继续完成之前被中断的任务。请基于当前项目状态与已完成的修改继续推进，最终交付可运行的结果。若已完成，请总结：\n${base}"
        val resolved = if (proj != null) ProjectKnowledge.resolveVariables(proj, task) else task
        AgentChatStore.addMsg(s, "USER", resolved)
        if (s.title.isBlank() || s.title == "新对话") s.title = resolved.replace('\n', ' ').trim().take(24)
        saveNow()
        appendUserToUi()
        updateStatusBar()
        running = true
        chatInput.isEnabled = true
        refreshRunButton()
        refreshHeader()
        if (autoCompress) {
            compressOldIfNeeded { runAgentNow(s, resolved, proj) }
        } else {
            runAgentNow(s, resolved, proj)
        }
    }

    /**
     * 运行/空闲按钮切换：空闲为「发送」，运行中变为红色「停止」。
     * 让用户可以随时中断 AI 任务（旧的停止逻辑无 UI 入口，属空壳）。
     */
    private fun refreshRunButton() {
        if (!::sendBtn.isInitialized) return
        if (running) {
            sendBtn.isEnabled = true
            sendBtn.text = "停止"
            sendBtn.setTextColor(android.graphics.Color.WHITE)
            sendBtn.background = UiKit.rounded(ctx, UiKit.themeColor(ctx, R.color.error), 18)
            sendBtn.setOnClickListener { stopRun() }
        } else {
            sendBtn.isEnabled = true
            sendBtn.text = "发送"
            sendBtn.setTextColor(android.graphics.Color.WHITE)
            sendBtn.background = UiKit.rounded(ctx, UiKit.themeColor(ctx, R.color.primary), 18)
            sendBtn.setOnClickListener { sendCurrent() }
        }
    }

    // ================================================================
    //  会话管理
    // ================================================================
    private fun openSessionManager() {
        val s = activeSession ?: return
        val key = s.projectKey
        val metas = AgentChatStore.listSessions(ctx, key)
        val box = UiKit.vstack(ctx).apply { setPadding(dp(8), dp(4), dp(8), dp(4)) }
        box.addView(UiKit.label(ctx, "当前项目：${currentProject()?.name ?: "全局"} · ${metas.size} 个会话", color = R.color.muted, size = 11f))
        box.addView(UiKit.label(ctx, "点击下方会话即可切换", color = R.color.muted, size = 10.5f))
        box.addView(UiKit.spacer(ctx, 6))
        var dlg: android.app.Dialog? = null
        if (metas.isEmpty()) {
            box.addView(UiKit.label(ctx, "暂无历史会话", color = R.color.muted, size = 12f))
        } else {
            metas.forEach { m ->
                box.addView(sessionRow(m.id, m.title, m.msgCount, m.summarized, m.id == s.id, m.updatedAt) {
                    dlg?.dismiss()
                    switchSession(m.id)
                })
            }
        }
        val actions = UiKit.vstack(ctx).apply {
            addView(UiKit.spacer(ctx, 4))
            addView(UiKit.ghostButton(ctx, "新建会话") { dlg?.dismiss(); switchSession(newSessionId()); host.toast("已新建对话") })
            addView(UiKit.spacer(ctx, 6))
            addView(UiKit.ghostButton(ctx, "重命名当前会话") { dlg?.dismiss(); renameSession(s) })
            addView(UiKit.spacer(ctx, 6))
            addView(UiKit.ghostButton(ctx, "导出对话") { exportSession(s) })
            addView(UiKit.spacer(ctx, 6))
            addView(UiKit.ghostButton(ctx, "删除当前会话") {
                UiKit.confirm(ctx, "删除会话", "确定删除「${s.title}」及全部消息？", {
                    AgentChatStore.deleteSession(ctx, key, s.id)
                    activeSession = null // 防止 switchSession 内部 saveNow 把已删会话写回
                    dlg?.dismiss()
                    switchSession(newSessionId())
                    host.toast("已删除")
                })
            })
        }
        box.addView(actions)
        val wrap = UiKit.vstack(ctx)
        wrap.addView(box)
        dlg = showPanel("会话管理", wrap)
    }

    private fun newSessionId(): String = "s" + System.currentTimeMillis() + "_" + (Math.random() * 1000).toInt()

    private fun sessionRow(id: String, title: String, count: Int, summarized: Int, active: Boolean, ts: Long = 0L, onOpen: () -> Unit = {}): LinearLayout {
        val timeStr = if (ts > 0) java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts)) else ""
        return UiKit.hstack(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = UiKit.rounded(ctx, if (active) UiKit.themeColor(ctx, R.color.primary).let { android.graphics.Color.argb(0x1A, android.graphics.Color.red(it), android.graphics.Color.green(it), android.graphics.Color.blue(it)) } else UiKit.themeColor(ctx, R.color.surface_elevated), 10, UiKit.themeColor(ctx, R.color.divider), 1)
            setOnClickListener { onOpen(); switchSession(id) }
            addView(UiKit.label(ctx, if (active) "●" else "○", color = if (active) R.color.primary else R.color.muted, size = 12f))
            addView(UiKit.spacer(ctx, 8))
            addView(UiKit.vstack(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(UiKit.label(ctx, title.ifBlank { "对话" }, color = R.color.on_surface, size = 13f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(UiKit.label(ctx, "$count 条消息${if (summarized > 0) " · 已总结 $summarized" else ""}", color = R.color.muted, size = 10.5f))
            })
            addView(UiKit.spacer(ctx, 6))
            addView(UiKit.label(ctx, timeStr, color = R.color.muted, size = 10f))
        }
    }

    private fun renameSession(s: ChatSession) {
        val box = UiKit.vstack(ctx).apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val input = UiKit.input(ctx, s.title)
        box.addView(input)
        UiKit.dialog(ctx, "重命名会话", box, onOk = {
            val t = input.text.toString().trim()
            if (t.isNotBlank()) { s.title = t; saveNow(); refreshHeader() }
        }, onCancel = {})
    }

    private fun exportSession(s: ChatSession) {
        try {
            val md = AgentChatStore.exportText(s)
            val dir = File(ctx.filesDir, "agent_exports").apply { mkdirs() }
            val f = File(dir, "chat_${s.projectKey}_${System.currentTimeMillis()}.md")
            f.writeText(md)
            host.toast("已导出：${f.absolutePath}")
            // 尝试复制到公共 Downloads
            try {
                val dl = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "githubk_${s.title}.md")
                if (dl.parentFile?.exists() == true) { java.nio.file.Files.copy(f.toPath(), dl.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); host.toast("已导出到 ${dl.absolutePath}") }
            } catch (_: Throwable) {}
        } catch (e: Throwable) { host.toast("导出失败：${e.message}") }
    }

    // ================================================================
    //  设置
    // ================================================================
    private fun showQuickSettings() {
        val scroll = android.widget.ScrollView(ctx)
        val box = UiKit.vstack(ctx).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            scroll.addView(this)
        }
        box.addView(UiKit.label(ctx, "运行设置", color = R.color.primary, size = 13f).apply { typeface = Typeface.DEFAULT_BOLD })
        box.addView(UiKit.switchRow(ctx, "自动压缩历史", "上下文超过阈值时自动总结早期消息，防止丢上下文/超限", autoCompress) { autoCompress = it })
        box.addView(UiKit.spacer(ctx, 4))
        box.addView(UiKit.switchRow(ctx, "显示工具明细", "保留工具执行结果到对话记录", showToolArgs) { showToolArgs = it })
        box.addView(UiKit.spacer(ctx, 4))
        box.addView(UiKit.switchRow(ctx, "需求确认", "发送任务后先展示理解摘要，确认后才执行", confirmReq) { confirmReq = it })
        box.addView(UiKit.spacer(ctx, 4))
        box.addView(UiKit.switchRow(ctx, "风险操作审批", "写删/构建等高风险工具执行前弹窗批准", confirmRisky) { confirmRisky = it; applyAgentSettings() })
        box.addView(UiKit.spacer(ctx, 4))
        box.addView(UiKit.switchRow(ctx, "失败自动回滚", "构建失败或任务异常时自动回滚本轮改动", autoRollback) { autoRollback = it; applyAgentSettings() })
        box.addView(UiKit.spacer(ctx, 4))
        box.addView(UiKit.switchRow(ctx, "联网结果轻量整理", "web_search/web_fetch 的结果用轻模型整理成要点", lightTidy) { lightTidy = it; applyAgentSettings() })
        box.addView(UiKit.spacer(ctx, 4))
        box.addView(UiKit.switchRow(ctx, "文字动画", "AI 回复使用逐字动画展示", typingAnim) { typingAnim = it })
        box.addView(UiKit.spacer(ctx, 4))
        box.addView(UiKit.switchRow(ctx, "工具面板起始折叠", "工具卡片默认折叠，节省空间", collapseTools) { collapseTools = it })
        box.addView(UiKit.spacer(ctx, 6))

        val modeRow = UiKit.hstack(ctx)
        val lightBtn = UiKit.button(ctx, "轻模型：${lightModelId ?: "随主模型"}") { showLightModelPicker() }
        lightBtn.setPadding(dp(4), 0, dp(4), 0)
        lightBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        modeRow.addView(lightBtn)
        modeRow.addView(UiKit.spacer(ctx, 6))
        val stopBtn = UiKit.ghostButton(ctx, "停止任务") { stopRun() }
        stopBtn.setPadding(dp(4), 0, dp(4), 0)
        stopBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        modeRow.addView(stopBtn)
        box.addView(modeRow)
        box.addView(UiKit.spacer(ctx, 8))

        box.addView(UiKit.label(ctx, "功能面板", color = R.color.primary, size = 13f).apply { typeface = Typeface.DEFAULT_BOLD })
        val panelItems = listOf<Pair<String, () -> Unit>>(
            "任务看板" to { openTaskBoard() },
            "变量" to { openVariables() },
            "模板" to { openQuickCmds() },
            "变更日志" to { openChangeLog() },
            "复盘" to { openReview() },
            "定时" to { openSchedule() },
            "安全审计" to { openSafety() },
            "迁移" to { openMigration() },
            "预览" to { openPreview() },
            "标签" to { setSessionTag() },
            "MCP 服务" to { openMcpPanel() },
            "导出记录" to { openExportLog() }
        )
        panelItems.chunked(3).forEach { rowItems ->
            val row = UiKit.hstack(ctx)
            rowItems.forEach { (label, act) ->
                val b = UiKit.ghostButton(ctx, label) { act() }
                b.setPadding(0, 0, 0, 0)
                b.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                row.addView(b)
                row.addView(UiKit.spacer(ctx, 5))
            }
            box.addView(row)
            box.addView(UiKit.spacer(ctx, 5))
        }
        box.addView(UiKit.spacer(ctx, 2))
        box.addView(UiKit.spacer(ctx, 6))
        box.addView(UiKit.button(ctx, "切换 AI 模型") { showAiPicker() })
        box.addView(UiKit.spacer(ctx, 6))
        box.addView(UiKit.button(ctx, "AI 配置中心") { host.pushPage(AiSettingsPage(host).buildView()) })
        box.addView(UiKit.spacer(ctx, 6))
        box.addView(UiKit.button(ctx, "项目记忆") { openMemoryEditor() })
        showPanel("工作台设置", scroll, onOk = {})
    }

    private fun showLightModelPicker() {
        val profiles = state.ai.profiles()
        if (profiles.isEmpty()) { host.pushPage(AiSettingsPage(host).buildView()); return }
        val items = listOf(UiKit.SheetItem(icon = "", title = "随主模型", subtitle = "轻量整理改用默认模型", colorRes = R.color.muted) {
            lightModelId = null; applyAgentSettings(); host.toast("轻模型：随主模型")
        }) + profiles.map { p ->
            UiKit.SheetItem(icon = if (p.id == lightModelId) "✓" else "", title = p.name, subtitle = p.model, colorRes = R.color.muted) {
                lightModelId = p.id; applyAgentSettings(); host.toast("轻模型：${p.name}")
            }
        }
        UiKit.sheet(ctx, "轻量模型（用于快速整理）", "联网结果整理用的轻模型，可降低耗时", items)
    }

    private fun panelScroll(): Pair<android.widget.ScrollView, LinearLayout> {
        val scroll = android.widget.ScrollView(ctx)
        val box = UiKit.vstack(ctx).apply { setPadding(dp(4), dp(4), dp(4), dp(4)); scroll.addView(this) }
        return scroll to box
    }

    private fun openChangeLog() {
        val proj = currentProject() ?: run { host.toast("请先选择项目"); return }
        val (scroll, box) = panelScroll()
        val list = ProjectKnowledge.changeList(proj)
        if (list.isEmpty()) {
            box.addView(UiKit.label(ctx, "暂无变更记录。任务执行并写入文件后会自动生成。", color = R.color.muted, size = 12f))
        } else {
            list.take(30).forEach { e ->
                val card = UiKit.vstack(ctx).apply {
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = UiKit.rounded(ctx, UiKit.themeColor(ctx, R.color.surface_elevated), 12, UiKit.themeColor(ctx, R.color.divider), 1)
                }
                card.addView(UiKit.label(ctx, e.reason, color = R.color.on_surface, size = 12.5f).apply { typeface = Typeface.DEFAULT_BOLD })
                card.addView(UiKit.label(ctx, "时间 ${e.ts} · ${e.files.size} 文件", color = R.color.muted, size = 10.5f))
                card.addView(UiKit.label(ctx, e.files.distinct().take(8).joinToString("  ") { it }, color = R.color.primary, size = 10.5f).apply { maxLines = 2 })
                box.addView(card)
                box.addView(UiKit.spacer(ctx, 5))
            }
        }
        box.addView(UiKit.spacer(ctx, 4))
        box.addView(UiKit.button(ctx, "查看 Markdown") { previewText("变更日志", ProjectKnowledge.changelogMarkdown(proj)) })
        showPanel("变更日志", scroll)
    }

    private fun openReview() {
        val proj = currentProject() ?: run { host.toast("请先选择项目"); return }
        val (scroll, box) = panelScroll()
        val list = ProjectKnowledge.reviewList(proj)
        if (list.isEmpty()) {
            box.addView(UiKit.label(ctx, "暂无任务复盘。成功完成任务并写入文件后会自动记录要点。", color = R.color.muted, size = 12f))
        } else {
            list.take(30).forEach { r ->
                val card = UiKit.vstack(ctx).apply {
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = UiKit.rounded(ctx, UiKit.themeColor(ctx, R.color.surface_elevated), 12, UiKit.themeColor(ctx, R.color.divider), 1)
                }
                card.addView(UiKit.label(ctx, r.summary, color = R.color.on_surface, size = 12.5f).apply { typeface = Typeface.DEFAULT_BOLD })
                card.addView(UiKit.label(ctx, "${r.ts} · ${r.task.take(40)}", color = R.color.muted, size = 10.5f))
                r.points.take(6).forEach { p -> card.addView(UiKit.label(ctx, "· $p", color = R.color.muted, size = 11f)) }
                box.addView(card)
                box.addView(UiKit.spacer(ctx, 5))
            }
        }
        showPanel("任务复盘", scroll)
    }

    private fun openSchedule() {
        val proj = currentProject()
        val key = proj?.name ?: projectKey()
        val (scroll, box) = panelScroll()
        val jobs = ScheduleStore.list(ctx)
        if (jobs.isEmpty()) {
            box.addView(UiKit.label(ctx, "暂无定时任务。下方可创建：扫描待办或导出能力包。", color = R.color.muted, size = 12f))
        } else {
            jobs.forEach { j ->
                val row = UiKit.hstack(ctx).apply { setPadding(dp(10), dp(8), dp(10), dp(8)); background = UiKit.rounded(ctx, UiKit.themeColor(ctx, R.color.surface_elevated), 12, UiKit.themeColor(ctx, R.color.divider), 1) }
                row.addView(UiKit.label(ctx, "${if (j.enabled) "●" else "○"} ${j.name} · ${j.action} · 每${j.periodMin}分钟", color = R.color.on_surface, size = 12f).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(UiKit.ghostButton(ctx, if (j.enabled) "停" else "启") {
                    ScheduleStore.upsert(ctx, j.copy(enabled = !j.enabled)); openSchedule()
                })
                row.addView(UiKit.spacer(ctx, 3))
                row.addView(UiKit.ghostButton(ctx, "删") { ScheduleStore.delete(ctx, j.id); openSchedule() })
                box.addView(row)
                box.addView(UiKit.spacer(ctx, 5))
            }
        }
        box.addView(UiKit.spacer(ctx, 6))
        val name = UiKit.input(ctx, "任务名称")
        var action = "scan"
        val typeRow = UiKit.hstack(ctx)
        listOf("扫描待办", "导出能力包").forEachIndexed { i, t ->
            typeRow.addView(UiKit.chip(ctx, t, (i == 0 && action == "scan") || (i == 1 && action == "export")) {
                action = if (i == 0) "scan" else "export"
            })
            typeRow.addView(UiKit.spacer(ctx, 6))
        }
        var period = 30
        box.addView(name); box.addView(UiKit.spacer(ctx, 6)); box.addView(typeRow)
        box.addView(UiKit.spacer(ctx, 6))
        box.addView(UiKit.button(ctx, "创建定时任务（每30分钟）") {
            val j = AgentJob(java.util.UUID.randomUUID().toString().substring(0, 8), name.text.toString().ifBlank { "定时任务" }, key, action, period, true)
            ScheduleStore.upsert(ctx, j)
            host.toast("已创建：${j.name}")
            openSchedule()
        })
        showPanel("定时任务", scroll)
    }

    private fun openMcpPanel() {
        val (scroll, box) = panelScroll()
        box.addView(UiKit.label(ctx, "MCP 服务器（远程 JSON-RPC）", color = R.color.primary, size = 13f).apply { typeface = Typeface.DEFAULT_BOLD })
        val servers = state.mcp.servers()
        if (servers.isEmpty()) {
            box.addView(UiKit.label(ctx, "暂无 MCP 服务器。下方可添加远程 MCP Server（HTTP JSON-RPC，如 dsapi/glm 桥或自建工具）。", color = R.color.muted, size = 12f))
        } else {
            servers.forEach { s ->
                val row = UiKit.hstack(ctx).apply {
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = UiKit.rounded(ctx, UiKit.themeColor(ctx, R.color.surface_elevated), 12, UiKit.themeColor(ctx, R.color.divider), 1)
                }
                val info = UiKit.vstack(ctx)
                val toolLabel = UiKit.label(ctx, "工具加载中…", color = R.color.muted, size = 10.5f)
                info.addView(UiKit.label(ctx, "${if (s.enabled) "●" else "○"} ${s.name}", color = R.color.on_surface, size = 12f).apply { typeface = Typeface.DEFAULT_BOLD })
                info.addView(UiKit.label(ctx, s.url, color = R.color.muted, size = 10f).apply { maxLines = 1 })
                info.addView(toolLabel)
                info.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                row.addView(info)
                row.addView(UiKit.ghostButton(ctx, "刷") {
                    // 刷新工具列表是网络请求，放到后台线程
                    toolLabel.text = "刷新中…"
                    Thread {
                        val n = runCatching { state.mcp.refreshTools(s).size }.getOrDefault(-1)
                        host.runUi { openMcpPanel() }
                    }.start()
                })
                row.addView(UiKit.ghostButton(ctx, if (s.enabled) "停" else "启") { state.mcp.toggleServer(s.name); openMcpPanel() })
                row.addView(UiKit.ghostButton(ctx, "删") { state.mcp.removeServer(s.name); openMcpPanel() })
                box.addView(row)
                box.addView(UiKit.spacer(ctx, 5))
                // MCP 工具列表为网络请求，必须在后台线程执行，避免 NetworkOnMainThreadException
                Thread {
                    val n = runCatching { state.mcp.listTools(s).size }.getOrDefault(-1)
                    host.runUi { if (n >= 0) toolLabel.text = "$n 个工具" else toolLabel.text = "工具不可达" }
                }.start()
            }
        }
        box.addView(UiKit.spacer(ctx, 8))
        box.addView(UiKit.label(ctx, "添加服务器", color = R.color.primary, size = 13f).apply { typeface = Typeface.DEFAULT_BOLD })
        val nameInput = UiKit.input(ctx, "名称（如 ds）")
        val urlInput = UiKit.input(ctx, "URL（如 http://192.168.1.5:8788）")
        box.addView(nameInput)
        box.addView(urlInput)
        box.addView(UiKit.button(ctx, "添加") {
            val ok = state.mcp.addServer(nameInput.text.toString().trim(), urlInput.text.toString().trim())
            if (ok) { host.toast("已添加 ${nameInput.text.trim()}"); openMcpPanel() } else host.toast("名称与 URL 必填")
        })
        box.addView(UiKit.spacer(ctx, 8))
        box.addView(UiKit.switchRow(ctx, "本地 MCP Server", "将本 App 的 Agent 工具暴露为本地 MCP 服务（端口 8788）", state.mcp.isLocalRunning) {
            if (it) { state.mcp.startLocalServer(8788); host.toast("本地 MCP 已启动（8788）") } else { state.mcp.stopLocalServer(); host.toast("本地 MCP 已停止") }
        })
        showPanel("MCP 服务", scroll)
    }

    private fun openExportLog() {
        val (scroll, box) = panelScroll()
        val list = ExportLogStore.list(ctx)
        if (list.isEmpty()) {
            box.addView(UiKit.label(ctx, "暂无导出记录。AI/用户在本设备导出的 APK、源码包、文件会在此记录。", color = R.color.muted, size = 12f))
        } else {
            list.take(60).forEach { o ->
                val card = UiKit.vstack(ctx).apply {
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = UiKit.rounded(ctx, UiKit.themeColor(ctx, R.color.surface_elevated), 12, UiKit.themeColor(ctx, R.color.divider), 1)
                }
                card.addView(UiKit.label(ctx, "● ${o.optString("name")}", color = R.color.on_surface, size = 12.5f).apply { typeface = Typeface.DEFAULT_BOLD })
                card.addView(UiKit.label(ctx, "${o.optString("time")} · ${fmtSize(o.optLong("size"))} · ${o.optString("kind")}", color = R.color.muted, size = 10.5f))
                card.addView(UiKit.label(ctx, o.optString("path"), color = R.color.primary, size = 10.5f).apply { maxLines = 1 })
                val actions = UiKit.hstack(ctx)
                actions.addView(UiKit.ghostButton(ctx, "复制路径") {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("path", o.optString("path")))
                    host.toast("路径已复制")
                })
                actions.addView(UiKit.spacer(ctx, 5))
                actions.addView(UiKit.ghostButton(ctx, "打开目录") { openExportDir(o.optString("path")) })
                card.addView(actions)
                box.addView(card)
                box.addView(UiKit.spacer(ctx, 5))
            }
        }
        box.addView(UiKit.spacer(ctx, 4))
        box.addView(UiKit.button(ctx, "刷新") { openExportLog() })
        box.addView(UiKit.spacer(ctx, 5))
        box.addView(UiKit.ghostButton(ctx, "清空全部记录") {
            ExportLogStore.clear(ctx)
            host.toast("已清空导出记录")
            openExportLog()
        })
        showPanel("导出记录", scroll)
    }

    private fun openExportDir(p: String) {
        try {
            val dir = File(p).parentFile ?: File(p)
            val uri = Uri.fromFile(dir)
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            host.context.startActivity(intent)
        } catch (_: Throwable) {
            host.toast("无法打开目录：请使用文件管理器定位\n$p")
        }
    }

    private fun openSafety() {
        val proj = currentProject() ?: run { host.toast("请先选择项目"); return }
        val (scroll, box) = panelScroll()
        val logs = AuditLog.list(ctx, proj.name, null, 60)
        if (logs.isEmpty()) box.addView(UiKit.label(ctx, "暂无审计记录。任务执行、写删文件、高风险审批都会记录在此。", color = R.color.muted, size = 12f))
        else {
            box.addView(UiKit.label(ctx, "最近审计（${logs.size} 条）", color = R.color.on_surface, size = 12f).apply { typeface = Typeface.DEFAULT_BOLD })
            logs.forEach { o ->
                box.addView(UiKit.label(ctx, "${o.optString("ts").take(16)} [${o.optString("kind")}] ${o.optString("detail").take(90)}", color = if (o.optBoolean("ok")) R.color.muted else R.color.warning, size = 10.5f))
            }
        }
        box.addView(UiKit.spacer(ctx, 8))
        box.addView(UiKit.button(ctx, "扫描敏感信息") {
            val out = scanSecrets(proj)
            previewText("安全审计", out)
        })
        showPanel("安全与审计", scroll)
    }

    private fun scanSecrets(proj: Project): String {
        val sb = StringBuilder("扫描项目「${proj.name}」敏感信息…\n")
        val needles = listOf("-----BEGIN", "AKIA", "sk-", "api_key", "apikey", "password", "passwd", "secret", "token=", "private_key", "BEGIN PRIVATE KEY")
        val ext = setOf("kt", "java", "dart", "py", "js", "ts", "gradle", "xml", "json", "properties", "env", "md")
        var hits = 0
        var scanned = 0
        fun walk(d: File) {
            val cs = d.listFiles() ?: return
            for (c in cs) {
                if (c.isDirectory) {
                    if (c.name !in setOf("build", ".gradle", ".git", "node_modules")) walk(c)
                } else if (c.extension.lowercase() in ext && c.length() < 900_000) {
                    scanned++
                    val text = runCatching { c.readText(Charsets.UTF_8) }.getOrNull() ?: continue
                    if (text.any { needles.any { it in text } }) {
                        hits++
                        val line = text.lines().firstOrNull { l -> needles.any { it in l } }?.trim()?.take(80) ?: ""
                        sb.append("· ${c.name}: ${line.replace(Regex("(?i)(sk-[A-Za-z0-9]{8,}|AKIA[A-Za-z0-9]{10,})"), "[REDACTED]")}\n")
                    }
                }
            }
        }
        walk(proj.path)
        sb.append("\n共扫描 $scanned 个文本文件，$hits 处可能涉及敏感信息。\n")
        sb.append("提示：如需改动敏感或危险项，可在 AI 工作台确认后由 Agent 修复；打印报告时不显示完整密钥。")
        return sb.toString()
    }

    private fun openMigration() {
        val proj = currentProject() ?: run { host.toast("请先选择项目"); return }
        val (scroll, box) = panelScroll()
        box.addView(UiKit.label(ctx, "能力迁移：可把项目记忆/变更日志/变量/插件打包导出，或从 ZIP 导入到其它项目。", color = R.color.muted, size = 12f))
        box.addView(UiKit.spacer(ctx, 8))
        val src = UiKit.input(ctx, "ZIP 绝对路径（导入用）")
        box.addView(src)
        box.addView(UiKit.spacer(ctx, 8))
        box.addView(UiKit.button(ctx, "导出能力包") {
            val f = runCatching { ProjectKnowledge.exportAbility(proj) }.getOrNull()
            if (f != null) { host.toast("已导出：${f.name}"); previewText("导出成功", "能力包已生成：\n${f.absolutePath}\n\n${f.length() / 1024} KB，位于项目 .studio 之外 Downloads/导出目录。") }
            else host.toast("导出失败")
        })
        box.addView(UiKit.spacer(ctx, 6))
        box.addView(UiKit.button(ctx, "导入能力包") {
            val p = src.text.toString().trim()
            if (p.isBlank()) { host.toast("请填写 ZIP 绝对路径"); return@button }
            val ok = runCatching { ProjectKnowledge.importAbility(proj, File(p)) }.getOrDefault(0) > 0
            host.toast(if (ok) "导入成功" else "导入失败，请检查路径")
        })
        showPanel("迁移", scroll)
    }

    private fun openPreview() {
        val s = activeSession
        val r = s?.msgs?.lastOrNull { it.kind == "AI" }
        if (r == null) { host.toast("暂无 AI 回复可预览"); return }
        previewText("结果预览", r.text)
    }

    private fun previewText(title: String, text: String) {
        val scroll = android.widget.ScrollView(ctx)
        val tv = UiKit.label(ctx, text, color = R.color.on_surface, size = 13f).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            scroll.addView(this)
        }
        showPanel(title, scroll)
    }

    private fun setSessionTag() {
        val s = ensureSession()
        val tags = listOf("开发", "排错", "文档", "迭代", "AI逆向", "其它")
        val cur = s.tag
        val box = UiKit.vstack(ctx).apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }
        tags.chunked(3).forEach { lineTags ->
            val row = UiKit.hstack(ctx)
            lineTags.forEach { t ->
                val chip = UiKit.chip(ctx, t, cur == t) {
                    s.tag = t
                    AgentChatStore.saveSession(ctx, s)
                    host.toast("已设标签：$t")
                    setSessionTag()
                }
                chip.setPadding(0, 0, 0, 0)
                chip.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                row.addView(chip)
                row.addView(UiKit.spacer(ctx, 5))
            }
            box.addView(row)
            box.addView(UiKit.spacer(ctx, 5))
        }
        val clear = UiKit.ghostButton(ctx, "清除标签") {
            s.tag = ""; AgentChatStore.saveSession(ctx, s); host.toast("已清除标签")
        }
        box.addView(UiKit.spacer(ctx, 6))
        box.addView(clear)
        showPanel("会话标签", box).setOnDismissListener { refreshHeader() }
    }

    private fun openVariables() {
        val proj = currentProject() ?: run { host.toast("请先选择项目"); return }
        val (scroll, box) = panelScroll()
        box.addView(UiKit.label(ctx, "项目：${proj.name} · .studio/variables.json", color = R.color.muted, size = 11f))
        box.addView(UiKit.label(ctx, "任务中可用 {{key}} 引用；内置 project/version/path。", color = R.color.muted, size = 10.5f))
        box.addView(UiKit.spacer(ctx, 6))
        val kInput = UiKit.input(ctx, "变量名（key）")
        val vInput = UiKit.input(ctx, "变量值（value）")
        box.addView(kInput); box.addView(UiKit.spacer(ctx, 4)); box.addView(vInput)
        box.addView(UiKit.spacer(ctx, 4))
        box.addView(UiKit.button(ctx, "添加 / 更新变量") {
            val k = kInput.text.toString().trim()
            val v = vInput.text.toString().trim()
            if (k.isBlank()) { host.toast("变量名不能为空"); return@button }
            if (ProjectKnowledge.setVariable(proj, k, v)) { host.toast("已保存变量"); openVariables() }
        })
        val cur = ProjectKnowledge.listVariables(proj)
        if (cur.isEmpty()) {
            box.addView(UiKit.spacer(ctx, 4))
            box.addView(UiKit.label(ctx, "暂无变量。", color = R.color.muted, size = 11f))
        } else {
            box.addView(UiKit.spacer(ctx, 6))
            box.addView(UiKit.label(ctx, "当前变量", color = R.color.on_surface, size = 12f).apply { typeface = Typeface.DEFAULT_BOLD })
            cur.forEach { (k, v) ->
                val r = UiKit.hstack(ctx).apply { setPadding(0, dp(4), 0, 0) }
                r.addView(UiKit.label(ctx, "$k = ${v.take(24)}", color = R.color.muted, size = 11.5f).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                r.addView(UiKit.ghostButton(ctx, "删") { ProjectKnowledge.deleteVariable(proj, k); openVariables() })
                box.addView(r)
            }
        }
        showPanel("变量管理", scroll)
    }

    private fun openQuickCmds() {
        val presets = listOf(
            "修复构建错误" to "请构建项目，读取构建日志，自动修复所有编译错误，直到构建成功。",
            "代码审查" to "请审查项目核心代码（重点 Kotlin/Gradle），找出潜在 bug、性能问题与安全隐患，给出修复建议；如有明确问题请直接修复。",
            "统计项目规模" to "统计项目的代码行数、文件数、各类文件占比，列出最大文件并给出简要结构说明。",
            "生成变更日志" to "汇总本项目近期改动，生成一份面向用户的变更日志，并写成一个 markdown 文件。",
            "安全审计" to "审计项目的敏感信息与安全隐患：搜索密钥/token、危险代码调用等，输出报告并给出整改建议（不要打印完整密钥）。",
            "分析技术债务" to "识别项目的技术债务：重复代码、过时 API、过大的方法/类，按优先级给出重构建议。"
        )
        val items = presets.map { (t, d) ->
            UiKit.SheetItem(icon = "", title = t, subtitle = d.take(46) + "…", colorRes = R.color.primary) {
                chatInput.setText(d); chatInput.setSelection(d.length)
            }
        }
        UiKit.sheet(ctx, "快捷任务模板", "点击填入输入框，再发送；也可在设置里用「常用指令」扩展", items)
    }

    private fun showAiPicker() {
        val profiles = state.ai.profiles()
        val activeId = state.ai.activeId()
        if (profiles.isEmpty()) {
            host.pushPage(AiSettingsPage(host).buildView())
            return
        }
        val items = profiles.map { p ->
            UiKit.SheetItem(
                icon = if (p.id == activeId) "✓" else "",
                title = "${p.name} · ${state.ai.providerLabel(p.provider)}",
                subtitle = p.model.ifBlank { "未设模型" } + if (p.id == activeId) "（当前）" else "",
                colorRes = if (p.id == activeId) R.color.primary else R.color.muted
            ) {
                state.ai.setActive(p.id)
                host.toast("已切换模型：${p.name}")
                updateStatusBar()
            }
        }
        UiKit.sheet(ctx, "切换 AI 模型", "点击切换，配置见「AI 配置中心」", items)
    }

    // ================================================================
    //  必备能力 / AI 逆向
    // ================================================================
    private fun capFill(text: String) {
        chatInput.setText(text)
        chatInput.setSelection(text.length)
        chatInput.requestFocus()
    }

    private fun reverseApkPrompt(): String =
        "进入 AI 逆向模式，对当前项目${projectRef()}最新附件/构建产物 APK 做逆向分析：1) 用 list_files/file_tree 找到 .studio/attachments 下最近的 apk，若无则用 build 构建后在 build/outputs 定位 apk；2) 用 apk_info 读取包名/权限/ABI/dex/组件与条目；3) 用 apk_unzip 解压到 .studio/reverse/ 并有选择地浏览关键文件；4) 若 aapt2/aapt 或 dexdump 可用，用 run_command 提取更多信息；5) 将完整报告写入 docs/reverse/<名称>.md，并在对话中给出要点与风险/安全建议。"

    private fun reverseWebPrompt(): String =
        "进入 AI 逆向模式分析以下 Web 页面：请用 web_fetch/page_analyze 抓取并解析结构（标题/描述/JS/CSS/表单/接口/技术栈），必要时再抓取关键 JS 分析其逻辑与鉴权/加密，最后把报告写入 docs/reverse/<域名>.md，并给出架构与安全要点。\n目标 URL："

    private fun reverseFridaPrompt(): String =
        "进入 AI 逆向模式，生成一份可直接使用的 Frida 注入脚本：请根据目标应用包名与需求（如加密函数 Hook、网络拦截、调用栈捕获、主动调用）生成完整 hooks.js 保存到 .studio/reverse/frida/hooks.js，并给出 frida -U -f <包名> -l hooks.js 的使用说明；若通过 Shizuku 能在设备运行 frida-server，再给出注入命令。\n目标包名："

    private fun showReverseSheet() {
        val items = listOf(
            UiKit.SheetItem(icon = "📱", title = "逆向分析 APK", subtitle = "包名 / 权限 / ABI / dex / 组件，输出报告", colorRes = R.color.primary) {
                runOrToast(reverseApkPrompt())
            },
            UiKit.SheetItem(icon = "🌐", title = "分析 Web 页面", subtitle = "抓取并解析页面结构 / JS / 接口 / 技术栈", colorRes = R.color.accent) {
                capFill(reverseWebPrompt())
            },
            UiKit.SheetItem(icon = "🧬", title = "生成 Frida 注入脚本", subtitle = "生成可直接使用的 hooks.js 与注入说明", colorRes = R.color.accent) {
                capFill(reverseFridaPrompt())
            },
            UiKit.SheetItem(icon = "🛠", title = "从当前项目构建并逆向", subtitle = "先构建 APK，再自动逆向分析", colorRes = R.color.primary) {
                runOrToast("构建当前项目${projectRef()}的 APK，成功后用 apk_info 分析包信息/权限/结构，再 apk_unzip 解压查看关键文件，最后把报告写入 docs/reverse/ 并给出要点。")
            },
            UiKit.SheetItem(icon = "🧰", title = "启动独立逆向会话", subtitle = "切换/新建 AI逆向 记忆分区会话，专门执行 Frida/Proot/APK 逆向", colorRes = R.color.accent) {
                startReverseSession(reverseApkPrompt())
            }
        )
        UiKit.sheet(ctx, "AI 逆向工作台", "选择分析模式（Web / Frida 会自动填入模板，补充目标后发送）", items)
    }

    /** 启动/切换到「AI逆向」记忆分区会话并预填任务。 */
    private fun startReverseSession(task: String) {
        val key = projectKey()
        val s = ensureSession()
        s.tag = "AI逆向"
        AgentChatStore.saveSession(ctx, s)
        host.toast("已进入 AI 逆向工作台（独立记忆分区）")
        chatInput.setText(task)
        chatInput.setSelection(task.length)
        chatInput.requestFocus()
    }

    private fun showCapabilitySheet() {
        val groups = listOf<Pair<String, List<UiKit.SheetItem>>>(
            "智能增强" to listOf(
                UiKit.SheetItem(icon = "🔍", title = "联网搜索", subtitle = "web_search / web_fetch 实时抓取网络信息", colorRes = R.color.primary) {
                    capFill("请联网搜索并总结：")
                },
                UiKit.SheetItem(icon = "📚", title = "学习知识库", subtitle = "lessons_list / lessons_record，自动沉淀并复用修复经验", colorRes = R.color.accent) {
                    runOrToast("读取当前项目${projectRef()}的经验库（lessons_list）与项目记忆（memory_read），简要总结现状、可复用的经验与下一步建议。")
                },
                UiKit.SheetItem(icon = "🛠", title = "AI 逆向", subtitle = "APK / Web / Frida 注入逆向工作流", colorRes = R.color.primary) {
                    showReverseSheet()
                },
                UiKit.SheetItem(icon = "🔎", title = "源码搜索", subtitle = "search_code 在当前项目内精准检索代码与符号", colorRes = R.color.accent) {
                    capFill("用 search_code 在当前项目${projectRef()}内搜索：")
                },
                UiKit.SheetItem(icon = "🖼", title = "图像识别", subtitle = "vision_analyze 识别并分析图片（先附加图片再发送）", colorRes = R.color.primary) {
                    capFill("请先用 vision_analyze 分析我额外附加的图片，并给出要点：")
                },
                UiKit.SheetItem(icon = "📦", title = "导入外部库", subtitle = "import_lib 将本地/远程 jar、aar 导入工程并接入构建", colorRes = R.color.accent) {
                    capFill("用 import_lib 将外部库导入当前项目${projectRef()}：")
                }
            ),
            "效率与交付" to listOf(
                UiKit.SheetItem(icon = "🧩", title = "新建项目模板", subtitle = "Android / Compose / Xposed / Web / 嵌入式 C 等骨架", colorRes = R.color.primary) {
                    askNewProjectTask()
                },
                UiKit.SheetItem(icon = "🧬", title = "Xposed 模块", subtitle = "一键创建可编译的 Xposed / LSPosed 模块骨架", colorRes = R.color.accent) {
                    runOrToast("创建一个可编译的 Xposed 模块项目（使用 Xposed 模块模板），实现一个示例 Hook 并构建成功；若依赖的 XposedBridgeApi-82.jar 缺失，请说明如何补齐并如期完成构建。")
                },
                UiKit.SheetItem(icon = "🧪", title = "技能工作流", subtitle = "Skill / 预设指令 / 快捷任务模板", colorRes = R.color.primary) {
                    openQuickCmds()
                },
                UiKit.SheetItem(icon = "🛡", title = "特权命令", subtitle = "Shizuku 特权 Shell（需在设置/终端授权）", colorRes = R.color.accent) {
                    runOrToast("用 run_command 查看当前设备与运行时环境概要；若需系统级操作再使用 shizuku_exec 并说明权限要求。")
                }
            )
        )
        UiKit.groupedSheet(ctx, "必备能力", "点击即用；Web / Frida / 搜索类会自动填入模板，补充目标后发送", groups)
    }

    // ================================================================
    //  项目 / 新建 / 记忆
    // ================================================================
    private fun chooseProject() {
        val projects = state.workspace.projects()
        if (projects.isEmpty()) { host.toast("请先在工作区新建或导入项目"); return }
        val items = projects.map { p ->
            UiKit.SheetItem(
                icon = if (p.name == runningProject?.name || p.name == currentProject()?.name) "✓" else "",
                title = p.name,
                subtitle = "${p.type} · ${if (p.path.absolutePath.length > 34) "…" + p.path.absolutePath.takeLast(34) else p.path.absolutePath}",
                colorRes = if (p.name == runningProject?.name || p.name == currentProject()?.name) R.color.primary else R.color.muted
            ) {
                if (running) { host.toast("Agent 运行中，请先停止再切换项目"); return@SheetItem }
                saveNow()
                host.toast("已选择项目：${p.name}")
                runningProject = p
                ensureSession()
                refresh()
                applyAgentSettings()
            }
        }
        UiKit.sheet(ctx, "切换目标项目", "会话按项目隔离存储", items)
    }

    private fun askNewProjectTask() {
        val box = UiKit.vstack(ctx).apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val nameInput = UiKit.input(ctx, "项目名称，如 CounterApp")
        var type = "Android"
        box.addView(UiKit.label(ctx, "项目名称", color = R.color.on_surface, size = 12f))
        box.addView(nameInput)
        box.addView(UiKit.spacer(ctx, 8))
        val typeRow = UiKit.hstack(ctx)
        listOf("Android", "Compose", "Flutter", "Web", "Xposed", "嵌入式 C", "空项目").forEach { t ->
            typeRow.addView(UiKit.chip(ctx, t, type == t) { type = t })
            typeRow.addView(UiKit.spacer(ctx, 6))
        }
        box.addView(typeRow)
        box.addView(UiKit.spacer(ctx, 8))
        val goalInput = UiKit.textArea(ctx, "例如：一个带加减按钮和计数显示的主页面")
        box.addView(goalInput)
        UiKit.dialog(ctx, "新建并构建项目", box, onOk = {
            val name = nameInput.text.toString().trim().ifBlank { "NewProject" }
            val goal = goalInput.text.toString().trim()
            val task = buildString {
                append("创建一个名为 $name 的 $type 项目")
                if (goal.isNotBlank()) append("，实现以下功能：$goal")
                append("。完成后构建项目，若失败请读取错误信息修复后重新构建，直到构建成功，最后导出 APK 到 Downloads 目录。")
            }
            sendTask(task)
        }, onCancel = {})
    }

    private fun memoFile(project: Project): File = File(File(project.path, ".studio"), "memory.json")
    private fun loadMemoryText(project: Project): String = runCatching {
        val f = memoFile(project)
        if (f.isFile) f.readText().trim() else ""
    }.getOrDefault("")

    private fun saveMemoryText(project: Project, text: String) {
        runCatching {
            val f = memoFile(project)
            f.parentFile?.mkdirs()
            f.writeText(text)
        }
    }

    private fun openMemoryEditor() {
        val proj = currentProject()
        if (proj == null) { host.toast("请先在工作区选择/创建项目，才能写项目记忆"); return }
        val box = UiKit.vstack(ctx).apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }
        box.addView(UiKit.label(ctx, "项目：${proj.name} · 存储于 .studio/memory.json", color = R.color.muted, size = 11f))
        box.addView(UiKit.label(ctx, "Agent 每次开工自动读取此记忆；对话中可用 memory_write 追加。请勿存放 Token/密钥。", color = R.color.muted, size = 10.5f))
        box.addView(UiKit.spacer(ctx, 6))
        val area = UiKit.textArea(ctx, "记录项目的关键约定、已完成事项、下一步计划…")
        area.setText(loadMemoryText(proj))
        box.addView(area, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)))
        showPanel("项目记忆", box, onOk = {
            saveMemoryText(proj, area.text.toString().trim())
            host.toast("已保存项目记忆")
        })
    }

    // ================================================================
    //  图片视觉 & 通用附件上传
    // ================================================================
    /** 选择任意类型附件（图片/压缩包/APK/文本等）并暂存，发送时注入任务或走视觉解析。 */
    private fun pickAndAttachFiles() {
        host.pickFile { uri ->
            if (uri == null) { host.toast("未选择文件"); return@pickFile }
            Thread {
                try {
                    val info = stageAttachment(uri)
                    host.runUi {
                        pendingAttachments.add(info)
                        renderAttachHint()
                        // 保持输入框可用并重新聚焦，避免出现「附加后无法输入」的问题
                        chatInput.isEnabled = true
                        chatInput.post { chatInput.requestFocus() }
                        host.toast("已附加：${info.name}（${kindLabel(info.kind)}）")
                    }
                } catch (e: Throwable) {
                    host.runUi { host.toast("附件读取失败：${e.message}") }
                }
            }.start()
        }
    }

    /** 将附件复制到项目 .studio/attachments（无项目则存应用私有目录），并返回附件信息。 */
    private fun stageAttachment(uri: Uri): AttachmentInfo {
        val resolver = ctx.contentResolver
        val name = queryDisplayName(uri) ?: "file_${System.currentTimeMillis()}"
        val size = querySize(uri)
        val kind = classifyKind(name, resolver.getType(uri))
        val proj = currentProject()
        val destDir = if (proj != null) java.io.File(java.io.File(proj.path, ".studio"), "attachments")
                      else java.io.File(java.io.File(ctx.filesDir, ".studio"), "attachments")
        destDir.mkdirs()
        val destName = "${System.currentTimeMillis()}_$name"
        val dest = java.io.File(destDir, destName)
        try {
            resolver.openInputStream(uri)?.use { inp -> dest.outputStream().use { out -> inp.copyTo(out) } }
                ?: throw IllegalStateException("无法读取文件")
        } catch (e: Throwable) {
            // 上传/复制失败：清理残留半成品，避免留下无法撤回的垃圾文件
            runCatching { dest.delete() }
            throw e
        }
        val rel = if (proj != null) ".studio/attachments/$destName" else dest.absolutePath
        val dataUrl = if (kind == "image") runCatching { imageToDataUrl(dest.absolutePath) }.getOrDefault("") else ""
        return AttachmentInfo(name, rel, size, kind, dataUrl)
    }

    /** 更新输入区附件：每项一行（图标+文件名+类型/大小+撤回按钮），可逐项删除。 */
    private fun renderAttachHint() {
        attachBar.removeAllViews()
        if (pendingAttachments.isEmpty()) {
            attachBar.visibility = View.GONE
            attachScroll.visibility = View.GONE
            return
        }
        attachBar.visibility = View.VISIBLE
        attachScroll.visibility = View.VISIBLE
        pendingAttachments.forEachIndexed { i, a ->
            val icon = UiKit.label(ctx, kindIcon(a.kind), color = R.color.primary, size = 14f).apply {
                gravity = Gravity.CENTER
                background = UiKit.rounded(ctx, UiKit.themeColor(ctx, R.color.surface), 9, UiKit.themeColor(ctx, R.color.divider), 1)
            }
            val row = UiKit.hstack(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(6), dp(6), dp(6))
                background = UiKit.rounded(ctx, UiKit.themeColor(ctx, R.color.surface), 11, UiKit.themeColor(ctx, R.color.divider), 1)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)))
            row.addView(UiKit.spacer(ctx, 8))
            val info = UiKit.label(ctx, "${a.name}", color = R.color.on_surface, size = 12f).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(info)
            row.addView(UiKit.spacer(ctx, 6))
            row.addView(UiKit.label(ctx, "${kindLabel(a.kind)} ${fmtSize(a.size)}", color = R.color.muted, size = 11f))
            row.addView(UiKit.spacer(ctx, 6))
            val del = UiKit.ghostButton(ctx, "✕") { removeAttachment(i) }.apply {
                textSize = 14f
                minWidth = 0
                isClickable = true
                isFocusable = false
                isFocusableInTouchMode = false
                setPadding(dp(10), 0, dp(10), 0)
            }
            row.addView(del)
            attachBar.addView(row)
        }
        // 附件多了之后限制容器高度，避免把输入框挤出屏幕；超出部分内部滚动
        attachScroll.post {
            runCatching {
                val maxH = dp(132)
                val lp = attachScroll.layoutParams as LinearLayout.LayoutParams
                lp.height = if (attachBar.height > maxH) maxH else LinearLayout.LayoutParams.WRAP_CONTENT
                attachScroll.layoutParams = lp
                attachScroll.requestLayout()
            }
        }
    }

    /** 撤回指定附件：从列表移除并删除已写入的文件。 */
    private fun removeAttachment(index: Int) {
        if (index !in pendingAttachments.indices) return
        val a = pendingAttachments.removeAt(index)
        runCatching {
            val f = java.io.File(a.rel)
            if (f.isFile) f.delete()
        }
        renderAttachHint()
        host.toast("已撤回附件：${a.name}")
    }

    private fun kindIcon(kind: String): String = when (kind) {
        "image" -> "🖼"
        "zip" -> "🗜"
        "apk" -> "📦"
        "text" -> "📄"
        else -> "📎"
    }

    private fun queryDisplayName(uri: Uri): String? {
        val c = ctx.contentResolver.query(uri, null, null, null, null) ?: return uri.lastPathSegment?.substringAfterLast('/')
        var name: String? = null
        c.use {
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && it.moveToFirst() && !it.isNull(idx)) name = it.getString(idx)
        }
        return name ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun querySize(uri: Uri): Long {
        val c = ctx.contentResolver.query(uri, null, null, null, null)
        c?.use {
            val idx = it.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && it.moveToFirst() && !it.isNull(idx)) return it.getLong(idx)
        }
        return runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: 0L }.getOrDefault(0L)
    }

    /** 依据扩展名/MIME 判定附件类型（image/zip/apk/text/other）。 */
    private fun classifyKind(name: String, mime: String?): String {
        val n = name.lowercase()
        val m = (mime ?: "").lowercase()
        return when {
            n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".gif") ||
                n.endsWith(".webp") || n.endsWith(".bmp") || m.startsWith("image/") -> "image"
            n.endsWith(".apk") || m.contains("vnd.android") || m.contains("android/package") -> "apk"
            n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".aar") || n.endsWith(".7z") ||
                n.endsWith(".rar") || n.endsWith(".tar") || n.endsWith(".gz") || n.endsWith(".tgz") ||
                m.contains("zip") || m.contains("compressed") || m.contains("tar") -> "zip"
            n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".kt") || n.endsWith(".java") ||
                n.endsWith(".json") || n.endsWith(".xml") || n.endsWith(".py") || n.endsWith(".js") ||
                n.endsWith(".ts") || n.endsWith(".gradle") || n.endsWith(".properties") ||
                n.endsWith(".yaml") || n.endsWith(".yml") || n.endsWith(".log") || m.startsWith("text/") -> "text"
            else -> "other"
        }
    }

    /** 读取文件转 base64（带采样压缩，用于图片视觉解析）。 */
    private fun imageToDataUrl(path: String): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(path, opts) ?: throw IllegalStateException("无法解码图片")
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        return "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    /** 人类可读文件大小。 */
    private fun fmtSize(size: Long): String {
        if (size <= 0) return "未知"
        if (size < 1024) return "${size}B"
        val kb = size / 1024.0
        if (kb < 1024) return "%.1fKB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1fMB".format(mb)
        return "%.2fGB".format(mb / 1024.0)
    }

    /** 图片对话：先做视觉解析，将结果写入会话；若文字附带任务则继续执行 Agent。 */
    private fun sendWithImage(text: String, dataUrl: String) {
        if (!hasAiReady()) return
        if (running) { host.toast("Agent 正在运行中"); return }
        val s = ensureSession()
        val question = if (text.isNotBlank()) text else "请分析这张图片的内容并总结要点。"
        AgentChatStore.addMsg(s, "USER", "[图片] $question")
        saveNow()
        appendUserToUi()
        addSystem("正在解析图片…")
        Thread {
            try {
                val answer = state.ai.chatWithImage(question, dataUrl)
                host.runUi {
                    if (answer.isNotBlank()) {
                        AgentChatStore.addMsg(s, "AI", answer.trim())
                        saveNow()
                        msgList.addView(aiBubble(s.msgs.last(), anim = true))
                        addSystem("图片解析完成。可继续输入任务让 Agent 执行（如需我修改代码/构建，请直接描述需求）。")
                        scrollToBottom()
                        updateStatusBar()
                    } else addSystem("图片解析无结果")
                }
            } catch (e: Throwable) {
                host.runUi { addSystem("图片解析失败：${e.message}") }
            }
        }.start()
    }

    // ================================================================
    //  Problems 扫描（轻量）
    // ================================================================
    private data class Problem(val file: String, val line: Int, val type: String, val text: String)

    private fun runProblemsScan() {
        val proj = currentProject() ?: run { host.toast("请先选择项目"); return }
        if (running) { host.toast("Agent 正在运行"); return }
        addSystem("正在扫描「${proj.name}」待办/问题…")
        Thread {
            val result = mutableListOf<Problem>()
            val re = Regex("\\b(TODO|FIXME|HACK|XXX|BUG)\\b[:：]?\\s*(.*)")
            val skipDirs = setOf("build", ".gradle", ".git", "node_modules", ".dart_tool", "gradle")
            val exts = setOf("kt", "java", "xml", "py", "dart", "js", "ts", "gradle", "kts", "md")
            fun walk(dir: File) {
                val children = dir.listFiles() ?: return
                for (child in children) {
                    if (child.isDirectory) {
                        if (child.name in skipDirs) continue
                        walk(child)
                    } else {
                        if (child.extension.lowercase() !in exts) continue
                        if (child.length() > 1024 * 1024) continue
                        val lines = runCatching { child.readText(Charsets.UTF_8).lines() }.getOrDefault(emptyList())
                        lines.forEachIndexed { i, line ->
                            val m = re.find(line)
                            if (m != null) result.add(Problem(child.absolutePath, i + 1, m.groupValues[1].uppercase(), m.groupValues[2].trim().ifBlank { "待办标记" }))
                        }
                    }
                }
            }
            walk(proj.path)
            host.runUi {
                if (result.isEmpty()) addSystem("未发现 TODO/FIXME 等标记")
                else {
                    val text = result.take(60).joinToString("\n") { "• ${it.type} ${it.file.substringAfterLast('/')}:${it.line} ${it.text.take(40)}" }
                    AgentChatStore.addMsg(activeSession ?: ensureSession(), "AI", "扫描「${proj.name}」发现 ${result.size} 处待办：\n$text")
                    rebuildMessageList()
                    scrollToBottom()
                }
            }
        }.start()
    }
}
