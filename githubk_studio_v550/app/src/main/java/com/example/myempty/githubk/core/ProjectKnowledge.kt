package com.example.myempty.githubk.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ProjectKnowledge v6.0 — 项目工程智能层
 *
 * 围绕每个项目的 .studio/ 目录提供：
 * - 长期记忆 memory.json（与历史版本兼容，纯文本追加）
 * - 自动变更日志 changelog.json / changelog.md（记录修改文件、原因、时间）
 * - 任务复盘 reviews.json（记录问题/踩坑/解决方案，供同类任务复用）
 * - 运行日志 logs/（完整保留 Agent 报错日志）
 * - 项目变量 variables.json（{{key}} 引用）
 * - 自定义插件 plugins 目录下的 *.json（沙盒内由 Agent/用户一键启停）
 * - 能力迁移 export/import（记忆+变更+复盘+变量+插件打包迁移到其它项目）
 * - 轻量自动文档生成（README/docs）
 *
 * 所有路径都严格限制在项目目录内。
 */
object ProjectKnowledge {

    fun studioDir(p: Project): File = File(p.path, ".studio").apply { mkdirs() }
    private fun dir(p: Project, name: String): File = File(studioDir(p), name).apply { mkdirs() }
    private fun ts(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

    private fun ensureIgnored(p: Project) {
        runCatching {
            val gi = File(p.path, ".gitignore")
            if (gi.isFile && !gi.readText().contains(".studio")) gi.appendText("\n.studio/\n")
        }
    }

    // ======================= 记忆 =======================

    fun memoryFile(p: Project): File {
        ensureIgnored(p)
        return File(studioDir(p), "memory.json")
    }

    fun memoryText(p: Project): String = runCatching {
        val f = memoryFile(p)
        if (f.isFile) f.readText().trim() else ""
    }.getOrDefault("")

    fun saveMemory(p: Project, text: String) {
        val f = memoryFile(p)
        f.parentFile?.mkdirs()
        f.writeText(text.trim() + "\n")
    }

    /** 追加一个带标题的记忆小节（例如“复盘 / 新能力 / 约定”）。 */
    fun memoryAppend(p: Project, title: String, body: String) {
        val old = memoryText(p)
        val block = "\n\n## ${title} · ${ts()}\n$body".trimStart()
        saveMemory(p, if (old.isBlank()) "## $title\n$body" else old + "\n" + block)
    }

    // ======================= 变更日志 =======================

    data class ChangeEntry(val ts: String, val reason: String, val files: List<String>)

    fun changeList(p: Project): List<ChangeEntry> {
        val f = File(studioDir(p), "changelog.json")
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.optJSONObject(i) ?: return@map null
                val fa = o.optJSONArray("files") ?: JSONArray()
                val files = (0 until fa.length()).mapNotNull { j -> fa.optString(j).ifBlank { null } }
                ChangeEntry(o.optString("ts", ""), o.optString("reason", ""), files)
            }.filterNotNull()
        }.getOrDefault(emptyList())
    }

    /** 追加一条变更日志（reason 为 AI 任务摘要；files 为本次改动的相对路径）。 */
    fun changelogAdd(p: Project, reason: String, files: List<String>, extra: String = "") {
        val list = changeList(p).toMutableList()
        list.add(0, ChangeEntry(ts(), reason.take(200).ifBlank { "（无说明）" }, files.distinct().take(200)))
        if (list.size > 120) list.removeAt(list.size - 1)
        val arr = JSONArray()
        list.forEach { e ->
            val fa = JSONArray()
            e.files.forEach { fa.put(it) }
            arr.put(JSONObject().put("ts", e.ts).put("reason", e.reason).put("files", fa))
        }
        File(studioDir(p), "changelog.json").writeText(arr.toString())
        val md = File(studioDir(p), "changelog.md")
        val head = if (md.isFile) md.readText() else "# 变更日志\n\n> 由 AI 工作台自动记录\n"
        val newEntry = buildString {
            append("\n## ").append(ts()).append("\n")
            append("- 原因：").append(reason.take(200).ifBlank { "（无说明）" }).append("\n")
            if (extra.isNotBlank()) append("- 说明：").append(extra.take(300)).append("\n")
            files.distinct().forEach { append("- `").append(it).append("`\n") }
        }
        md.writeText(head.trimEnd() + "\n" + newEntry)
    }

    fun changelogMarkdown(p: Project): String = runCatching {
        val f = File(studioDir(p), "changelog.md")
        if (f.isFile) f.readText().take(60000) else "（暂无变更记录）"
    }.getOrDefault("")

    // ======================= 任务复盘 =======================

    data class Review(val ts: String, val task: String, val summary: String, val points: List<String>)

    fun reviewList(p: Project): List<Review> {
        val f = File(studioDir(p), "reviews.json")
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.optJSONObject(i) ?: return@map null
                val pa = o.optJSONArray("points") ?: JSONArray()
                val points = (0 until pa.length()).mapNotNull { j -> pa.optString(j).ifBlank { null } }
                Review(o.optString("ts", ""), o.optString("task", ""), o.optString("summary", ""), points)
            }.filterNotNull()
        }.getOrDefault(emptyList())
    }

    /** 任务完成后自动复盘：写入 reviews.json，并追加一条记忆小节供后续复用。 */
    fun reviewAdd(p: Project, task: String, summary: String, points: List<String>) {
        val list = reviewList(p).toMutableList()
        list.add(0, Review(ts(), task.take(120), summary.take(800), points.take(40)))
        if (list.size > 60) list.removeAt(list.size - 1)
        val arr = JSONArray()
        list.forEach { r ->
            val pa = JSONArray()
            r.points.forEach { pa.put(it) }
            arr.put(JSONObject().put("ts", r.ts).put("task", r.task).put("summary", r.summary).put("points", pa))
        }
        File(studioDir(p), "reviews.json").writeText(arr.toString())
        val body = buildString {
            append("任务：").append(task.take(120)).append("\n")
            append("结果：").append(summary.take(400)).append("\n")
            if (points.isNotEmpty()) {
                append("经验点：\n")
                points.take(20).forEach { append("- ").append(it.take(160)).append("\n") }
            }
        }
        memoryAppend(p, "任务复盘", body.trim())
    }

    // ======================= 运行日志 =======================

    fun logFile(p: Project, tag: String): File =
        File(dir(p, "logs"), "${tag}_${stamp()}.log")

    fun saveRunLog(p: Project, tag: String, content: String): File {
        val f = logFile(p, tag)
        f.writeText(content)
        return f
    }

    fun listLogs(p: Project): List<File> =
        dir(p, "logs").listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun listStudioFiles(p: Project): List<File> =
        studioDir(p).listFiles()?.filter { it.isFile } ?: emptyList()

    // ======================= 项目变量 =======================

    fun variablesFile(p: Project): File = File(studioDir(p), "variables.json")

    fun listVariables(p: Project): Map<String, String> {
        val f = variablesFile(p)
        if (!f.isFile) return emptyMap()
        return runCatching {
            val o = JSONObject(f.readText())
            o.keys().asSequence().map { it to o.optString(it, "") }.toMap()
        }.getOrDefault(emptyMap())
    }

    fun setVariable(p: Project, key: String, value: String): Boolean {
        val k = key.trim()
        if (k.isBlank()) return false
        val map = listVariables(p).toMutableMap()
        map[k] = value
        val o = JSONObject()
        map.forEach { (a, b) -> o.put(a, b) }
        variablesFile(p).writeText(o.toString())
        return true
    }

    fun deleteVariable(p: Project, key: String) {
        val map = listVariables(p).toMutableMap()
        map.remove(key)
        val o = JSONObject()
        map.forEach { (a, b) -> o.put(a, b) }
        variablesFile(p).writeText(o.toString())
    }

    /** 把文本中的 {{key}} / ${key} 替换为项目变量；内置 project/version/path。 */
    fun resolveVariables(p: Project, text: String): String {
        var out = text
        val builtin = mutableMapOf(
            "project" to p.name,
            "path" to p.path.absolutePath,
            "type" to p.type
        )
        // 尝试读取 Android versionName / Flutter version
        runCatching {
            val g = File(p.path, "app/build.gradle")
            if (g.isFile) {
                Regex("versionName\\s+\"([^\"]+)\"").find(g.readText())?.let { builtin["version"] = it.groupValues[1] }
            }
        }
        runCatching {
            val pu = File(p.path, "pubspec.yaml")
            if (pu.isFile) {
                Regex("^version:\\s*(.+)$", RegexOption.MULTILINE).find(pu.readText())?.let {
                    builtin["version"] = it.groupValues[1].trim()
                }
            }
        }
        if (!builtin.containsKey("version")) builtin["version"] = "1.0.0"
        (builtin + listVariables(p)).forEach { (k, v) ->
            out = out.replace("{{" + k + "}}", v)
            out = out.replace("${'$'}{" + k + "}", v)
        }
        return out
    }

    // ======================= 自定义插件（沙盒隔离） =======================

    data class PluginSpec(
        val name: String,
        val description: String,
        val command: String,
        val enabled: Boolean,
        val file: File
    )

    private fun pluginsDir(p: Project): File = dir(p, "plugins")

    /** 是否被用户一键禁用（.studio/.features_off 存在）。 */
    fun featuresDisabled(p: Project): Boolean = File(studioDir(p), ".features_off").isFile

    fun setFeaturesDisabled(p: Project, disabled: Boolean) {
        val mark = File(studioDir(p), ".features_off")
        if (disabled) { ensureIgnored(p); mark.writeText("all custom features disabled\n") } else mark.delete()
    }

    fun listPlugins(p: Project): List<PluginSpec> {
        val base = pluginsDir(p)
        return runCatching {
            base.listFiles()?.filter { it.isFile && it.extension.equals("json", true) }
                ?.mapNotNull { f ->
                    runCatching {
                        val o = JSONObject(f.readText())
                        val name = o.optString("name").ifBlank { f.nameWithoutExtension }
                        PluginSpec(name, o.optString("description", ""), o.optString("command", ""), true, f)
                    }.getOrNull()
                } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun savePlugin(p: Project, name: String, description: String, command: String) {
        val f = File(pluginsDir(p), "${name.trim().replace(Regex("[^\\w\\-.]"), "_")}.json")
        val o = JSONObject().put("name", name.trim()).put("description", description.trim()).put("command", command.trim())
        f.writeText(o.toString())
        memoryAppend(p, "自定义插件", "新增插件 `$name`（command: `${command.take(80)}`）。启用状态保存在 .studio/plugins/。")
    }

    fun deletePlugin(p: Project, name: String) {
        runCatching { listPlugins(p).firstOrNull { it.name == name }?.file?.delete() }
    }

    // ======================= 能力导出 / 迁移 =======================

    /**
     * 把项目能力（记忆/变更/复盘/变量/插件/运行日志清单）打包为 ZIP。
     * 返回文件路径；调用方可用 WorkspaceManager/ApkManager 导出到 Downloads。
     */
    fun exportAbility(p: Project, into: File? = null): File {
        val out = into ?: File(dir(p, "export"), "ability_${stamp()}.zip")
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            val include = listOf("memory.json", "changelog.json", "changelog.md", "reviews.json", "variables.json", ".features_off")
            include.forEach { name ->
                val f = File(studioDir(p), name)
                if (f.isFile) putZip(zos, "$name", f.readBytes())
            }
            val pd = pluginsDir(p)
            pd.listFiles()?.filter { it.isFile }?.forEach { f ->
                putZip(zos, "plugins/${f.name}", f.readBytes())
            }
            putZip(zos, "manifest.txt",
                ("GitHubK Ability Bundle\nExported: ${ts()}\nProject: ${p.name}\n" +
                    "Plugins: ${listPlugins(p).size}\nFeaturesDisabled: ${featuresDisabled(p)}\n").toByteArray())
        }
        return out
    }

    /** 导入能力包：安全解压 zip（拒绝 .. / 绝对路径），覆盖/追加对应文件。 */
    fun importAbility(p: Project, zipFile: File): Int {
        var count = 0
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var e: ZipEntry? = zis.nextEntry
            while (e != null) {
                val name = e.name.replace('\\', '/')
                if (!e.isDirectory) {
                    val safe = name.removePrefix("/").split('/').filter { it != ".." }
                    val rel = safe.joinToString("/")
                    if (rel.isNotBlank() && !rel.endsWith(".zip")) {
                        val target = File(studioDir(p), rel).canonicalFile
                        if (target.path.startsWith(studioDir(p).canonicalFile.path + File.separator)) {
                            target.parentFile?.mkdirs()
                            target.writeBytes(zis.readBytes())
                            count++
                        }
                    }
                }
                e = zis.nextEntry
            }
        }
        if (count > 0) memoryAppend(p, "能力迁移", "已导入能力包：${zipFile.name}，共 $count 个文件。")
        return count
    }

    private fun putZip(zos: ZipOutputStream, name: String, bytes: ByteArray) {
        val ze = ZipEntry(name)
        zos.putNextEntry(ze)
        zos.write(bytes)
        zos.closeEntry()
    }

    // ======================= 自动文档生成（轻量确定性） =======================

    /**
     * 生成 README.md + docs/guide.md：基于目录结构、关键源文件的类/函数注释。
     * 全部写回项目目录（走 writeSafe 语义，调用方确保路径可写）。
     */
    fun generateDocs(p: Project, out: (File, String) -> Boolean): String {
        val files = p.path.walkTopDown()
            .filter { it.isFile && it.extension in WorkspaceManager.SOURCE_EXT && !it.path.contains(File.separator + ".studio" + File.separator) }
            .filter { !it.path.contains("build") && !it.path.contains(".gradle") }
            .toList().sortedBy { it.path }
        val sb = StringBuilder()
        sb.append("# ").append(p.name).append("\n\n")
        sb.append("> 自动生成 · ").append(ts()).append("\n\n")
        sb.append("## 项目概览\n\n")
        sb.append("- 类型：").append(p.type).append("\n")
        sb.append("- 源文件数：").append(files.size).append("\n")
        sb.append("- 能力清单：").append(buildString {
            append("修改文件自动记录、任务复盘、项目记忆、依赖助手、批量修复、失败回滚、审计日志")
        }).append("\n\n")
        sb.append("## 目录结构\n\n```\n")
        p.path.walkTopDown()
            .filter { it.isDirectory }
            .filter { d ->
                val n = d.name
                n !in setOf("build", ".gradle", ".git", "node_modules", ".dart_tool", ".studio", "gradle", "cxx", ".cxx") &&
                    !d.path.contains(File.separator + "build" + File.separator)
            }
            .forEach { d -> sb.append(d.relativeTo(p.path).path.replace('\\', '/')).append("/\n") }
        sb.append("```\n\n## 主要文件\n\n")
        files.take(120).forEach { f ->
            val rel = f.relativeTo(p.path).path.replace('\\', '/')
            val first = runCatching {
                f.readLines().firstOrNull { it.startsWith("//") || it.startsWith("#") || it.startsWith("/*") }?.substringAfter("//").orEmpty().trim().take(60)
            }.getOrDefault("")
            sb.append("- `").append(rel).append("`").append(if (first.isNotBlank()) " — " + first else "").append("\n")
        }
        sb.append("\n## 使用\n\n由 AI 工作台自动管理任务、构建、回滚与记忆。详细说明见 docs/guide.md。\n")

        val guide = StringBuilder()
        guide.append("# 使用教程\n\n").append(ts()).append("\n\n")
        guide.append("## 常用操作\n\n1. 在 AI 工作台描述需求（可附图）并发送。\n")
        guide.append("2. 系统先摘要确认，再执行；风险操作会弹窗审批。\n")
        guide.append("3. AI 自动：读取项目记忆 → 修改/新增文件（自动快照）→ 构建验证 → 变更日志/复盘。\n")
        guide.append("4. 构建失败：自动回滚本次文件修改并保留完整错误日志（.studio/logs/）。\n")
        guide.append("5. 查看问题：扫描 Problems / 批量修复；记忆与能力可从「功能面板」迁移/导出。\n")
        guide.append("\n## 文件说明\n\n")
        guide.append("- .studio/memory.json：长期记忆\n")
        guide.append("- .studio/changelog.md：变更日志\n")
        guide.append("- .studio/reviews.json：任务复盘\n")
        guide.append("- .studio/variables.json：变量面板（任务中用 {{key}}）\n")
        guide.append("- .studio/plugins/：沙盒自定义插件\n")
        guide.append("- .studio/logs/：Agent 运行与报错日志\n")

        val readmeOk = out(File(p.path, "README.md"), sb.toString())
        val guideOk = out(File(File(p.path, "docs"), "guide.md"), guide.toString())
        return if (readmeOk || guideOk) {
            memoryAppend(p, "自动文档", "已生成 README.md / docs/guide.md")
            "OK 已生成 README.md 与 docs/guide.md"
        } else "ERROR: 文档写入失败"
    }
}
