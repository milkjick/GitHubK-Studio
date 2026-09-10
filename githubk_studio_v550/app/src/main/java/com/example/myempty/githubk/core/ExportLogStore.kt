package com.example.myempty.githubk.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ExportLogStore v5.1 — 统一本地下载导出管理
 *
 * 记录所有 AI / 用户在设备本地导出的文件（文件名、保存路径、大小、导出时间），
 * 供「导出记录」面板展示与重新打开；最多保留 200 条，新的覆盖旧的。
 */
object ExportLogStore {

    private const val MAX = 200

    private fun file(c: Context): File =
        File(File(c.filesDir, ".studio"), "export_log.json").apply { parentFile?.mkdirs() }

    fun add(c: Context, name: String, path: String, size: Long, kind: String = "export") {
        runCatching {
            val arr = read(c)
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            arr.put(
                JSONObject()
                    .put("time", now)
                    .put("name", name)
                    .put("path", path)
                    .put("size", size)
                    .put("kind", kind)
            )
            while (arr.length() > MAX) arr.remove(0)
            file(c).writeText(arr.toString())
        }
    }

    /** 倒序（最新在前）。 */
    fun list(c: Context): List<JSONObject> {
        val arr = read(c)
        val out = ArrayList<JSONObject>(arr.length())
        for (i in arr.length() - 1 downTo 0) out.add(arr.getJSONObject(i))
        return out
    }

    private fun read(c: Context): JSONArray {
        val f = file(c)
        if (!f.isFile) return JSONArray()
        return runCatching { JSONArray(f.readText()) }.getOrDefault(JSONArray())
    }

    fun clear(c: Context) {
        runCatching { file(c).delete() }
    }
}
