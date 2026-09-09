package com.example.myempty.githubk.terminal.vt

import kotlin.math.max
import kotlin.math.min

/**
 * Compact VT100/xterm emulator used by GitHubK's native PTY terminal.
 * It intentionally keeps the terminal state separate from Android rendering.
 * The PTY remains the source of truth for job control (Ctrl-C/Ctrl-Z/tmux/vim/top).
 */
class AnsiTerminal(var columns: Int = 80, var rows: Int = 24) {
    data class Cell(var ch: Char = ' ', var fg: Int = 0xFFD0D7DE.toInt(), var bg: Int = 0xFF0D1117.toInt(), var bold: Boolean = false, var underline: Boolean = false)
    private data class Screen(val cells: Array<Array<Cell>>, var cx: Int = 0, var cy: Int = 0, var savedX: Int = 0, var savedY: Int = 0, var fg: Int = 0xFFD0D7DE.toInt(), var bg: Int = 0xFF0D1117.toInt(), var bold: Boolean = false, var underline: Boolean = false, var top: Int = 0, var bottom: Int = 0)

    private var main = newScreen(columns, rows)
    private var alt = newScreen(columns, rows)
    private var usingAlt = false
    private val screen: Screen get() = if (usingAlt) alt else main
    private val scrollback = ArrayDeque<Array<Cell>>()
    private val maxScrollback = 4000
    private var state = 0
    private val csi = StringBuilder()
    private var osc = StringBuilder()
    private var oscEsc = false
    private var scrollOffset = 0
    var title: String = "GitHubK Studio"
        private set
    var cursorVisible = true
    var dirty: Boolean = true
        private set

    val cursorX get() = screen.cx
    val cursorY get() = screen.cy

    private fun newScreen(c: Int, r: Int): Screen {
        val cells = Array(r) { Array(c) { Cell() } }
        return Screen(cells, bottom = r - 1)
    }

    fun reset() {
        main = newScreen(columns, rows); alt = newScreen(columns, rows); usingAlt = false
        title = "GitHubK Studio"; state = 0; csi.clear(); osc.clear(); oscEsc = false
        scrollOffset = 0
        dirty = true
    }

    fun resize(newColumns: Int, newRows: Int) {
        if (newColumns == columns && newRows == rows) return
        fun resizeOne(old: Screen): Screen {
            val n = newScreen(newColumns, newRows)
            val copyRows = min(old.cells.size, n.cells.size)
            val copyCols = min(old.cells[0].size, n.cells[0].size)
            for (y in 0 until copyRows) for (x in 0 until copyCols) n.cells[y][x] = old.cells[y][x].copy()
            n.cx = min(old.cx, newColumns - 1); n.cy = min(old.cy, newRows - 1)
            n.savedX = min(old.savedX, newColumns - 1); n.savedY = min(old.savedY, newRows - 1)
            n.fg = old.fg; n.bg = old.bg; n.bold = old.bold; n.underline = old.underline
            n.top = 0; n.bottom = newRows - 1
            return n
        }
        main = resizeOne(main); alt = resizeOne(alt); columns = newColumns; rows = newRows; dirty = true
    }

    fun append(bytes: ByteArray, length: Int = bytes.size) {
        val text = bytes.copyOf(length).toString(Charsets.UTF_8)
        for (ch in text) feed(ch.code)
        if (scrollOffset > 0) { /* keep user scroll position while output continues */ }
        dirty = true
    }

    fun feed(code: Int) {
        when (state) {
            0 -> normal(code)
            1 -> esc(code)
            2 -> csiState(code)
            3 -> oscState(code)
        }
    }

    private fun normal(c: Int) {
        when (c) {
            0x1B -> state = 1
            0x0A -> lineFeed()
            0x0D -> screen.cx = 0
            0x08 -> screen.cx = max(0, screen.cx - 1)
            0x09 -> screen.cx = min(columns - 1, ((screen.cx / 8) + 1) * 8)
            0x07 -> Unit
            0x0E, 0x0F -> Unit
            in 0x20..0x7E, in 0xA0..0x10FFFF -> put(c.toChar())
        }
    }

