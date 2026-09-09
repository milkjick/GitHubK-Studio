package com.example.myempty.githubk.ai

import com.example.myempty.githubk.buildsys.BuildManager
import com.example.myempty.githubk.core.Project
import com.example.myempty.githubk.core.WorkspaceManager
import com.example.myempty.githubk.git.GitHubApi
import com.example.myempty.githubk.git.GitManager
import com.example.myempty.githubk.terminal.BuiltinRuntime
import com.example.myempty.githubk.terminal.ToolchainManager
import org.json.JSONObject

/**
 * AgentToolDispatcher v2.7
 * 完整工具集：
 * - 工作区：list_projects / create_project / list_files / file_tree / read_file / write_file / search_code / delete_file
 * - 构建与打包：build / export_apk / package_source / export_file
 * - Git：git_status / git_diff / git_branch / git_commit / git_push
 * - 终端 / 下载：run_command / download_file
 * - 工具链：toolchain_status / toolchain_doctor / toolchain_install / toolchain_install_full
 * - Skill 工作流：list_skills / run_skill
 * - MCP：mcp_servers / mcp_list_tools / mcp_call_tool
 * - GitHub：repo_info / repo_contents / repo_file / repo_branches / repo_star / repo_unstar / repo_fork / repo_issues
 */
class AgentToolDispatcher(
    private val workspace: WorkspaceManager,
    private val buildManager: BuildManager,
    private val git: GitManager,
    private val skill: SkillManager,
    private val mcp: McpManager,
    private val gitHub: GitHubApi? = null,
    private val apkManager: com.example.myempty.githubk.apk.ApkManager? = null,
    private val runtime: BuiltinRuntime? = null,
    private val toolchain: ToolchainManager? = null
) {

    fun execute(project: Project, tool: String, args: JSONObject = JSONObject()): String = try {
        when (tool.lowercase()) {
            // ---------- 工作区 ----------
            "list_projects" -> workspace.projects().joinToString("\n") { "${it.name} [${it.type}]" }
            "create_project" -> {
                val name = args.optString("name").ifBlank { "NewProject" }
                val type = args.optString("type", "Android").let {
                    when (it.lowercase()) {
                        "flutter" -> "Flutter"
                        "android" -> "Android"
                        "empty", "空项目", "other" -> "空项目"
                        else -> "Android"
                    }
                }
                val p = workspace.createProject(name, type)
                "OK created project ${p.name} [${p.type}] at ${p.path}. Use list_projects/file_tree to inspect it before editing."
            }
            "package_source" -> {
                val zip = workspace.packageSource(project)
                "OK packaged source: ${zip.absolutePath} (${zip.length() / 1024} KB). Call export_file with {\"path\":\"${zip.absolutePath}\"} to save it to Downloads."
            }
            "export_apk" -> {
                val apk = workspace.findLatestApk(project)
                if (apk == null) "ERROR: 未找到 APK，请先调用 build 完成构建"
                else {
                    val dest = apkManager?.export(apk)
                    if (dest != null) "OK exported APK to ${dest.absolutePath} (${apk.length() / 1024} KB)"
                    else "ERROR: 导出失败（apkManager 不可用）"
                }
            }
            "export_file" -> {
                val path = args.optString("path")
                val f = java.io.File(path)
                if (!f.isFile) "ERROR: 文件不存在: $path"
                else {
                    val dest = apkManager?.export(f)
                    if (dest != null) "OK exported ${f.name} to ${dest.absolutePath} (${f.length() / 1024} KB)"
                    else "ERROR: 导出失败（apkManager 不可用）"
                }
            }
            "list_files" -> workspace.agentContext(project)
            "file_tree" -> workspace.fileTree(project)
            "read_file" -> {
                val rel = args.optString("path")
                workspace.readSafe(project, rel) ?: "ERROR: file not found or outside project"
            }
            "write_file" -> {
                val rel = args.optString("path")
                val content = args.optString("content")
                if (workspace.writeSafe(project, rel, content)) "OK wrote $rel (${content.length} chars)"
                else "ERROR: write rejected (outside project or empty path)"
            }
            "search_code" -> workspace.searchCode(project, args.optString("query"))
            "delete_file" -> {
                val rel = args.optString("path")
                if (workspace.deleteSafe(project, rel)) "OK deleted $rel" else "ERROR: delete rejected"
            }
            "build" -> buildManager.buildDetailed(project.path, project.type).render()
            "git_status" -> git.status(project.path).joinToString("\n") { (p, s) -> "$s $p" }
                .ifBlank { "clean working tree" }
            "git_diff" -> git.diff(project.path)
            "git_branch" -> git.branch(project.path).output
            "git_commit" -> {
                val msg = args.optString("message").ifBlank { "Update via GitHubK Agent" }
                git.commitAll(project.path, msg).output
            }
            "git_push" -> git.push(project.path).output

            // ---------- Skill 工作流 ----------
            "list_skills" -> skill.list().joinToString("\n") { "${it.id} — ${it.name}: ${it.description}" }
            "run_skill" -> {
                val id = args.optString("skill")
                if (id.isBlank()) "ERROR: skill 参数不能为空"
                else skill.execute(id, project)
            }

            // ---------- MCP ----------
            "mcp_servers" -> mcp.allEnabledServers().joinToString("\n") { "${it.name} — ${it.url}" }
                .ifBlank { "(未配置 MCP Server，可在设置中添加)" }
            "mcp_list_tools" -> {
                val serverName = args.optString("server")
                if (serverName.isBlank()) "ERROR: server 参数不能为空"
                else {
                    val s = mcp.allEnabledServers().firstOrNull { it.name == serverName }
                    if (s == null) "ERROR: MCP server 不存在: $serverName"
                    else mcp.listTools(s).joinToString("\n") { "${it.name} — ${it.description}" }
                }
            }
            "mcp_call_tool" -> {
                val serverName = args.optString("server")
                val toolName = args.optString("tool")
                val s = mcp.allEnabledServers().firstOrNull { it.name == serverName }
                if (s == null) "ERROR: MCP server 不存在: $serverName"
                else {
                    val toolArgs = args.optJSONObject("arguments") ?: JSONObject()
                    mcp.callTool(s, toolName, toolArgs)
                }
            }

            // ---------- 终端 / 下载 ----------
            "run_command", "shell", "terminal", "exec" -> {
                val cmd = args.optString("command")
                if (cmd.isBlank()) "ERROR: command 参数不能为空"
                else if (runtime == null) "ERROR: 终端运行时不可用"
                else {
                    // 构建环境变量导出前缀
                    val envExports = buildString {
                        toolchain?.environment()?.forEach { (k, v) ->
                            append("export $k='${v.replace("'", "'\\\\''")}'; ")
                        }
                    }
                    val fullCmd = if (envExports.isNotEmpty()) "$envExports $cmd" else cmd
                    val result = runtime.run(fullCmd)
                    if (result.isBlank()) "(命令无输出)" else result
                }
            }
            "download_file", "download" -> {
                val url = args.optString("url")
                val dest = args.optString("path")
                if (url.isBlank() || dest.isBlank()) "ERROR: url 和 path 参数不能为空"
                else {
                    try {
                        val destFile = java.io.File(dest)
                        destFile.parentFile?.mkdirs()
                        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 30000
                        conn.readTimeout = 30000
                        conn.inputStream.use { input ->
                            java.io.FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        "OK downloaded $url -> ${destFile.absolutePath} (${destFile.length()} bytes)"
                    } catch (e: Exception) {
                        "ERROR: 下载失败 ${e.message}"
                    }
                }
            }
            "toolchain_status", "env_status" -> {
                if (toolchain == null) "ERROR: 工具链管理器不可用"
                else toolchain.doctor()
            }
            "toolchain_doctor" -> {
                if (toolchain == null) "ERROR: 工具链管理器不可用"
                else toolchain.doctor()
            }
            "toolchain_install" -> {
                if (toolchain == null) "ERROR: 工具链管理器不可用"
                else {
                    val result = StringBuilder()
                    val ok = toolchain.ensureIdeToolchain { result.append(it) }
                    if (ok) "OK 工具链安装完成\n$result" else "工具链安装失败\n$result"
                }
            }
            "toolchain_install_full" -> {
                if (toolchain == null) "ERROR: 工具链管理器不可用"
                else {
                    val result = StringBuilder()
                    val ok = toolchain.installCompleteIdeEnvironment { result.append(it) }
                    if (ok) "OK 完整环境安装完成\n$result" else "完整环境安装失败\n$result"
                }
            }

            // ---------- 联网搜索 / 网页抓取 / 依赖安装 ----------
            "web_search", "search" -> {
                val q = args.optString("query").ifBlank { args.optString("q") }
                if (q.isBlank()) "ERROR: query 参数不能为空"
                else webSearch(q)
            }
            "web_fetch", "fetch_url" -> {
                val url = args.optString("url")
                if (url.isBlank()) "ERROR: url 参数不能为空"
                else try {
                    val text = httpGet(url, 25000, 40000)
                    if (text.isBlank()) "(页面无内容)" else text.take(20000)
                } catch (e: Exception) {
                    "ERROR: 抓取失败 ${e.message}"
                }
            }
            "install_packages", "pkg_install" -> {
                if (runtime == null) "ERROR: 终端运行时不可用"
                else {
                    val pkgs = mutableListOf<String>()
                    args.optJSONArray("packages")?.let { arr ->
                        for (i in 0 until arr.length()) pkgs.add(arr.optString(i))
                    }
                    args.optString("package").split(',', ' ').map { it.trim() }
                        .filter { it.isNotBlank() }.forEach { pkgs.add(it) }
                    if (pkgs.isEmpty()) "ERROR: 请提供 packages 数组（如 {\"packages\":[\"python\",\"nodejs\"]}）"
                    else {
                        val log = StringBuilder()
                        val ok = runtime.installPackages(pkgs) { log.append(it) }
                        if (ok) "OK 已安装依赖 ${pkgs.joinToString(",")}\n$log"
                        else "依赖安装失败 ${pkgs.joinToString(",")}\n$log"
                    }
                }
            }

            // ---------- GitHub ----------
            "repo_info" -> gitHub?.repoInfo(args.optString("repo"))?.let {
                "${it.fullName}\n★ ${it.stars} 🍴 ${it.forks}\n${it.description}\nbranch=${it.defaultBranch}\n${it.htmlUrl}"
            } ?: "ERROR: gitHub 不可用或仓库不存在"
            "repo_contents" -> {
                val repo = args.optString("repo")
                val path = args.optString("path", "")
                gitHub?.contents(repo, path)?.joinToString("\n") {
                    (if (it.type == "dir") "[D] " else "[F] ") + it.path + (if (it.type == "file") " (${it.size}B)" else "")
                } ?: "ERROR: gitHub 不可用"
            }
            "repo_file" -> {
                val repo = args.optString("repo")
                val path = args.optString("path")
                gitHub?.fileContent(repo, path)?.take(8000) ?: "ERROR: gitHub 不可用"
            }
            "repo_branches" -> gitHub?.branches(args.optString("repo"))?.joinToString("\n") { it.name }
                ?: "ERROR: gitHub 不可用"
            "repo_star" -> {
                val repo = args.optString("repo")
                if (gitHub?.star(repo) == true) "OK starred $repo" else "ERROR: star 失败"
            }
            "repo_unstar" -> {
                val repo = args.optString("repo")
                if (gitHub?.unstar(repo) == true) "OK unstarred $repo" else "ERROR: unstar 失败"
            }
            "repo_fork" -> {
                val repo = args.optString("repo")
                "Fork 结果: ${gitHub?.fork(repo) ?: "ERROR"}"
            }
            "repo_issues" -> {
                val repo = args.optString("repo")
                gitHub?.issues(repo)?.joinToString("\n") { "#${it.number} [${it.state}] ${it.title} (@${it.user})" }
                    ?.ifBlank { "(无 issue)" } ?: "ERROR: gitHub 不可用"
            }

            else -> "ERROR: unknown tool $tool"
        }
    } catch (e: Throwable) {
        "ERROR: ${e.message ?: "tool execution failed"}"
    }

    /** 纯 Java HTTP GET，返回文本（用于联网搜索/抓取网页）。 */
    private fun httpGet(url: String, timeoutMs: Int = 25000, maxBytes: Int = 60000): String {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 GitHubK-Studio-Agent")
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        val code = conn.responseCode
        if (code !in 200..399) return "HTTP $code"
        return conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8).take(maxBytes) }
    }

    /** DuckDuckGo HTML 搜索，返回前若干条结果（标题 + URL + 摘要）。 */
    private fun webSearch(query: String): String {
        val sb = StringBuilder()
        try {
            val url = "https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(query, "UTF-8")
            val html = httpGet(url, 25000, 300000)
            val titleRe = Regex("<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
            val snipRe = Regex("<a[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
            val titles = titleRe.findAll(html).toList()
            val snips = snipRe.findAll(html).toList()
            if (titles.isEmpty()) {
                sb.appendLine("(未解析到结构化结果)")
                sb.appendLine(stripHtml(html).take(3000))
            } else {
                titles.take(8).forEachIndexed { i, m ->
                    var link = m.groupValues[1].trim()
                    if (link.startsWith("//")) link = "https:" + link
                    val t = stripHtml(m.groupValues[2]).trim().replace(Regex("\\s+"), " ")
                    sb.appendLine("${i + 1}. $t")
                    sb.appendLine("   URL: $link")
                    if (i < snips.size) {
                        val sn = stripHtml(snips[i].groupValues[1]).trim().replace(Regex("\\s+"), " ")
                        if (sn.isNotBlank()) sb.appendLine("   ${sn.take(400)}")
                    }
                }
            }
        } catch (e: Exception) {
            sb.appendLine("搜索失败: ${e.message}")
            sb.appendLine("提示：可改用 run_command 执行 curl 抓取，或 web_fetch 访问具体 URL。")
        }
        return sb.toString().trim()
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#x27;", "'").replace("&nbsp;", " ")
            .replace("&#39;", "'")

    fun runProtocol(project: Project, text: String): String {
        val out = StringBuilder()
        AgentProtocol.parseTools(text).forEach { tc ->
            out.append("[TOOL] ").append(tc.name).append('\n')
            out.append(execute(project, tc.name, tc.args)).append("\n\n")
        }
        return out.toString().trim()
    }

    fun applyToolText(project: Project, text: String): Int = workspace.applyAiChanges(text)
}