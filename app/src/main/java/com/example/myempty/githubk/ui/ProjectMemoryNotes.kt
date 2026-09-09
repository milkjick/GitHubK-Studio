package com.example.myempty.githubk.ui

import com.example.myempty.githubk.core.Project
import java.io.File

/**
 * 项目记忆（备忘）读写工具。
 *
 * 与 AgentPage 使用同一存储约定：<project>/.studio/memory.json（纯文本）。
 * 仅记录用户可见的普通备忘（如发布记录），绝不写入任何 Token / 密钥。
 * 若对应仓库在本地工作区没有同名项目，则静默跳过（不创建本地文件）。
 */
object ProjectMemoryNotes {

    fun fileOf(proj: Project): File = File(File(proj.path, ".studio"), "memory.json")

    fun load(proj: Project): String =
        runCatching { val f = fileOf(proj); if (f.isFile) f.readText() else "" }.getOrDefault("")

    /** 追加一行备忘；本地项目不存在时不操作并返回 false。 */
    fun appendIfProjectExists(proj: Project?, note: String): Boolean {
        if (proj == null || note.isBlank()) return false
        return runCatching {
            val f = fileOf(proj)
            f.parentFile?.mkdirs()
            val old = if (f.isFile) f.readText().trim() else ""
            f.writeText(if (old.isBlank()) "$note\n" else "$old\n$note\n")
            true
        }.getOrDefault(false)
    }
}
