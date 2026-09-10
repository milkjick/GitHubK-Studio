package com.example.myempty.githubk.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ScheduleStore — 定时任务（前台轻调度，v6.0）
 *
 * 说明：GitHubK 在免 Root 环境下无法长期驻留后台；本模块提供
 * “应用/AgentPage 存活期间”的可靠前台调度（Handler 每 30 秒检查）。
 * 支持动作：
 *  - scan   自动扫描项目 Problems，并提示到系统条
 *  - export 自动把能力（记忆/变更/复盘/变量/插件）打包导出
 */
data class AgentJob(
    val id: String,
    val name: String,
    val projectKey: String,
    val action: String, // scan | export
    val periodMin: Int,
    val enabled: Boolean,
    var lastRunAt: Long = 0
) {
    companion object {
        fun fromJson(o: JSONObject): AgentJob = AgentJob(
            o.optString("id", "j" + System.currentTimeMillis()),
            o.optString("name", "定时任务"),
            o.optString("projectKey", "_"),
            o.optString("action", "scan"),
            o.optInt("periodMin", 30).coerceIn(5, 10080),
            o.optBoolean("enabled", true),
            o.optLong("lastRunAt", 0)
        )
        fun toJson(j: AgentJob): JSONObject = JSONObject()
            .put("id", j.id).put("name", j.name).put("projectKey", j.projectKey)
            .put("action", j.action).put("periodMin", j.periodMin)
            .put("enabled", j.enabled).put("lastRunAt", j.lastRunAt)
    }
}

object ScheduleStore {
    private fun file(c: Context): File = File(File(c.filesDir, ".studio"), "agent_jobs.json")

    fun list(c: Context): List<AgentJob> {
        val f = file(c)
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { AgentJob.fromJson(it) } }
        }.getOrDefault(emptyList())
    }

    private fun persist(c: Context, list: List<AgentJob>) {
        val arr = JSONArray()
        list.forEach { arr.put(AgentJob.toJson(it)) }
        file(c).writeText(arr.toString())
    }

    fun upsert(c: Context, j: AgentJob) {
        val list = list(c).toMutableList()
        val idx = list.indexOfFirst { it.id == j.id }
        if (idx >= 0) list[idx] = j else list.add(j)
        persist(c, list)
    }

    fun delete(c: Context, id: String) {
        persist(c, list(c).filter { it.id != id })
    }

    fun touch(c: Context, id: String) {
        val cur = list(c).firstOrNull { it.id == id } ?: return
        upsert(c, cur.copy(lastRunAt = System.currentTimeMillis()))
    }

    fun due(c: Context, now: Long = System.currentTimeMillis()): List<AgentJob> =
        list(c).filter { it.enabled && (now - it.lastRunAt) >= it.periodMin * 60_000L }
}