    private fun esc(c: Int) {
        when (c) {
            '['.code -> { csi.clear(); state = 2 }
            ']'.code -> { osc.clear(); oscEsc = false; state = 3 }
            '7'.code -> saveCursor()
            '8'.code -> restoreCursor()
            'D'.code -> lineFeed()
            'M'.code -> reverseIndex()
            'E'.code -> { screen.cx = 0; lineFeed() }
            'H'.code -> { screen.cx = min(screen.cx, columns - 1); screen.cy = min(screen.cy, rows - 1) }
            'c'.code -> reset()
            '('.code, ')'.code, '#'.code -> { state = 0 }
            else -> state = 0
        }
    }

    private fun csiState(c: Int) {
        if (c in 0x40..0x7E) { applyCsi(c.toChar(), csi.toString()); csi.clear(); state = 0 }
        else if (c == 0x1B) state = 1
        else csi.append(c.toChar())
    }

    private fun oscState(c: Int) {
        if (oscEsc) {
            oscEsc = false
            if (c == '\\'.code) { applyOsc(osc.toString()); osc.clear(); state = 0 } else osc.append(0x1B.toChar()).append(c.toChar())
        } else if (c == 0x07) { applyOsc(osc.toString()); osc.clear(); state = 0 }
        else if (c == 0x1B) oscEsc = true
        else if (osc.length < 4096) osc.append(c.toChar())
    }

    private fun params(raw: String): Pair<Boolean, MutableList<Int>> {
        val private = raw.startsWith("?") || raw.startsWith(">") || raw.startsWith("!")
        val body = if (private) raw.substring(1) else raw
        val list = if (body.isBlank()) mutableListOf(0) else body.split(';').map { it.toIntOrNull() ?: 0 }.toMutableList()
        return private to list
    }

    private fun applyCsi(final: Char, raw: String) {
        val (private, p) = params(raw)
        fun n(i: Int, d: Int = 1) = if (i < p.size && p[i] != 0) p[i] else d
        when (final) {
            'A' -> screen.cy = max(screen.top, screen.cy - n(0))
            'B', 'e' -> screen.cy = min(screen.bottom, screen.cy + n(0))
            'C', 'a' -> screen.cx = min(columns - 1, screen.cx + n(0))
            'D' -> screen.cx = max(0, screen.cx - n(0))
            'E' -> { screen.cy = min(screen.bottom, screen.cy + n(0)); screen.cx = 0 }
            'F' -> { screen.cy = max(screen.top, screen.cy - n(0)); screen.cx = 0 }
            'G', '`' -> screen.cx = max(0, min(columns - 1, n(0) - 1))
            'd' -> screen.cy = max(screen.top, min(screen.bottom, n(0) - 1))
            'H', 'f' -> { screen.cy = max(screen.top, min(screen.bottom, n(0) - 1)); screen.cx = max(0, min(columns - 1, if (p.size > 1) n(1) - 1 else 0)) }
            'J' -> eraseDisplay(n(0))
            'K' -> eraseLine(n(0))
            'P' -> deleteChars(n(0))
            '@' -> insertChars(n(0))
            'L' -> insertLines(n(0))
            'M' -> deleteLines(n(0))
            'S' -> repeatScroll(n(0), true)
            'T' -> repeatScroll(n(0), false)
            'm' -> sgr(p)
            's' -> saveCursor()
            'u' -> restoreCursor()
            'r' -> { screen.top = max(0, min(rows - 1, n(0) - 1)); screen.bottom = max(screen.top, min(rows - 1, if (p.size > 1) n(1) - 1 else rows - 1)); screen.cx = 0; screen.cy = screen.top }
            'h', 'l' -> if (private) mode(private = true, set = final == 'h', p = p) else Unit
            't' -> Unit
            else -> Unit
        }
    }

    private fun mode(private: Boolean, set: Boolean, p: List<Int>) {
        for (m in p) when (m) {
            25 -> cursorVisible = set
            47, 1047, 1049 -> if (set) enterAlt() else leaveAlt()
            else -> Unit
        }
    }

    private fun enterAlt() { if (!usingAlt) { usingAlt = true; alt = newScreen(columns, rows) } }
    private fun leaveAlt() { if (usingAlt) { usingAlt = false } }

