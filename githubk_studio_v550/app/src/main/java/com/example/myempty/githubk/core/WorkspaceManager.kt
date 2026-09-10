package com.example.myempty.githubk.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 项目元数据。path 为工作区内项目根目录。
 */
data class Project(val name: String, val type: String, val path: File)

/**
 * WorkspaceManager v2.5
 *
 * P0 修复：路径穿越。
 * 1. AI 提供的 projectName 必须来自 workspace.projects()（不允许用 AI 路径直接 File(root, first)）。
 * 2. 相对路径必须 canonicalize 后位于项目目录内。
 * 3. 上传（SAF）改为基于 ContentResolver + DocumentsContract，不再依赖 DocumentFile 库。
 */
class WorkspaceManager(private val c: Context) {

    private val root = File(c.filesDir, "workspace").apply { mkdirs() }

    fun rootDir(): File = root

    fun projects(): List<Project> =
        root.listFiles()?.filter { it.isDirectory }?.map { Project(it.name, detect(it), it) } ?: emptyList()

    fun project(name: String): Project? = projects().firstOrNull { it.name == name }

    /** 删除整个项目；只允许删除 workspace 根目录下的直接子目录。 */
    fun deleteProject(p: Project): Boolean {
        return try {
            val base = root.canonicalFile
            val target = p.path.canonicalFile
            if (!target.isDirectory) return false
            if (target.parentFile?.canonicalFile != base) return false
            if (target == base) return false
            target.deleteRecursively()
        } catch (_: Throwable) { false }
    }

    fun projectSizeBytes(p: Project): Long = try {
        p.path.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (_: Throwable) { 0L }

    /** 重新扫描工作区（projects() 已实时扫描，此方法保持兼容）。 */
    fun scan() { root.listFiles()?.filter { it.isDirectory } }

    private fun detect(f: File): String = when {
        File(f, "pubspec.yaml").exists() -> "Flutter"
        File(f, "settings.gradle").exists() || File(f, "settings.gradle.kts").exists() || File(f, "build.gradle").exists() -> "Android"
        else -> "Other"
    }

    // ---------- SAF 导入（无 DocumentFile 依赖） ----------

    fun importFile(uri: Uri, done: () -> Unit, failed: (Throwable) -> Unit = {}) {
        try {
            val name = queryDisplayName(uri) ?: "file_${System.currentTimeMillis()}"
            // ZIP 是“项目导入”而不是普通文件导入：先复制到 cache，再安全解压到 workspace。
            if (name.substringAfterLast('.', "").equals("zip", true)) {
                val temp = File(c.cacheDir, "imports/import_${System.currentTimeMillis()}.zip").apply {
                    parentFile?.mkdirs()
                }
                c.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(temp).use { output -> input.copyTo(output) }
                } ?: throw java.io.IOException("无法打开 ZIP 文件")
                val projectName = sanitize(name.substringBeforeLast('.')).ifBlank { "imported_project" }
                val ok = importZip(temp, projectName)
                temp.delete()
                if (!ok) throw java.io.IOException("ZIP 无有效项目文件或解压失败")
                done()
                return
            }
            val projectDir = uniqueProjectDir(sanitize(name.substringBeforeLast('.', name)))
            projectDir.mkdirs()
            val out = File(projectDir, sanitize(name))
            val input = c.contentResolver.openInputStream(uri) ?: throw java.io.IOException("无法打开文件")
            input.use { stream -> FileOutputStream(out).use { stream.copyTo(it) } }
            done()
        } catch (e: Throwable) {
            failed(e)
        }
    }

    fun importTree(uri: Uri, done: () -> Unit, failed: (Throwable) -> Unit = {}) {
        try {
            // SAF tree URI 通常没有 DISPLAY_NAME 列，queryDisplayName 会返回 null。
            // 改为优先从 tree URI 的 lastPathSegment 提取文件夹名。
            val name = extractFolderNameFromTreeUri(uri)
            val out = uniqueProjectDir(sanitize(name))
            out.mkdirs()
            copyTree(uri, out)
            if (out.walkTopDown().any { it.isFile }) done()
            else {
                out.deleteRecursively()
                throw java.io.IOException("文件夹为空或无法读取")
            }
        } catch (e: Throwable) {
            failed(e)
        }
    }

