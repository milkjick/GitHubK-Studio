package com.example.myempty.githubk.buildsys

import com.example.myempty.githubk.core.Project
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * BuildManager v4.5
 * 统一构建队列管理器：串行执行构建任务，维护当前/队列/历史状态。
 */
class BuildManager(
    private val engine: BuildEngine,
    private val history: BuildHistory,
    private val onLog: (String) -> Unit = {}
) {

    data class BuildRequest(
        val id: Long,
        val project: Project,
        val task: String,
        val sdkHome: String = "",
        val javaHome: String = "",
        val gradleHome: String = "",
        val flutterBin: String = ""
    )

    data class BuildItem(
        val request: BuildRequest,
        var state: String = "queued"
    )

    private val idGen = AtomicLong(1)
    private val queue = ArrayDeque<BuildItem>()
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var currentItem: BuildItem? = null
    @Volatile private var cancelled = false

    /** 同步执行一次构建（用于 AI/Skill/MCP 等非 UI 调用） */
    fun buildDetailed(path: File, type: String): BuildResult {
        onLog("[BUILD] 开始构建: $path ($type)")
        val result = engine.build(path, type)
        record(result, path.name, if (type == "Flutter") "build apk --debug" else "assembleDebug", "")
        return result
    }

    /** 异步提交构建任务到队列 */
    fun submit(
        project: Project,
        task: String = if (project.type == "Flutter") "build apk --debug" else "assembleDebug",
        sdkHome: String = "",
        javaHome: String = "",
        gradleHome: String = "",
        flutterBin: String = "",
        onDone: (BuildResult) -> Unit = {}
    ) {
        val item = BuildItem(
            BuildRequest(idGen.getAndIncrement(), project, task, sdkHome, javaHome, gradleHome, flutterBin)
        )
        queue.addLast(item)
        onLog("[BUILD] #${item.request.id} 已入队: ${project.name} / $task")
        executor.execute { processQueue(onDone) }
    }

    private fun processQueue(onDone: (BuildResult) -> Unit) {
        if (currentItem != null) return
        val item = queue.removeFirstOrNull() ?: return
        currentItem = item
        item.state = "running"
        onLog("[BUILD] #${item.request.id} 开始执行: ${item.request.project.name}")
        val result = engine.build(
            projectPath = item.request.project.path,
            type = item.request.project.type,
            task = item.request.task,
            sdkHome = item.request.sdkHome,
            javaHome = item.request.javaHome,
            gradleHome = item.request.gradleHome,
            flutterBin = item.request.flutterBin
        )
        item.state = if (result.success) "success" else "failed"
        record(result, item.request.project.name, item.request.task, "")
        onDone(result)
        currentItem = null
        // 继续处理队列中的下一个任务
        executor.execute { processQueue(onDone) }
    }

    /** 当前是否有构建在跑 */
    fun isBuilding(): Boolean = currentItem != null && currentItem!!.state == "running"

    /** 请求取消当前构建 */
    fun cancel(): Boolean {
        cancelled = true
        engine.requestCancel()
        return isBuilding()
    }

    /** 当前构建项 */
    fun current(): BuildItem? = currentItem

    /** 队列中的所有项（含当前） */
    fun queued(): List<BuildItem> = queue.toList()

    /** 清空队列，返回移除数量 */
    fun clearQueue(): Int {
        val n = queue.size
        queue.clear()
        return n
    }

    private fun record(result: BuildResult, projectName: String, task: String, summary: String) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        history.save(
            BuildHistory.Entry(
                success = result.success,
                project = projectName,
                task = task,
                time = time,
                summary = summary.ifBlank {
                    if (result.success) "构建成功 ($task)" else "构建失败 ($task)"
                },
                output = result.output
            )
        )
    }
}
