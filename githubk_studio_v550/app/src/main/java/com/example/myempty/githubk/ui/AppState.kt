package com.example.myempty.githubk.ui

import android.content.Context
import com.example.myempty.githubk.ai.AiManager
import com.example.myempty.githubk.ai.AgentRuntime
import com.example.myempty.githubk.ai.AgentToolDispatcher
import com.example.myempty.githubk.ai.McpManager
import com.example.myempty.githubk.ai.SkillManager
import com.example.myempty.githubk.apk.ApkManager
import com.example.myempty.githubk.buildsys.BuildEngine
import com.example.myempty.githubk.buildsys.BuildHistory
import com.example.myempty.githubk.core.SecureStore
import com.example.myempty.githubk.core.WorkspaceManager
import com.example.myempty.githubk.editor.EditorManager
import com.example.myempty.githubk.git.GitHubApi
import com.example.myempty.githubk.git.GitManager
import com.example.myempty.githubk.terminal.BuiltinRuntime
import com.example.myempty.githubk.terminal.TerminalManager
import com.example.myempty.githubk.terminal.ToolchainManager
import java.io.File

/**
 * AppState v2.6：聚合应用内所有核心服务，供各页面共享。
 */
class AppState(context: Context, val onLog: (String) -> Unit = {}) {

    val ctx: Context = context.applicationContext
    val secure: SecureStore = SecureStore(ctx)

    val workspace: WorkspaceManager = WorkspaceManager(ctx)
    val gitHub: GitHubApi = GitHubApi { secure.get("github_token") }
    val git: GitManager = GitManager(onLog) { secure.get("github_token") }
    val buildEngine: BuildEngine = BuildEngine(onLog).also { it.attachContext(ctx) }
    val buildHistory: BuildHistory = BuildHistory(File(ctx.filesDir, "build_history.json"))
    val buildManager: com.example.myempty.githubk.buildsys.BuildManager = com.example.myempty.githubk.buildsys.BuildManager(buildEngine, buildHistory, onLog)
    val ai: AiManager = AiManager(ctx)
    val terminal: TerminalManager = TerminalManager(onLog).also { it.attachContext(ctx) }
    val apk: ApkManager = ApkManager(ctx)
    val editor: EditorManager = EditorManager()
    val skill: SkillManager = SkillManager(workspace, buildManager, git)
    val mcp: McpManager = McpManager(secure)
    /** 内置 POSIX 运行时（应用私有目录，无需 Termux）。 */
    val runtime: BuiltinRuntime = BuiltinRuntime(ctx)
    /** 统一 IDE 工具链中心：终端、Agent、BuildEngine 共用。 */
    val toolchain: ToolchainManager = ToolchainManager(ctx)

    init {
        buildEngine.attachToolchain(toolchain)
    }

    lateinit var dispatcher: AgentToolDispatcher
    lateinit var agent: AgentRuntime

    init {
        dispatcher = AgentToolDispatcher(workspace, buildManager, git, skill, mcp, gitHub, apk, runtime, toolchain, ctx) { question, imagePath ->
            runCatching { java.io.File(imagePath).readBytes() }
                .map { bytes -> android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP) }
                .map { b64 -> "data:image/jpeg;base64,$b64" }
                .map { dataUrl -> ai.chatWithImage(question, dataUrl) }
                .getOrElse { "图像读取失败：${it.message ?: "未知错误"}" }
        }
        agent = AgentRuntime(ai, workspace, dispatcher)
    }

    fun hasToken(): Boolean = !secure.get("github_token").isNullOrBlank()

    fun sdkHome(): String = secure.get("sdk_home") ?: ""
    fun javaHome(): String = secure.get("java_home") ?: ""
    fun gradleHome(): String = secure.get("gradle_home") ?: ""
    fun flutterBin(): String = secure.get("flutter_bin") ?: ""

    // ---------- 主题 ----------

    fun loadTheme() {
        ThemeManager.apply(secure.get("theme") ?: "dark")
    }

    fun saveTheme(id: String) {
        ThemeManager.apply(id)
        secure.put("theme", id)
    }
}