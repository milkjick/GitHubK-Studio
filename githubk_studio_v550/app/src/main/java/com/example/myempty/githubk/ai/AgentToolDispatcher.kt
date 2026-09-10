package com.example.myempty.githubk.ai

import android.content.Context
import com.example.myempty.githubk.buildsys.BuildManager
import com.example.myempty.githubk.core.AuditLog
import com.example.myempty.githubk.core.ExportLogStore
import com.example.myempty.githubk.core.Project
import com.example.myempty.githubk.core.WorkspaceManager
import com.example.myempty.githubk.git.GitHubApi
import com.example.myempty.githubk.git.GitManager
import com.example.myempty.githubk.localai.InferTask
import com.example.myempty.githubk.localai.InferenceCatalog
import com.example.myempty.githubk.localai.InferenceComponent
import com.example.myempty.githubk.localai.LocalInferenceManager
import com.example.myempty.githubk.localai.LocalVisionEngine
import com.example.myempty.githubk.terminal.BuiltinRuntime
import com.example.myempty.githubk.terminal.ShizukuShell
import com.example.myempty.githubk.terminal.ToolchainManager
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * AgentToolDispatcher v3.0
 * 完整工具集：
 * - 工作区：list_projects / create_project / list_files / file_tree / read_file / write_file / search_code / delete_file
 * - 构建与打包：build / export_apk / package_source / export_file（导出自动记录到 ExportLogStore）
 * - Git：git_status / git_diff / git_branch / git_commit / git_push / git_pull / git_fetch / git_log /
 *        git_branch_create / git_branch_merge / git_branch_delete / git_branch_delete_remote / git_push_force
 * - 终端 / 下载：run_command / download_file
 * - 工具链：toolchain_status / toolchain_doctor / toolchain_install / toolchain_install_full
 * - Skill 工作流：list_skills / run_skill
 * - MCP：mcp_servers / mcp_list_tools / mcp_call_tool
 * - GitHub 仓库：repo_info / repo_contents / repo_file / repo_readme / repo_branches / repo_star / repo_unstar /
 *        repo_fork / repo_issues / repo_commits / repo_create / repo_delete / repo_rename / repo_config /
 *        repo_branch_create / repo_branch_delete / repo_clone / repo_upload_zip / repo_context
 * - GitHub Release：release_list / release_create / release_edit / release_links / release_upload / release_delete
 * - Issue / PR：issue_create / issue_comment / pr_create / pr_list / pr_merge
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
    private val toolchain: ToolchainManager? = null,
    private val context: Context? = null,
    private val vision: ((question: String, imagePath: String) -> String)? = null
) {

    /** v6.0：安装写/删追踪器，记录审计日志（每次任务写/删文件都落盘）。 */
    init {
        workspace.writeTracker = { p, rel -> context?.let { AuditLog.add(it, p.name, "write", rel, true) } }
        workspace.deleteTracker = { p, rel -> context?.let { AuditLog.add(it, p.name, "delete", rel, true) } }
    }

    /** 请求中断当前进行中的长命令（build 等），使「停止」即时生效。 */
    fun requestAbort() {
        runCatching { buildManager.requestCancel() }
    }

    /** 从构建输出中提取编译错误的位置（文件:行号: 描述），用于自动定位源码修复。 */
    data class BuildError(val file: String, val line: Int, val message: String)

    fun extractBuildErrors(output: String?): List<BuildError> {
        val out = output ?: return emptyList()
        val result = mutableListOf<BuildError>()
        // 兼容 gradle / javac / kotlinc / flutter 等常见错误行格式
        //   /path/Foo.kt:12:15: error: xxx
        //   e: file:///path/Foo.kt:12:15: xxx
        //   /path/Foo.java:12: error: xxx
        val re = Regex("""(?:^|\s)(?:e:|error:|warning:)?\s*(?:file://)?([^\s:]+\.\w{1,6}):(\d+):(?:\d+:)?\s*(.*)""")
        for (m in re.findAll(out)) {
            val file = m.groupValues[1].trim()
            val line = m.groupValues[2].toIntOrNull() ?: continue
            val msg = m.groupValues[3].trim()
            if (msg.isBlank()) continue
            result.add(BuildError(file, line, msg.take(280)))
            if (result.size >= 12) break
        }
        return result
    }

    fun execute(project: Project, tool: String, args: JSONObject = JSONObject(), onProgress: ((String) -> Unit)? = null): String = try {
        context?.let { AuditLog.add(it, project.name, "tool", "$tool ${args.toString().take(160)}", true) }
        when (tool.lowercase()) {
            // ---------- 工作区 ----------
            "list_projects" -> workspace.projects().joinToString("\n") { "${it.name} [${it.type}]" }
            "create_project" -> {
                val name = args.optString("name").ifBlank { "NewProject" }
                val type = args.optString("type", "Android").let { raw ->
                    when (raw.lowercase().replace(" ", "").replace("-", "")) {
                        "flutter" -> "Flutter"
                        "android" -> "Android"
                        "compose", "jetpackcompose", "kotlincompose", "androidcompose" -> "Compose"
                        "web", "html", "staticweb", "website", "frontend", "vanillajs" -> "Web"
                        "reactnative", "react", "rn", "reactnativeapp", "reactnativeproject" -> "React Native"
                        "xposed", "xposedmodule", "xposed模块", "lsposed", "lsposedmodule" -> "Xposed"
                        "empty", "空项目", "other" -> "空项目"
                        else -> raw
                    }
                }
                val p = workspace.createProject(name, type)
                "OK created project ${p.name} [${p.type}] at ${p.path}. Use list_projects/file_tree to inspect it before editing."
            }
            "import_project" -> {
                val source = args.optString("source", "repo")
                if (source == "repo") {
                    val url = args.optString("url")
                    if (url.isBlank()) "ERROR: source=repo 需要 url 参数（git 仓库地址，如 https://github.com/user/repo.git）"
                    else {
                        val name = args.optString("name").ifBlank { url.trimEnd('/').substringAfterLast('/').removeSuffix(".git").ifBlank { "imported_repo" } }
                        val dest = java.io.File(workspace.rootDir(), name)
                        if (dest.exists()) "ERROR: 工作区已存在同名项目/目录 $name"
                        else {
                            val branch = args.optString("branch").ifBlank { null }
                            val r = git.clone(url, dest, branch)
                            if (r.ok) "OK 已导入项目到工作区：$name [repo]\n用 list_projects 确认，再用 file_tree/source_files 浏览。"
                            else "ERROR: 克隆失败\n${r.output.take(1500)}"
                        }
                    }
                } else if (source == "zip") {
                    val zipPath = args.optString("zip")
                    val f = java.io.File(zipPath)
                    if (!f.isFile) "ERROR: zip 文件不存在 $zipPath"
                    else {
                        val name = args.optString("name").ifBlank { f.nameWithoutExtension.ifBlank { "imported_zip" } }
                        if (workspace.importZip(f, name)) "OK 已导入 ZIP 项目到工作区：$name\n用 list_projects 确认，再用 file_tree/source_files 浏览。"
                        else "ERROR: ZIP 解压失败或内容无效（需包含项目文件）"
                    }
                } else "ERROR: source 仅支持 repo/zip"
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
                    if (dest != null) {
                        context?.let { ExportLogStore.add(it, apk.name, dest.absolutePath, apk.length(), "apk") }
                        "OK exported APK to ${dest.absolutePath} (${apk.length() / 1024} KB)"
                    }
                    else "ERROR: 导出失败（apkManager 不可用）"
                }
            }
            "export_file" -> {
                val path = args.optString("path")
                val f = java.io.File(path)
                if (!f.isFile) "ERROR: 文件不存在: $path"
                else {
                    val dest = apkManager?.export(f)
                    if (dest != null) {
                        context?.let { ExportLogStore.add(it, f.name, dest.absolutePath, f.length(), "file") }
                        "OK exported ${f.name} to ${dest.absolutePath} (${f.length() / 1024} KB)"
                    }
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
            "search_code" -> workspace.searchCode(
                project,
                args.optString("query"),
                file = args.optString("file", args.optString("path", "")),
                context = args.optInt("context", 0).coerceIn(0, 5),
                ignoreCase = args.optBoolean("ignore_case", true)
            )
            "delete_file" -> {
                val rel = args.optString("path")
                if (workspace.deleteSafe(project, rel)) "OK deleted $rel" else "ERROR: delete rejected"
            }
            "build" -> buildManager.buildDetailed(project.path, project.type) { line -> onProgress?.invoke(line) }.render()
            "git_status" -> git.status(project.path).joinToString("\n") { (p, s) -> "$s $p" }
                .ifBlank { "clean working tree" }
            "git_diff" -> git.diff(project.path)
            "git_branch" -> git.branch(project.path).output
            "git_commit" -> {
                val msg = args.optString("message").ifBlank { "Update via GitHubK Agent" }
                git.commitAll(project.path, msg).output
            }
            "git_push" -> git.push(project.path).output
            // v5.1 仓库全量管理扩展
            "git_pull" -> {
                val r = git.pull(project.path)
                if (r.ok) "OK 已拉取远端更新到本地${if (r.output.isNotBlank()) "\n" + r.output.take(1200) else ""}"
                else "ERROR: git pull 失败\n${r.output.take(1200)}"
            }
            "git_fetch" -> {
                val r = git.fetchAll(project.path)
                if (r.ok) "OK 已同步远端全部分支/标签" else "ERROR: git fetch 失败\n${r.output.take(1200)}"
            }
            "git_log" -> {
                val n = args.optInt("count", 15).coerceIn(1, 100)
                val r = git.log(project.path, n)
                if (r.ok && r.output.isNotBlank()) r.output else "ERROR: git log 失败或无提交记录"
            }
            "git_branch_create" -> {
                val branch = args.optString("branch").ifBlank { args.optString("name") }
                if (branch.isBlank()) "ERROR: branch 不能为空"
                else {
                    val from = args.optString("from").ifBlank { null }
                    val r = git.createBranch(project.path, branch, from)
                    if (r.ok) "OK 已创建并切换到分支 $branch${if (from != null) "（基于 $from）" else ""}"
                    else "ERROR: 创建分支失败\n${r.output.take(1200)}"
                }
            }
            "git_branch_merge" -> {
                val branch = args.optString("branch")
                if (branch.isBlank()) "ERROR: branch 不能为空"
                else {
                    val r = git.mergeBranch(project.path, branch)
                    if (r.ok) "OK 已把 $branch 合并到当前分支 ${git.currentBranch(project.path)}"
                    else "ERROR: 合并冲突或失败\n${r.output.take(1500)}（解决冲突后可重新 git_commit）"
                }
            }
            "git_branch_delete" -> {
                val branch = args.optString("branch")
                if (branch.isBlank()) "ERROR: branch 不能为空"
                else {
                    val cur = git.currentBranch(project.path)
                    if (cur == branch) "ERROR: 不能删除当前所在分支 $branch，请先切换（git_branch_create 或 git checkout）"
                    else {
                        val r = git.deleteBranchLocal(project.path, branch)
                        if (r.ok) "OK 已删除本地分支 $branch" else "ERROR: 删除分支失败\n${r.output.take(1200)}"
                    }
                }
            }
            "git_branch_delete_remote", "git_delete_remote_branch" -> {
                val branch = args.optString("branch")
                if (branch.isBlank()) "ERROR: branch 不能为空"
                else {
                    val r = git.deleteRemoteBranch(project.path, branch)
                    if (r.ok) "OK 已删除远程分支 $branch（本地未删除，可再用 git_branch_delete）"
                    else "ERROR: 删除远程分支失败\n${r.output.take(1200)}"
                }
            }
            "git_push_force", "git_force_push" -> {
                val r = git.pushForce(project.path)
                if (r.ok) "OK 已强制推送（--force-with-lease）" else "ERROR: 强制推送失败\n${r.output.take(1200)}"
            }

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
                val state = args.optString("state", "open")
                gitHub?.issues(repo, state)?.joinToString("\n") { "#${it.number} [${it.state}] ${it.title} (@${it.user})" }
                    ?.ifBlank { "(无 $state issue)" } ?: "ERROR: gitHub 不可用"
            }

            // ---------- GitHub 全量仓库管理 v5.1 ----------
            "repo_create" -> {
                val name = args.optString("name")
                if (name.isBlank()) "ERROR: name 不能为空（要创建的仓库名）"
                else if (gitHub == null) ghUnavailable()
                else try {
                    val raw = gitHub.createRepo(name, args.optString("description"), args.optBoolean("private", false))
                    val p = gitHub.parseRepo(raw)
                    if (p != null) "OK 已创建仓库 ${p.fullName}\n${p.htmlUrl}" else "OK 已创建仓库 $name"
                } catch (e: Throwable) { "ERROR: 创建仓库失败 ${e.message?.take(300)}" }
            }
            "repo_delete" -> {
                val repo = args.optString("repo")
                if (repo.isBlank()) "ERROR: repo 必填（owner/repo）"
                else if (gitHub == null) ghUnavailable()
                else if (gitHub.deleteRepo(repo)) "OK 已删除远程仓库 $repo（不可恢复，请确认本地已有备份）"
                else "ERROR: 删除仓库失败（token 需含 delete_repo 权限）"
            }
            "repo_rename" -> {
                val repo = args.optString("repo")
                val name = args.optString("name").ifBlank { args.optString("new_name") }
                if (repo.isBlank() || name.isBlank()) "ERROR: repo 与 name 必填"
                else if (gitHub?.renameRepo(repo, name) == true) "OK 仓库已重命名为 $name"
                else "ERROR: 重命名失败"
            }
            "repo_config" -> {
                val repo = args.optString("repo")
                if (repo.isBlank()) "ERROR: repo 必填（owner/repo）"
                else if (gitHub == null) ghUnavailable()
                else {
                    val desc = if (args.has("description")) args.optString("description") else null
                    val priv = if (args.has("private")) args.optBoolean("private") else null
                    val wiki = if (args.has("wiki")) args.optBoolean("wiki") else null
                    val home = if (args.has("homepage")) args.optString("homepage") else null
                    val r = gitHub.updateRepoOp(repo, priv, desc, home, wiki, null)
                    if (r.ok) "OK 已更新仓库配置（description/private/wiki/homepage）"
                    else "ERROR: 更新失败 ${ghErr(r)}"
                }
            }
            "repo_commits" -> {
                val repo = args.optString("repo")
                gitHub?.commits(repo, args.optString("branch").ifBlank { null })?.joinToString("\n")?.ifBlank { "(无提交)" }
                    ?: "ERROR: gitHub 不可用"
            }
            "repo_readme" -> {
                val repo = args.optString("repo")
                val text = gitHub?.readme(repo, args.optString("branch").ifBlank { null })
                if (text.isNullOrBlank()) "(该仓库没有 README 或读取失败)" else text.take(8000)
            }
            "repo_branch_create" -> {
                val repo = args.optString("repo")
                val branch = args.optString("branch")
                if (repo.isBlank() || branch.isBlank()) "ERROR: repo 与 branch 必填"
                else if (gitHub == null) ghUnavailable()
                else if (gitHub.createBranch(repo, branch, args.optString("from").ifBlank { null })) "OK 已在远程创建分支 $branch"
                else "ERROR: 创建远程分支失败（分支可能已存在或 token 无写权限）"
            }
            "repo_branch_delete" -> {
                val repo = args.optString("repo")
                val branch = args.optString("branch")
                if (repo.isBlank() || branch.isBlank()) "ERROR: repo 与 branch 必填"
                else if (gitHub == null) ghUnavailable()
                else if (gitHub.deleteBranch(repo, branch)) "OK 已删除远程分支 $branch"
                else "ERROR: 删除远程分支失败（不能删除默认分支或 token 权限不足）"
            }
            "repo_clone" -> {
                val path = args.optString("path")
                if (path.isBlank()) "ERROR: path 必填（克隆到哪个本地目录，例如 /sdcard/Download/xxx 或项目目录下子目录）"
                else {
                    val url = args.optString("url").ifBlank {
                        val repo = args.optString("repo")
                        if (repo.isBlank()) "ERROR: 需要 url 或 repo 参数" else "https://github.com/$repo.git"
                    }
                    if (url.startsWith("ERROR")) url
                    else {
                        val branch = args.optString("branch").ifBlank { null }
                        val r = git.clone(url, java.io.File(path), branch)
                        if (r.ok) "OK 已克隆到 $path${if (branch != null) "（分支 $branch）" else ""}\n可用 file_tree/list_files 浏览；如需作为项目可 create_project 或在工作区选择目录"
                        else "ERROR: 克隆失败\n${r.output.take(1500)}"
                    }
                }
            }
            "repo_upload_zip" -> {
                val repo = args.optString("repo")
                val zipPath = args.optString("zip").ifBlank { args.optString("file") }
                if (repo.isBlank() || zipPath.isBlank()) "ERROR: repo 与 zip 必填（zip 为本地压缩包绝对路径）"
                else {
                    val zip = java.io.File(zipPath)
                    if (!zip.isFile) "ERROR: 文件不存在 $zipPath"
                    else if (gitHub == null) ghUnavailable()
                    else {
                        val branch = args.optString("branch").ifBlank { null }
                        val n = gitHub.uploadZipToRepo(repo, zip, args.optString("root", ""), branch)
                        if (n > 0) "OK 已上传 $n 个文件到 ${repo}${if (branch != null) ":$branch" else ""}（覆盖同名文件）"
                        else "ERROR: 上传失败（0 个文件上传成功，检查 zip 内容与 token 权限）"
                    }
                }
            }
            // ---- Release ----
            "release_list" -> {
                val repo = args.optString("repo")
                val r = gitHub?.releasesOp(repoOwner(repo), repoName(repo))
                if (r == null) ghUnavailable()
                else if (!r.ok) "ERROR: ${ghErr(r)}"
                else {
                    val arr = r.dataArray ?: JSONArray()
                    if (arr.length() == 0) "(该仓库还没有 Release，可用 release_create 创建)"
                    else (0 until arr.length()).joinToString("\n") { i -> releaseLine(arr.optJSONObject(i) ?: JSONObject()) }
                }
            }
            "release_create" -> {
                val repo = args.optString("repo")
                val tag = args.optString("tag").ifBlank { args.optString("tag_name") }
                if (repo.isBlank() || tag.isBlank()) "ERROR: repo 与 tag 必填"
                else if (gitHub == null) ghUnavailable()
                else {
                    val r = gitHub.createReleaseOp(repoOwner(repo), repoName(repo), tag,
                        args.optString("name").ifBlank { null },
                        args.optString("body").ifBlank { null },
                        args.optBoolean("prerelease", false),
                        args.optString("target").ifBlank { null })
                    if (r.ok) {
                        val id = r.data?.optLong("id", 0L) ?: 0L
                        "OK 已创建 Release v$tag（id=$id）${if (args.has("name")) "「${args.optString("name")}」" else ""}\n可用 release_upload 上传资产"
                    }
                    else "ERROR: ${ghErr(r)}"
                }
            }
            "release_edit" -> {
                val repo = args.optString("repo")
                val id = args.optLong("id", 0L)
                if (repo.isBlank() || id <= 0L) "ERROR: repo 与 id 必填（release_list 查看 id）"
                else if (gitHub == null) ghUnavailable()
                else {
                    val name = if (args.has("name")) args.optString("name") else null
                    val body = if (args.has("body")) args.optString("body") else null
                    val r = gitHub.updateReleaseOp(repoOwner(repo), repoName(repo), id, name, body)
                    if (r.ok) "OK 已编辑 Release #$id（标题/说明已更新）" else "ERROR: ${ghErr(r)}"
                }
            }
            "release_links" -> {
                val repo = args.optString("repo")
                val tag = args.optString("tag").ifBlank { null }
                val r = gitHub?.releasesOp(repoOwner(repo), repoName(repo))
                if (r == null) ghUnavailable()
                else if (!r.ok) "ERROR: ${ghErr(r)}"
                else {
                    val arr = r.dataArray ?: JSONArray()
                    val out = StringBuilder()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        if (tag != null && o.optString("tag_name") != tag) continue
                        out.appendLine("${o.optString("tag_name")} — ${o.optString("html_url")}")
                        val assets = o.optJSONArray("assets") ?: continue
                        for (j in 0 until assets.length()) {
                            val a = assets.optJSONObject(j) ?: continue
                            out.appendLine("   ${a.optString("name")}: ${a.optString("browser_download_url")}")
                        }
                    }
                    out.toString().ifBlank { "(未找到对应 Release 下载链接)" }
                }
            }
            "release_upload" -> {
                val repo = args.optString("repo")
                val id = args.optLong("id", 0L)
                val path = args.optString("file").ifBlank { args.optString("path") }
                if (repo.isBlank() || id <= 0L || path.isBlank()) "ERROR: repo/id/file 必填"
                else {
                    val f = java.io.File(path)
                    if (!f.isFile) "ERROR: 资产文件不存在 $path"
                    else if (gitHub == null) ghUnavailable()
                    else {
                        val name = args.optString("name").ifBlank { f.name }
                        val r = gitHub.uploadReleaseAssetOp(repoOwner(repo), repoName(repo), id, f, name)
                        if (r.ok) "OK 已上传资产 $name 到 Release #$id（${f.length() / 1024} KB）"
                        else "ERROR: ${ghErr(r)}"
                    }
                }
            }
            "release_delete" -> {
                val repo = args.optString("repo")
                val id = args.optLong("id", 0L)
                if (repo.isBlank() || id <= 0L) "ERROR: repo 与 id 必填（release_list 查看 id；先删除全部资产才能删除 Release）"
                else if (gitHub == null) ghUnavailable()
                else {
                    val r = gitHub.deleteReleaseOp(repoOwner(repo), repoName(repo), id)
                    if (r.ok) "OK 已删除 Release #$id" else "ERROR: ${ghErr(r)}"
                }
            }
            // ---- Issue / PR ----
            "issue_create" -> {
                val repo = args.optString("repo")
                val title = args.optString("title")
                if (repo.isBlank() || title.isBlank()) "ERROR: repo 与 title 必填"
                else if (gitHub == null) ghUnavailable()
                else if (gitHub.createIssue(repo, title, args.optString("body"))) "OK 已创建 Issue「$title」"
                else "ERROR: 创建 Issue 失败"
            }
            "issue_comment" -> {
                val repo = args.optString("repo")
                val num = args.optInt("number", args.optInt("issue", 0))
                val body = args.optString("body")
                if (repo.isBlank() || num <= 0 || body.isBlank()) "ERROR: repo/number/body 必填"
                else if (gitHub == null) ghUnavailable()
                else {
                    val r = gitHub.issueCommentOp(repoOwner(repo), repoName(repo), num, body)
                    if (r.ok) "OK 已回复 Issue #$num" else "ERROR: ${ghErr(r)}"
                }
            }
            "pr_create" -> {
                val repo = args.optString("repo")
                val title = args.optString("title")
                val head = args.optString("head")
                val base = args.optString("base")
                if (repo.isBlank() || title.isBlank() || head.isBlank() || base.isBlank()) "ERROR: repo/title/head/base 必填（head=源分支，base=目标分支）"
                else if (gitHub == null) ghUnavailable()
                else {
                    val r = gitHub.createPrOp(repoOwner(repo), repoName(repo), title, head, base, args.optString("body"))
                    if (r.ok) {
                        val o = r.data
                        "OK 已创建 PR #${o?.optInt("number") ?: "?"}：${o?.optString("html_url") ?: title}"
                    } else "ERROR: ${ghErr(r)}"
                }
            }
            "pr_list" -> {
                val repo = args.optString("repo")
                val r = gitHub?.listPrsOp(repoOwner(repo), repoName(repo), args.optString("state", "open"))
                if (r == null) ghUnavailable()
                else if (!r.ok) "ERROR: ${ghErr(r)}"
                else {
                    val arr = r.dataArray ?: JSONArray()
                    if (arr.length() == 0) "(没有 ${args.optString("state", "open")} 的 PR)"
                    else (0 until arr.length()).joinToString("\n") { i ->
                        val o = arr.optJSONObject(i) ?: return@joinToString ""
                        "#${o.optInt("number")} ${o.optString("title")} [${o.optString("state")}] @${o.optJSONObject("user")?.optString("login") ?: ""}"
                    }
                }
            }
            "pr_merge" -> {
                val repo = args.optString("repo")
                val num = args.optInt("number", args.optInt("pr", 0))
                if (repo.isBlank() || num <= 0) "ERROR: repo 与 number 必填"
                else if (gitHub == null) ghUnavailable()
                else {
                    val method = args.optString("method").ifBlank { null }
                    val r = gitHub.mergePrOp(repoOwner(repo), repoName(repo), num, method)
                    if (r.ok) "OK 已合并 PR #$num${if (!method.isNullOrBlank()) "（$method 合并）" else ""}"
                    else "ERROR: ${ghErr(r)}"
                }
            }
            "repo_context" -> repoContext(project)

            // ---------- 增强工具 v1.0 ----------
            // Problems 待办扫描
            "scan_problems", "problems", "scan_todos" -> scanProblems(project)

            // 项目记忆（长期记忆，纯文本 .studio/memory.json）
            "memory_read" -> {
                val cur = readMemory(project)
                if (cur.isBlank()) "(项目记忆为空，可用 memory_write 写入长期记忆)" else cur
            }
            "memory_write" -> {
                val text = args.optString("content")
                if (text.isBlank()) "ERROR: content 不能为空"
                else if (writeMemory(project, text)) "OK 已写入项目记忆（${text.length} 字符）"
                else "ERROR: 项目记忆写入失败"
            }
            "memory_append" -> {
                val text = args.optString("content")
                if (text.isBlank()) "ERROR: content 不能为空"
                else {
                    val cur = readMemory(project)
                    val ok = writeMemory(project, if (cur.isBlank()) text else cur.trimEnd() + "\n" + text)
                    if (ok) "OK 已追加到项目记忆" else "ERROR: 追加失败"
                }
            }
            // 逆向记忆分区（AI逆向工作台专属，与普通项目记忆隔离）
            "reverse_memory_read", "rev_memory_read" -> {
                val cur = readRevMemory(project)
                if (cur.isBlank()) "(逆向记忆分区为空，可用 reverse_memory_write 记录逆向结论)" else cur
            }
            "reverse_memory_write", "rev_memory_write" -> {
                val text = args.optString("content")
                if (text.isBlank()) "ERROR: content 不能为空"
                else if (writeRevMemory(project, text)) "OK 已写入逆向记忆分区"
                else "ERROR: 逆向记忆写入失败"
            }
            "reverse_memory_append", "rev_memory_append" -> {
                val text = args.optString("content")
                if (text.isBlank()) "ERROR: content 不能为空"
                else {
                    val cur = readRevMemory(project)
                    val ok = writeRevMemory(project, if (cur.isBlank()) text else cur.trimEnd() + "\n" + text)
                    if (ok) "OK 已追加到逆向记忆分区" else "ERROR: 追加失败"
                }
            }

            // 增量补丁：patch_file / patch_diff（生成 diff 预览 / 应用精确替换）
            "patch_diff" -> {
                val rel = args.optString("path")
                val search = args.optString("search")
                val oldText = workspace.readSafe(project, rel)
                if (oldText == null) "ERROR: 文件不存在或路径非法: $rel"
                else if (search.isBlank()) "ERROR: search 参数不能为空"
                else {
                    if (!oldText.contains(search)) "ERROR: 未在文件 $rel 中找到待替换文本（可能已变更），请先 read_file 查看现状"
                    else {
                        val newText = oldText.replace(search, args.optString("replace", ""))
                        makeDiff(rel, oldText, newText)
                    }
                }
            }
            "patch_file" -> {
                val rel = args.optString("path")
                val search = args.optString("search")
                val replace = args.optString("replace")
                val oldText = workspace.readSafe(project, rel)
                if (oldText == null) "ERROR: 文件不存在或路径非法: $rel"
                else if (search.isBlank()) "ERROR: search 参数不能为空"
                else if (!oldText.contains(search)) "ERROR: 未找到待替换文本（可能已变更），请先 read_file 查看现状"
                else {
                    val newText = oldText.replace(search, replace)
                    if (workspace.writeSafe(project, rel, newText)) {
                        "OK 已应用补丁到 $rel\n${makeDiff(rel, oldText, newText)}"
                    } else "ERROR: 写入失败"
                }
            }
            // 文件追加（适合日志/清单类增量写入，无需输出整个文件）
            "append_file" -> {
                val rel = args.optString("path")
                val content = args.optString("content")
                val exist = workspace.readSafe(project, rel) ?: ""
                val newText = if (exist.isBlank()) content else exist.trimEnd() + "\n" + content
                if (workspace.writeSafe(project, rel, newText)) "OK 已追加到 $rel" else "ERROR: 追加失败"
            }
            // 模板库：list_templates / templates（无需项目上下文，列入 projectFree）
            "list_templates", "templates" -> listTemplates()

            // ---------- v6.1 学习与项目体检 ----------
            "project_summary" -> projectSummary(project)
            "lessons_record" -> lessonsRecord(project, args)
            "lessons_list" -> lessonsList(project)

            // Shizuku 特权命令（需要应用已授权 Shizuku 时可用，否则提示授权）
            "shizuku_exec", "shizuku" -> {
                val cmd = args.optString("command")
                if (cmd.isBlank()) "ERROR: command 参数不能为空"
                else if (context == null) "ERROR: Shizuku 需要 Context（当前不可用）"
                else {
                    val env = args.optString("env", "")
                    val dir = args.optString("dir", "")
                    val timeout = args.optLong("timeout", 30).coerceIn(5, 600)
                    if (!ShizukuShell.isReady() || !ShizukuShell.hasPermission()) {
                        "ERROR: Shizuku 未授权/不可用。请在 AI 工作台设置或终端页先完成 Shizuku 授权（无 Root 时也需授予 Shizuku 权限）。"
                    } else {
                        val envMap = if (env.isNotBlank()) {
                            env.split('\n').mapNotNull {
                                val kv = it.trim().split("=", limit = 2)
                                if (kv.size == 2) kv[0].trim() to kv[1].trim() else null
                            }.toMap()
                        } else emptyMap()
                        val out = ShizukuShell.execUserService(context, cmd, envMap, dir.ifBlank { null }, timeout)
                        if (out == null) "ERROR: Shizuku 执行失败（进程异常）" else out.trim().ifBlank { "(命令无输出)" }
                    }
                }
            }
            // ---------- v6.0 增强工具：批量/文档/zip/依赖/模板/插件/迁移 ----------
            "batch_replace" -> batchReplace(project, args)
            "read_doc" -> readDoc(project, args)
            "read_zip" -> readZipList(project, args)
            "read_zip_entry" -> readZipEntry(project, args)
            "dependency_scan" -> dependencyScan(project)
            "dependency_add" -> dependencyAdd(project, args)
            "dependency_lock" -> dependencyLock(project, args)
            "auto_docs" -> com.example.myempty.githubk.core.ProjectKnowledge.generateDocs(project) { f, s ->
                val base = project.path.absoluteFile.toURI().path.trimEnd('/')
                val abs = f.absolutePath
                if (!abs.startsWith(base + "/") && abs != base) false
                else {
                    val rel = abs.removePrefix(base + "/")
                    workspace.writeSafe(project, rel, s)
                }
            }
            "list_plugins" -> {
                val k = com.example.myempty.githubk.core.ProjectKnowledge
                if (k.featuresDisabled(project)) "插件与自定义能力已由用户停用（.studio/.features_off 存在），可通过 UI 重新启用。"
                else {
                    val ps = k.listPlugins(project)
                    if (ps.isEmpty()) "（暂无自定义插件。可用 save_plugin 添加，命令在项目沙盒内运行）"
                    else ps.joinToString("\n") { "• ${it.name} — ${it.description}（command: ${it.command.take(60)}）" }
                }
            }
            "save_plugin" -> {
                val k = com.example.myempty.githubk.core.ProjectKnowledge
                val name = args.optString("name")
                if (name.isBlank()) "ERROR: name 不能为空"
                else if (args.optString("command").isBlank()) "ERROR: command 不能为空"
                else {
                    k.savePlugin(project, name, args.optString("description", ""), args.optString("command"))
                    "OK 已保存自定义插件 $name 到 .studio/plugins/（可在功能面板停用）"
                }
            }
            "run_plugin" -> {
                val k = com.example.myempty.githubk.core.ProjectKnowledge
                if (k.featuresDisabled(project)) "ERROR: 自定义插件已停用（.features_off）"
                else {
                    val name = args.optString("name")
                    val spec = k.listPlugins(project).firstOrNull { it.name == name }
                    if (spec == null) "ERROR: 插件不存在：$name（用 list_plugins 查看）"
                    else if (runtime == null) "ERROR: 终端运行时不可用"
                    else {
                        val extra = args.optString("args", "")
                        val cmd = "cd '${project.path.absolutePath}' && " + spec.command + (if (extra.isBlank()) "" else " $extra")
                        val out = runCatching { runtime!!.run(cmd) }.getOrDefault("插件执行失败")
                        out.trim().take(8000).ifBlank { "(插件无输出)" }
                    }
                }
            }
            "export_ability" -> {
                val f = com.example.myempty.githubk.core.ProjectKnowledge.exportAbility(project)
                "OK 能力包已生成：${f.absolutePath}。需要交付到 Download 时调用 export_file {\"path\":\"${f.absolutePath}\"}。"
            }
            "import_ability" -> {
                val path = args.optString("path")
                val f = java.io.File(path)
                if (!f.isFile) "ERROR: 能力包不存在：$path"
                else {
                    val n = com.example.myempty.githubk.core.ProjectKnowledge.importAbility(project, f)
                    if (n > 0) "OK 已迁移 $n 个能力文件（记忆/变更/复盘/变量/插件）到当前项目 .studio/" else "ERROR: 迁移失败或包内无有效文件"
                }
            }

            // ---------- v6.2 AI 逆向：APK / Web / Frida 工作流支撑 ----------
            "apk_info" -> apkInfo(project, args)
            "apk_unzip" -> apkUnzip(project, args)
            "page_analyze" -> pageAnalyze(args)

            // ---------- v6.3 图像识别 / 神经网络 / 外部库导入 / Frida-Proot 编排 ----------
            "vision_analyze", "image_recognize", "image_analyze", "img_recognize" -> {
                val path = args.optString("path").ifBlank { args.optString("file") }
                if (path.isBlank()) "ERROR: vision_analyze 需要 path 参数（图片路径）"
                else if (vision == null) "ERROR: 当前环境未接入视觉模型（vision 回调不可用）"
                else runCatching { vision?.invoke(args.optString("question").ifBlank { "请分析这张图片的内容并总结要点。" }, path) ?: "ERROR: 视觉模型不可用" }
                    .getOrElse { "ERROR: 视觉分析失败 ${it.message}" }
            }
            "import_lib", "import_external_lib" -> importLib(project, args)
            "frida_gen", "frida_hooks" -> fridaGen(project, args)
            "proot_orchestrate", "proot_exec" -> prootOrchestrate(project, args)

            // ---------- v6.5 本地离线推理：本机神经网络（分类 / 检测 / OCR），图片不出设备 ----------
            "local_vision", "local_image", "local_recognize" -> localVision(project, args, onProgress)
            "local_ai_status", "localai_status" -> localAiStatus(args)
            "local_ai_install", "localai_install" -> localAiInstall(args, onProgress)

            else -> "ERROR: unknown tool $tool"
        }
    } catch (e: Throwable) {
        "ERROR: ${e.message ?: "tool execution failed"}"
    }

    // ---------- v6.5 本地离线推理 helpers ----------

    /** 解析图片参数：绝对路径 → 项目内相对路径 → 附件目录 → 全项目按文件名。 */
    private fun resolveImage(project: Project, raw: String): File? {
        val p = raw.trim()
        if (p.isBlank()) return null
        val abs = File(p)
        if (abs.isFile) return abs
        val rel = File(project.path, p)
        if (rel.isFile) return rel
        val att = File(attachmentsDir(project), p)
        if (att.isFile) return att
        val base = File(p).name
        if (base.isNotBlank()) {
            val byName = File(attachmentsDir(project), base)
            if (byName.isFile) return byName
            findFileByName(project.path, base, 0)?.let { return it }
        }
        return null
    }

    private fun findFileByName(root: File, name: String, depth: Int): File? {
        if (depth > 6) return null
        val kids = root.listFiles() ?: return null
        for (f in kids) if (f.isFile && f.name.equals(name, ignoreCase = true)) return f
        for (f in kids) {
            if (f.isDirectory && !skipDirName(f.name)) {
                val r = findFileByName(f, name, depth + 1)
                if (r != null) return r
            }
        }
        return null
    }

    /**
     * local_vision：完全离线的本机图像识别（分类 / 目标检测 / OCR）。
     * 依赖未就绪时不擅自安装，而是返回「检测 → 告知」文本；用户同意后由 local_ai_install 安装。
     */
    private fun localVision(project: Project, args: JSONObject, onProgress: ((String) -> Unit)?): String {
        val ctx = context ?: return "ERROR: 无 Context，无法使用本地推理"
        val raw = args.optString("image").ifBlank { args.optString("path").ifBlank { args.optString("file") } }
        val img = resolveImage(project, raw)
            ?: return "ERROR: 找不到图片：'$raw'（可用绝对路径、项目内相对路径，或 .studio/attachments 下的文件名）"
        val engine = LocalVisionEngine(ctx)
        val taskArg = args.optString("task").ifBlank { InferTask.AUTO }
        val plan = runCatching { engine.plan(taskArg, img.absolutePath) }.getOrNull()
        if (plan != null && !plan.ready) {
            val need = plan.missing.joinToString("、") { "${it.displayName}（${it.id}）" }
            return buildString {
                append("本地推理依赖尚未就绪（任务：").append(InferTask.label(plan.task)).append("）\n")
                append("缺少：").append(if (need.isBlank()) "—" else need).append('\n')
                if (plan.blockedReason != null) append("阻塞原因：").append(plan.blockedReason).append('\n')
                append(plan.describe()).append('\n')
                append("如需本机离线识别，请先征得用户同意，再调用 local_ai_install {\"task\":\"")
                    .append(plan.task).append("\"} 安装；安装全程在本机完成（免 Root，失败自动回滚）。")
            }
        }
        onProgress?.invoke("本地推理中：${InferTask.label(taskArg)} …")
        val res = engine.run(
            imagePath = img.absolutePath,
            task = taskArg,
            model = args.optString("model"),
            labels = args.optString("labels"),
            topK = args.optInt("topk", 5),
            conf = args.optDouble("conf", 0.25),
            iou = args.optDouble("iou", 0.45),
            inputSize = args.optInt("size", 0),
            langs = args.optString("langs"),
            psm = args.optInt("psm", 6),
            normalize = args.optBoolean("normalize", false)
        )
        return "图片：${img.absolutePath}\n" + res.report()
    }

    /** local_ai_status：本机推理环境状态与可用组件（只读，低风险）。 */
    private fun localAiStatus(args: JSONObject): String {
        val ctx = context ?: return "ERROR: 无 Context"
        val mgr = LocalInferenceManager(ctx)
        val st = runCatching { mgr.probe(args.optBoolean("force", false)) }.getOrElse {
            return "ERROR: 探测失败：${it.message}"
        }
        val sb = StringBuilder()
        sb.append("内置运行时：").append(if (st.runtimeInstalled) "已安装" else "未安装")
        sb.append(" · proot 沙盒：").append(if (st.sandboxReady) "可用" else "不可用").append('\n')
        sb.append("python=").append(st.pythonVersion ?: "-")
        sb.append(" numpy=").append(st.numpyVersion ?: "-")
        sb.append(" pillow=").append(st.pillowVersion ?: "-")
        sb.append(" onnxruntime=").append(st.onnxVersion ?: "-")
        sb.append(" tflite=").append(st.tfliteVersion ?: "-")
        sb.append(" tesseract=").append(st.tesseractVersion ?: "-").append('\n')
        val comps = runCatching { mgr.installedComponents() }.getOrDefault(emptyList())
        sb.append("已安装组件：").append(if (comps.isEmpty()) "无" else comps.joinToString("、") { it.displayName }).append('\n')
        val models = runCatching { mgr.installedModels() }.getOrDefault(emptyList())
        sb.append("本地模型：").append(
            if (models.isEmpty()) "无"
            else models.joinToString("、") { "${it.name}[${it.backend}/${it.tasks.joinToString("/")}]" }
        ).append('\n')
        for (t in listOf(InferTask.CLASSIFY, InferTask.DETECT, InferTask.OCR)) {
            val p = runCatching { mgr.planFor(t) }.getOrNull() ?: continue
            sb.append("任务 ").append(InferTask.label(t)).append("：")
            sb.append(if (p.ready) "就绪" else "缺少 " + p.missing.joinToString("、") { it.displayName })
            if (p.blockedReason != null) sb.append("（").append(p.blockedReason).append("）")
            sb.append('\n')
        }
        sb.append("组件总数：").append(InferenceCatalog.all().size)
        sb.append("，用 local_ai_install {\"component\":\"<id>\"} 或 {\"task\":\"classify\"} 安装。")
        return sb.toString()
    }

    /**
     * local_ai_install：把推理依赖安装到设备本地（高风险，调用前必须已获得用户同意）。
     * component=<id> 单装，或 task=classify|detect|ocr 批量装齐；失败自动回滚。
     */
    private fun localAiInstall(args: JSONObject, onProgress: ((String) -> Unit)?): String {
        val ctx = context ?: return "ERROR: 无 Context"
        val mgr = LocalInferenceManager(ctx)
        val want = args.optString("component").ifBlank { args.optString("id") }
        val list: List<InferenceComponent> = if (want.isNotBlank()) {
            val c = InferenceCatalog.find(want)
                ?: return "ERROR: 未知组件 '$want'。可用：${InferenceCatalog.all().joinToString(", ") { it.id }}"
            listOf(c)
        } else if (args.optString("task").isNotBlank()) {
            mgr.planFor(InferTask.fromText(args.optString("task"))).missing
        } else {
            return "ERROR: 需要 component=<id> 或 task=classify|detect|ocr"
        }
        if (list.isEmpty()) return "OK 依赖已就绪，无需安装"
        val notes = ArrayList<String>()
        notes.add("将安装 ${list.size} 项：" + list.joinToString("、") { "${it.displayName}（${it.id}）" })
        var okAll = true
        for ((i, c) in list.withIndex()) {
            onProgress?.invoke("[${i + 1}/${list.size}] 安装 ${c.displayName} …")
            val o = runCatching {
                mgr.install(
                    c,
                    onProgress = { p ->
                        val pct = if (p.percent >= 0) " ${p.percent}%" else ""
                        onProgress?.invoke("[${i + 1}/${list.size}] ${c.displayName} ${p.stage} ${p.doneText}$pct")
                    },
                    onLine = null
                )
            }.getOrElse {
                com.example.myempty.githubk.localai.InstallOutcome(
                    ok = false,
                    componentId = c.id,
                    displayName = c.displayName,
                    message = "${it.javaClass.simpleName}: ${it.message}"
                )
            }
            if (o.ok) {
                notes.add("✔ ${c.displayName} 安装完成")
            } else {
                okAll = false
                notes.add("✘ ${c.displayName} 安装失败：${o.message}" + if (o.rolledBack) "（已回滚，无残留）" else "")
                break
            }
        }
        notes.add(if (okAll) "全部完成，可继续调用 local_vision 进行本机识别。" else "安装未全部完成，可用 local_ai_status 查看状态。")
        return notes.joinToString("\n")
    }

    // ---------- v6.2 AI 逆向 helpers ----------

    /** 定位 APK：优先 path 参数（绝对/相对），否则找项目附件目录最近 apk，最后回退到构建产物。 */
    private fun findApkFile(project: Project, pathArg: String): java.io.File? {
        val candidates = mutableListOf<java.io.File>()
        if (pathArg.isNotBlank()) {
            // 支持绝对路径或项目内相对路径
            listOf(
                java.io.File(pathArg),
                java.io.File(project.path, pathArg),
                java.io.File(java.io.File(project.path, ".studio/attachments"), pathArg.substringAfterLast('/'))
            ).forEach { if (it.isFile) candidates.add(it) }
        }
        if (candidates.isNotEmpty()) return candidates.first()
        val attDir = attachmentsDir(project)
        attDir.listFiles()?.filter { it.isFile && it.extension.equals("apk", true) }
            ?.sortedByDescending { it.lastModified() }
            ?.firstOrNull()?.let { return it }
        return workspace.findLatestApk(project)
    }

    /** 附件目录：全局（无项目）时落到应用私有 .studio/attachments。 */
    private fun attachmentsDir(project: Project): java.io.File =
        if (project.path.absolutePath == "/dev/null") {
            context?.let { java.io.File(java.io.File(it.filesDir, ".studio"), "attachments") }
                ?: java.io.File(java.io.File(project.path, ".studio"), "attachments")
        } else java.io.File(java.io.File(project.path, ".studio"), "attachments")

    /** apk_info：读取 APK 结构 / 权限 / ABI / dex / 组件（含 aapt 尽力解析与 manifest 字符串启发式）。 */
    private fun apkInfo(project: Project, args: JSONObject): String {
        val f = findApkFile(project, args.optString("path"))
            ?: return "ERROR: 未找到 APK（可在 path 参数指定绝对/相对路径，或在聊天里上传 apk 附件）"
        val sb = StringBuilder()
        sb.appendLine("APK: ${f.name}")
        sb.appendLine("路径: ${f.absolutePath}")
        sb.appendLine("大小: ${f.length() / 1024} KB")
        var entryCount = 0; var dexCount = 0; var dexSize = 0L
        val abis = mutableSetOf<String>(); var assets = 0; var hasManifest = false; var hasResources = false
        val sample = mutableListOf<String>()
        try {
            java.util.zip.ZipFile(f).use { z ->
                val en = z.entries()
                while (en.hasMoreElements()) {
                    val e = en.nextElement()
                    entryCount++
                    val n = e.name
                    when {
                        n == "AndroidManifest.xml" -> hasManifest = true
                        n == "resources.arsc" -> hasResources = true
                        n.startsWith("lib/") -> n.split('/').getOrNull(1)?.takeIf { it.isNotBlank() }?.let { abis.add(it) }
                        n.startsWith("assets/") -> assets++
                        n.startsWith("classes") && n.endsWith(".dex") -> { dexCount++; dexSize += e.size }
                    }
                    if (sample.size < 12 && (n.endsWith(".dex") || n.endsWith(".so") || n.startsWith("res/") || n.endsWith(".json") || n.endsWith(".xml") || n.startsWith("META-INF/"))) sample.add(n)
                }
            }
        } catch (e: Exception) { return "ERROR: 读取 APK 失败 ${e.message}" }
        sb.appendLine("条目数: $entryCount")
        sb.appendLine("dex: ${dexCount} 个，共 ${dexSize / 1024} KB")
        sb.appendLine("ABI: ${abis.joinToString(", ").ifBlank { "未知/纯解释" }}")
        sb.appendLine("assets: $assets 个${if (hasResources) "" else "（无 resources.arsc）"}")
        sb.appendLine("AndroidManifest:${if (hasManifest) " 有（二进制）" else " 缺失"}")
        // aapt 尽力解析
        if (runtime != null) {
            val aapt = runCatching { tryAaptBadging(f) }.getOrDefault("")
            if (aapt.isNotBlank()) sb.appendLine("aapt badging:\n$aapt")
        }
        // manifest 字符串启发式
        val manifestHint = runCatching { readManifestStrings(f) }.getOrDefault("")
        if (manifestHint.isNotBlank()) sb.appendLine(manifestHint)
        sb.appendLine("关键条目示例:\n${sample.joinToString("\n").ifBlank { "（无）" }}")
        sb.appendLine("提示：可继续调用 apk_unzip {\"path\":\"${f.absolutePath}\"} 解压后浏览更多文件；用 run_command 执行 aapt2/aapt 可获取更完整信息。")
        return sb.toString()
    }

    /** 尝试用 aapt2/aapt dump badging 获取更精确的包名/版本/权限。 */
    private fun tryAaptBadging(f: java.io.File): String {
        val rt = runtime ?: return ""
        val envExports = buildString {
            toolchain?.environment()?.forEach { (k, v) -> append("export $k='${v.replace("'", "'\\''")}'; ") }
        }
        for (cmd in listOf("aapt2 dump badging", "aapt dump badging")) {
            val out = runCatching { rt.run("$envExports $cmd '${f.absolutePath}'") }.getOrDefault("")
            if (out.isNotBlank() && !out.contains("not found") && !out.contains("No such file") && !out.contains("error:")) return out.take(4000)
        }
        return ""
    }

    /** 从二进制 AndroidManifest 启发式提取 package/permission/组件名（string pool 扫描）。 */
    private fun readManifestStrings(f: java.io.File): String {
        val bytes = runCatching {
            java.util.zip.ZipFile(f).use { z -> z.getInputStream(z.getEntry("AndroidManifest.xml"))?.readBytes() }
        }.getOrNull() ?: return ""
        if (bytes.isEmpty()) return ""
        val utf8 = String(bytes, Charsets.UTF_8).replace('\u0000', ' ')
        val utf16 = String(bytes, Charsets.UTF_16LE).replace('\u0000', ' ')
        val re = Regex("[\\x20-\\x7E]{4,}")
        val found = mutableSetOf<String>()
        (re.findAll(utf8).toList() + re.findAll(utf16).toList()).forEach { if (it.value.length in 4..120) found.add(it.value) }
        val sb = StringBuilder()
        val perms = found.filter { it.startsWith("android.permission.") || it.startsWith("com.android.permission.") }.distinct()
        val pkg = found.firstOrNull { it.matches(Regex("^[a-z][a-z0-9]*(\\.[a-z0-9_]+){2,}$")) && it.length > 6 }
        val comps = found.filter { it.endsWith("Activity") || it.endsWith("Service") || it.endsWith("Provider") || it.endsWith("Receiver") }.distinct().take(50)
        if (pkg != null) sb.appendLine("包名(启发式): $pkg")
        if (perms.isNotEmpty()) sb.appendLine("权限: ${perms.joinToString(", ")}")
        if (comps.isNotEmpty()) sb.appendLine("组件: ${comps.joinToString(", ")}")
        return sb.toString().trim()
    }

    /** apk_unzip：把 APK 解压到项目 .studio/reverse/<name>/，便于 AI 浏览。 */
    private fun apkUnzip(project: Project, args: JSONObject): String {
        val f = findApkFile(project, args.optString("path"))
            ?: return "ERROR: 未找到 APK（可在 path 参数指定绝对/相对路径，或在聊天里上传 apk 附件）"
        val reverseRoot = java.io.File(java.io.File(project.path, ".studio"), "reverse").apply { mkdirs() }
        val dir = java.io.File(reverseRoot, f.nameWithoutExtension)
        if (dir.exists() && args.optBoolean("overwrite", false)) dir.deleteRecursively()
        dir.mkdirs()
        var n = 0
        try {
            java.util.zip.ZipFile(f).use { z ->
                val en = z.entries()
                while (en.hasMoreElements()) {
                    val e = en.nextElement()
                    val target = java.io.File(dir, e.name)
                    if (!target.canonicalPath.startsWith(dir.canonicalPath + java.io.File.separator)) continue
                    if (e.isDirectory) { target.mkdirs(); continue }
                    target.parentFile?.mkdirs()
                    z.getInputStream(e).use { inp -> target.outputStream().use { out -> inp.copyTo(out) } }
                    n++
                }
            }
        } catch (e: Exception) { return "ERROR: 解压失败 ${e.message}" }
        return "OK 已解压 $n 个文件到 ${dir.absolutePath}（项目相对 .studio/reverse/${dir.name}）。二进制（xml/arsc/dex/so）建议用工具分析；json/文本可直接 read_file 读取。"
    }

    /** page_analyze：抓取网页并提取结构（标题/描述/JS/CSS/链接/表单/iframe/技术栈线索）。 */
    private fun pageAnalyze(args: JSONObject): String {
        val url = args.optString("url")
        if (url.isBlank()) return "ERROR: url 参数不能为空"
        if (!url.startsWith("http")) return "ERROR: url 需以 http(s):// 开头"
        val html = runCatching { httpGet(url, 25000, 300000) }.getOrDefault("")
        if (html.isBlank()) return "ERROR: 抓取失败（无内容）"
        fun findRE(pat: String): List<String> = Regex(pat, RegexOption.DOT_MATCHES_ALL).findAll(html)
            .map { stripHtml(it.groupValues[1]).trim() }.filter { it.isNotBlank() }.distinct().toList()
        val title = findRE("<title[^>]*>(.*?)</title>").firstOrNull() ?: ""
        val desc = findRE("""<meta[^>]*name=["']?description["']?[^>]*content=["']([^"']+)""").firstOrNull() ?: ""
        val scripts = findRE("""<script[^>]*src=["']([^"']+)""")
        val styles = findRE("""<link[^>]*rel=["']?stylesheet["']?[^>]*href=["']([^"']+)""")
        val links = findRE("""<a[^>]*href=["']([^"']+)""").filter { it.startsWith("http") || it.startsWith("/") }
        val forms = findRE("""<form[^>]*action=["']([^"']+)""")
        val iframes = findRE("""<iframe[^>]*src=["']([^"']+)""")
        val wf = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).findAll(html)
            .map { it.groupValues[1] }.joinToString(" ")
        val stack = buildList {
            if (Regex("""react|react-dom|__REACT_DEVTOOLS""", RegexOption.IGNORE_CASE).containsMatchIn(html)) add("React")
            if (Regex(""""vue"|Vue\.|__VUE__""", RegexOption.IGNORE_CASE).containsMatchIn(html)) add("Vue")
            if (Regex("""angular|ng-""", RegexOption.IGNORE_CASE).containsMatchIn(html)) add("Angular")
            if (Regex("""next__|__NEXT_DATA__""").containsMatchIn(html)) add("Next.js")
            if (Regex("""nuxt|__NUXT__""").containsMatchIn(html)) add("Nuxt")
            if (Regex("""jquery""" , RegexOption.IGNORE_CASE).containsMatchIn(html)) add("jQuery")
            if (Regex("""bootstrap""", RegexOption.IGNORE_CASE).containsMatchIn(html)) add("Bootstrap")
            if (Regex("""tailwind|tw-""", RegexOption.IGNORE_CASE).containsMatchIn(html)) add("Tailwind")
            if (Regex("""webpack|bundle""", RegexOption.IGNORE_CASE).containsMatchIn(html)) add("webpack")
            if (Regex("""api[_-]?key|apikey""", RegexOption.IGNORE_CASE).containsMatchIn(wf)) add("含前端 API Key 引用（需核验）")
        }
        val endpoints = Regex("""["'](/[a-zA-Z0-9_\-./?=&]{3,})["']""").findAll(html).map { it.groupValues[1] }
            .filter { it.startsWith("/api") || it.startsWith("/v") || it.startsWith("/graphql") || it.startsWith("/ajax") || it.startsWith("/rest") }
            .distinct().take(30).toList()
        return buildString {
            appendLine("【页面分析】$url")
            if (title.isNotBlank()) appendLine("标题: $title")
            if (desc.isNotBlank()) appendLine("描述: ${desc.take(200)}")
            if (stack.isNotEmpty()) appendLine("技术栈线索: ${stack.distinct().joinToString(", ")}")
            appendLine("脚本(${scripts.size}): ${scripts.take(20).joinToString(", ").ifBlank { "（无/内联）" }}")
            appendLine("样式(${styles.size}): ${styles.take(12).joinToString(", ").ifBlank { "（无）" }}")
            appendLine("表单 action: ${forms.joinToString(", ").ifBlank { "（无）" }}")
            appendLine("iframe: ${iframes.joinToString(", ").take(200).ifBlank { "（无）" }}")
            appendLine("疑似接口: ${endpoints.joinToString(", ").ifBlank { "（无明显 API 路径，可抓取 JS 深入分析）" }}")
            appendLine("外链(${links.size}): ${links.take(12).joinToString(", ").ifBlank { "（无）" }}")
            appendLine("提示：如需分析更深层逻辑，可用 web_fetch 抓取上表列出的关键 JS 或接口后再让 AI 解析。")
        }.trim()
    }

    // ---------- 新增增强工具 ----------

    /**
     * 生成供 UI 展示的工具名到 emoji 的映射（dispatcher 无关，仅帮助 UI 渲染）。
     */
    fun toolIcon(tool: String): String = when (tool.lowercase()) {
        "web_search", "search" -> "🔍"
        "web_fetch", "fetch_url" -> "🌐"
        "read_file" -> "📖"
        "write_file" -> "✍️"
        "patch_file" -> "🩹"
        "delete_file" -> "🗑️"
        "file_tree", "list_files" -> "📂"
        "search_code" -> "🔎"
        "list_projects" -> "🗂️"
        "create_project" -> "🆕"
        "build" -> "🛠️"
        "run_command", "shell", "terminal", "exec" -> "💻"
        "run_skill", "list_skills" -> "🧩"
        "mcp_call_tool", "mcp_list_tools", "mcp_servers" -> "🔌"
        "repo_info", "repo_contents", "repo_file", "repo_branches" -> "🐙"
        "git_status", "git_diff", "git_branch", "git_commit", "git_push" -> "🌿"
        "export_apk", "export_file", "package_source" -> "📦"
        "install_packages", "pkg_install" -> "⬇️"
        "download_file", "download" -> "⬇️"
        "toolchain_status", "toolchain_doctor", "toolchain_install", "toolchain_install_full" -> "🧰"
        "shizuku_exec", "shizuku" -> "🛡️"
        "scan_problems", "problems" -> "🚩"
        "memory_read", "memory_write", "memory_append" -> "🧠"
        "reverse_memory_read", "reverse_memory_write", "reverse_memory_append",
        "rev_memory_read", "rev_memory_write", "rev_memory_append" -> "🧠"
        "lessons_record", "lessons_list" -> "📚"
        "project_summary" -> "🧭"
        "apk_info", "apk_unzip" -> "📱"
        "page_analyze" -> "🧭"
        "local_vision", "local_image", "local_recognize" -> "👁️"
        "local_ai_status", "localai_status" -> "🧪"
        "local_ai_install", "localai_install" -> "🧠"
        else -> "🛠️"
    }

    private fun scanProblems(project: Project): String {
        val result = mutableListOf<Pair<String, Int>>()
        val re = Regex("\\b(TODO|FIXME|HACK|XXX|BUG)\\b[:：]?\\s*(.*)")
        val skipDirs = setOf("build", ".gradle", ".git", "node_modules", ".dart_tool", "gradle", ".studio")
        val exts = setOf("kt", "java", "xml", "py", "dart", "js", "ts", "gradle", "kts", "md", "yaml", "yml")
        fun walk(dir: java.io.File) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) {
                    if (child.name in skipDirs) continue
                    walk(child)
                } else {
                    val ext = child.extension.lowercase()
                    if (ext !in exts) continue
                    if (child.length() > 1024 * 1024) continue
                    val lines = runCatching { child.readText(Charsets.UTF_8).lines() }.getOrDefault(emptyList())
                    lines.forEachIndexed { i, line ->
                        val m = re.find(line)
                        if (m != null) result.add(Pair(child.name, i + 1))
                    }
                }
            }
        }
        walk(project.path)
        if (result.isEmpty()) return "(未发现 TODO/FIXME/HACK/XXX/BUG 待办标记)"
        val sb = StringBuilder()
        result.take(200).forEach { (f, line) -> sb.append("$f:$line\n") }
        return sb.toString().trim()
    }

    private fun memoryFile(project: Project): java.io.File {
        val f = java.io.File(java.io.File(project.path, ".studio"), "memory.json")
        runCatching {
            val gi = java.io.File(project.path, ".gitignore")
            if (gi.isFile && !gi.readText().contains(".studio")) gi.appendText("\n.studio/\n")
        }
        return f
    }

    private fun readMemory(project: Project): String =
        runCatching { val f = memoryFile(project); if (f.isFile) f.readText() else "" }.getOrDefault("")

    private fun writeMemory(project: Project, text: String): Boolean = runCatching {
        val f = memoryFile(project)
        f.parentFile?.mkdirs()
        f.writeText(text)
        true
    }.getOrDefault(false)

    /** 逆向记忆分区文件：.studio/reverse/memory.json（与普通项目记忆隔离）。 */
    private fun reverseMemoryFile(project: Project): java.io.File =
        java.io.File(java.io.File(java.io.File(project.path, ".studio"), "reverse"), "memory.json")

    private fun readRevMemory(project: Project): String =
        runCatching { val f = reverseMemoryFile(project); if (f.isFile) f.readText() else "" }.getOrDefault("")

    private fun writeRevMemory(project: Project, text: String): Boolean = runCatching {
        val f = reverseMemoryFile(project)
        f.parentFile?.mkdirs()
        f.writeText(text)
        true
    }.getOrDefault(false)

    /** 生成两段文本之间最朴素的统一 diff（行级，仅用于展示预览）。 */
    private fun makeDiff(rel: String, oldText: String, newText: String): String {
        val sb = StringBuilder()
        sb.appendLine("--- a/$rel")
        sb.appendLine("+++ b/$rel")
        val oldLines = oldText.split("\n")
        val newLines = newText.split("\n")
        // 朴素行 diff：等长位置比较
        val maxLen = maxOf(oldLines.size, newLines.size)
        var changed = 0
        for (i in 0 until maxLen) {
            val o = oldLines.getOrNull(i)
            val n = newLines.getOrNull(i)
            if (o == n) continue
            changed++
            if (changed > 60) { sb.appendLine("... (差异较多，已截断)"); break }
            if (o != null) sb.appendLine("- $o")
            if (n != null) sb.appendLine("+ $n")
        }
        if (changed == 0) return "(内容无变化)"
        return sb.toString().trim()
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

    private fun skipDirName(name: String): Boolean =
        name in setOf("build", ".gradle", ".git", ".studio", "gradle", "node_modules", ".dart_tool", ".idea", "cxx", ".cxx")

    private fun batchReplace(p: Project, args: JSONObject): String {
        val dirArg = args.optString("path").ifBlank { "." }
        val dir = workspace.resolveSafe(p, dirArg) ?: return "ERROR: 目录路径非法: $dirArg"
        if (!dir.isDirectory) return "ERROR: 不是目录: $dirArg"
        val find = args.optString("find")
        if (find.isBlank()) return "ERROR: find 不能为空"
        val replace = args.optString("replace")
        val extsArg = args.optJSONArray("exts")
        val exts = mutableSetOf<String>()
        extsArg?.let { for (i in 0 until it.length()) exts.add(it.optString(i).trim().lowercase().removePrefix(".")) }
        val changed = StringBuilder()
        var count = 0
        dir.walkTopDown()
            .onEnter { d -> !skipDirName(d.name) }
            .filter { it.isFile && (exts.isEmpty() || it.extension.lowercase() in exts) }
            .take(400)
            .forEach { f ->
                if (count >= 60) return@forEach
                val rel = f.relativeTo(p.path).path.replace(File.separatorChar, '/')
                val txt = runCatching { f.readText() }.getOrNull() ?: return@forEach
                if (!txt.contains(find)) return@forEach
                val newText = txt.replace(find, replace)
                if (workspace.writeSafe(p, rel, newText)) {
                    count++
                    changed.append(rel).append('\n')
                }
            }
        return if (count == 0) "（未发现匹配文件）" else "OK 批量替换 $count 个文件：\n$changed"
    }

    private fun readDoc(p: Project, args: JSONObject): String {
        val rel = args.optString("path")
        val f = workspace.resolveSafe(p, rel) ?: return "ERROR: 路径非法或不存在: $rel"
        if (f.isDirectory) return "ERROR: 是目录，请提供文件"
        if (f.length() > 400_000) return "ERROR: 文件过大（${f.length()}B）"
        val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return "ERROR: 读取失败（可能非文本文件）"
        return "---- $rel (${text.length} chars) ----\n" + text.take(24000)
    }

    private fun zipFile(p: Project, args: JSONObject): java.io.File? {
        val rel = args.optString("path").ifBlank { return null }
        val f = workspace.resolveSafe(p, rel) ?: return null
        return if (f.isFile) f else null
    }

    private fun readZipList(p: Project, args: JSONObject): String {
        val f = zipFile(p, args) ?: return "ERROR: 压缩包不存在或路径非法"
        return runCatching {
            java.util.zip.ZipFile(f).use { zf ->
                val entries = zf.entries().asSequence().toList()
                val sb = StringBuilder("${entries.size} 个条目：\n")
                entries.take(200).forEach { e -> sb.append(if (e.isDirectory) "[D] " else "[F] ").append(e.name).append(if (e.isDirectory) "/" else " (${e.size}B)").append('\n') }
                if (entries.size > 200) sb.append("…（仅显示前 200 条）\n")
                sb.toString().trim()
            }
        }.getOrElse { "ERROR: 无法打开 zip: ${it.message}" }
    }

    private fun readZipEntry(p: Project, args: JSONObject): String {
        val f = zipFile(p, args) ?: return "ERROR: 压缩包不存在或路径非法"
        val want = args.optString("entry")
        if (want.isBlank()) return "ERROR: entry 不能为空"
        return runCatching {
            java.util.zip.ZipFile(f).use { zf ->
                val e = zf.getEntry(want) ?: return "ERROR: zip 中不存在条目 $want"
                if (e.isDirectory) return "ERROR: 是目录条目"
                if (e.size > 400_000) return "ERROR: 条目过大（${e.size}B）"
                val text = zf.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) }
                "---- ${want} (${text.length} chars) ----\n" + text.take(24000)
            }
        }.getOrElse { "ERROR: ${it.message}" }
    }

    private fun dependencyTarget(p: Project): Pair<String, String> {
        val gradle = File(p.path, "app/build.gradle")
        val gradleKts = File(p.path, "app/build.gradle.kts")
        val pub = File(p.path, "pubspec.yaml")
        return when {
            pub.isFile -> "flutter" to "pubspec.yaml"
            gradleKts.isFile -> "gradle" to "app/build.gradle.kts"
            gradle.isFile -> "gradle" to "app/build.gradle"
            else -> "other" to ""
        }
    }

    private fun dependencyScan(p: Project): String {
        val (kind, rel) = dependencyTarget(p)
        val sb = StringBuilder("项目类型：$kind\n")
        if (kind == "flutter") {
            val txt = File(p.path, rel).readText()
            val deps = txt.lines().dropWhile { it.trim() != "dependencies:" }
            deps.takeWhile { it.isNotBlank() && !it.trim().startsWith("dev_dependencies:") }
                .filter { it.contains(':') && it.trim().startsWith("-") || (it.contains(':') && !it.trim().startsWith('#')) }
                .forEach { if (it.contains(':')) sb.appendLine("  ${it.trim().substringBefore('#').trim()}") }
            sb.appendLine("提示：可用 dependency_add {\"artifact\":\"pkg\",\"version\":\"^1.0.0\"} 添加；dependency_lock 固定精确版本。")
        } else if (kind == "gradle") {
            val lines = File(p.path, rel).readLines()
            lines.filter { l ->
                val t = l.trim()
                t.startsWith("implementation") || t.startsWith("api ") || t.startsWith("kapt") || t.startsWith("annotationProcessor") || t.startsWith("classpath")
            }.forEach { sb.appendLine("  ").append(it.trim()) }
            if (lines.none { it.contains("implementation") }) sb.appendLine("（未在 $rel 发现依赖；若在根 build.gradle，请让 AI 先 read_file 查看）")
            sb.appendLine("提示：用 dependency_add 添加；构建冲突时用 dependency_lock 切换到兼容版本。")
        } else sb.appendLine("当前项目不含 gradle/flutter 依赖清单；可检查其它配置文件（read_file）。")
        return sb.toString().trim()
    }

    private fun dependencyAdd(p: Project, args: JSONObject): String {
        val artifact = args.optString("artifact").ifBlank { args.optString("name") }
        if (artifact.isBlank()) return "ERROR: artifact 参数不能为空"
        val version = args.optString("version").ifBlank { "" }
        val (kind, rel) = dependencyTarget(p)
        val f = workspace.resolveSafe(p, rel) ?: return "ERROR: 找不到依赖清单 $rel"
        val txt = f.readText()
        return if (kind == "flutter") {
            val indent = if (txt.contains("^\\s{2}".toRegex())) "  " else "  "
            val line = "$indent$artifact: ${if (version.isBlank()) "any" else version}"
            if (txt.contains("\n$indent$artifact:")) "ALREADY: 依赖 $artifact 已存在，可用 dependency_lock 调整版本"
            else {
                val idx = txt.lines().indexOfFirst { it.trim() == "dependencies:" }
                if (idx < 0) "ERROR: pubspec.yaml 缺少 dependencies: 段"
                else {
                    val lines = txt.lines().toMutableList()
                    lines.add(idx + 1, line)
                    if (workspace.writeSafe(p, rel, lines.joinToString("\n"))) "OK 已添加依赖 $artifact（flutter）；运行 build 验证解析"
                    else "ERROR: 写入失败"
                }
            }
        } else if (kind == "gradle") {
            val prefix = if (rel.endsWith(".kts")) "implementation(\"$artifact:${if (version.isBlank()) "latest.release" else version}\")" else "implementation '$artifact:${if (version.isBlank()) "latest.release" else version}'"
            val m = Regex("(?s)(dependencies\\s*\\{)(.*?)(\\n\\s*})").find(txt)
            if (m == null) "OK 未找到依赖块，请先 read_file $rel 后由模型选择合适位置写入（避免盲目拼接）。"
            else {
                val whole = m.value
                val cut = whole.lastIndexOf('}')
                val newWhole = whole.substring(0, cut) + "\n    $prefix" + whole.substring(cut)
                val newText = txt.replaceFirst(whole, newWhole)
                if (workspace.writeSafe(p, rel, newText)) "OK 已添加依赖 $artifact（$rel）；请调用 build 验证解析，失败则由模型读日志调整版本"
                else "ERROR: 写入失败"
            }
        } else "ERROR: 不支持自动添加依赖的项目类型（$kind）"
    }

    private fun dependencyLock(p: Project, args: JSONObject): String {
        val artifact = args.optString("artifact").ifBlank { return "ERROR: artifact 参数不能为空" }
        val version = args.optString("version").ifBlank { return "ERROR: version 参数不能为空" }
        val (kind, rel) = dependencyTarget(p)
        val f = workspace.resolveSafe(p, rel) ?: return "ERROR: 找不到依赖清单"
        val txt = f.readText()
        return if (kind == "flutter") {
            val re = Regex("^([ \\t]*)(${Regex.escape(artifact)})\\s*:\\s*[^#\\n]*", RegexOption.MULTILINE)
            if (!re.containsMatchIn(txt)) "NOT_FOUND: pubspec 中没有 $artifact"
            else {
                val newText = re.replace(txt) { m -> "${m.groupValues[1]}${m.groupValues[2]}: $version" }
                if (workspace.writeSafe(p, rel, newText)) "OK 已将 $artifact 固定为 $version（精确锁定，避免 ^ 浮动）"
                else "ERROR: 写入失败"
            }
        } else if (kind == "gradle") {
            val esc = Regex.escape(artifact)
            val newText = txt.replace(Regex("('$esc:[^']*'|\"$esc:[^\"]*\")")) { "\"$artifact:$version\"" }
            val ok = newText != txt && workspace.writeSafe(p, rel, newText)
            if (ok) "OK 已把 $artifact 固定为 $version。若与其它依赖冲突，模型应先扫描本地缓存版本（run_command: ls ~/.gradle/caches/modules-2/files-2.1）再切换到兼容版本。"
            else "NOT_FOUND: $rel 中未发现 $artifact"
        } else "ERROR: 不支持项目类型"
    }

    fun runProtocol(project: Project, text: String): String {
        val out = StringBuilder()
        AgentProtocol.parseTools(text).forEach { tc ->
            out.append("[TOOL] ").append(tc.name).append('\n')
            out.append(execute(project, tc.name, tc.args)).append("\n\n")
        }
        return out.toString().trim()
    }

    /** 列出可用的项目模板（无需项目上下文）。 */
    private fun listTemplates(): String {
        return buildString {
            appendLine("可用项目模板（ProjectTemplate）：")
            appendLine("- android —— Android 项目（kotlin + xml + 构建脚本 + gradlew launcher）")
            appendLine("- compose —— Android + Jetpack Compose 项目（compose-bom + material3 + activity-compose）")
            appendLine("- flutter —— Flutter 应用骨架（settings.gradle + main.dart）")
            appendLine("- web —— 静态 Web 项目（index.html + styles.css + app.js，无需构建，可直接预览/导出）")
            appendLine("- reactnative —— React Native 项目骨架（package.json + App.js + index.js）")
            appendLine("- embedded, embeddedc, 嵌入式c —— C 嵌入式工程（Makefile + main.c + 简单说明）")
            appendLine("- linux, linuxc, linuxtool, clitool, c工具 —— Linux 命令行 C 工具（Makefile + main.c）")
            appendLine("- xposed, xposedmodule, lsposed, lsposedmodule —— Xposed/LSPosed 模块（纯 Java：manifest 模块声明 + xposed_init + HookMain 入口，需 app/libs/XposedBridgeApi-82.jar 才能编译）")
            appendLine("- 空项目 / 其它 —— 空目录骨架（README + .gitkeep）")
            appendLine("创建方式：create_project {name, type, goal}（type 见上；goal 可附加实现说明）。")
        }
    }

    /** GitHub 仓库上下文（供 AgentRuntime 启动时自动注入；非 GitHub/未配置时返回空串）。 */
    fun repoContextFor(project: Project): String = repoContext(project)

    // ---------- v6.1 项目体检 / 经验学习 helpers ----------

    private fun lessonsFile(project: Project?): java.io.File {
        val base = project?.path?.let { java.io.File(it, ".studio") } ?: java.io.File(workspace.rootDir(), ".studio")
        base.mkdirs()
        return java.io.File(base, "lessons.md")
    }

    /** 读取项目经验库 lessons.md（供 AI 学习历史修复经验）。 */
    /** 读取逆向记忆分区（供 AgentRuntime 在逆向模式下注入系统提示）。 */
    fun dispatcherReadRevMemory(project: Project?): String {
        if (project == null || project.name.equals("__none__", true)) return ""
        return readRevMemory(project)
    }

    fun readLessons(project: Project?, maxChars: Int = 6000): String {
        val f = lessonsFile(project)
        if (!f.isFile) return ""
        val text = runCatching { f.readText().trim() }.getOrDefault("")
        if (text.isBlank()) return ""
        return if (text.length <= maxChars) text else text.take(maxChars) + "\n…（经验库较长，已截断）"
    }

    /** lessons_list：列出当前项目沉淀的经验。 */
    private fun lessonsList(project: Project?): String {
        if (project == null) return "ERROR: lessons_list 需要先打开一个项目（可先用 list_projects 查看）"
        val text = readLessons(project)
        return if (text.isBlank()) "(经验库为空。解决过重要问题时，用 lessons_record {problem, lesson} 沉淀经验，后续任务会自动参考。)"
        else "【项目经验库 lessons.md】\n$text"
    }

    /** lessons_record：把一次问题的解决方案写入项目经验库（去重：同问题不重复追加）。 */
    private fun lessonsRecord(project: Project?, args: org.json.JSONObject): String {
        if (project == null) return "ERROR: lessons_record 需要项目上下文"
        val problem = args.optString("problem").trim()
        val lesson = args.optString("lesson").trim().ifBlank { args.optString("solution").trim() }
        if (problem.isBlank() || lesson.isBlank()) return "ERROR: 需要 problem 与 lesson（或 solution）参数"
        val f = lessonsFile(project)
        val existing = if (f.isFile) runCatching { f.readText() }.getOrDefault("") else ""
        val key = problem.take(40)
        // 简单去重：若库中已出现同题前 40 字，则不重复追加
        if (existing.contains(key, ignoreCase = true)) {
            return "OK 该经验已存在，跳过重复记录（可用 lessons_list 查看）"
        }
        val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val entry = "\n### [$ts] $problem\n经验：$lesson\n"
        val newText = (existing + entry).lines().filter { it.isNotBlank() }.joinToString("\n").trim() + "\n"
        val capped = newText.split("\n").takeLast(400).joinToString("\n") + "\n"
        f.writeText(capped)
        return "OK 已记录经验（库内共 ${capped.lines().size} 行）。此经验将在后续任务自动注入参考。"
    }

    /** project_summary：快速体检项目，避免 AI 盲目全量浏览。 */
    private fun projectSummary(project: Project?): String {
        val root = project?.path ?: return "ERROR: project_summary 需要项目上下文（先 create_project / 打开项目）"
        if (!root.isDirectory) return "ERROR: 项目目录不存在 ${root.path}"
        val type = project.type
        val hasApk = workspace.findLatestApk(project)?.let { it.name } ?: "（尚无 APK）"
        val gradleFiles = root.walkTopDown()
            .onEnter { d -> d == root || d.name !in com.example.myempty.githubk.core.WorkspaceManager.SKIP_DIRS }
            .filter { it.isFile && (it.name == "build.gradle" || it.name == "settings.gradle" || it.name == "build.gradle.kts" || it.name == "settings.gradle.kts" || it.name == "pom.xml" || it.name == "package.json" || it.name == "pubspec.yaml" || it.name == "CMakeLists.txt" || it.name == "Makefile") }
            .take(20)
            .map { it.relativeTo(root).path }
            .sorted()
        val src = com.example.myempty.githubk.core.WorkspaceManager.SOURCE_EXT
        val byExt = root.walkTopDown()
            .onEnter { d -> d == root || d.name !in com.example.myempty.githubk.core.WorkspaceManager.SKIP_DIRS }
            .filter { it.isFile && it.extension.lowercase() in src }
            .groupingBy { it.extension.lowercase() }
            .eachCount()
            .entries.sortedByDescending { it.value }
        val dirs = root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted().orEmpty()
        return buildString {
            appendLine("【项目体检】${project.name}  type=${type}  path=${project.path}")
            appendLine("最近 APK：$hasApk")
            appendLine("顶层目录：${dirs.joinToString(", ")}")
            appendLine("构建入口：${gradleFiles.joinToString(", ").ifBlank { "（未识别）" }}")
            appendLine("源码统计：${byExt.joinToString(", ") { "${it.key}×${it.value}" }.ifBlank { "（无源码）" }}")
            appendLine("建议下一步：先 file_tree 看整体，再 read_file 关键入口；有报错优先 lessons_list 参考经验。")
        }
    }

    /** 列出可用的项目模板（无需项目上下文）。 */
    fun applyToolText(project: Project, text: String): Int = workspace.applyAiChanges(text)

    // ---------- v6.3 图像 / 外部库 / Frida-Proot 编排 helpers ----------

    /** import_lib：把外部库（jar/aar/zip 源码包/远程 URL/本地副本）导入项目并尽量接入构建。 */
    private fun importLib(p: Project, args: JSONObject): String {
        val src = args.optString("path").ifBlank { args.optString("url") }
        if (src.isBlank()) return "ERROR: import_lib 需要 path（本地 jar/aar 路径）或 url（远程下载地址）"
        val name = args.optString("name").ifBlank { src.substringAfterLast('/').substringAfterLast('\\').ifBlank { "lib" } }
        // 目标：优先 Android app/libs
        val libsDir = when {
            File(p.path, "app/libs").isDirectory -> File(p.path, "app/libs")
            else -> File(p.path, "libs").apply { mkdirs() }
        }
        libsDir.mkdirs()
        val dest = File(libsDir, name)
        val ok = if (src.startsWith("http")) {
            runCatching {
                val conn = java.net.URL(src).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 30000; conn.readTimeout = 60000
                conn.inputStream.use { inp -> dest.outputStream().use { out -> inp.copyTo(out) } }
                true
            }.getOrDefault(false)
        } else {
            // 本地路径：支持绝对路径或项目内相对路径
            val candidates = listOf(File(src), File(p.path, src), File(File(p.path, ".studio/attachments"), src.substringAfterLast('/')))
            val found = candidates.firstOrNull { it.isFile } ?: return "ERROR: 本地库文件不存在 $src"
            runCatching { found.copyTo(dest, overwrite = true); true }.getOrDefault(false)
        }
        if (!ok || !dest.exists()) return "ERROR: 导入外部库失败 $src"
        val ext = dest.extension.lowercase()
        // 尝试接入 gradle 依赖
        val wire = wireLibraryIntoGradle(p, name, ext, args.optString("group"))
        return "OK 已导入外部库到 ${libsDir.absolutePath}（${dest.name}，${dest.length() / 1024} KB）\n$wire"
    }

    /** 把导入的 jar/aar 写入 gradle 依赖块（尽力而为，人工可改）。 */
    private fun wireLibraryIntoGradle(p: Project, name: String, ext: String, group: String): String {
        val (kind, rel) = dependencyTarget(p)
        if (kind != "gradle") return "提示：当前项目非 gradle 工程，请手动在构建脚本中引用 ${libsRefLine(p, name, ext, group)}。"
        val f = workspace.resolveSafe(p, rel) ?: return "提示：找不到依赖清单 $rel，请手动引用 ${libsRefLine(p, name, ext, group)}。"
        val txt = f.readText()
        val line = libsRefLine(p, name, ext, group)
        val single = line
        val byName = if (ext == "aar") "implementation(name: '$name', ext: 'aar')" else "implementation files('libs/$name')"
        val expr = when {
            ext == "aar" -> byName
            line.startsWith("implementation files") -> line
            else -> "implementation '$group:$name'"
        }
        if (txt.contains(expr) || txt.contains(name)) return "提示：$rel 中已存在与 $name 相关的依赖，避免重复添加。"
        val m = Regex("(?s)(dependencies\\s*\\{)(.*?)(\\n\\s*})").find(txt)
            ?: return "提示：请在 $rel 的 dependencies 块手动加入：$expr"
        val whole = m.value
        val cut = whole.lastIndexOf('}')
        val newWhole = whole.substring(0, cut) + "\n    $expr" + whole.substring(cut)
        val newText = txt.replaceFirst(whole, newWhole)
        return if (workspace.writeSafe(p, rel, newText)) "OK 已自动接入 $rel 依赖：$expr（若为 aar 请在 app/build.gradle 视需要开启 flatDir 或由模型调整）。"
        else "提示：写入 $rel 失败，请手动加入：$expr"
    }

    private fun libsRefLine(p: Project, name: String, ext: String, group: String): String =
        if (ext == "aar") "implementation(name: '$name', ext: 'aar')"
        else "implementation files('libs/${name}')"

    /** frida_gen：生成一份可直接使用的 Frida hooks.js 与注入说明，落到 .studio/reverse/frida/。 */
    private fun fridaGen(project: Project, args: JSONObject): String {
        val pkg = args.optString("package").ifBlank { args.optString("pkg") }
        val need = args.optString("need").ifBlank { "加密函数 Hook / 网络拦截 / 调用栈捕获" }
        val hooks = buildString {
            appendLine("/*")
            appendLine(" * hooks.js — 由 GitHubK Studio AI 逆向工作台生成")
            appendLine(" * 目标包名: ${pkg.ifBlank { "(未指定，请填写)" }}")
            appendLine(" * 用途: $need")
            appendLine(" * 注入: frida -U -f <包名> -l hooks.js")
            appendLine(" */")
            appendLine("Java.perform(function () {")
            appendLine("    console.log('[Frida] hooks.js loaded, target=' + (Java.use('android.app.Application').androidGetApplicationContext().getPackageName()));")
            appendLine("")
            appendLine("    // 1) Hook 网络请求（OkHttp 拦截）")
            appendLine("    try {")
            appendLine("        var OkHttpClient = Java.use('okhttp3.OkHttpClient');")
            appendLine("        var Builder = Java.use('okhttp3.OkHttpClient\$Builder');")
            appendLine("        console.log('[Frida] OkHttpClient found, ready to addInterceptor');")
            appendLine("    } catch (e) { console.log('[Frida] no okhttp3: ' + e); }")
            appendLine("")
            appendLine("    // 2) Hook console / Log 输出")
            appendLine("    try {")
            appendLine("        var Log = Java.use('android.util.Log');")
            appendLine("        var orig = Log.d.overload('java.lang.String', 'java.lang.String');")
            appendLine("        orig.implementation = function (tag, msg) {")
            appendLine("            console.log('[Log.d] ' + tag + ': ' + msg);")
            appendLine("            return orig.call(this, tag, msg);")
            appendLine("        };")
            appendLine("    } catch (e) { console.log('[Frida] no Log: ' + e); }")
            appendLine("")
            appendLine("    // 3) 主动调用示例：遍历所有 Activity")
            appendLine("    try {")
            appendLine("        Java.enumerateLoadedClasses({")
            appendLine("            onMatch: function (name) { if (name.indexOf('Activity') >= 0) { /* console.log(name); */ } },")
            appendLine("            onComplete: function () { console.log('[Frida] loadClass scan done'); }")
            appendLine("        });")
            appendLine("    } catch (e) { console.log('[Frida] enumerate err: ' + e); }")
            appendLine("});")
        }
        val reverseRoot = File(File(project.path, ".studio"), "reverse/frida").apply { mkdirs() }
        val f = File(reverseRoot, "hooks.js")
        f.writeText(hooks)
        return buildString {
            appendLine("OK 已生成 Frida 脚本：${f.absolutePath}")
            appendLine("注入命令：frida -U -f ${pkg.ifBlank { "<包名>" }} -l ${f.absolutePath}")
            if (pkg.isNotBlank()) {
                appendLine("Shizuku/免Root 说明：若设备可通过 Shizuku 运行 frida-server（或已 root），用 adb push 到 /data/local/tmp 后 chmod 755 再启动，或用 frida-server 作为 uid 运行。")
            } else appendLine("提示：请在参数中补充 package=<目标包名> 以便注入，并可根据 need 描述调整 hook 目标。")
            appendLine("后续可调用 proot_orchestrate 生成 proot/frida 编排命令。")
        }
    }

    /** proot_orchestrate：生成 proot 环境下的 frida-server/工具编排命令（免Root思路）。 */
    private fun prootOrchestrate(project: Project, args: JSONObject): String {
        val pkg = args.optString("package").ifBlank { args.optString("pkg") }
        val mode = args.optString("mode").ifBlank { "frida" }
        val out = buildString {
            appendLine("【proot 编排 · $mode】")
            when (mode.lowercase()) {
                "frida" -> {
                    appendLine("1) 下载合适的 frida-server：")
                    appendLine("   python3 -c \"import frida\" 或用 pip/frida 工具获取；Android 需对应 ABI(arm64-v8a)。")
                    appendLine("2) 放入 proot 家目录并运行：")
                    appendLine("   cp frida-server ~/frida-server && chmod 755 ~/frida-server")
                    appendLine("   ~/frida-server -D &   # 或监听 /data/local/tmp 需要 root；proot 内用用户态指纹采集/注入")
                    appendLine("3) 在具备 Shizuku 权限的宿主侧：")
                    appendLine("   adb forward tcp:27042 tcp:27042")
                    appendLine("   frida -H 127.0.0.1:27042 -f ${pkg.ifBlank { "<包名>" }} -l ${File(File(project.path, ".studio"), "reverse/frida/hooks.js").absolutePath}")
                    appendLine("4) 若缺少 frida 工具，先 web_search 官方文档安装，再继续。")
                }
                "proot", "env" -> {
                    appendLine("1) 项目在 proot 中运行，工具链可从 toolchain_status 查看。")
                    appendLine("2) 若需在 proot 内安装工具：install_packages / apt-get install -y <pkg>。")
                    appendLine("3) 编排脚本可写入项目 .studio/reverse/proot.sh 再 run_command 执行。")
                }
                else -> appendLine("未知 mode：$mode（可选 frida / proot / env）")
            }
            appendLine("提示：逆向需遵守合规与法律边界，仅用于自有权/授权目标分析。")
        }
        return out
    }

    // ---------- GitHub v5.1 helpers ----------

    private fun ghUnavailable(): String = "ERROR: GitHub 未配置 Token，请先在工作区「GitHub 仓库」或设置页填写 Personal Access Token"

    private fun ghErr(r: com.example.myempty.githubk.git.GitHubOpResult): String {
        val t = com.example.myempty.githubk.git.gitHubErrorText(r, r.message)
        return if (t.isNotBlank()) t else (r.message.ifBlank { "操作失败" })
    }

    private fun repoOwner(repo: String): String = repo.trim('/').substringBefore('/')

    private fun repoName(repo: String): String {
        val c = repo.trim('/')
        val after = c.substringAfter('/', "")
        return after.substringBefore('/')
    }

    private fun releaseLine(o: JSONObject): String {
        val tag = o.optString("tag_name")
        val id = o.optLong("id")
        val name = o.optString("name").ifBlank { tag }
        val flag = (if (o.optBoolean("draft")) " [草稿]" else "") + (if (o.optBoolean("prerelease")) " [预发布]" else "")
        val sb = StringBuilder("Release #$id $name [$tag]$flag — ${o.optString("html_url")}")
        val assets = o.optJSONArray("assets")
        if (assets != null && assets.length() > 0) {
            for (j in 0 until assets.length()) {
                val a = assets.optJSONObject(j) ?: continue
                sb.append("\n   资产: ").append(a.optString("name")).append(" (").append(a.optString("browser_download_url")).append(")")
            }
        }
        return sb.toString()
    }

    /** 解析 git remote 得到 owner/repo；非 GitHub 返回 null。 */
    private fun parseRemoteToRepo(url: String): String? {
        val u = url.trim()
        if (u.isBlank()) return null
        val m = Regex("(?:https?://[^/]+/|git@[^:]+:|ssh://[^/]+/)([^\\s]+?)(?:\\.git)?$").find(u) ?: return null
        val path = m.groupValues[1].trim('/')
        return if (path.contains('/') && path.split('/').size >= 2) path.substringBeforeLast('/') + "/" + path.substringAfterLast('/') else null
    }

    /**
     * GitHub 仓库上下文自动加载：当项目 remote 指向 GitHub 且已配置 Token 时，
     * 拉取仓库元信息、README、Open Issues、最近 Releases 作为 Agent 上下文。
     */
    private fun repoContext(project: Project): String {
        if (gitHub == null) return ""
        if (project.name == "__none__" || project.path.absolutePath == "/dev/null") return ""
        val url = git.remoteUrl(project.path)
        if (url.isBlank()) return ""
        val repo = parseRemoteToRepo(url) ?: return ""
        return try {
            val info = gitHub.repoInfo(repo) ?: return ""
            val sb = StringBuilder()
            sb.appendLine("【GitHub 仓库上下文 $repo】")
            sb.appendLine("描述: ${info.description ?: ""} | 默认分支: ${info.defaultBranch} | ★${info.stars} fork ${info.forks} | ${info.htmlUrl}")
            val readme = gitHub.readme(repo, info.defaultBranch)
            if (readme.isNotBlank()) sb.appendLine("README(节选):\n${readme.take(1200)}")
            val issues = gitHub.issues(repo, "open")
            if (issues.isNotEmpty()) sb.appendLine("Open issues(${issues.size}): " + issues.take(8).joinToString("; ") { "#${it.number} ${it.title}" })
            val rel = gitHub.releasesOp(repoOwner(repo), repoName(repo))
            if (rel.ok && rel.dataArray != null && rel.dataArray.length() > 0) {
                val arr = rel.dataArray
                val tags = (0 until arr.length()).take(3).joinToString(", ") { arr.getJSONObject(it).optString("tag_name") }
                sb.appendLine("最近 Releases: $tags")
            }
            sb.appendLine("当前任务如需推送代码，先 git_status/git_commit 提交，再 git_push；如需发布可用 release_create。")
            sb.toString()
        } catch (_: Throwable) { "" }
    }
}