package com.example.myempty.githubk.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AuditLog — 完整审计日志（v6.0）
 *
 * 所有 Agent 修改、工具调用、审批决定、AI 新增模块、回滚、导出等
 * 都以 JSONL 追加写入 filesDir/.studio/audit.jsonl；
 * 支持按项目/类型筛选查看，并一键导出 txt 到 Download。
 */
object AuditLog {
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun file(c: Context): File = File(File(c.filesDir, ".studio"), "audit.jsonl").apply { parentFile?.mkdirs() }

    private fun stamp(): String = fmt.format(Date())

    fun add(c: Context, projectKey: String, kind: String, detail: String, ok: Boolean = true, who: String = "agent") {
        runCatching {
            val o = JSONObject()
                .put("ts", stamp())
                .put("who", who)
                .put("project", projectKey)
                .put("kind", kind)
                .put("ok", ok)
                .put("detail", detail.take(2000))
            file(c).appendText(o.toString() + "\n")
        }
    }

    fun list(c: Context, projectKey: String? = null, kind: String? = null, limit: Int = 200): List<JSONObject> {
        val f = file(c)
        if (!f.isFile) return emptyList()
        return runCatching {
            f.readLines().reversed().mapNotNull { line ->
                runCatching { JSONObject(line) }.getOrNull()
            }.filter { o ->
                (projectKey == null || o.optString("project") == projectKey) &&
                    (kind == null || o.optString("kind") == kind)
            }.take(limit)
        }.getOrDefault(emptyList())
    }

    fun toText(list: List<JSONObject>): String = buildString {
        list.forEach { o ->
            append(o.optString("ts"))
            append("  [").append(o.optString("kind")).append("] ")
            append(o.optString("who")).append(" @")
            append(o.optString("project")).append(if (o.optBoolean("ok")) " ✓ " else " ✗ ")
            appendLine(o.optString("detail"))
        }
    }

    /** 导出审计日志到 Download，返回路径。 */
    fun export(c: Context, tag: String = "audit"): String? {
        val d = File(File(File(c.filesDir, ".studio"), "export"), "${tag}_${System.currentTimeMillis()}.txt")
        d.parentFile?.mkdirs()
        d.writeText(toText(list(c)) + "\n导出时间：${stamp()}\n")
        val dl = File("/sdcard/Download", d.name)
        return runCatching { d.copyTo(dl, overwrite = true); dl.absolutePath }.getOrNull() ?: d.absolutePath
    }
}
