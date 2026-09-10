package com.example.myempty.githubk.ai

import com.example.myempty.githubk.core.Project
import com.example.myempty.githubk.core.SecurityPolicy
import com.example.myempty.githubk.core.WorkspaceManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * AgentRuntime v3.0（对话式 AI 工作台）
 *
 * 相比 v2.5：
 * - 事件流：把 AI 回复、工具执行、文件写入以事件推送给 UI（聊天式渲染）
 * - 历史注入：runConversation 可带多轮 user/assistant 历史 + 对话摘要，实现连续对话
 * - 强制任务完成：工具失败不会立刻以纯文本结束；会强制 AI 继续分析/重试
 * - 任务拆解与自主扩展：system prompt 强化为「拆解执行 / 自举新能力 / 不要中途停止」
 * - 最大迭代 40，支持上下文记忆（.studio/memory.json 注入）
 */
data class AgentResult(
    val success: Boolean,
    val log: String,
    val filesChanged: Int,
    val iterations: Int,
    val finalMessage: String,
    val summary: String = "",   // 对话摘要（可选），由调用方回写
    val interrupted: Boolean = false
)

/** 会改动工作区文件的工具名（成功执行时计入 filesChanged，驱动变更日志/复盘/审计）。 */
private val FILE_MUTATING_TOOLS = setOf(
    "write_file", "file_write", "write", "overwrite", "create_file", "insert_file", "update_file",
    "append_file", "patch_file", "patch", "apply_patch", "batch_replace", "auto_docs",
    "delete_file", "delete", "remove_file", "remove", "unlink"
)

/** 运行时事件。 */
sealed class AgentEvent {
    /** 新一轮 AI 原始回复（可能含 FILE/TOOL 标签；UI 可做“思考中”展示）。 */
    data class AiTurn(val round: Int, val raw: String) : AgentEvent()

    /** 纯文本最终回复（无工具标签，聊天 UI 的直接回答）。 */
    data class FinalText(val text: String) : AgentEvent()

    /** 文件写入事件。 */
    data class FileWrite(val path: String, val ok: Boolean) : AgentEvent()

    /** 工具开始执行。 */
    data class ToolStart(val seq: Int, val name: String, val args: String) : AgentEvent()

    /** 工具执行结束。 */
    data class ToolEnd(val seq: Int, val ok: Boolean, val output: String) : AgentEvent()

    /** 系统提示（如：自动从项目记忆注入、强制继续等）。 */
    data class Note(val text: String) : AgentEvent()

    /** 实时日志行（构建/命令输出的流式回传，用于进度可视化）。 */
    data class Log(val text: String) : AgentEvent()
}