    /**
     * 从 SAF tree URI 中提取文件夹名称。
     * tree URI 的 lastPathSegment 格式多样：
     *   "primary:Documents/MyProject" -> "MyProject"
     *   "raw:/storage/emulated/0/Download/MyApp" -> "MyApp"
     */
    private fun extractFolderNameFromTreeUri(uri: Uri): String {
        // 1. 尝试 queryDisplayName（个别 provider 支持）。
        //    注意：对 ACTION_OPEN_DOCUMENT_TREE 返回的 tree URI 直接 query 时，
        //    ExternalStorageProvider 会抛 IllegalArgumentException("Unsupported Uri
        //    content://com.android.externalstorage.documents/tree/...")，导致
        //    “文件夹导入失败”。必须 try/catch 吞掉并改用 URI 路径提取名称。
        try {
            queryDisplayName(uri)?.let { display ->
                if (display.isNotBlank() && display != "content" && !display.startsWith("UiContent")) {
                    return display.substringBeforeLast('.').ifBlank { display }
                }
            }
        } catch (_: Throwable) { /* tree URI 不支持直接 query，走 URI 路径提取 */ }

        // 2. 从 lastPathSegment 提取
        val segment = uri.lastPathSegment ?: return "project"

        // 解码 URL 编码
        val decoded = java.net.URLDecoder.decode(segment, "UTF-8")

        // 格式 "primary:Documents/MyProject" 或 "raw:/storage/.../MyProject"
        // 先取冒号后的部分（如果有），再取最后一个路径段
        val pathPart = decoded.substringAfter(':').ifBlank { decoded }

        // 取最后一个非空路径段
        val folderName = pathPart.trimEnd('/').substringAfterLast('/').ifBlank { pathPart }

        // 如果仍然无效，尝试从 URI 的 path 部分提取
        if (folderName.isBlank() || folderName == "content" || folderName == "UiContent") {
            val path = uri.path ?: return "project"
            val parts = path.split('/').filter { it.isNotBlank() }
            val treeIdx = parts.indexOf("tree")
            if (treeIdx >= 0 && treeIdx + 1 < parts.size) {
                val treePart = java.net.URLDecoder.decode(parts[treeIdx + 1], "UTF-8")
                return treePart.substringAfter(':').trimEnd('/').substringAfterLast('/').ifBlank { "project" }
            }
            return "project"
        }

        return folderName
    }

    /**
     * SAF tree URI 只能作为根 URI 使用。旧实现递归时把 child document URI
     * 再传给 getTreeDocumentId()，部分 DocumentsProvider 会直接抛异常并导致
     * “导入文件夹”闪退。这里始终保留原始 treeUri，只改变 documentId。
     */
    private fun copyTree(treeUri: Uri, out: File) {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
            ?: throw java.io.IOException("无效的目录 URI")
        copyTreeChildren(treeUri, docId, out)
    }

