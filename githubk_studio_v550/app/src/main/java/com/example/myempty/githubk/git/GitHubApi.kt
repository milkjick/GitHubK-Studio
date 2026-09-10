package com.example.myempty.githubk.git

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * GitHubApi v2.6
 * - Trending（解析 HTML）/ Search / User repos / Create repo / Readme
 * - 仓库源码浏览：contents（目录列表）、fileContent（文件内容）
 * - 仓库管理：branches / star / unstar / fork / issues / createIssue
 * - 全部走 Authorization: Bearer Token
 */
data class GitRepo(
    val fullName: String,
    val name: String,
    val owner: String,
    val description: String,
    val stars: Int,
    val forks: Int,
    val language: String,
    val htmlUrl: String,
    val defaultBranch: String = "main",
    val private: Boolean = false,
    val updatedAt: String = "",
    // v2.9：完整项目信息
    val createdAt: String = "",
    val pushedAt: String = "",
    val watchers: Int = 0,
    val openIssues: Int = 0,
    val size: Long = 0,
    val archived: Boolean = false,
    val homepage: String = "",
    val topics: List<String> = emptyList(),
    val license: String = "",       // 如 "MIT License"
    val parentFullName: String = "" // fork 自
)

data class GitRepoEntry(
    val name: String,
    val path: String,
    val type: String, // "file" / "dir"
    val size: Long,
    val downloadUrl: String
)

data class GitBranch(val name: String, val protected: Boolean, val sha: String)

data class GitIssue(
    val number: Int,
    val title: String,
    val state: String,
    val user: String,
    val createdAt: String,
    val comments: Int,
    val body: String
)

/** GitHub HTTP 错误：携带状态码（0 表示网络 / IO / 解析类错误）。 */
class GitHubHttpException(val httpCode: Int, val raw: String) :
    RuntimeException("HTTP $httpCode: ${raw.take(300)}")

/**
 * 结构化操作结果：页面可按 httpCode 分类提示（401 / 403 / 404 / 422 / 429 等）。
 * ok=false 且 httpCode=0 表示网络 / IO 类错误，message 为底层描述。
 */
data class GitHubOpResult(
    val ok: Boolean,
    val httpCode: Int = 0,
    val message: String = "",
    val data: JSONObject? = null,
    val dataArray: JSONArray? = null
) {
    companion object {
        fun ok(data: JSONObject? = null, arr: JSONArray? = null) =
            GitHubOpResult(true, 200, "", data, arr)

        fun fail(code: Int, msg: String = "") = GitHubOpResult(false, code, msg)
    }
}

/** 将 GitHubOpResult 转成面向用户的中文提示。 */
fun gitHubErrorText(r: GitHubOpResult, fallback: String): String {
    if (r.ok) return ""
    return when (r.httpCode) {
        401 -> "401 令牌无效或已过期，请前往设置更新 GitHub Token"
        403 -> "403 权限不足：访问令牌缺少 repo 写入权限，请前往 GitHub 网站生成具有 repo 范围的 Personal Access Token"
        404 -> "404 文件或仓库不存在，请刷新后重试"
        409 -> "409 冲突：文件内容已变化，请刷新后重试"
        413 -> "413 文件过大，超出 GitHub 限制"
        422 -> "422 校验失败：标签可能已存在或提交信息不完整"
        429 -> "429 请求限流，请稍后再试"
        0 -> if (r.message.isNotBlank()) r.message else fallback
        else -> "HTTP ${r.httpCode}：${r.message.take(160)}"
    }
}

class GitHubApi(private val getToken: () -> String?) {

