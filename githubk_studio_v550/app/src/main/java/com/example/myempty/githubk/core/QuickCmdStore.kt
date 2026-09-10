package com.example.myempty.githubk.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * QuickCmdStore — 自定义快捷指令面板（v6.0）
 * 保存常用指令，任务栏一键触发；按项目存储于 .studio/quick_cmds.json。
 */
object QuickCmdStore {

    private fun file(p: Project): File = File(File(File(p.path, ".studio"), ""), "quick_cmds.json")

    fun list(p: Project): List<String> {
        val f = file(p)
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
        }.getOrDefault(emptyList())
    }

    fun add(p: Project, cmd: String): Boolean {
        val c = cmd.trim()
        if (c.isBlank()) return false
        val list = list(p).toMutableList()
        if (list.contains(c)) return true
        list.add(0, c)
        if (list.size > 40) list.removeAt(list.size - 1)
        file(p).writeText(JSONArray().also { a -> list.forEach { a.put(it) } }.toString())
        return true
    }

    fun remove(p: Project, cmd: String) {
        file(p).writeText(JSONArray().also { a -> list(p).filter { it != cmd }.forEach { a.put(it) } }.toString())
    }
}