    private fun sgr(p: List<Int>) {
        if (p.isEmpty()) return
        var i = 0
        while (i < p.size) {
            when (p[i]) {
                0 -> { screen.fg = 0xFFD0D7DE.toInt(); screen.bg = 0xFF0D1117.toInt(); screen.bold = false; screen.underline = false }
                1 -> screen.bold = true
                22 -> screen.bold = false
                4 -> screen.underline = true
                24 -> screen.underline = false
                7 -> { val t = screen.fg; screen.fg = screen.bg; screen.bg = t }
                27 -> { val t = screen.fg; screen.fg = screen.bg; screen.bg = t }
                in 30..37 -> screen.fg = ansi16(p[i] - 30, screen.bold)
                39 -> screen.fg = 0xFFD0D7DE.toInt()
                in 40..47 -> screen.bg = ansi16(p[i] - 40, false)
                49 -> screen.bg = 0xFF0D1117.toInt()
                in 90..97 -> screen.fg = ansi16(p[i] - 90 + 8, false)
                in 100..107 -> screen.bg = ansi16(p[i] - 100 + 8, false)
                38, 48 -> {
                    val fg = p[i] == 38
                    if (i + 1 < p.size && p[i + 1] == 5 && i + 2 < p.size) {
                        val c = xterm256(p[i + 2]); if (fg) screen.fg = c else screen.bg = c; i += 2
                    } else if (i + 1 < p.size && p[i + 1] == 2 && i + 4 < p.size) {
                        val c = android.graphics.Color.rgb(p[i + 2].coerceIn(0,255), p[i + 3].coerceIn(0,255), p[i + 4].coerceIn(0,255)) or 0xFF000000.toInt(); if (fg) screen.fg = c else screen.bg = c; i += 4
                    }
                }
            }
            i++
        }
    }

    private fun ansi16(i: Int, bright: Boolean): Int {
        val palette = intArrayOf(0xFF0D1117.toInt(),0xFFF85149.toInt(),0xFF3FB950.toInt(),0xFFD29922.toInt(),0xFF58A6FF.toInt(),0xFFA371F7.toInt(),0xFF39C5CF.toInt(),0xFFD0D7DE.toInt(),0xFF484F58.toInt(),0xFFFF7B72.toInt(),0xFF56D364.toInt(),0xFFE3B341.toInt(),0xFF79C0FF.toInt(),0xFFD2A8FF.toInt(),0xFF56D4DD.toInt(),0xFFFFFFFF.toInt())
        return palette[(i + if (bright && i < 8) 8 else 0).coerceIn(0,15)]
    }
    private fun xterm256(i: Int): Int {
        if (i < 16) return ansi16(i, false)
        if (i in 16..231) {
            val n=i-16
            val r=n/36
            val g=(n%36)/6
            val b=n%6
            fun value(x:Int): Int = if(x==0) 0 else 55+x*40
            return (android.graphics.Color.rgb(value(r),value(g),value(b)) or 0xFF000000.toInt())
        }
        val v=8+(i-232)*10
        return android.graphics.Color.rgb(v,v,v) or 0xFF000000.toInt()
    }

