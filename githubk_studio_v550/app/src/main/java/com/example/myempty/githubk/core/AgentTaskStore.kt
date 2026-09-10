package com.example.myempty.githubk.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AgentTaskStore — 任务持久化（v6.0）
 *
 * 任务状态仅三态：待办 / 进行中 / 已完成（Todo / Running / Done）。
 * 存储：filesDir/agent_tasks/<projectKey>/tasks.json
 * AgentPage 退出后再进入可从「任务看板」恢复未完成任务（继续执行）。
 */
data class AgentTask(
    val id: String,
    val projectKey: String,
    val projectName: String,
    val title: String,
    val taskText: String,
    val createdAt: Long,
    var updatedAt: Long,
    var state: String, // 待办 | 进行中 | 已完成
    var note: String = "",
    var filesChanged: Int = 0,
    var iterations: Int = 0
) {
    companion object {
        const val TODO = "待办"
        const val RUN = "进行中"
        const val DONE = "已完成"
        fun fromJson(o: JSONObject): AgentTask = AgentTask(
            o.optString("id", System.currentTimeMillis().toString()),
            o.optString("projectKey", "_"),
            o.optString("projectName", ""),
            o.optString("title", "任务"),
            o.optString("taskText", ""),
            o.optLong("createdAt", System.currentTimeMillis()),
            o.optLong("updatedAt", System.currentTimeMillis()),
            o.optString("state", TODO),
            o.optString("note", ""),
            o.optInt("filesChanged", 0),
            o.optInt("iterations", 0)
        )
        fun toJson(t: AgentTask): JSONObject = JSONObject()
            .put("id", t.id).put("projectKey", t.projectKey).put("projectName", t.projectName)
            .put("title", t.title).put("taskText", t.taskText)
            .put("createdAt", t.createdAt).put("updatedAt", t.updatedAt)
            .put("state", t.state).put("note", t.note)
            .put("filesChanged", t.filesChanged).put("iterations", t.iterations)
    }
}

object AgentTaskStore {
    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    fun dir(c: Context, projectKey: String): File =
        File(File(c.filesDir, "agent_tasks"), sanitize(projectKey)).apply { mkdirs() }

    private fun sanitize(k: String): String = k.replace(Regex("[^\\w\\-.]"), "_")

    private fun file(c: Context, projectKey: String): File = File(dir(c, projectKey), "tasks.json")

    fun list(c: Context, projectKey: String): List<AgentTask> {
        val f = file(c, projectKey)
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                AgentTask.fromJson(o)
            }.sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    fun get(c: Context, projectKey: String, id: String): AgentTask? =
        list(c, projectKey).firstOrNull { it.id == id }

    private fun persist(c: Context, projectKey: String, list: List<AgentTask>) {
        val arr = JSONArray()
        list.sortedByDescending { it.updatedAt }.forEach { arr.put(AgentTask.toJson(it)) }
        file(c, projectKey).writeText(arr.toString())
    }

    fun upsert(c: Context, t: AgentTask) {
        val list = list(c, t.projectKey).toMutableList()
        val idx = list.indexOfFirst { it.id == t.id }
        if (idx >= 0) list[idx] = t else list.add(t)
        if (list.size > 200) list.removeAt(list.size - 1)
        persist(c, t.projectKey, list)
    }

    fun update(c: Context, projectKey: String, id: String, patch: (AgentTask) -> AgentTask) {
        val cur = get(c, projectKey, id) ?: return
        upsert(c, patch(cur).apply { updatedAt = System.currentTimeMillis() })
    }

    fun delete(c: Context, projectKey: String, id: String) {
        persist(c, projectKey, list(c, projectKey).filter { it.id != id })
    }

    fun newId(): String = "t" + System.currentTimeMillis() + "_" + (Math.random() * 10000).toInt()

    fun timeLabel(t: AgentTask): String = fmt.format(Date(t.updatedAt))

    /** 由 Agent 开始任务时调用：已有则转“进行中”，否则新建。返回任务。 */
    fun start(c: Context, projectKey: String, projectName: String, taskText: String, resumeId: String? = null): AgentTask {
        val title = taskText.replace(Regex("\\s+"), " ").trim().take(24).ifBlank { "（无标题）" }
        val now = System.currentTimeMillis()
        val existing = resumeId?.let { get(c, projectKey, it) }
        val t = existing ?: AgentTask(
            newId(), projectKey, projectName, title, taskText,
            now, now, AgentTask.RUN
        )
        t.state = AgentTask.RUN
        t.updatedAt = now
        t.note = ""
        upsert(c, t)
        return t
    }

    fun finish(c: Context, projectKey: String, id: String?, ok: Boolean, iterations: Int, filesChanged: Int, note: String = "") {
        if (id == null) return
        update(c, projectKey, id) {
            it.copy(state = if (ok) AgentTask.DONE else AgentTask.TODO, note = note)
                .apply { this.iterations = iterations; this.filesChanged = filesChanged }
        }
    }
}