    private fun copyTreeChildren(treeUri: Uri, parentDocId: String, out: File) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        c.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            if (idCol < 0 || nameCol < 0 || mimeCol < 0) throw java.io.IOException("DocumentsProvider 返回字段不完整")
            while (cursor.moveToNext()) {
                val childId = cursor.getString(idCol) ?: continue
                val childName = sanitize(cursor.getString(nameCol) ?: "item")
                if (childName == "." || childName == ".." || childName.isBlank()) continue
                val mime = cursor.getString(mimeCol) ?: ""
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                val target = File(out, childName).canonicalFile
                val base = out.canonicalFile
                if (target.path != base.path && !target.path.startsWith(base.path + File.separator)) continue
                if (DocumentsContract.Document.MIME_TYPE_DIR == mime) {
                    target.mkdirs()
                    copyTreeChildren(treeUri, childId, target)
                } else {
                    c.contentResolver.openInputStream(childUri)?.use { input ->
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { input.copyTo(it) }
                    } ?: throw java.io.IOException("无法读取：$childName")
                }
            }
        } ?: throw java.io.IOException("无法读取目录内容")
    }

    // ---------- 对外 SAF 拷贝工具（离线工具链导入等场景复用） ----------

    /** 将 SAF 选择的文件复制到 dest（自动创建父目录）。 */
    fun copyFileTo(uri: Uri, dest: File): Boolean {
        return try {
            dest.parentFile?.mkdirs()
            c.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(dest).use { out -> input.copyTo(out) }
            } ?: return false
            dest.length() > 0
        } catch (_: Throwable) { false }
    }

    /** 将 SAF 选择的目录递归复制到 out；避免把 content uri 当作 File 使用。 */
    fun copyTreeTo(uri: Uri, out: File): Boolean {
        return try {
            out.mkdirs()
            copyTree(uri, out)
            out.walkTopDown().any { it.isFile }
        } catch (_: Throwable) { false }
    }

    private fun uniqueProjectDir(baseName: String): File {
        val safe = sanitize(baseName).ifBlank { "project" }
        var candidate = File(root, safe)
        var i = 2
        while (candidate.exists()) {
            candidate = File(root, "${safe}_$i")
            i++
        }
        return candidate
    }

    fun queryDisplayName(uri: Uri): String? =
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun sanitize(name: String): String =
        name.replace(File.separator, "_").replace("/", "_").replace("..", "_").ifBlank { "item" }

    // ---------- ZIP 导入（无 git 依赖的克隆方案） ----------

    /**
     * 解压 GitHub ZIP 到工作区根目录。ZIP 顶层通常含一层仓库目录（name-branch/），
     * 将其内容平铺到 root/<name>/ 下。
     */
    fun importZip(zip: java.io.File, projectName: String): Boolean {
        return try {
            if (!zip.isFile || zip.length() < 22L) return false
            val outDir = uniqueProjectDir(sanitize(projectName))
            outDir.mkdirs()
            var count = 0
            java.util.zip.ZipFile(zip).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val raw = entry.name.replace('\\', '/').trimStart('/')
                    if (raw.isBlank()) continue
                    val parts = raw.split('/').filter { it.isNotBlank() && it != "." }
                    if (parts.any { it == ".." }) continue
                    // GitHub archive通常是单一顶层目录；普通本地ZIP也允许多目录。
                    val rel = if (parts.size > 1 && isSingleTopLevelArchive(zf, parts[0])) {
                        parts.drop(1).joinToString("/")
                    } else {
                        parts.joinToString("/")
                    }
                    if (rel.isBlank()) continue
                    val target = File(outDir, rel).canonicalFile
                    val base = outDir.canonicalFile
                    if (target.path != base.path && !target.path.startsWith(base.path + File.separator)) continue
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        zf.getInputStream(entry).use { input -> FileOutputStream(target).use { input.copyTo(it) } }
                        count++
                    }
                }
            }
            if (count > 0) true else { outDir.deleteRecursively(); false }
        } catch (_: Throwable) { false }
    }

    private fun isSingleTopLevelArchive(zf: java.util.zip.ZipFile, top: String): Boolean {
        val it = zf.entries()
        var seen = false
        while (it.hasMoreElements()) {
            val n = it.nextElement().name.replace('\\', '/').trimStart('/')
            val first = n.substringBefore('/', n)
            if (first.isBlank()) continue
            if (!seen) { seen = true } else if (first != top) return false
        }
        return seen
    }

    // ---------- 文件访问（P0 安全修复） ----------

    // ---------- v6.0：写入追踪与快照回滚 ----------
    @Volatile
    var writeTracker: ((Project, String) -> Unit)? = null
    @Volatile
    var deleteTracker: ((Project, String) -> Unit)? = null
    private val captureEnabled = AtomicBoolean(false)

    /** 是否对随后的写/删进行快照（AI 任务运行期开启）。 */
    fun setRunCapture(on: Boolean) { captureEnabled.set(on) }

    fun captureActive(): Boolean = captureEnabled.get()

    private fun rollbackDir(p: Project): File =
        File(File(File(p.path, ".studio"), "rollback"), "").apply { mkdirs() }

    private fun rollbackIndexFile(p: Project): File =
        File(File(File(p.path, ".studio"), "rollback"), "rollback_index.json")

    private data class Snapshot(val rel: String, val backup: String, val existed: Boolean, val ts: Long)

    private fun readIndex(p: Project): MutableList<Snapshot> {
        val f = rollbackIndexFile(p)
        if (!f.isFile) return mutableListOf()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Snapshot(o.optString("rel"), o.optString("backup"), o.optBoolean("existed", true), o.optLong("ts", 0))
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun writeIndex(p: Project, list: List<Snapshot>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().put("rel", s.rel).put("backup", s.backup).put("existed", s.existed).put("ts", s.ts))
        }
        rollbackIndexFile(p).writeText(arr.toString())
    }

    /** 写/删前调用：若运行期捕获开启，则把原文件备份到 .studio/rollback。 */
    private fun snapshotBefore(p: Project, relative: String): Boolean {
        if (!captureEnabled.get()) return false
        val target = resolveSafe(p, relative) ?: return false
        val existed = target.isFile
        val bytes = if (existed) runCatching { target.readBytes() }.getOrNull() else null
        val dir = rollbackDir(p)
        val backup = "bak_${System.currentTimeMillis()}_${(Math.random() * 100000).toInt()}"
        if (bytes != null) File(dir, backup).writeBytes(bytes)
        val idx = readIndex(p)
        if (idx.size > 300) {
            idx.removeAt(0).let { old -> File(dir, old.backup).delete() }
        }
        idx.add(Snapshot(relative.replace('\\', '/'), backup, existed, System.currentTimeMillis()))
        writeIndex(p, idx)
        return true
    }

    fun beginRollbackScope(p: Project) { clearRollback(p) }

    fun clearRollback(p: Project) {
        runCatching {
            val dir = rollbackDir(p)
            readIndex(p).forEach { s -> File(dir, s.backup).delete() }
            rollbackIndexFile(p).delete()
        }
    }

    fun rollbackCount(p: Project): Int = readIndex(p).size

    /** v6.0：返回本次任务已写/删过的相对路径（供自动变更日志/审计），来自快照索引。 */
    fun changedFiles(p: Project): List<String> = readIndex(p).map { it.rel }

    /** 回滚全部快照（恢复原内容/删除本次新增），返回恢复文件数。 */
    fun applyRollback(p: Project): Int {
        val dir = rollbackDir(p)
        val idx = readIndex(p)
        if (idx.isEmpty()) return 0
        var count = 0
        idx.asReversed().forEach { s ->
            val target = resolveSafe(p, s.rel) ?: return@forEach
            val bak = File(dir, s.backup)
            if (s.existed && bak.isFile) {
                runCatching { target.parentFile?.mkdirs(); bak.copyTo(target, overwrite = true); count++ }
            } else if (!s.existed) {
                runCatching { if (target.isFile) { target.delete(); count++ } }
            }
            bak.delete()
        }
        writeIndex(p, emptyList())
        return count
    }

    fun rollbackSummary(p: Project): String {
        val list = readIndex(p)
        if (list.isEmpty()) return "（无待回滚快照）"
        return list.take(80).joinToString("\n") { s ->
            (if (s.existed) "改 " else "增 ") + s.rel
        } + if (list.size > 80) "\n… 共 ${list.size} 项" else ""
    }

    /** 源码文件枚举：跳过构建产物/依赖目录与大文件，保证搜索/上下文质量与速度。 */
    fun sourceFiles(p: Project): List<File> =
        p.path.walkTopDown()
            .onEnter { d -> d == p.path || d.name !in SKIP_DIRS }
            .filter { it.isFile && it.length() <= MAX_SOURCE_FILE && it.extension.lowercase() in SOURCE_EXT }
            .take(400)
            .toList()

    fun read(f: File): String = f.readText()

    fun write(f: File, s: String) = f.writeText(s)

    /** 仅允许项目内的相对路径。 */
    fun resolveSafe(p: Project, relative: String): File? {
        val rel = relative.replace('\\', '/').trim().removePrefix("/")
        if (rel.isBlank()) return null
        if (rel.split('/').any { it == ".." }) return null
        val target = File(p.path, rel).canonicalFile
        val base = p.path.canonicalFile
        return if (target.path.startsWith(base.path + File.separator)) target else null
    }

    fun readSafe(p: Project, relative: String): String? {
        val target = resolveSafe(p, relative) ?: return null
        return if (target.isFile) target.readText() else null
    }

    fun writeSafe(p: Project, relative: String, text: String): Boolean {
        val target = resolveSafe(p, relative) ?: return false
        snapshotBefore(p, relative)
        target.parentFile?.mkdirs()
        target.writeText(text)
        writeTracker?.invoke(p, relative.replace('\\', '/'))
        return true
    }

    fun deleteSafe(p: Project, relative: String): Boolean {
        val target = resolveSafe(p, relative) ?: return false
        snapshotBefore(p, relative)
        val ok = target.delete()
        if (ok) deleteTracker?.invoke(p, relative.replace('\\', '/'))
        return ok
    }

    // ---------- AI 文件写入（P0 修复：projectName 必须来自 workspace.projects()） ----------

    /**
     * 解析 AI 返回的 <FILE path="project/rel/path">content</FILE>。
     * path 第一段必须是已存在的项目名；剩余部分必须位于该项目 canonical 路径内。
     */
    fun applyAiChanges(text: String): Int {
        val re = Regex("""<FILE\s+path="([^"]+)">(.*?)</FILE>""", RegexOption.DOT_MATCHES_ALL)
        var count = 0
        re.findAll(text).forEach { m ->
            val raw = m.groupValues[1].replace('\\', '/').trim().removePrefix("/")
            if (raw.isBlank()) return@forEach
            val projectName = raw.substringBefore('/')
            val project = project(projectName) ?: return@forEach // 只接受已知项目
            val rel = raw.substringAfter('/', "")
            if (writeSafe(project, rel, m.groupValues[2].trimStart('\n'))) count++
        }
        return count
    }

    fun snapshot(): String = projects().joinToString("\n") { "${it.name} [${it.type}] ${it.path}" }

    // ---------- 新建项目 / 源码打包（供 Agent 工具与 UI 复用） ----------

    /**
     * 创建新项目骨架（Android / Flutter / 空项目），委托 ProjectTemplate。
     * name 会被 sanitize；若目录已存在返回已存在的项目（不覆盖）。
     */
    fun createProject(name: String, type: String): Project {
        val safeName = sanitize(name.trim()).ifBlank { "project" }
        val dir = uniqueProjectDir(safeName)
        com.example.myempty.githubk.core.ProjectTemplate.create(dir, dir.name, type)
        return Project(dir.name, detect(dir), dir)
    }

    /**
     * 将项目源码打包为 ZIP，输出到 cache/exports/ 下，返回文件。
     * 自动跳过构建产物目录（build/、.gradle/、.dart_tool/ 等）以控制体积。
     */
    fun packageSource(p: Project, includeBuild: Boolean = false): File {
        val outDir = File(c.cacheDir, "exports").apply { mkdirs() }
        val tag = if (includeBuild) "full" else "source"
        val zipFile = File(outDir, "${p.name}_${tag}_${System.currentTimeMillis()}.zip")
        val excludedDirs = if (includeBuild)
            setOf(".git", ".dart_tool", "node_modules", ".idea", "cxx", ".cxx")
        else
            setOf("build", ".gradle", ".dart_tool", ".git", ".studio", "gradle", "node_modules", ".idea", "cxx", ".cxx")
        java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            p.path.walkTopDown()
                .onEnter { dir -> dir.name !in excludedDirs }
                .filter { it.isFile }
                .forEach { file ->
                    val rel = file.relativeTo(p.path).path.replace(File.separatorChar, '/')
                    val entry = java.util.zip.ZipEntry("${p.name}/$rel")
                    zos.putNextEntry(entry)
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
        }
        return zipFile
    }

    /** 在项目目录下查找最近生成的 APK（供 build 后导出使用）。 */
    fun findLatestApk(p: Project): File? =
        p.path.walkTopDown()
            .filter { it.isFile && it.extension.equals("apk", true) }
            .maxByOrNull { it.lastModified() }

    fun agentContext(p: Project? = null): String {
        val ps = if (p != null) listOf(p) else projects()
        return ps.joinToString("\n") { pr ->
            "PROJECT ${pr.name} TYPE=${pr.type}\n" +
                sourceFiles(pr).joinToString("\n") { it.relativeTo(pr.path).path }
        }
    }

    fun fileTree(p: Project): String =
        p.path.walkTopDown()
            .filter { it.isFile }
            .take(500)
            .joinToString("\n") { it.relativeTo(p.path).path }

    /**
     * 代码搜索：返回 file:line: 内容 列表。
     * @param file 可选文件名/路径关键字过滤；@param context 匹配行前后各取几行（0 表示只返回匹配行）；
     * @param ignoreCase 是否忽略大小写（默认 true，保持旧行为）。
     */
    fun searchCode(p: Project, q: String, file: String = "", context: Int = 0, ignoreCase: Boolean = true): String {
        if (q.isBlank()) return ""
        val files = if (file.isNotBlank()) {
            sourceFiles(p).filter { f ->
                val rel = f.relativeTo(p.path).path
                rel.contains(file, true) || f.name.contains(file, true)
            }
        } else sourceFiles(p)
        if (files.isEmpty()) return "(无匹配文件)"
        val out = ArrayList<String>()
        val needle = if (ignoreCase) q.lowercase() else q
        outer@ for (f in files) {
            val rel = f.relativeTo(p.path).path
            val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
            var hitsInFile = 0
            for ((idx, line) in lines.withIndex()) {
                val hay = if (ignoreCase) line.lowercase() else line
                if (!hay.contains(needle)) continue
                hitsInFile++
                if (context > 0) {
                    val from = (idx - context).coerceAtLeast(0)
                    val to = (idx + context).coerceAtMost(lines.size - 1)
                    val sb = StringBuilder()
                    for (j in from..to) {
                        sb.append(if (j == idx) '>' else ' ').append(' ').append(rel)
                            .append(':').append(j + 1).append(": ").append(lines[j]).append('\n')
                    }
                    out.add(sb.trimEnd().toString())
                } else {
                    out.add("$rel:${idx + 1}: $line")
                }
                if (out.size >= 120) break@outer
            }
            if (out.size >= 120) break@outer
        }
        if (out.isEmpty()) return "(未找到匹配：$q)"
        return out.joinToString("\n") + if (out.size >= 120) "\n…（结果过多已截断，请缩小范围或指定 file 参数）" else ""
    }

    companion object {
        val SOURCE_EXT = setOf("kt", "kts", "java", "dart", "xml", "gradle", "properties", "yaml", "yml", "json", "md", "txt", "c", "h", "cpp", "py", "js", "ts", "html", "css")
        val SKIP_DIRS = setOf(".git", "build", ".gradle", ".idea", ".cxx", "node_modules", ".dart_tool", ".studio", "gradle", "Pods")
        const val MAX_SOURCE_FILE = 2 * 1024 * 1024L
    }
}