    private fun put(ch: Char) {
        if (screen.cx >= columns) { screen.cx = 0; lineFeed() }
        val old = screen.cells[screen.cy][screen.cx]
        old.ch = ch; old.fg = screen.fg; old.bg = screen.bg; old.bold = screen.bold; old.underline = screen.underline
        screen.cx++
        if (screen.cx >= columns) screen.cx = columns // xterm autowrap pending is simplified
    }
    private fun lineFeed() { if (screen.cy == screen.bottom) scrollUp(1) else screen.cy = min(screen.bottom, screen.cy + 1) }
    private fun reverseIndex() { if (screen.cy == screen.top) scrollDown(1) else screen.cy = max(screen.top, screen.cy - 1) }
    private fun scrollUp(count: Int) { repeat(count) { val line=screen.cells[screen.top].map{it.copy()}.toTypedArray(); if(!usingAlt) { scrollback.add(line); while(scrollback.size>maxScrollback) scrollback.removeFirst() }; for(y in screen.top until screen.bottom) screen.cells[y]=screen.cells[y+1]; screen.cells[screen.bottom]=Array(columns){Cell(fg=screen.fg,bg=screen.bg)} } }
    private fun scrollDown(count: Int) { repeat(count) { for(y in screen.bottom downTo screen.top+1) screen.cells[y]=screen.cells[y-1]; screen.cells[screen.top]=Array(columns){Cell(fg=screen.fg,bg=screen.bg)} } }
    private fun repeatScroll(count:Int, up:Boolean){ repeat(count){ if(up) scrollUp(1) else scrollDown(1) } }
    private fun eraseDisplay(mode:Int){ when(mode){0->eraseLine(0).also{for(y in screen.cy+1 until rows) clearRow(y)};1->{for(y in 0 until screen.cy) clearRow(y); eraseLine(1)};2,3->for(y in 0 until rows) clearRow(y)} }
    private fun eraseLine(mode:Int){ val start=when(mode){1->0;2->0;else->screen.cx}; val end=when(mode){1->screen.cx;else->columns}; for(x in start until end) screen.cells[screen.cy][x]=Cell(fg=screen.fg,bg=screen.bg) }
    private fun clearRow(y:Int){ for(x in 0 until columns) screen.cells[y][x]=Cell(fg=screen.fg,bg=screen.bg) }
    private fun deleteChars(n:Int){ val k=n.coerceAtMost(columns-screen.cx); for(x in screen.cx until columns-k) screen.cells[screen.cy][x]=screen.cells[screen.cy][x+k].copy(); for(x in columns-k until columns) screen.cells[screen.cy][x]=Cell(fg=screen.fg,bg=screen.bg) }
    private fun insertChars(n:Int){ val k=n.coerceAtMost(columns-screen.cx); for(x in columns-1 downTo screen.cx+k) screen.cells[screen.cy][x]=screen.cells[screen.cy][x-k].copy(); for(x in screen.cx until min(columns,screen.cx+k)) screen.cells[screen.cy][x]=Cell(fg=screen.fg,bg=screen.bg) }
    private fun insertLines(n:Int){ val k=n.coerceAtMost(screen.bottom-screen.cy+1); repeat(k){ for(y in screen.bottom downTo screen.cy+1) screen.cells[y]=screen.cells[y-1]; screen.cells[screen.cy]=Array(columns){Cell(fg=screen.fg,bg=screen.bg)} } }
    private fun deleteLines(n:Int){ val k=n.coerceAtMost(screen.bottom-screen.cy+1); repeat(k){ for(y in screen.cy until screen.bottom) screen.cells[y]=screen.cells[y+1]; screen.cells[screen.bottom]=Array(columns){Cell(fg=screen.fg,bg=screen.bg)} } }
    private fun saveCursor(){screen.savedX=screen.cx;screen.savedY=screen.cy}
    private fun restoreCursor(){screen.cx=screen.savedX.coerceIn(0,columns-1);screen.cy=screen.savedY.coerceIn(screen.top,screen.bottom)}
    private fun applyOsc(s:String){ val idx=s.indexOf(';'); if(idx>=0 && s.substring(0,idx) in setOf("0","1","2")) title=s.substring(idx+1).take(256) }

    /** Scrollback API used by the Android surface. offset=0 means live bottom. */
    fun scrollBy(deltaRows: Int) {
        val maxOffset = scrollback.size
        scrollOffset = (scrollOffset + deltaRows).coerceIn(0, maxOffset)
        dirty = true
    }

    fun scrollToBottom() { scrollOffset = 0; dirty = true }
    fun isAtBottom(): Boolean = scrollOffset == 0
    fun currentScrollOffset(): Int = scrollOffset
    fun scrollbackSize(): Int = scrollback.size

    /** Return the cell from the virtual scrollback+screen viewport. */
    fun visibleCell(x: Int, y: Int): Cell {
        val history = scrollback.size
        val total = history + rows
        val start = (total - rows - scrollOffset).coerceAtLeast(0)
        val index = start + y
        return if (index < history) scrollback.elementAt(index)[x]
        else screen.cells[(index - history).coerceIn(0, rows - 1)][x]
    }

    fun visibleText(): String = buildString {
        for (y in 0 until rows) {
            append((0 until columns).joinToString("") { visibleCell(it, y).ch.toString() }.trimEnd())
            if (y != rows - 1) append('\n')
        }
    }

    fun cell(x:Int,y:Int): Cell = screen.cells[y][x]
    fun transcript(): List<String> = scrollback.map { row -> row.joinToString("") { it.ch.toString() }.trimEnd() }
}