    private fun request(
        method: String,
        urlStr: String,
        body: String? = null,
        accept: String = "application/vnd.github+json"
    ): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            val tk = getToken()
            if (!tk.isNullOrBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $tk")
            }
            conn.setRequestProperty("Accept", accept)
            conn.setRequestProperty("User-Agent", "GitHubK-Studio/2.6")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""
            if (code !in 200..299) throw GitHubHttpException(code, text)
            return text
        } finally {
            conn.disconnect()
        }
    }

    // ---------- 热门 / 搜索 / 仓库列表 ----------

    fun trending(since: String = "daily"): List<GitRepo> {
        val html = request(
            "GET",
            "https://github.com/trending?since=$since",
            accept = "text/html"
        )
        if (!html.contains("<article")) return emptyList()
        val repos = mutableListOf<GitRepo>()
        val re = Regex("""<h2 class="h3 lh-condensed">.*?href="/([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val descRe = Regex("""<p class="col-9[^"]*">\s*(.*?)\s*</p>""", RegexOption.DOT_MATCHES_ALL)
        val starRe = Regex("""aria-label="([\d,]+) users starred""")
        val langRe = Regex("""itemprop="programmingLanguage">([^<]+)<""")
        val descs = descRe.findAll(html).map { it.groupValues[1].replace(Regex("<[^>]+>"), "").trim() }.toList()
        val langs = langRe.findAll(html).map { it.groupValues[1].trim() }.toList()
        val stars = starRe.findAll(html).map { it.groupValues[1].replace(",", "").toIntOrNull() ?: 0 }.toList()
        var i = 0
        re.findAll(html).forEach { m ->
            val fullName = m.groupValues[1].trim()
            val parts = fullName.split("/")
            if (parts.size == 2) {
                repos.add(
                    GitRepo(
                        fullName = fullName,
                        name = parts[1],
                        owner = parts[0],
                        description = descs.getOrNull(i) ?: "",
                        stars = stars.getOrNull(i) ?: 0,
                        forks = 0,
                        language = langs.getOrNull(i) ?: "",
                        htmlUrl = "https://github.com/$fullName"
                    )
                )
            }
            i++
        }
        return repos.take(25)
    }

    fun search(q: String, sort: String = "stars", order: String = "desc"): List<GitRepo> {
        val text = request("GET", "https://api.github.com/search/repositories?q=${URLEncoder.encode(q, "UTF-8")}&sort=$sort&order=$order&per_page=30")
        return parseRepos(text)
    }

    fun myRepos(): List<GitRepo> {
        val text = request("GET", "https://api.github.com/user/repos?per_page=100&sort=updated")
        return parseRepos(text)
    }

    fun userRepos(owner: String): List<GitRepo> {
        val text = request("GET", "https://api.github.com/users/${URLEncoder.encode(owner, "UTF-8")}/repos?per_page=100&sort=updated")
        return parseRepos(text)
    }

    fun repoInfo(fullName: String): GitRepo? = try {
        parseRepos("[${request("GET", "https://api.github.com/repos/$fullName")}]").firstOrNull()
    } catch (_: Throwable) { null }

    fun readme(fullName: String, branch: String? = null): String {
        val ref = if (branch != null) "?ref=${URLEncoder.encode(branch, "UTF-8")}" else ""
        return try {
            val text = request("GET", "https://api.github.com/repos/$fullName/readme$ref", accept = "application/vnd.github.raw+json")
            text.take(60000)
        } catch (_: Throwable) { "" }
    }

    fun languages(fullName: String): List<Pair<String, Int>> {
        return try {
            val text = request("GET", "https://api.github.com/repos/$fullName/languages")
            val obj = JSONObject(text)
            obj.keys().asSequence().map { it to obj.getInt(it) }.toList().sortedByDescending { it.second }
        } catch (_: Throwable) { emptyList() }
    }

    fun createRepo(name: String, desc: String, isPrivate: Boolean): String {
        val body = JSONObject()
            .put("name", name)
            .put("description", desc)
            .put("private", isPrivate)
            .put("auto_init", true)
        return request("POST", "https://api.github.com/user/repos", body.toString())
    }

    /** 将 createRepo()/repoInfo() 等接口返回的单个仓库 JSON 解析为 GitRepo，便于创建后立即使用。 */
    fun parseRepo(json: String): GitRepo? = try {
        convert(JSONArray("[$json]")).firstOrNull()
    } catch (_: Throwable) { null }

    // ---------- 源码浏览 ----------

    fun contents(fullName: String, path: String = "", branch: String? = null): List<GitRepoEntry> {
        val p = if (path.isBlank()) "" else "/${path.trim('/')}"
        val ref = if (branch != null) "?ref=${URLEncoder.encode(branch, "UTF-8")}" else ""
        val text = request("GET", "https://api.github.com/repos/$fullName/contents$p$ref")
        val arr = JSONArray(text)
        val out = mutableListOf<GitRepoEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                GitRepoEntry(
                    name = o.optString("name"),
                    path = o.optString("path"),
                    type = o.optString("type"),
                    size = o.optLong("size"),
                    downloadUrl = o.optString("download_url")
                )
            )
        }
        return out.sortedWith(compareBy({ it.type != "dir" }, { it.name.lowercase() }))
    }

    fun fileContent(fullName: String, path: String, branch: String? = null): String {
        val ref = if (branch != null) "?ref=${URLEncoder.encode(branch, "UTF-8")}" else ""
        val text = request(
            "GET",
            "https://api.github.com/repos/$fullName/contents/${path.trim('/')}$ref",
            accept = "application/vnd.github.raw+json"
        )
        return text.take(200000)
    }

    // ---------- ZIP 下载（无 git 依赖的克隆方案） ----------

    /**
     * 通过 codeload 下载仓库默认分支 ZIP 到目标文件（流式写入，防 OOM）。
     * Android 无 git 命令时用于"克隆"到工作区。
     */
    fun downloadZip(fullName: String, target: java.io.File, branch: String? = null): Boolean {
        return try {
            val ref = branch ?: "HEAD"
            val conn = URL("https://codeload.github.com/$fullName/zip/refs/heads/$ref").openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.setRequestProperty("User-Agent", "GitHubK-Studio/2.11")
            val tk = getToken()
            if (!tk.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $tk")
            if (conn.responseCode !in 200..299) return false
            conn.inputStream.use { input ->
                java.io.FileOutputStream(target).use { out -> input.copyTo(out) }
            }
            target.length() > 0
        } catch (_: Throwable) { false }
    }

    /** 下载单文件（raw URL）到目标文件，流式写入。用于仓库文件浏览器的单文件下载。 */
    fun downloadFile(url: String, target: java.io.File): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.setRequestProperty("User-Agent", "GitHubK-Studio/2.11")
            val tk = getToken()
            if (!tk.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $tk")
            if (conn.responseCode !in 200..299) return false
            conn.inputStream.use { input ->
                java.io.FileOutputStream(target).use { out -> input.copyTo(out) }
            }
            target.length() > 0
        } catch (_: Throwable) { false }
    }


    // ---------- 文件上传（Contents API） ----------

    /** 创建或更新单个仓库文件。目录会由 GitHub 自动创建。 */
    fun upsertFile(
        fullName: String,
        path: String,
        bytes: ByteArray,
        message: String = "Upload via GitHubK Studio",
        branch: String? = null
    ): Boolean {
        if (path.isBlank() || bytes.isEmpty() || bytes.size > 100 * 1024 * 1024) return false
        val cleanPath = path.trim('/').replace('\\', '/')
        if (cleanPath.split('/').any { it.isBlank() || it == "." || it == ".." }) return false
        return try {
            val existingSha = try {
                val ref = if (branch.isNullOrBlank()) "" else "?ref=${encode(branch)}"
                val raw = request("GET", "https://api.github.com/repos/$fullName/contents/${encodePath(cleanPath)}$ref")
                JSONObject(raw).optString("sha").ifBlank { null }
            } catch (_: Throwable) { null }
            val body = JSONObject()
                .put("message", message)
                .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            if (!branch.isNullOrBlank()) body.put("branch", branch)
            if (existingSha != null) body.put("sha", existingSha)
            request("PUT", "https://api.github.com/repos/$fullName/contents/${encodePath(cleanPath)}", body.toString())
            true
        } catch (_: Throwable) { false }
    }

    /** 递归上传本地文件夹；.git 元数据永远不上传。 */
    /** 收集可发布的项目文件；构建产物与 IDE 缓存不上传。 */
    fun collectUploadFiles(localRoot: java.io.File): List<java.io.File> {
        if (!localRoot.exists()) return emptyList()
        val excludedDirs = setOf(".git", ".gradle", ".idea", "build", "out", "captures", ".cxx")
        return if (localRoot.isFile) listOf(localRoot) else localRoot.walkTopDown()
            .onEnter { dir -> dir.name !in excludedDirs }
            .filter { it.isFile && it.name != "local.properties" && it.length() <= 100L * 1024L * 1024L }
            .toList()
    }

    fun uploadTree(
        fullName: String,
        localRoot: java.io.File,
        remoteRoot: String = "",
        message: String = "Upload folder via GitHubK Studio",
        branch: String? = null,
        onProgress: (done: Int, total: Int, path: String, ok: Boolean) -> Unit = { _, _, _, _ -> }
    ): Int {
        if (!localRoot.exists()) return 0
        val files = collectUploadFiles(localRoot)
        var success = 0
        files.forEachIndexed { index, file ->
            val rel = if (localRoot.isFile) file.name else file.relativeTo(localRoot).path.replace('\\', '/')
            val remote = listOf(remoteRoot.trim('/'), rel).filter { it.isNotBlank() }.joinToString("/")
            val ok = runCatching { upsertFile(fullName, remote, file.readBytes(), message, branch) }.getOrDefault(false)
            if (ok) success++
            onProgress(index + 1, files.size, remote, ok)
        }
        return success
    }

    // ---------- 仓库增强管理 ----------

    /** 删除仓库（需要 delete_repo scope）。 */
    fun deleteRepo(fullName: String): Boolean = try {
        request("DELETE", "https://api.github.com/repos/$fullName")
        true
    } catch (_: Throwable) { false }

    /** 创建分支（基于指定 base 分支的 SHA）。 */
    fun createBranch(fullName: String, branchName: String, fromBranch: String? = null): Boolean {
        return try {
            // 获取 base 分支的 SHA
            val refPath = if (fromBranch.isNullOrBlank()) "heads/main" else "heads/$fromBranch"
            val refText = request("GET", "https://api.github.com/repos/$fullName/git/refs/$refPath")
            val sha = JSONObject(refText).getJSONObject("object").getString("sha")
            // 创建新引用
            val body = JSONObject()
                .put("ref", "refs/heads/$branchName")
                .put("sha", sha)
            request("POST", "https://api.github.com/repos/$fullName/git/refs", body.toString())
            true
        } catch (_: Throwable) { false }
    }

    /** 删除分支（不能删除默认分支）。 */
    fun deleteBranch(fullName: String, branchName: String): Boolean = try {
        request("DELETE", "https://api.github.com/repos/$fullName/git/refs/heads/$branchName")
        true
    } catch (_: Throwable) { false }

    /** 删除远程文件或目录（通过 Contents API）。 */
    fun deleteFile(
        fullName: String,
        path: String,
        message: String = "Delete via GitHubK Studio",
        branch: String? = null
    ): Boolean {
        val cleanPath = path.trim('/').replace('\\', '/')
        if (cleanPath.isBlank()) return false
        return try {
            val ref = if (branch.isNullOrBlank()) "" else "?ref=${encode(branch)}"
            val raw = request("GET", "https://api.github.com/repos/$fullName/contents/${encodePath(cleanPath)}$ref")
            val sha = JSONObject(raw).optString("sha").ifBlank { null } ?: return false
            val body = JSONObject()
                .put("message", message)
                .put("sha", sha)
            if (!branch.isNullOrBlank()) body.put("branch", branch)
            request("DELETE", "https://api.github.com/repos/$fullName/contents/${encodePath(cleanPath)}", body.toString())
            true
        } catch (_: Throwable) { false }
    }

    /** 获取仓库默认分支。 */
    fun defaultBranch(fullName: String): String = try {
        val raw = request("GET", "https://api.github.com/repos/$fullName")
        JSONObject(raw).optString("default_branch").ifBlank { "main" }
    } catch (_: Throwable) { "main" }

    /** 重命名仓库（修改仓库名称）。 */
    fun renameRepo(fullName: String, newName: String): Boolean = try {
        val body = JSONObject().put("name", newName)
        request("PATCH", "https://api.github.com/repos/$fullName", body.toString())
        true
    } catch (_: Throwable) { false }

    /**
     * 更新仓库状态（PATCH /repos/{owner}/{repo}）。
     * 可修改：可见性 private、描述 description、首页 homepage、归档 archived、默认分支 default_branch
     * 等。传入 null 表示不修改该字段。返回是否成功。
     */
    fun updateRepo(
        fullName: String,
        isPrivate: Boolean? = null,
        description: String? = null,
        homepage: String? = null,
        defaultBranch: String? = null
    ): Boolean = try {
        val body = JSONObject()
        if (isPrivate != null) body.put("private", isPrivate)
        if (description != null) body.put("description", description)
        if (homepage != null) body.put("homepage", homepage)
        if (defaultBranch != null) body.put("default_branch", defaultBranch)
        request("PATCH", "https://api.github.com/repos/$fullName", body.toString())
        true
    } catch (_: Throwable) { false }

    /** 从本地 ZIP 文件导入到远程仓库（解压后逐文件上传，自动替换已有文件）。 */
    fun uploadZipToRepo(
        fullName: String,
        zipFile: java.io.File,
        remoteRoot: String = "",
        branch: String? = null,
        onProgress: (done: Int, total: Int, path: String, ok: Boolean) -> Unit = { _, _, _, _ -> }
    ): Int {
        if (!zipFile.exists()) return 0
        // 解压到临时目录
        val tmpDir = java.io.File(zipFile.parentFile, "unzip_${System.currentTimeMillis()}")
        tmpDir.mkdirs()
        try {
            val zip = java.util.zip.ZipFile(zipFile)
            val entries = zip.entries()
            val fileList = mutableListOf<java.io.File>()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val outFile = java.io.File(tmpDir, entry.name)
                outFile.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    java.io.FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
                fileList.add(outFile)
            }
            zip.close()

            // 过滤并上传
            val excludedDirs = setOf(".git", ".gradle", ".idea", "build", "out", "captures", ".cxx")
            val uploadFiles = fileList.filter { file ->
                val rel = file.relativeTo(tmpDir).path
                !rel.startsWith(".git") && !excludedDirs.any { rel.startsWith("$it/") || rel.startsWith("$it\\") } &&
                file.length() <= 100L * 1024L * 1024L && file.name != "local.properties"
            }

            var success = 0
            uploadFiles.forEachIndexed { index, file ->
                val rel = file.relativeTo(tmpDir).path.replace('\\', '/')
                val remote = listOf(remoteRoot.trim('/'), rel).filter { it.isNotBlank() }.joinToString("/")
                val ok = runCatching { upsertFile(fullName, remote, file.readBytes(), "Import ZIP via GitHubK Studio", branch) }.getOrDefault(false)
                if (ok) success++
                onProgress(index + 1, uploadFiles.size, remote, ok)
            }
            return success
        } catch (_: Throwable) {
            return 0
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun encodePath(path: String): String = path.split('/').joinToString("/") { encode(it) }

    /** 当前登录用户名（需 Token），失败返回空串。 */
    fun myLogin(): String = try {
        val text = request("GET", "https://api.github.com/user")
        JSONObject(text).optString("login")
    } catch (_: Throwable) { "" }

    // ---------- 仓库管理 ----------

    fun branches(fullName: String): List<GitBranch> = try {
        val text = request("GET", "https://api.github.com/repos/$fullName/branches?per_page=100")
        val arr = JSONArray(text)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            GitBranch(
                name = o.optString("name"),
                protected = o.optBoolean("protected"),
                sha = o.optJSONObject("commit")?.optString("sha")?.take(7) ?: ""
            )
        }
    } catch (_: Throwable) { emptyList() }

    fun star(fullName: String): Boolean = try {
        request("PUT", "https://api.github.com/user/starred/$fullName")
        true
    } catch (_: Throwable) { false }

    fun unstar(fullName: String): Boolean = try {
        request("DELETE", "https://api.github.com/user/starred/$fullName")
        true
    } catch (_: Throwable) { false }

    fun isStarred(fullName: String): Boolean = try {
        request("GET", "https://api.github.com/user/starred/$fullName")
        true
    } catch (_: Throwable) { false }

    fun fork(fullName: String): String = try {
        val t = request("POST", "https://api.github.com/repos/$fullName/forks")
        JSONObject(t).optString("full_name", "forked")
    } catch (_: Throwable) { "fork failed" }

    fun issues(fullName: String, state: String = "open"): List<GitIssue> = try {
        val text = request("GET", "https://api.github.com/repos/$fullName/issues?state=$state&per_page=50")
        val arr = JSONArray(text)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            GitIssue(
                number = o.optInt("number"),
                title = o.optString("title"),
                state = o.optString("state"),
                user = o.optJSONObject("user")?.optString("login") ?: "",
                createdAt = o.optString("created_at"),
                comments = o.optInt("comments"),
                body = o.optString("body")
            )
        }
    } catch (_: Throwable) { emptyList() }

    fun createIssue(fullName: String, title: String, body: String = ""): Boolean = try {
        val b = JSONObject().put("title", title).put("body", body)
        request("POST", "https://api.github.com/repos/$fullName/issues", b.toString())
        true
    } catch (_: Throwable) { false }

    fun commits(fullName: String, branch: String? = null, path: String? = null, perPage: Int = 20): List<String> = try {
        var url = "https://api.github.com/repos/$fullName/commits?per_page=$perPage"
        if (branch != null) url += "&sha=${URLEncoder.encode(branch, "UTF-8")}"
        if (path != null) url += "&path=${URLEncoder.encode(path, "UTF-8")}"
        val text = request("GET", url)
        val arr = JSONArray(text)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val msg = o.optJSONObject("commit")?.optString("message")?.lines()?.firstOrNull() ?: ""
            val author = o.optJSONObject("commit")?.optJSONObject("author")?.optString("name") ?: ""
            val date = o.optJSONObject("commit")?.optJSONObject("author")?.optString("date")?.take(10) ?: ""
            "[$date] $msg ($author)"
        }
    } catch (_: Throwable) { emptyList() }

    private fun parseRepos(text: String): List<GitRepo> {
        try {
            val raw = JSONArray(text)
            return convert(raw)
        } catch (_: Throwable) { /* fall through */ }
        return try {
            convert(JSONObject(text).getJSONArray("items"))
        } catch (_: Throwable) { emptyList() }
    }

    private fun convert(arr: JSONArray): List<GitRepo> {
        val out = mutableListOf<GitRepo>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val owner = o.optJSONObject("owner")?.optString("login") ?: ""
            val fullName = o.optString("full_name")
            val topics = try {
                val t = o.optJSONArray("topics")
                if (t == null) emptyList<String>() else (0 until t.length()).map { t.getString(it) }
            } catch (_: Throwable) { emptyList<String>() }
            val lic = o.optJSONObject("license")?.optString("spdx_id").orEmpty()
            val parent = o.optJSONObject("parent")?.optString("full_name").orEmpty()
            out.add(
                GitRepo(
                    fullName = fullName,
                    name = o.optString("name"),
                    owner = owner,
                    description = o.optString("description"),
                    stars = o.optInt("stargazers_count"),
                    forks = o.optInt("forks_count"),
                    language = o.optString("language"),
                    htmlUrl = o.optString("html_url"),
                    defaultBranch = o.optString("default_branch").ifBlank { "main" },
                    private = o.optBoolean("private"),
                    updatedAt = o.optString("updated_at"),
                    createdAt = o.optString("created_at"),
                    pushedAt = o.optString("pushed_at"),
                    watchers = o.optInt("subscribers_count", o.optInt("watchers_count")),
                    openIssues = o.optInt("open_issues_count"),
                    size = o.optLong("size"),
                    archived = o.optBoolean("archived"),
                    homepage = o.optString("homepage"),
                    topics = topics,
                    license = lic,
                    parentFullName = parent
                )
            )
        }
        return out
    }

    fun createRelease(
        owner: String,
        repo: String,
        tagName: String,
        targetCommitish: String? = null,
        name: String? = null,
        body: String? = null,
        draft: Boolean = false,
        prerelease: Boolean = false
    ): JSONObject? {
        val token = getToken() ?: return null
        val url = "https://api.github.com/repos/$owner/$repo/releases"
        val json = JSONObject().apply {
            put("tag_name", tagName)
            targetCommitish?.let { put("target_commitish", it) }
            name?.let { put("name", it) }
            body?.let { put("body", it) }
            put("draft", draft)
            put("prerelease", prerelease)
        }
        return try {
            val response = request("POST", url, json.toString())
            JSONObject(response)
        } catch (e: Exception) {
            null
        }
    }

    fun uploadReleaseAsset(
        owner: String,
        repo: String,
        releaseId: Long,
        assetFile: java.io.File,
        assetName: String
    ): JSONObject? {
        val token = getToken() ?: return null
        val url = "https://uploads.github.com/repos/$owner/$repo/releases/$releaseId/assets?name=${java.net.URLEncoder.encode(assetName, "UTF-8")}"
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "GitHubK-Studio/2.6")
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.doOutput = true
            conn.outputStream.use { it.write(assetFile.readBytes()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                java.io.BufferedReader(java.io.InputStreamReader(it, java.nio.charset.StandardCharsets.UTF_8)).use { r -> r.readText() }
            } ?: ""
            if (code !in 200..299) throw RuntimeException("HTTP $code: ${text.take(300)}")
            JSONObject(text)
        } catch (e: Exception) {
            null
        }
    }

    fun getReleases(owner: String, repo: String): JSONArray? {
        val token = getToken() ?: return null
        val url = "https://api.github.com/repos/$owner/$repo/releases"
        return try {
            val response = request("GET", url)
            JSONArray(response)
        } catch (e: Exception) {
            null
        }
    }

    fun deleteRelease(owner: String, repo: String, releaseId: Long): Boolean {
        val token = getToken() ?: return false
        val url = "https://api.github.com/repos/$owner/$repo/releases/$releaseId"
        return try {
            request("DELETE", url)
            true
        } catch (e: Exception) {
            false
        }
    }

    // =====================================================================
    //  结构化操作（v5.2：Release 发布 / 远程文件删除，错误可分类）
    // =====================================================================

    /** 获取 Releases 列表（dataArray=releases）。 */
    fun releasesOp(owner: String, repo: String): GitHubOpResult {
        return try {
            val response = request("GET", "https://api.github.com/repos/$owner/$repo/releases")
            GitHubOpResult.ok(arr = JSONArray(response))
        } catch (e: GitHubHttpException) {
            GitHubOpResult.fail(e.httpCode, e.raw)
        } catch (e: Exception) {
            GitHubOpResult.fail(0, e.message ?: "网络错误")
        }
    }

    /** 查找指定标签对应的 Release；ok=true 且 data=null 表示列表已取得但该标签不存在。 */
    fun findReleaseByTag(owner: String, repo: String, tag: String): GitHubOpResult {
        val list = releasesOp(owner, repo)
        if (!list.ok) return list
        val arr = list.dataArray ?: return GitHubOpResult.fail(0, "Releases 列表为空")
        return try {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("tag_name") == tag) {
                    return GitHubOpResult.ok(data = o)
                }
            }
            GitHubOpResult.ok(data = null)
        } catch (e: Exception) {
            GitHubOpResult.fail(0, e.message ?: "解析错误")
        }
    }

    /** 创建 Release（标签已存在 / 权限不足会返回对应错误码）。 */
    fun createReleaseOp(
        owner: String,
        repo: String,
        tagName: String,
        name: String? = null,
        body: String? = null,
        prerelease: Boolean = false,
        targetCommitish: String? = null
    ): GitHubOpResult {
        return try {
            val json = JSONObject().apply {
                put("tag_name", tagName)
                targetCommitish?.let { put("target_commitish", it) }
                name?.let { put("name", it) }
                body?.let { put("body", it) }
                put("draft", false)
                put("prerelease", prerelease)
            }
            val response = request("POST", "https://api.github.com/repos/$owner/$repo/releases", json.toString())
            GitHubOpResult.ok(data = JSONObject(response))
        } catch (e: GitHubHttpException) {
            GitHubOpResult.fail(e.httpCode, e.raw)
        } catch (e: Exception) {
            GitHubOpResult.fail(0, e.message ?: "网络错误")
        }
    }

    /** 删除指定 Release。 */
    fun deleteReleaseOp(owner: String, repo: String, releaseId: Long): GitHubOpResult {
        return try {
            request("DELETE", "https://api.github.com/repos/$owner/$repo/releases/$releaseId")
            GitHubOpResult.ok()
        } catch (e: GitHubHttpException) {
            GitHubOpResult.fail(e.httpCode, e.raw)
        } catch (e: Exception) {
            GitHubOpResult.fail(0, e.message ?: "网络错误")
        }
    }

    /** 获取仓库默认分支的完整文件树（data.tree = JSONArray，truncated 提示截断）。 */
    fun listTreeOp(fullName: String, branch: String): GitHubOpResult {
        return try {
            val response = request("GET", "https://api.github.com/repos/$fullName/git/trees/${encode(branch)}?recursive=1")
            GitHubOpResult.ok(data = JSONObject(response))
        } catch (e: GitHubHttpException) {
            GitHubOpResult.fail(e.httpCode, e.raw)
        } catch (e: Exception) {
            GitHubOpResult.fail(0, e.message ?: "网络错误")
        }
    }

    /** 删除 Git 标签引用（refs/tags/{tag}）。标签不存在视为成功（无需再删）。 */
    fun deleteTagOp(fullName: String, tag: String): GitHubOpResult {
        val safeTag = tag.trim('/').removePrefix("refs/tags/")
        if (safeTag.isBlank()) return GitHubOpResult.fail(0, "标签为空")
        return try {
            request("DELETE", "https://api.github.com/repos/$fullName/git/refs/tags/${encodePath(safeTag)}")
            GitHubOpResult.ok()
        } catch (e: GitHubHttpException) {
            // 422/404：Git ref 不存在，视为无需删除
            if (e.httpCode == 422 || e.httpCode == 404) GitHubOpResult.ok()
            else GitHubOpResult.fail(e.httpCode, e.raw)
        } catch (e: Exception) {
            GitHubOpResult.fail(0, e.message ?: "网络错误")
        }
    }

    /** 上传 Release 资产（APK），错误区分状态码。 */
    fun uploadReleaseAssetOp(
        owner: String,
        repo: String,
        releaseId: Long,
        assetFile: java.io.File,
        assetName: String
    ): GitHubOpResult {
        val url = "https://uploads.github.com/repos/$owner/$repo/releases/$releaseId/assets?name=${java.net.URLEncoder.encode(assetName, "UTF-8")}"
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            val tk = getToken()
            if (!tk.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $tk")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "GitHubK-Studio/5.2")
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.doOutput = true
            conn.outputStream.use { it.write(assetFile.readBytes()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                java.io.BufferedReader(java.io.InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""
            if (code !in 200..299) throw GitHubHttpException(code, text)
            GitHubOpResult.ok(data = JSONObject(text))
        } catch (e: GitHubHttpException) {
            GitHubOpResult.fail(e.httpCode, e.raw)
        } catch (e: Exception) {
            GitHubOpResult.fail(0, e.message ?: "上传网络错误")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 流式分块上传 Release 资产（大文件使用，≤2GB）。
     * 底层按 8MB 块读取并写出（固定 Content-Length 流式传输，不把整文件读入内存），
     * 避免大文件 OOM，支持进度回调与随时取消。
     */
    fun uploadReleaseAssetAdvanced(
        owner: String,
        repo: String,
        releaseId: Long,
        assetFile: java.io.File,
        assetName: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
        registerCancel: ((() -> Unit) -> Unit)? = null
    ): GitHubOpResult {
        val url = "https://uploads.github.com/repos/$owner/$repo/releases/$releaseId/assets?name=${java.net.URLEncoder.encode(assetName, "UTF-8")}"
        val total = assetFile.length()
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 300000
            val tk = getToken()
            if (!tk.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $tk")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "GitHubK-Studio/5.2")
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(total)
            // 允许外部注册取消逻辑：取消时断开连接，中断正在写入的流
            registerCancel?.invoke { runCatching { conn?.disconnect() } }
            val buf = ByteArray(8 * 1024 * 1024)
            var sent = 0L
            conn.outputStream.use { out ->
                assetFile.inputStream().use { fin ->
                    while (true) {
                        if (isCancelled()) throw java.io.IOException("upload cancelled")
                        val n = fin.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        out.flush()
                        sent += n
                        onProgress(sent, total)
                    }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                java.io.BufferedReader(java.io.InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""
            if (code !in 200..299) throw GitHubHttpException(code, text)
            GitHubOpResult.ok(data = JSONObject(text))
        } catch (e: GitHubHttpException) {
            GitHubOpResult.fail(e.httpCode, e.raw)
        } catch (e: java.io.IOException) {
            if (e.message?.contains("cancelled") == true || isCancelled()) {
                GitHubOpResult.fail(0, "已取消上传")
            } else {
                GitHubOpResult.fail(0, e.message ?: "上传网络错误")
            }
        } catch (e: Exception) {
            GitHubOpResult.fail(0, e.message ?: "上传网络错误")
        } finally {
            conn?.disconnect()
        }
    }

    /** 删除远程文件 / 目录（Contents API，单文件）。错误区分 404/403/401/429。 */
    fun deleteFileOp(
        fullName: String,
        path: String,
        message: String = "Delete via GitHubK Studio",
        branch: String? = null
    ): GitHubOpResult {
        val cleanPath = path.trim('/').replace('\\', '/')
        if (cleanPath.isBlank()) return GitHubOpResult.fail(0, "文件路径为空")
        if (cleanPath.split('/').any { it == "." || it == ".." || it.isBlank() }) {
            return GitHubOpResult.fail(0, "非法路径")
        }
        return try {
            val ref = if (branch.isNullOrBlank()) "" else "?ref=${encode(branch)}"
            val raw = request("GET", "https://api.github.com/repos/$fullName/contents/${encodePath(cleanPath)}$ref")
            val sha = JSONObject(raw).optString("sha").ifBlank { null } ?: return GitHubOpResult.fail(0, "未能获取文件 SHA")
            val body = JSONObject()
                .put("message", message)
                .put("sha", sha)
            if (!branch.isNullOrBlank()) body.put("branch", branch)
            request("DELETE", "https://api.github.com/repos/$fullName/contents/${encodePath(cleanPath)}", body.toString())
            GitHubOpResult.ok()
        } catch (e: GitHubHttpException) {
            GitHubOpResult.fail(e.httpCode, e.raw)
        } catch (e: Exception) {
            GitHubOpResult.fail(0, e.message ?: "网络错误")
        }
    }

    // =====================================================================
    //  v5.1：Issue 评论 / Pull Request / Release 编辑 / 仓库配置
    // =====================================================================

    /** 给 Issue 发表评论。 */
    fun issueCommentOp(owner: String, repo: String, issueNumber: Int, body: String): GitHubOpResult {
        if (body.isBlank()) return GitHubOpResult.fail(0, "评论内容为空")
        return try {
            val json = JSONObject().put("body", body)
            val response = request("POST", "https://api.github.com/repos/$owner/$repo/issues/$issueNumber/comments", json.toString())
            GitHubOpResult.ok(data = JSONObject(response))
        } catch (e: GitHubHttpException) {
            GitHubOpResult.fail(e.httpCode, e.raw)
        } catch (e: Exception) {
            GitHubOpResult.fail(0, e.message ?: "网络错误")
        }
    }

    /** 创建 Pull Request（head 为源分支，base 为目标分支）。 */
    fun createPrOp(owner: String, repo: String, title: String, head: String, base: String, body: String = ""): GitHubOpResult {
        if (title.isBlank() || head.isBlank() || base.isBlank()) return GitHubOpResult.fail(0, "title/head/base 不能为空")
        return try {
            val json = JSONObject()
                .put("title", title)
                .put("head", head.trim())
                .put("base", base.trim())
                .put("body", body)
            val response = request("POST", "https://api.github.com/repos/$owner/$repo/pulls", json.toString())
            val o = JSONObject(response)
            GitHubOpResult.ok(data = JSONObject().put("number", o.optInt("number")).put("html_url", o.optString("html_url")).put("state", o.optString("state")))
        } catch (e: GitHubHttpException) {
            GitHubOpResult.fail(e.httpCode, e.raw)
        } catch (e: Exception) {
            GitHubOpResult.fail(0, e.message ?: "网络错误")
        }
    }

    /** 合并 Pull Request。method 可选 merge / squash / rebase。 */
    fun mergePrOp(owner: String, repo: String, prNumber: Int, method: String? = null): GitHubOpResult {
        return try {
            val body = JSONObject()
            if (!method.isNullOrBlank()) body.put("merge_method", method.trim().lowercase())
            val response = request("PUT", "https://api.github.com/repos/$owner/$repo/pulls/$prNumber/merge", body.toString())
            GitHubOpResult.ok(data = JSONObject(response))
        } catch (e: GitHubHttpException) {
            GitHubOpResult.fail(e.httpCode, e.raw)
        } catch (e: Exception) {
            GitHubOpResult.fail(0, e.message ?: "网络错误")
        }
    }

    /** 列出 Pull Requests（state=open/closed/all），dataArray=PR 数组。 */
    fun listPrsOp(owner: String, repo: String, state: String = "open"): GitHubOpResult {
        return try {
            val response = request("GET", "https://api.github.com/repos/$owner/$repo/pulls?state=${encode(state)}&per_page=50")
            GitHubOpResult.ok(arr = JSONArray(response))
        } catch (e: GitHubHttpException) {
            GitHubOpResult.fail(e.httpCode, e.raw)
        } catch (e: Exception) {
            GitHubOpResult.fail(0, e.message ?: "网络错误")
        }
    }

    /** 编辑 Release 名称/正文（PATCH /releases/{id}），用于修改说明、调整标题。 */
    fun updateReleaseOp(owner: String, repo: String, releaseId: Long, name: String? = null, body: String? = null): GitHubOpResult {
        return try {
            val json = JSONObject()
            if (name != null) json.put("name", name)
            if (body != null) json.put("body", body)
            if (json.length() == 0) return GitHubOpResult.fail(0, "没有可修改的字段")
            val response = request("PATCH", "https://api.github.com/repos/$owner/$repo/releases/$releaseId", json.toString())
            GitHubOpResult.ok(data = JSONObject(response))
        } catch (e: GitHubHttpException) {
            GitHubOpResult.fail(e.httpCode, e.raw)
        } catch (e: Exception) {
            GitHubOpResult.fail(0, e.message ?: "网络错误")
        }
    }

    /** 仓库全量配置（PATCH /repos/{fullName}）：可见性/描述/首页/是否开启 Wiki/默认分支。 */
    fun updateRepoOp(
        fullName: String,
        isPrivate: Boolean? = null,
        description: String? = null,
        homepage: String? = null,
        hasWiki: Boolean? = null,
        defaultBranch: String? = null
    ): GitHubOpResult {
        return try {
            val json = JSONObject()
            if (isPrivate != null) json.put("private", isPrivate)
            if (description != null) json.put("description", description)
            if (homepage != null) json.put("homepage", homepage)
            if (hasWiki != null) json.put("has_wiki", hasWiki)
            if (defaultBranch != null) json.put("default_branch", defaultBranch)
            if (json.length() == 0) return GitHubOpResult.fail(0, "没有可修改的字段")
            val response = request("PATCH", "https://api.github.com/repos/$fullName", json.toString())
            GitHubOpResult.ok(data = JSONObject(response))
        } catch (e: GitHubHttpException) {
            GitHubOpResult.fail(e.httpCode, e.raw)
        } catch (e: Exception) {
            GitHubOpResult.fail(0, e.message ?: "网络错误")
        }
    }
}