package com.example.myempty.githubk.editor

import java.io.File

/**
 * EditorTab：单文件编辑状态。
 */
data class EditorTab(
    val file: File,
    var content: String,
    var dirty: Boolean = false,
    var scrollPos: Int = 0,
    var cursorLine: Int = 0
) {
    val name: String get() = file.name
    val path: String get() = file.absolutePath
}

/**
 * EditorManager v2.5：真正的多 Tab 编辑状态管理。
 */
class EditorManager {

    private val tabs = mutableListOf<EditorTab>()
    var activeIndex: Int = -1

    val size: Int get() = tabs.size

    fun active(): EditorTab? = if (activeIndex in tabs.indices) tabs[activeIndex] else null

    fun open(file: File): EditorTab {
        tabs.indexOfFirst { it.file.absolutePath == file.absolutePath }.let { idx ->
            if (idx >= 0) { activeIndex = idx; return tabs[idx] }
        }
        val tab = EditorTab(file, if (file.exists()) file.readText() else "", dirty = false)
        tabs.add(tab)
        activeIndex = tabs.lastIndex
        return tab
    }

    fun openNew(file: File, content: String): EditorTab {
        closeByPath(file.absolutePath)
        val tab = EditorTab(file, content, dirty = true)
        tabs.add(tab)
        activeIndex = tabs.lastIndex
        return tab
    }

    fun close(index: Int) {
        if (index !in tabs.indices) return
        tabs.removeAt(index)
        if (tabs.isEmpty()) activeIndex = -1
        else activeIndex = index.coerceAtMost(tabs.lastIndex)
    }

    fun closeByPath(path: String) {
        val idx = tabs.indexOfFirst { it.file.absolutePath == path }
        if (idx >= 0) close(idx)
    }

    /** 关闭所有路径满足条件的标签页（用于删除目录/文件后清理）。 */
    fun closeWhere(predicate: (EditorTab) -> Boolean) {
        val indices = tabs.indices.filter { predicate(tabs[it]) }.sortedDescending()
        indices.forEach { close(it) }
    }

    fun all(): List<EditorTab> = tabs.toList()

    fun markSaved(tab: EditorTab) {
        tab.dirty = false
    }

    fun saveActive(): Boolean {
        val tab = active() ?: return false
        return try {
            tab.file.parentFile?.mkdirs()
            tab.file.writeText(tab.content)
            tab.dirty = false
            true
        } catch (_: Throwable) { false }
    }

    fun saveAs(tab: EditorTab, newFile: File): Boolean {
        return try {
            newFile.parentFile?.mkdirs()
            newFile.writeText(tab.content)
            true
        } catch (_: Throwable) { false }
    }

    fun hasDirty(): Boolean = tabs.any { it.dirty }

    fun dirtyFiles(): List<File> = tabs.filter { it.dirty }.map { it.file }

    /** 在当前 tab 中搜索，返回 (行号列表, 匹配总数) */
    fun search(tab: EditorTab, query: String): Pair<List<Int>, Int> {
        if (query.isBlank()) return emptyList<Int>() to 0
        val lines = tab.content.lines()
        val matches = mutableListOf<Int>()
        lines.forEachIndexed { i, line -> if (line.contains(query, true)) matches.add(i + 1) }
        return matches to matches.size
    }

    /** 替换所有，返回替换次数 */
    fun replaceAll(tab: EditorTab, query: String, replacement: String): Int {
        if (query.isBlank()) return 0
        val before = tab.content
        tab.content = tab.content.replace(query, replacement)
        val n = countOccurrences(before, query)
        if (n > 0) tab.dirty = true
        return n
    }

    private fun countOccurrences(s: String, q: String): Int {
        var count = 0
        var idx = 0
        while (true) {
            idx = s.indexOf(q, idx)
            if (idx < 0) break
            count++
            idx += q.length
        }
        return count
    }

    fun gotoLine(tab: EditorTab, line: Int): Int {
        val target = line.coerceAtLeast(1)
        tab.cursorLine = target
        return target
    }
}