package com.example.myempty.githubk.ai

import com.example.myempty.githubk.core.Project
import com.example.myempty.githubk.core.WorkspaceManager

/**
 * AgentRuntime v2.5
 *
 * P0 修复：真正的多轮 Agent Loop。
 * 2.4 只有 AI → TOOL → AI 一轮；现在循环执行：
 *   AI → 是否有 TOOL？ → 有：Dispatcher 执行 → 结果追加历史 → 继续 AI
 *                        → 无：任务完成
 * 同时支持 <FILE> 安全写入。
 * maxIterations = 20。
 */
data class AgentResult(
    val success: Boolean,
    val log: String,
    val filesChanged: Int,
    val iterations: Int,
    val finalMessage: String
)

class AgentRuntime(
    private val ai: AiManager,
    private val workspace: WorkspaceManager,
    private val dispatcher: AgentToolDispatcher,
    private val maxIterations: Int = 20
) {

    companion object {
        /** create_project / list_projects 不需要真实项目上下文时使用的占位对象。 */
        private val PLACEHOLDER_PROJECT by lazy {
            Project("__none__", "Other", java.io.File("/dev/null"))
        }
    }

    fun run(task: String, project: Project?, onLog: (String) -> Unit = {}): AgentResult {
        val ctx = workspace.agentContext(project)
        val system = """You are GitHubK Studio coding agent.
Workspace:
$ctx

You complete the user's task autonomously, end-to-end: creating a new project when needed, writing code, building it, and packaging results for the user — without waiting for step-by-step confirmation unless something is genuinely ambiguous.

You can call tools by returning exactly:
<TOOL name="tool_name">{"path":"...","query":"...","content":"..."}</TOOL>
Available tools:
- Project lifecycle: create_project {"name":"...","type":"Android|Flutter|空项目"}, list_projects, list_files, file_tree, read_file, write_file, search_code, delete_file
- Build & package: build (compiles the project; for Android produces a debug APK, for Flutter runs `flutter build apk --debug`), export_apk (copies the most recently built APK to Downloads), package_source (zips the project's source, excluding build/.gradle/.git/node_modules), export_file {"path":"absolute path"} (copies any file, e.g. the zip from package_source, to Downloads)
- Terminal & auto-setup: run_command {"command":"..."} (run any shell command inside the runtime), download_file {"url":"...","path":"dest"} (download a file to dest), toolchain_status / toolchain_doctor / toolchain_install / toolchain_install_full (inspect and auto-install JDK/Gradle/Git/Android SDK/Dart/Flutter)
- Web & learning: web_search {"query":"..."} (search the web for docs/solutions/unknown skills), web_fetch {"url":"..."} (read a webpage as text), install_packages {"packages":["pkg1","pkg2"]} (apt/pkg install missing tools & dependencies inside runtime)
- Git: git_status, git_diff, git_branch, git_commit, git_push
- Skill workflows: list_skills, run_skill
- MCP: mcp_servers, mcp_list_tools, mcp_call_tool
- GitHub: repo_info, repo_contents, repo_file, repo_branches, repo_star, repo_unstar, repo_fork, repo_issues

You can write files by returning:
<FILE path="projectName/relative/path">full file content</FILE>

Rules:
- If the user asks you to build something and no suitable project exists yet, call create_project first, then write files into it (projectName in <FILE path> must then be the name returned by create_project).
- projectName in <FILE path> MUST be an existing project from list_projects (or one you just created).
- Write complete files, never partial snippets.
- Prefer small, buildable changes. After editing, call build to verify.
- If build fails, read the errors, fix files, then build again — iterate until it succeeds or you've made a genuine best effort.
- If the user's task implies they want an installable app (e.g. "build an APK", "打包apk"), after a successful build call export_apk so the file lands in Downloads, and mention the path in your summary.
- If the user's task implies they want the source code (e.g. "打包源码", "give me the source", "zip the project"), call package_source then export_file with the returned zip path, and mention the path in your summary.
- For skill workflows use list_skills then run_skill with {"skill":"skill_id"}.
- For MCP tools use mcp_servers to list configured servers, mcp_list_tools to see tools, mcp_call_tool with {"server":"name","tool":"tool_name","arguments":{...}}.
- If the task needs a dependency, tool, or skill you don't have installed or don't know yet: DO NOT stop. Use web_search to research the solution, web_fetch to read official docs, then install_packages / download_file / run_command / toolchain_install to automatically download, install, and configure the missing piece. Verify with run_command (e.g. check the tool's --version) before finishing.
- When the task is fully complete and verified, output a short summary WITHOUT any tool tags, including any file paths you exported."""

        val messages = mutableListOf<ChatMessage>()
        messages.add(ChatMessage("user", task))
        val log = StringBuilder()
        var filesChanged = 0
        var iterations = 0
        var lastReply = ""

        while (iterations < maxIterations) {
            iterations++
            val reply = try {
                ai.chat(messages, system)
            } catch (e: Throwable) {
                log.appendLine("AI 请求失败: ${e.message}")
                return AgentResult(false, log.toString(), filesChanged, iterations, e.message ?: "AI error")
            }
            lastReply = reply
            log.appendLine("── 第 $iterations 轮 AI ──")
            log.appendLine(reply.take(4000))
            onLog(reply)

            // 1) 处理 FILE 写入（安全，projectName 必须来自 workspace.projects()）
            val fileWrites = AgentProtocol.parseFiles(reply)
            fileWrites.forEach { fw ->
                val projectName = fw.path.replace('\\', '/').trim().removePrefix("/").substringBefore('/')
                val p = workspace.project(projectName)
                if (p != null) {
                    val rel = fw.path.replace('\\', '/').trim().removePrefix("/").substringAfter('/', "")
                    if (workspace.writeSafe(p, rel, fw.content)) {
                        filesChanged++
                        log.appendLine("✓ 写入 ${p.name}/$rel")
                    } else {
                        log.appendLine("✗ 拒绝写入 ${p.name}/$rel (路径非法)")
                    }
                } else {
                    log.appendLine("✗ 拒绝写入：项目 $projectName 不存在")
                }
            }

            // 2) 处理 TOOL 调用
            val tools = AgentProtocol.parseTools(reply)
            if (tools.isEmpty() && fileWrites.isEmpty()) {
                // 没有工具调用 → 任务结束
                return AgentResult(true, log.toString(), filesChanged, iterations, AgentProtocol.stripTags(reply))
            }

            messages.add(ChatMessage("assistant", reply))
            var toolOut = StringBuilder()
            tools.forEach { tc ->
                log.appendLine("→ 执行工具: ${tc.name} ${tc.args}")
                // create_project / list_projects 不依赖已选中的项目，允许在空工作区下调用
                val projectFree = tc.name in setOf("create_project", "list_projects")
                val targetProject = project ?: workspace.projects().firstOrNull()
                val result = when {
                    targetProject != null -> dispatcher.execute(targetProject, tc.name, tc.args)
                    projectFree -> dispatcher.execute(PLACEHOLDER_PROJECT, tc.name, tc.args)
                    else -> "ERROR: 未选择项目，请先使用 create_project 创建或 list_projects 查看并指定项目"
                }
                log.appendLine(result.take(2000))
                toolOut.append("Tool ${tc.name} result:\n").append(result.take(4000)).append("\n")
            }
            if (toolOut.isNotBlank()) {
                messages.add(ChatMessage("user", toolOut.toString().trim()))
            }
            if (tools.isEmpty()) {
                // 只有文件写入没有工具调用：让 AI 确认下一步（或直接继续，防止死循环）
                messages.add(ChatMessage("user", "文件已写入。如果需要验证，请调用 build；否则请总结完成情况。"))
            }
        }
        return AgentResult(false, log.toString(), filesChanged, iterations,
            "达到最大迭代次数 $maxIterations，任务可能未完成。\n$lastReply")
    }
}