class AgentRuntime(
    private val ai: AiManager,
    private val workspace: WorkspaceManager,
    private val dispatcher: AgentToolDispatcher,
    private val maxIterations: Int = 40
) {

    private val stopFlag = java.util.concurrent.atomic.AtomicBoolean(false)

    // ---------- v6.0 门控 / 分工 / 回滚 ----------
    /** 高/中风险弹窗审批开关（由 AgentPage 设置读取）。 */
    @Volatile var riskConfirm: Boolean = false
    /** 需求摘要 / 联网结果整理的“轻量模型”配置；空则回退当前激活模型。 */
    @Volatile var lightProfileId: String? = null
    /** 构建失败自动回滚开关。 */
    @Volatile var autoRollback: Boolean = false
    /** 联网搜索结果整理回调（由 UI 注入轻量模型整理器）。 */
    @Volatile var webLightRouter: ((String) -> String)? = null
    /** 审批请求回调：(seq, tool, args, reason)。 */
    @Volatile var onApprovalRequest: ((Int, String, String, String) -> Unit)? = null
    private val approvalGates = ConcurrentHashMap<Int, CompletableFuture<Boolean>>()
    private var lastBuildFailed = false

    /** UI 提交审批结果（批准/拒绝）。 */
    fun submitApproval(seq: Int, granted: Boolean) {
        approvalGates[seq]?.complete(granted)
        approvalGates.remove(seq)
    }

    fun pendingApprovalCount(): Int = approvalGates.size

    /** 请求停止当前任务：置停止标记、中断正在进行的 AI 网络请求、唤醒审批等待、中止长命令。 */
    fun requestStop() {
        stopFlag.set(true)
        // 唤醒处于审批等待中的工具（未批准执行）
        approvalGates.values.forEach { it.complete(false) }
        // 中断当前 AI 单次请求的阻塞读，使任务能“随时”停下
        runCatching { ai.cancelActiveRequest() }
        // 中止正在执行的长命令（build 等），避免“点停止无反应”
        runCatching { dispatcher.requestAbort() }
    }

    /** 新任务开始前复位停止标记。 */
    fun resetStopFlag() { stopFlag.set(false) }

    private fun stopped(): Boolean = stopFlag.get()

    companion object {
        private val PLACEHOLDER_PROJECT by lazy {
            Project("__none__", "Other", java.io.File("/dev/null"))
        }

        /** 工具名 → 描述（供 system prompt 生成，避免每次手工维护）。 */
        private val EXTRA_TOOL_DOC = """
- Import: import_project {"source":"repo"|"zip","url":"...","zip":"...","name":"..."} (import a project into the workspace: source=repo clones a git URL into the project list, source=zip unzips a local archive path; then confirm with list_projects and browse with file_tree/source_files)
- Problems & memory: scan_problems {"project":"可选"} (scan TODO/FIXME/BUG markers), memory_read (read .studio/memory.json), memory_write {"content":"..."} (store long-term project memory), memory_append {"content":"..."} (append to project memory). Reverse-specific memory partition: reverse_memory_read / reverse_memory_write / reverse_memory_append (store reverse findings in .studio/reverse/memory.json, separate from normal project memory).
- Incremental edit: patch_diff {"path":"rel","search":"...","replace":"..."} (preview a local replace), patch_file {"path":"rel","search":"...","replace":"..."} (apply precise local replace, safer than rewriting a whole big file), append_file {"path":"rel","content":"..."} (append to an existing file)
- Batch: batch_replace {"path":"rel-dir","find":"...","replace":"...","exts":["kt","java"]} (batch replace in many files, skip binaries)
- Dependency helper: dependency_scan (parse build.gradle/pubspec), dependency_add {"artifact":"androidx.appcompat:appcompat","version":"1.6.1","path":"app/build.gradle"} (append under dependencies; run build afterwards), dependency_lock {"artifact":"...","version":"..."} (pin exact version, resolve conflicts by switching to compatible version)
- Docs: auto_docs (generate README.md + docs/guide.md), read_doc {"path":"rel"} (read local docs/logs/config into context), read_zip {"path":"rel.zip"} (list archive entries), read_zip_entry {"path":"rel.zip","entry":"file"} (read one text entry from a zip into context)
- Templates: list_templates (Android/Compose/Flutter/Web/React Native/Xposed 模块/嵌入式 C/Linux 工具/空项目), create_project uses type "Compose|Web|React Native|Xposed|嵌入式 C|Linux 工具" to init corresponding skeleton; Xposed 模块骨架需 app/libs/XposedBridgeApi-82.jar 才能编译
- Search & learning: search_code {"query":"...","file":"可选文件名过滤","context":0-5,"ignore_case":true} (source search with file:line, optional surrounding lines; skips build/.git/node_modules), project_summary (fast project health check: type/build entry/source stats/APK), lessons_list (read project experience library .studio/lessons.md), lessons_record {"problem":"...","lesson":"..."} (write a hard-won fix into the project experience library; later tasks automatically get these lessons injected — when you solve a non-obvious problem or a build failure, ALWAYS record it so future sessions learn from it)
- Plugins: list_plugins (project .studio/plugins/*.json), save_plugin {"name":"...","description":"...","command":"shell"} (add sandbox plugin), run_plugin {"name":"...","args":"..."} (run plugin inside project runtime, sandboxed; disabled via features_off won't run), export_ability (zip memory/changelog/variables/plugins), import_ability {"path":"abs-zip"} (migrate ability from another project)
- Shizuku (privileged shell, requires the app granted Shizuku access): shizuku_exec {"command":"..."} (run a command with system/root-level privileges; try run_command first, only use shizuku_exec when run_command cannot access protected paths)
- AI 逆向 (reverse engineering): apk_info {"path":"可选.apk"} (analyze an APK: package/permissions/ABI/dex/components/entries; auto-finds the latest attachment APK), apk_unzip {"path":"可选.apk","overwrite":false} (unzip an APK into .studio/reverse/<name>/ to browse files), page_analyze {"url":"https://..."} (fetch a web page & extract title/description/scripts/styles/links/forms/api endpoints/tech stack). Use these before producing a reverse report; save reports into docs/reverse/.
- 图像识别/视觉 (image recognition): vision_analyze {"path":"图片路径","question":"可选问题"} (use the configured vision-capable model to describe/analyze an image; call after uploading/staging an image).
- 本地离线推理 (local offline inference; no network, no upload, image never leaves the device): local_vision {"image":"图片路径","task":"auto|classify|detect|ocr","model":"可选","labels":"可选","topk":5,"conf":0.25,"iou":0.45,"size":0,"langs":"可选","psm":6} (run a real neural network ON THIS DEVICE for image classification / object detection / OCR; uses the project-relative path, an absolute path, or a file name under .studio/attachments). local_ai_status {"force":false} (read-only probe of the local inference environment: python/onnxruntime/tflite/tesseract versions, installed components, local models, per-task readiness). local_ai_install {"component":"<id>"} 或 {"task":"classify|detect|ocr"} (download and install inference dependencies into the app private dir; HIGH RISK — get explicit user approval first; automatic rollback on failure). If local_vision reports missing dependencies, report the exact list to the user and ask for approval BEFORE calling local_ai_install. 
- 外部库导入 (external library): import_lib {"path":"本地 jar/aar"或"url":"远程","name":"可选文件名","group":"可选 maven 坐标"} (copy a local library or download a remote one into app/libs/ and wire it into app/build.gradle when possible). Also dependency_add to add a maven dependency.
- Frida / Proot 编排 (reverse orchestration): frida_gen {"package":"目标包名","need":"需求"} (generate a ready-to-use hooks.js into .studio/reverse/frida/ with injection instructions), proot_orchestrate {"package":"可选","mode":"frida|proot|env"} (print a proot/Shizuku-based frida-server orchestration plan). Always generate/verify before reporting success.
""".trim()
    }

    // ---------- 向后兼容入口（AgentPage v2 风格日志回调） ----------
    fun run(task: String, project: Project?, onLog: (String) -> Unit = {}): AgentResult {
        return runConversation(
            task = task,
            project = project,
            onEvent = { ev -> onLog(eventToText(ev)) }
        )
    }

    fun eventToText(ev: AgentEvent): String = when (ev) {
        is AgentEvent.AiTurn -> "── 第 ${ev.round} 轮 AI ──\n${ev.raw.take(3000)}"
        is AgentEvent.FinalText -> ev.text
        is AgentEvent.FileWrite -> if (ev.ok) "✓ 写入 ${ev.path}" else "✗ 写入失败 ${ev.path}"
        is AgentEvent.ToolStart -> "→ 工具 ${ev.name} ${ev.args.take(500)}"
        is AgentEvent.ToolEnd -> if (ev.ok) "✓ 工具完成" else "✗ 工具失败"
        is AgentEvent.Note -> "ℹ ${ev.text}"
        is AgentEvent.Log -> ev.text
    }

    /**
     * 对话式执行：task = 用户最新消息；
     * history = 之前的对话（仅 user/assistant 文本），summary = 压缩摘要注入 system；
     * forceFinish = 结束时若尚未成功也生成收尾说明。
     */
    fun runConversation(
        task: String,
        project: Project?,
        history: List<ChatMessage> = emptyList(),
        summary: String = "",
        reverseMode: Boolean = false,
        onEvent: (AgentEvent) -> Unit = {}
    ): AgentResult {
        lastBuildFailed = false
        if (project != null) {
            // v6.0：任务开始前清空旧快照并始终开启写前捕获（用于变更日志/审计/失败回滚）
            runCatching { workspace.beginRollbackScope(project) }
            workspace.setRunCapture(true)
        } else {
            workspace.setRunCapture(false)
        }
        val res = runConversationInternal(task, project, history, summary, reverseMode, onEvent)
        var finalRes = res
        // 编译失败自动回滚：保留完整错误日志，恢复本次任务改动的文件
        if (autoRollback && project != null && !res.success && !res.interrupted && lastBuildFailed &&
            workspace.rollbackCount(project) > 0
        ) {
            val n = workspace.applyRollback(project)
            val note = "编译失败自动回滚：已恢复本次任务修改的 $n 个文件。完整错误日志已保存，可打开「变更与日志」查看，项目源码未受破坏。"
            onEvent(AgentEvent.Note(note))
            finalRes = res.copy(
                filesChanged = 0,
                log = res.log + "\n\n[自动回滚] " + note,
                finalMessage = res.finalMessage.trim() + "\n\n" + note
            )
        }
        if (autoRollback) workspace.setRunCapture(false)
        return finalRes
    }

    private fun runConversationInternal(
        task: String,
        project: Project?,
        history: List<ChatMessage> = emptyList(),
        summary: String = "",
        reverseMode: Boolean = false,
        onEvent: (AgentEvent) -> Unit = {}
    ): AgentResult {
        val ctx = workspace.agentContext(project)
        val memoryNote = loadMemoryNote(project)
        val revMemoryNote = if (reverseMode && project != null && !project.name.equals("__none__", true)) {
            runCatching { dispatcher.dispatcherReadRevMemory(project) }.getOrDefault("")
        } else ""
        val lessonsNote = if (project != null && !project.name.equals("__none__", true)) {
            runCatching { dispatcher.readLessons(project, 5000) }.getOrDefault("")
        } else ""
        val repoNote = if (project != null && !project.name.equals("__none__", true)) {
            runCatching { dispatcher.repoContextFor(project) }.getOrDefault("")
        } else ""
        val system = buildSystemPrompt(ctx, memoryNote, summary, repoNote, lessonsNote, revMemoryNote, reverseMode)
        val log = StringBuilder()
        var filesChanged = 0
        var iterations = 0
        var lastReply = ""
        var toolSeq = 0

        // 历史注入：保留历史 user/assistant，并保证最终以最新任务（user）开头进行
        val messages = mutableListOf<ChatMessage>()
        history.forEach { h ->
            if (h.role == "user" || h.role == "assistant") {
                if (h.content.isNotBlank()) messages.add(ChatMessage(h.role, h.content))
            }
        }
        messages.add(ChatMessage("user", task))
        if (messages.sumOf { it.content.length } > 60000) {
            // 历史过长：保留最近 40000 字符 + 最新任务（末尾保护）
            while (messages.size > 2 && messages.subList(0, messages.size - 1).sumOf { it.content.length } > 40000) {
                messages.removeAt(0)
            }
        }

        var failedLastRound = false   // 本轮出现工具失败（要求继续修复）
        var forceContinueLeft = 4     // 失败后可强制继续轮数上限

        while (iterations < maxIterations) {
            if (stopped()) {
                return AgentResult(false, log.toString(), filesChanged, iterations, "任务已被用户停止。", interrupted = true)
            }
            iterations++
            val reply = try {
                ai.chat(messages, system)
            } catch (e: Throwable) {
                if (stopped()) {
                    return AgentResult(false, log.toString(), filesChanged, iterations, "任务已被用户停止。", interrupted = true)
                }
                log.appendLine("AI 请求失败: ${e.message}")
                val errMsg = e.message ?: "AI error"
                return AgentResult(false, log.toString(), filesChanged, iterations, errMsg)
            }
            lastReply = reply
            log.appendLine("── 第 $iterations 轮 AI ──")
            log.appendLine(reply.take(4000))
            onEvent(AgentEvent.AiTurn(iterations, reply))

            // 1) FILE 写入
            val fileWrites = AgentProtocol.parseFiles(reply)
            fileWrites.forEach { fw ->
                val clean = fw.path.replace('\\', '/').trim().removePrefix("/")
                val projectName = clean.substringBefore('/')
                val p = workspace.project(projectName)
                if (p != null) {
                    val rel = clean.substringAfter('/', "")
                    if (workspace.writeSafe(p, rel, fw.content)) {
                        filesChanged++
                        log.appendLine("✓ 写入 ${p.name}/$rel")
                        onEvent(AgentEvent.FileWrite("${p.name}/$rel", true))
                    } else {
                        log.appendLine("✗ 拒绝写入 ${p.name}/$rel (路径非法)")
                        onEvent(AgentEvent.FileWrite("${p.name}/$rel", false))
                    }
                } else {
                    log.appendLine("✗ 拒绝写入：项目 $projectName 不存在")
                    onEvent(AgentEvent.FileWrite(fw.path, false))
                }
            }

            // 2) TOOL 调用
            val tools = AgentProtocol.parseTools(reply)
            var anyToolFailed = false
            if (tools.isEmpty() && fileWrites.isEmpty()) {
                // 无工具调用 → 若上一轮失败则强制继续，否则任务完成
                if (failedLastRound && forceContinueLeft > 0) {
                    forceContinueLeft--
                    failedLastRound = false
                    log.appendLine("※ 工具曾有失败，强制 AI 继续修复（剩余 $forceContinueLeft 次）")
                    onEvent(AgentEvent.Note("工具执行存在失败，要求 AI 继续分析并重试…"))
                    messages.add(ChatMessage("assistant", reply))
                    messages.add(ChatMessage("user", "上一轮中工具执行有失败。请不要直接结束：先分析失败原因，再调用工具重试或更换方案；如果确实无法完成，请给出明确失败说明与建议。"))
                    continue
                }
                val stripped = AgentProtocol.stripTags(reply)
                return AgentResult(true, log.toString(), filesChanged, iterations, stripped)
            }

            messages.add(ChatMessage("assistant", reply))
            val toolOut = StringBuilder()
            tools.forEach { tc ->
                if (stopped()) {
                    log.appendLine("→ 用户已停止，跳过剩余工具 ${tc.name}")
                    return@forEach
                }
                toolSeq++
                val projectFree = tc.name in setOf("create_project", "import_project", "list_projects", "scan_problems", "problems", "scan_todos", "memory_read", "memory_write", "memory_append", "list_skills", "list_templates", "list_plugins", "apk_info", "apk_unzip", "page_analyze")
                val targetProject = project ?: workspace.projects().firstOrNull()
                log.appendLine("→ 执行工具: ${tc.name} ${tc.args.toString().take(500)}")
                onEvent(AgentEvent.ToolStart(toolSeq, tc.name, tc.args.toString()))
                val needApprove = riskConfirm && SecurityPolicy.needsApproval(tc.name, tc.args.toString())
                var approved = true
                var result = ""
                if (needApprove) {
                    val reason = SecurityPolicy.highRiskReason(tc.name, tc.args.toString())
                    val gate = CompletableFuture<Boolean>()
                    approvalGates[toolSeq] = gate
                    log.appendLine("→ 高风险工具 ${tc.name} 等待用户审批：$reason")
                    onEvent(AgentEvent.Note("请求审批：${tc.name}（$reason）"))
                    runCatching { onApprovalRequest?.invoke(toolSeq, tc.name, tc.args.toString(), reason) }
                    approved = try { gate.get(60, TimeUnit.SECONDS) } catch (e: Throwable) { false }
                    approvalGates.remove(toolSeq)
                }
                if (!approved) {
                    result = "SKIPPED_BY_APPROVAL: 用户未批准执行 ${tc.name}。请改用低风险替代方案，或向用户说明所需权限后让用户重新触发。"
                    onEvent(AgentEvent.Note("已拒绝 ${tc.name}，AI 将改用安全方式继续。"))
                } else if (stopped()) {
                    result = "SKIPPED_BY_USER_STOP: 用户已停止任务，不再执行 ${tc.name}。"
                } else {
                    val isBuildTool = tc.name.lowercase() in setOf("build", "flutter_build", "gradle_build", "compile")
                    val progressSink: ((String) -> Unit)? = if (isBuildTool) { { line -> onEvent(AgentEvent.Log(line)) } } else null
                    result = when {
                        targetProject != null -> dispatcher.execute(targetProject, tc.name, tc.args, progressSink)
                        projectFree -> dispatcher.execute(PLACEHOLDER_PROJECT, tc.name, tc.args, progressSink)
                        else -> "ERROR: 未选择项目，请先使用 create_project 创建或 list_projects 查看并指定项目"
                    }
                    if (tc.name.lowercase() in setOf("build", "flutter_build", "gradle_build", "compile") &&
                        (result.startsWith("ERROR") || result.contains("BUILD FAILED") || result.contains("FAILURE") || result.contains("error:"))
                    ) {
                        lastBuildFailed = true
                    }
                    // 联网搜索结果交给轻量模型整理，减少噪音并保留要点
                    if (webLightRouter != null && tc.name.lowercase() in setOf("web_search", "search", "web_fetch", "fetch_url") &&
                        !result.startsWith("ERROR")
                    ) {
                        val tidy = try { webLightRouter?.invoke(result.take(7000)).orEmpty() } catch (e: Throwable) { "" }
                        if (tidy.isNotBlank()) result = "【联网结果 · 轻量模型整理】\n$tidy"
                    }
                }
                val ok = !result.startsWith("ERROR") && !result.startsWith("SKIPPED_BY_APPROVAL")
                // 构建失败：自动定位输出中的错误（文件:行号），注入下一轮上下文，引导 AI 优先读源码修复
                if (!ok && tc.name.lowercase() in setOf("build", "flutter_build", "gradle_build", "compile")) {
                    val errs = dispatcher.extractBuildErrors(result)
                    if (errs.isNotEmpty()) {
                        val g = StringBuilder("构建失败（先保留完整错误，后附定位摘要）。请按如下位置读取对应源码行并修复，依次给出修正，再重新构建：\n")
                        errs.forEach { e -> g.append("• ").append(e.file).append(":").append(e.line).append(" — ").append(e.message).append("\n") }
                        messages.add(ChatMessage("assistant", g.toString().trim()))
                        onEvent(AgentEvent.Note("构建失败：自动定位 ${errs.size} 处错误，将据此读取源码修复…"))
                        log.appendLine("※ 构建失败，自动定位 ${errs.size} 处错误")
                    }
                }
                // 写/删类工具成功执行计入文件改动（保证 write_file/patch_file 等路径也能驱动变更日志/复盘/审计）
                if (ok && tc.name.lowercase() in FILE_MUTATING_TOOLS) filesChanged++
                anyToolFailed = anyToolFailed || !ok
                log.appendLine(result.take(2000))
                onEvent(AgentEvent.ToolEnd(toolSeq, ok, result.take(4000)))
                toolOut.append("Tool ${tc.name} result:\n").append(result.take(4000)).append("\n")
            }
            failedLastRound = anyToolFailed
            if (stopped()) {
                return AgentResult(false, log.toString(), filesChanged, iterations, "任务已被用户停止。", interrupted = true)
            }
            if (toolOut.isNotBlank()) {
                messages.add(ChatMessage("user", toolOut.toString().trim()))
            }
            if (tools.isEmpty() && fileWrites.isNotEmpty()) {
                messages.add(ChatMessage("user", "文件已写入。请根据任务需要继续执行工具进行验证（例如 build），直到任务完成并总结。"))
            }
        }
        val finalText = "已达到最大迭代次数 $maxIterations，任务可能未完成。\n${AgentProtocol.stripTags(lastReply)}"
        onEvent(AgentEvent.FinalText(finalText))
        return AgentResult(false, log.toString(), filesChanged, iterations, finalText)
    }

    // ---------- 项目记忆注入 ----------
    private fun loadMemoryNote(project: Project?): String {
        if (project == null) return ""
        val f = java.io.File(java.io.File(project.path, ".studio"), "memory.json")
        if (!f.isFile) return ""
        val text = runCatching { f.readText().trim() }.getOrDefault("")
        if (text.isBlank()) return ""
        return text.take(8000)
    }

    // ---------- system prompt ----------
    private fun buildSystemPrompt(ctx: String, memoryNote: String, summary: String, repoNote: String = "", lessonsNote: String = "", revMemoryNote: String = "", reverseMode: Boolean = false): String {
        val memoryPart = if (memoryNote.isNotBlank()) "\n\n【项目长期记忆(.studio/memory.json，重要：每次开工前先读、新能力必须写入)】\n$memoryNote" else ""
        val lessonsPart = if (lessonsNote.isNotBlank()) "\n\n【项目经验库 lessons.md（历史任务沉淀的教训，遇到类似报错/问题先参考，避免重复踩坑；自己解决新问题后应 lessons_record 沉淀）】\n$lessonsNote" else ""
        val summaryPart = if (summary.isNotBlank()) "\n\n【之前对话的压缩摘要，务必衔接此前的目标与进展】\n$summary" else ""
        val repoPart = if (repoNote.isNotBlank()) "\n\n$repoNote" else ""
        val revPart = if (reverseMode) {
            val revMem = if (revMemoryNote.isNotBlank()) "\n\n【逆向记忆分区(.studio/reverse/memory.json，逆向结论专属，逆向推理时先读、有新结论必须写入)】\n$revMemoryNote" else ""
            "\n\n【AI 逆向工作台模式】本会话处于独立逆向模式，与常规开发隔离。分析目标时使用 apk_info / apk_unzip / page_analyze / frida_gen / proot_orchestrate 等逆向工具；逆向结论用 reverse_memory_write / reverse_memory_append 写入逆向记忆分区（不要污染普通项目记忆）；必要时可用 vision_analyze 识别图像，用 import_lib 导入外部库。逆向仅用于自有权/授权目标，分析过程遵守合规与法律边界。$revMem"
        } else ""
        return """You are GitHubK Studio coding agent, running inside a chat-style AI workspace.
Workspace:
$ctx
$memoryPart
$lessonsPart
$summaryPart
$repoPart
$revPart

You complete the user's task autonomously, end-to-end: creating a new project when needed, writing code, building it, and packaging results — without waiting for step-by-step confirmation unless something is genuinely ambiguous.

You can call tools by returning exactly:
<TOOL name="tool_name">{"path":"...","query":"...","content":"..."}</TOOL>
Available tools:
- Project lifecycle: create_project {"name":"...","type":"Android|Compose|Flutter|Web|React Native|Xposed|嵌入式 C|Linux 工具|空项目"}, list_projects, list_files, file_tree, read_file, write_file, search_code, delete_file
- Build & package: build (compiles the project; Android produces a debug APK, Flutter runs `flutter build apk --debug`), export_apk (copies the APK to Downloads), package_source (zips source excluding build/.gradle/.git/node_modules), export_file {"path":"absolute path"} (copy a file to Downloads)
- Terminal & auto-setup: run_command {"command":"..."} (any shell command inside the runtime), download_file {"url":"...","path":"dest"}, toolchain_status / toolchain_doctor / toolchain_install / toolchain_install_full (inspect & auto-install JDK/Gradle/Git/Android SDK/Dart/Flutter)
- Web & learning: web_search {"query":"..."}, web_fetch {"url":"..."}, install_packages {"packages":["pkg1"]} (apt/pkg install inside runtime)
- Git: git_status, git_diff, git_branch, git_commit, git_push, git_pull, git_fetch, git_log, git_branch_create, git_branch_merge, git_branch_delete, git_branch_delete_remote, git_push_force
- Skill workflows: list_skills, run_skill
- MCP: mcp_servers, mcp_list_tools, mcp_call_tool
- GitHub (repo = owner/repo): repo_info, repo_contents, repo_file, repo_readme, repo_branches, repo_star, repo_unstar, repo_fork, repo_issues, repo_commits, repo_create, repo_delete, repo_rename, repo_config, repo_branch_create, repo_branch_delete, repo_clone, repo_upload_zip, repo_context
- GitHub Release: release_list, release_create, release_edit, release_links, release_upload, release_delete
- GitHub Issue / PR: issue_create, issue_comment, pr_create, pr_list, pr_merge
- NOTE: 若系统提示已自动注入“GitHub 仓库上下文”，不要再重复调用 repo_info/readme/repo_context；直接基于注入信息工作。远端不可逆操作（repo_delete / 强制推送 / 删除分支 / 删 Release / repo_upload_zip）会触发用户审批，请先说明理由。
${EXTRA_TOOL_DOC}
You can write files by returning:
<FILE path="projectName/relative/path">full file content</FILE>

Rules:
- TASK DECOMPOSITION: For complex/multi-step requests, first output a short decomposition plan (plain text lines prefixed with "PLAN:"), then execute each step with tools one by one. Do NOT give up after one tool result; continue until every sub-task is done.
- NEVER STOP EARLY: If the result is not yet verified, call more tools (build/read_file/run_command) to verify and iterate. Only output your final summary AFTER the task is fully complete and verified.
- SELF-EXTENSION (自主扩展): You can grow your own capabilities. When a needed tool/function/dependency is missing or unknown: web_search to research, web_fetch official docs, then install_packages/download_file/run_command/write code to add it yourself, verify with run_command, and finally memory_append to record the newly added capability for future sessions. Never refuse a task simply because a tool is not yet installed.
- FORCED-TOOL: If the task clearly needs a capability listed in Available tools, you MUST call that tool rather than describing what you would do. Tool calls are the only way to actually change the system.
- ENV AUTO-SETUP: Before build/run, only auto-install components that the CURRENT project type actually needs. Android/Gradle/Java projects need only JDK/Gradle/Git/Android SDK. Flutter projects need Dart/Flutter too. Ignore missing optional tools irrelevant to the current project (e.g. Dart/Flutter MISSING is fine for an Android/Gradle project). Call `toolchain_install` for the needed missing pieces; do NOT call `installCompleteIdeEnvironment`/`toolchain_install_full` for optional components. If the needed toolchain is genuinely unavailable, report it clearly instead of hanging.
- If the user asks to build something and no suitable project exists yet, call create_project first, then write files into it (use the name returned by create_project in <FILE path>).
- projectName in <FILE path> MUST be an existing project from list_projects (or one you just created).
- Write complete files, never partial snippets. For big existing files, prefer patch_file/append_file for small precise edits.
- If build fails, read the errors, fix files, then build again — iterate until success or a genuine best effort. If a tool returns ERROR, analyze and retry with another approach.
- If the user's task implies an installable app (e.g. "build an APK", "打包apk"), after a successful build call export_apk so the APK lands in Downloads, and mention the path.
- If the user's task implies source delivery (e.g. "打包源码"), call package_source then export_file with the returned zip path.
- For new projects/capabilities, record what you did into project memory (memory_write/memory_append) so later conversations can continue smoothly.
- When the task is fully complete and verified, output a short summary WITHOUT any tool tags, including file paths you exported. If you attempted but genuinely cannot finish, clearly state what failed and why.""" + "\n"
    }
}
