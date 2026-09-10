package com.example.myempty.githubk.git

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * GitManager v2.5
 * - 统一进程执行（reader thread，避免死锁）
 * - GIT_ASKPASS / GIT_TERMINAL_PROMPT=0 保障 HTTPS 认证，Token 绝不进 URL
 * - git status 解析为 M/A/D/? 状态
 * - 可靠 push：区分 "nothing to commit" 与真正失败
 */
class GitManager(
    private val onLog: (String) -> Unit = {},
    private val token: () -> String? = { null }
) {
    data class GitResult(val ok: Boolean, val output: String, val code: Int, val command: String)

    private fun askpassFile(): File? {
        val tk = token() ?: return null
        if (tk.isBlank()) return null
        return try {
            val f = File.createTempFile("ghk_askpass_", ".sh")
            f.writeText(
                "#!/system/bin/sh\n" +
                    "case \"\$1\" in\n" +
                    "  *[Uu]sername*) echo \"x-access-token\";;\n" +
                    "  *) echo \"$tk\";;\n" +
                    "esac\n"
            )
            f.setExecutable(true)
            f
        } catch (_: Throwable) { null }
    }

    /** 统一执行 git 命令，使用 reader thread 实时读取输出（P0：避免 pipe 死锁）。 */
    fun run(vararg args: String, dir: File?, timeoutSec: Long = 300): GitResult {
        val cmd = mutableListOf("git")
        cmd.addAll(args)
        val pb = ProcessBuilder(cmd)
        pb.directory(dir)
        val ap = askpassFile()
        val env = pb.environment()
        if (ap != null) env["GIT_ASKPASS"] = ap.absolutePath
        env["GIT_TERMINAL_PROMPT"] = "0"
        env["GIT_CONFIG_NOSYSTEM"] = "1"
        env["LC_ALL"] = "C"

        return try {
            val proc = pb.start()
            val sb = StringBuilder()
            val reader = Thread {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        sb.append(line).append('\n')
                        onLog(line)
                    }
                }
            }
            reader.start()
            val done = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            reader.join(2000)
            ap?.delete()
            val output = sb.toString()
            val success = done && proc.exitValue() == 0
            GitResult(success, output, if (done) proc.exitValue() else -1, args.joinToString(" "))
        } catch (e: Exception) {
            ap?.delete()
            GitResult(false, e.message ?: "error", -1, args.joinToString(" "))
        }
    }

    fun clone(url: String, dir: File, branch: String? = null): GitResult {
        dir.parentFile?.mkdirs()
        val args = mutableListOf("clone", "--progress")
        if (branch != null) { args.add("-b"); args.add(branch) }
        args.add(url)
        args.add(dir.absolutePath)
        return run(*args.toTypedArray(), dir = dir.parentFile, timeoutSec = 600)
    }

    fun pull(dir: File): GitResult = run("pull", "--rebase", "--autostash", dir = dir, timeoutSec = 600)

    fun push(dir: File): GitResult = run("push", "origin", "HEAD", dir = dir, timeoutSec = 600)

    fun addAll(dir: File): GitResult = run("add", "-A", dir = dir)

    fun commit(dir: File, msg: String): GitResult = run("commit", "-m", msg, dir = dir)

    fun commitAll(dir: File, msg: String): GitResult {
        addAll(dir)
        val status = status(dir)
        if (status.isEmpty()) return GitResult(true, "nothing to commit", 0, "commitAll")
        return commit(dir, msg)
    }

    fun log(dir: File, n: Int = 20): GitResult = run("log", "--oneline", "-$n", dir = dir)

    fun branch(dir: File): GitResult = run("branch", "-a", dir = dir)

    fun remoteUrl(dir: File): String = run("remote", "get-url", "origin", dir = dir).output.trim()

    fun diff(dir: File, showUntracked: Boolean = true): String {
        val tracked = run("diff", "--color=never", dir = dir).output
        val untracked = if (showUntracked) {
            run("status", "--porcelain", dir = dir).output.lines()
                .filter { it.startsWith("??") }
                .joinToString("") { line ->
                    val name = line.drop(3)
                    val f = File(dir, name)
                    "\n--- $name (untracked) ---\n" + (if (f.isFile) f.readText() else "")
                }
        } else ""
        return tracked + untracked
    }

    /** 解析 porcelain 状态为 ordered list of (path, statusChar)。 */
    fun status(dir: File): List<Pair<String, String>> {
        val out = run("status", "--porcelain", dir = dir).output
        return out.lines().filter { it.isNotBlank() }.map { line ->
            val st = line.take(2).trim()
            val path = line.drop(3)
            path to statusChar(st)
        }
    }

    private fun statusChar(st: String): String = when {
        st == "??" -> "?"
        st.contains("A") -> "A"
        st.contains("M") || st.contains("R") || st.contains("C") -> "M"
        st.contains("D") -> "D"
        st.contains("U") -> "U"
        else -> st
    }

    fun isRepo(dir: File): Boolean = File(dir, ".git").exists()

    fun init(dir: File): GitResult = run("init", dir = dir)

    // ---------- 分支管理（v5.1 Agent 全量仓库操作） ----------

    /** 当前分支名（失败时回退 main）。 */
    fun currentBranch(dir: File): String = run("rev-parse", "--abbrev-ref", "HEAD", dir = dir).output.trim().ifBlank { "main" }

    /** 基于当前 HEAD（或 from 分支）创建并切换到新分支。 */
    fun createBranch(dir: File, branch: String, from: String? = null): GitResult {
        val clean = branch.trim()
        if (clean.isBlank()) return GitResult(false, "分支名不能为空", 2, "createBranch")
        return if (from.isNullOrBlank()) run("checkout", "-b", clean, dir = dir)
        else run("checkout", "-b", clean, from.trim(), dir = dir)
    }

    /** 删除本地分支（-D 强制，可删未合并分支）。 */
    fun deleteBranchLocal(dir: File, branch: String): GitResult = run("branch", "-D", branch.trim(), dir = dir)

    /** 删除远程分支。 */
    fun deleteRemoteBranch(dir: File, branch: String): GitResult =
        run("push", "origin", "--delete", branch.trim(), dir = dir, timeoutSec = 600)

    /** 合并指定分支到当前分支。 */
    fun mergeBranch(dir: File, branch: String): GitResult = run("merge", "--no-edit", branch.trim(), dir = dir, timeoutSec = 600)

    /** 强制推送（--force-with-lease 比 --force 更安全，仍属高危需审批）。 */
    fun pushForce(dir: File): GitResult = run("push", "--force-with-lease", "origin", "HEAD", dir = dir, timeoutSec = 600)

    /** 拉取远端全部分支/标签并清理失效引用。 */
    fun fetchAll(dir: File): GitResult = run("fetch", "--all", "--prune", "--tags", dir = dir, timeoutSec = 600)
}