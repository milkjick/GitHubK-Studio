package com.example.myempty.githubk.ai

import com.example.myempty.githubk.buildsys.BuildManager
import com.example.myempty.githubk.core.Project
import com.example.myempty.githubk.core.WorkspaceManager
import com.example.myempty.githubk.git.GitManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * SkillManager v2.6：Skill 工作流系统。
 * - 内置 skill 模板（代码审查 / 构建修复 / 初始化项目 / 新增页面 / Git 发布）
 * - 从工作区 <project>/.githubk/skills/ 下的 JSON 文件加载自定义 skill
 * - skill 定义：id / 名称 / 描述 / 步骤（每步调用 Agent 工具或内置动作）
 * - execute(skillId, project, onLog) 顺序执行步骤，返回汇总
 */
data class SkillStep(
    val name: String,
    val action: String,     // tool 名称（list_files/read_file/build/git_commit...）或 "prompt"
    val param: String = ""  // 附加参数（路径/提示词模板）
)

data class SkillDef(
    val id: String,
    val name: String,
    val description: String,
    val steps: List<SkillStep>
)

class SkillManager(
    private val workspace: WorkspaceManager,
    private val buildManager: BuildManager,
    private val git: GitManager
) {

    private val builtin: List<SkillDef> = listOf(
        SkillDef(
            id = "code_review",
            name = "代码审查",
            description = "审查项目核心源码，找出潜在 bug、安全问题与改进建议",
            steps = listOf(
                SkillStep("列出源码", "list_files"),
                SkillStep("读取核心文件", "read_core"),
                SkillStep("生成审查报告", "prompt", "请对上面的项目源码做一次代码审查：列出 1) 潜在 bug 2) 安全问题 3) 可读性/性能改进建议。逐条说明。")
            )
        ),
        SkillDef(
            id = "build_fix",
            name = "构建修复",
            description = "构建项目，若失败则读取错误日志并给出修复方案",
            steps = listOf(
                SkillStep("执行构建", "build"),
                SkillStep("读取错误日志", "build_log"),
                SkillStep("分析并修复", "prompt", "构建输出如上。请分析编译错误，指出需要修改的文件与具体改法（包括精确代码片段）。")
            )
        ),
        SkillDef(
            id = "init_project",
            name = "初始化项目",
            description = "基于模板新建项目（Android / Flutter / 空目录）",
            steps = listOf(
                SkillStep("确认模板", "prompt", "用户想新建项目，请确认目标名称与类型（Android/Flutter/Other），然后我会调用工具创建。"),
                SkillStep("创建项目", "create_project")
            )
        ),
        SkillDef(
            id = "add_page",
            name = "新增页面",
            description = "为 Android 项目新增一个简单 Activity/页面",
            steps = listOf(
                SkillStep("读取项目结构", "list_files"),
                SkillStep("读取 AndroidManifest", "read_manifest"),
                SkillStep("生成页面代码", "prompt", "请为项目生成一个现代风格的新页面代码：Kotlin Activity + XML 布局 + 注册到 AndroidManifest 的步骤说明。给出完整文件内容。")
            )
        ),
        SkillDef(
            id = "git_release",
            name = "Git 发布",
            description = "查看状态 → 暂存全部 → 提交 → 推送",
            steps = listOf(
                SkillStep("查看状态", "git_status"),
                SkillStep("提交全部", "git_commit"),
                SkillStep("推送", "git_push")
            )
        ),
        SkillDef(
            id = "todo_scan",
            name = "待办扫描",
            description = "扫描项目源码中的 TODO/FIXME/HACK/XXX 标记，生成待办清单",
            steps = listOf(
                SkillStep("扫描待办标记", "todo_scan"),
                SkillStep("整理待办", "prompt", "请根据上面的待办扫描结果：1) 按文件分组列出未完成任务 2) 标出可立刻处理的高优先级项 3) 给出 2~3 条下一步建议。")
            )
        ),
        SkillDef(
            id = "dep_check",
            name = "依赖体检",
            description = "读取 build.gradle / pubspec.yaml / package.json 依赖，检查版本与冲突",
            steps = listOf(
                SkillStep("读取依赖清单", "read_deps"),
                SkillStep("分析依赖", "prompt", "请根据上面的依赖清单：1) 列出主要依赖及其用途 2) 指出版本过旧/有隐患的依赖 3) 给出升级建议。")
            )
        ),
        SkillDef(
            id = "project_stats",
            name = "项目统计",
            description = "统计源码文件数、代码行数、语言/类型分布，快速了解项目规模",
            steps = listOf(
                SkillStep("统计项目指标", "project_stats")
            )
        ),
        SkillDef(
            id = "readme_gen",
            name = "生成 README",
            description = "为项目生成基础 README.md（项目简介/结构/构建命令），已存在则不覆盖",
            steps = listOf(
                SkillStep("生成基础 README", "gen_readme")
            )
        ),
        SkillDef(
            id = "clean_build",
            name = "清理重建",
            description = "清理项目 build 产物后重新构建，验证干净环境能否编译通过",
            steps = listOf(
                SkillStep("清理构建产物", "clean_build"),
                SkillStep("查看结果", "build_log")
            )
        )
    )

    fun list(): List<SkillDef> = builtin + loadProjectSkills()

    private fun loadProjectSkills(): List<SkillDef> {
        val root = workspace.rootDir()
        if (!root.exists()) return emptyList()
        val out = mutableListOf<SkillDef>()
        root.listFiles()?.forEach { proj ->
            val dir = java.io.File(proj, ".githubk/skills")
            if (dir.exists()) {
                dir.listFiles()?.filter { it.extension == "json" }?.forEach { f ->
                    try {
                        val o = JSONObject(f.readText())
                        val steps = o.optJSONArray("steps") ?: JSONArray()
                        val stepList = (0 until steps.length()).map { i ->
                            val s = steps.getJSONObject(i)
                            SkillStep(s.optString("name"), s.optString("action"), s.optString("param"))
                        }
                        out.add(SkillDef(o.optString("id"), o.optString("name"), o.optString("description"), stepList))
                    } catch (_: Throwable) { /* skip invalid skill */ }
                }
            }
        }
        return out
    }

    fun find(id: String): SkillDef? = list().firstOrNull { it.id == id }

    private var lastBuildOutput: String = ""

    fun execute(skillId: String, project: Project?, onLog: (String) -> Unit = {}): String {
        val skill = find(skillId) ?: return "ERROR: skill not found: $skillId"
        val sb = StringBuilder()
        sb.appendLine("▶ 执行 Skill: ${skill.name} (${skill.id})")
        onLog("▶ 执行 Skill: ${skill.name}")
        project?.let { onLog("目标项目: ${it.name} [${it.type}]") }

        var stepNo = 0
        for (step in skill.steps) {
            stepNo++
            sb.appendLine("\n[步骤 $stepNo/${skill.steps.size}] ${step.name}")
            onLog("[步骤 $stepNo] ${step.name} ...")
            val output = runStep(skill, step, project, onLog)
            sb.append(output.take(3000)).append("\n")
            onLog(output.take(500))
        }
        sb.appendLine("\n✓ Skill 完成: ${skill.name}")
        return sb.toString()
    }

    private fun runStep(skill: SkillDef, step: SkillStep, project: Project?, onLog: (String) -> Unit): String {
        val p = project
        return when (step.action) {
            "list_files" -> if (p != null) workspace.agentContext(p).take(3000) else "ERROR: no project selected"
            "read_core" -> readCore(p)
            "read_manifest" -> readManifest(p)
            "build" -> {
                val r = if (p != null) buildManager.buildDetailed(p.path, p.type) else null
                lastBuildOutput = r?.output ?: "ERROR: no project selected"
                r?.render()?.take(4000) ?: "ERROR: no project selected"
            }
            "build_log" -> lastBuildOutput.take(4000).ifBlank { "(无构建日志，先执行构建步骤)" }
            "git_status" -> if (p != null) git.status(p.path).joinToString("\n") { (f, s) -> "$s $f" }.ifBlank { "clean" } else "ERROR: no project selected"
            "git_commit" -> if (p != null) git.commitAll(p.path, "Skill: ${skill.name}").output.take(1000) else "ERROR: no project selected"
            "git_push" -> if (p != null) git.push(p.path).output.take(1000) else "ERROR: no project selected"
            "todo_scan" -> if (p != null) scanTodos(p) else "ERROR: no project selected"
            "read_deps" -> if (p != null) readDeps(p) else "ERROR: no project selected"
            "project_stats" -> if (p != null) projectStats(p) else "ERROR: no project selected"
            "gen_readme" -> if (p != null) genReadme(p) else "ERROR: no project selected"
            "clean_build" -> if (p != null) cleanBuild(p) else "ERROR: no project selected"
            "create_project" -> "（请在 Agent 对话中提供项目名称与类型后，我调用工具创建）"
            "prompt" -> step.param
            else -> "unknown action: ${step.action}"
        }
    }

    private fun readCore(p: Project?): String {
        if (p == null) return "ERROR: no project selected"
        val sb = StringBuilder()
        val files = workspace.sourceFiles(p)
        files.filter { it.extension in setOf("kt", "java", "dart", "py") }
            .take(6)
            .forEach { f ->
                sb.appendLine("// ---- ${f.relativeTo(p.path)} ----")
                sb.appendLine(f.takeIf { it.length() < 30000 }?.readText()?.take(3000) ?: "(too large)")
                sb.appendLine()
            }
        return sb.toString().ifBlank { "(未找到核心源码文件)" }
    }

    private fun readManifest(p: Project?): String {
        if (p == null) return "ERROR: no project selected"
        val manifest = java.io.File(p.path, "app/src/main/AndroidManifest.xml")
        return if (manifest.exists()) manifest.readText().take(4000)
        else workspace.sourceFiles(p).firstOrNull { it.name == "AndroidManifest.xml" }?.let { it.readText().take(4000) }
            ?: "(未找到 AndroidManifest.xml)"
    }
    // ================= 新增工作流工具实现 =================

    private fun scanTodos(p: Project): String {
        val markers = listOf("TODO", "FIXME", "HACK", "XXX", "BUG")
        val sb = StringBuilder()
        var total = 0
        val exts = setOf("kt", "java", "dart", "py", "xml", "gradle", "kts", "json", "ts", "js", "md", "c", "h", "cpp")
        val skipDirs = setOf("build", ".gradle", ".git", "node_modules", ".dart_tool", ".idea", "gradle")
        val files = mutableListOf<File>()
        fun walk(dir: File) {
            val children = dir.listFiles() ?: return
            for (c in children) {
                if (c.isDirectory) { if (c.name !in skipDirs) walk(c) }
                else if (c.extension.lowercase() in exts && c.length() <= 1024 * 1024) files.add(c)
            }
        }
        walk(p.path)
        files.take(300).forEach { file ->
            val lines = runCatching { file.readText(Charsets.UTF_8).lines() }.getOrDefault(emptyList())
            lines.forEachIndexed { i, line ->
                val hit = markers.firstOrNull { line.contains(it, ignoreCase = true) } ?: return@forEachIndexed
                if (total < 120) sb.appendLine("${file.relativeTo(p.path)}:${i + 1} [$hit] ${line.trim().take(160)}")
                total++
            }
        }
        if (total == 0) return "✓ 未发现 TODO/FIXME/HACK/XXX 标记"
        return sb.toString() + "\n共 $total 处标记（仅显示前 120 条）"
    }

    private fun readDeps(p: Project): String {
        val sb = StringBuilder()
        val names = setOf(
            "build.gradle", "build.gradle.kts",
            "app/build.gradle", "app/build.gradle.kts",
            "settings.gradle", "settings.gradle.kts",
            "pubspec.yaml", "package.json", "requirements.txt", "Cargo.toml"
        )
        workspace.sourceFiles(p).filter { it.name in names }
            .take(10)
            .forEach { file ->
                sb.appendLine("// ---- ${file.relativeTo(p.path)} ----")
                sb.appendLine(file.takeIf { it.length() < 200_000 }?.readText()?.take(2500) ?: "(too large)")
                sb.appendLine()
            }
        return sb.toString().ifBlank { "(未找到依赖描述文件 build.gradle/pubspec.yaml/package.json)" }
    }

    private fun projectStats(p: Project): String {
        val files = workspace.sourceFiles(p)
        val byExt = linkedMapOf<String, Int>()
        var totalLines = 0L
        val countLimit = 600
        files.take(countLimit).forEach { file ->
            byExt[file.extension.ifBlank { "(none)" }] = (byExt[file.extension.ifBlank { "(none)" }] ?: 0) + 1
            if (file.length() < 512 * 1024) {
                try { totalLines += file.readLines().size } catch (_: Throwable) {}
            }
        }
        val sb = StringBuilder()
        sb.appendLine("文件总数: ${files.size}" + if (files.size > countLimit) "（统计前 $countLimit 个）" else "")
        sb.appendLine("代码总行数: $totalLines")
        sb.appendLine("类型分布:")
        byExt.entries.sortedByDescending { it.value }.take(12).forEach { (ext, cnt) ->
            sb.appendLine("  .$ext : $cnt")
        }
        return sb.toString()
    }

    private fun genReadme(p: Project): String {
        val readme = java.io.File(p.path, "README.md")
        if (readme.exists()) return "README.md 已存在，跳过生成（${readme.relativeTo(p.path)}）"
        val content = buildString {
            appendLine("# ${p.name}")
            appendLine()
            appendLine("> 由 GitHubK Studio 生成的基础项目说明")
            appendLine()
            appendLine("## 项目类型")
            appendLine(p.type)
            appendLine()
            appendLine("## 常用命令")
            appendLine("```bash")
            when (p.type.lowercase()) {
                "android" -> {
                    appendLine("# 构建 Debug APK")
                    appendLine("gradle assembleDebug")
                }
                "flutter" -> {
                    appendLine("# 拉取依赖并构建")
                    appendLine("flutter pub get && flutter build apk --debug")
                }
                else -> appendLine("# 请根据实际技术栈补充构建命令")
            }
            appendLine("```")
            appendLine()
            appendLine("## 目录结构")
            appendLine("```")
            appendLine("（请补充主要目录与用途说明）")
            appendLine("```")
        }
        readme.writeText(content, Charsets.UTF_8)
        return "✓ 已生成 README.md（${readme.relativeTo(p.path)}）"
    }

    private fun cleanBuild(p: Project): String {
        val base = p.path.canonicalFile
        val targets = listOf(
            java.io.File(p.path, "build"),
            java.io.File(p.path, "app/build"),
            java.io.File(p.path, ".gradle")
        )
        var cleaned = 0
        targets.forEach { t ->
            try {
                val c = t.canonicalFile
                if (c.path == base.path || !c.path.startsWith(base.path + java.io.File.separator)) return@forEach
                if (c.isDirectory) { c.deleteRecursively(); cleaned++ }
            } catch (_: Throwable) {}
        }
        val head = "已清理 $cleaned 个构建目录（build / app/build / .gradle）\n开始干净构建…\n"
        val r = buildManager.buildDetailed(p.path, p.type)
        return head + (r?.render()?.take(4000) ?: "ERROR: build failed or no output")
    }
}
