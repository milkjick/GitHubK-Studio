package com.example.myempty.githubk.buildsys

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * BuildHistory v4.5
 * 持久化构建历史到 JSON 文件，提供 load/save 接口。
 */
class BuildHistory(private val file: File) {

    data class Entry(
        val success: Boolean,
        val project: String,
        val task: String,
        val time: String,
        val summary: String,
        val output: String
    )

    fun load(): List<Entry> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val o = arr.getJSONObject(i)
                    Entry(
                        success = o.optBoolean("success"),
                        project = o.optString("project"),
                        task = o.optString("task"),
                        time = o.optString("time"),
                        summary = o.optString("summary"),
                        output = o.optString("output")
                    )
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) { emptyList() }
    }

    fun save(entry: Entry) {
        val arr = JSONArray()
        load().take(50).forEach { arr.put(it.toJson()) }
        arr.put(entry.toJson())
        file.parentFile?.mkdirs()
        file.writeText(arr.toString())
    }

    private fun Entry.toJson(): JSONObject = JSONObject().apply {
        put("success", success)
        put("project", project)
        put("task", task)
        put("time", time)
        put("summary", summary)
        put("output", output.take(4000))
    }
}
