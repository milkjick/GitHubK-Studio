package com.example.myempty.githubk.ui.terminal

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.example.myempty.githubk.terminal.pty.NativePty
import com.example.myempty.githubk.terminal.vt.AnsiTerminal
import kotlin.math.abs
import kotlin.math.max

/**
 * Native terminal surface: real PTY + VT renderer + Android IME.
 * No TextView/ScrollView/ProcessBuilder is used for terminal I/O.
 *
 * Keyboard fix: onTouchEvent explicitly requests focus + shows IME on every
 * ACTION_DOWN. BaseInputConnection handles commitText / deleteSurroundingText
 * / sendKeyEvent properly. onCheckIsTextEditor returns true so the system
 * treats this view as a text editor and will pop the IME on touch.
 */
class NativeTerminalView(context: Context) : View(context) {
    private val terminal = AnsiTerminal()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private var session: NativePty.Session? = null
    private var reader: Thread? = null
    private var waiter: Thread? = null
    private var cellW = 0f
    private var cellH = 0f
    private var fontPx = 14f
    private val minFontPx = 8f
    private val maxFontPx = 32f
    private var scaleDetector: ScaleGestureDetector
    private var gestureDetector: GestureDetector
    private var lastW = 80
    private var lastH = 24
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var lastTouchY = 0f
    private var longPress: Runnable? = null
    private var draggingScroll = false
    private var hasMoved = false
    var onTitleChanged: ((String) -> Unit)? = null
    var onSessionExit: ((Int) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null
    var onSwipeRight: (() -> Unit)? = null
    var onSwipeLeft: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

    init {
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val next = (fontPx * detector.scaleFactor).coerceIn(minFontPx, maxFontPx)
                if (abs(next - fontPx) >= 0.15f) {
                    fontPx = next
                    requestLayout()
                    invalidate()
                    post { session?.resize(lastH, lastW, cellW.toInt().coerceAtLeast(1), cellH.toInt().coerceAtLeast(1)) }
                }
                return true
            }
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                super.onScaleEnd(detector)
                onStatus?.invoke("字体 ${fontPx.toInt()}px · 双指缩放")
            }
        })
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // 点击终端区域 → 请求焦点 + 弹出键盘
                requestFocus()
                showIME()
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = abs(e2.y - e1.y)
                if (dy < 100 && abs(dx) > 120) {
                    if (dx > 0) onSwipeRight?.invoke()
                    else onSwipeLeft?.invoke()
                    return true
                }
                return false
            }
        })
        setBackgroundColor(0xFF0D1117.toInt())
        isFocusable = true
        isFocusableInTouchMode = true
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        paint.textSize = fontPx
    }

    fun start(shell: String, cwd: String, env: Map<String, String>) {
        start(shell, cwd, env, listOf(shell, "-l"))
    }

    /**
     * 以完整 argv 启动一个 PTY 会话。
     * @param argv0  实际 exec 的二进制（可能不是 shell 参数里的 argv[0]）。
     * @param argv   完整 argv（argv[0] 为进程名）。用于 proot 登录：argv =
     *               [libproot.so, -r, rootfs, ..., /bin/sh, -l]
     */
    fun start(argv0: String, cwd: String, env: Map<String, String>, argv: List<String>) {
        stop()
        try {
            session = NativePty.start(argv0, cwd, argv, env, lastH, lastW, 9, 18)
            onStatus?.invoke("PTY connected · pid=${session?.pid}")
            reader = Thread({ readLoop() }, "githubk-pty-reader").apply { isDaemon = true; start() }
            waiter = Thread({
                val code = try { session?.waitFor() ?: 0 } catch (_: Throwable) { 1 }
                post { onSessionExit?.invoke(code) }
            }, "githubk-pty-waiter").apply { isDaemon = true; start() }
            requestFocus()
            postDelayed({ showIME() }, 300)
        } catch (t: Throwable) {
            onStatus?.invoke("PTY failed: ${t.message}")
        }
    }

    private fun showIME() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideIME() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun readLoop() {
        val s = session ?: return
        val buf = ByteArray(16384)
        try {
            while (true) {
                val n = s.input.read(buf)
                if (n < 0) break
                if (n > 0) {
                    val copy = buf.copyOf(n)
                    post {
                        terminal.append(copy)
                        onTitleChanged?.invoke(terminal.title)
                        invalidate()
                    }
                }
            }
        } catch (_: Throwable) { }
    }

    fun write(bytes: ByteArray) {
        try { session?.output?.write(bytes); session?.output?.flush() } catch (_: Throwable) { }
    }

    fun write(text: String) {
        try {
            session?.output?.write(text.toByteArray(Charsets.UTF_8))
            session?.output?.flush()
        } catch (_: Throwable) { }
    }
    fun writeText(text: String) = write(text.toByteArray(Charsets.UTF_8))

    fun pasteFromClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        writeText(text)
    }

    fun copyVisibleText() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("terminal", terminal.visibleText()))
    }

    fun scrollPage(up: Boolean) {
        terminal.scrollBy(if (up) max(1, lastH - 2) else -max(1, lastH - 2))
        invalidate()
    }

    fun sendCtrlC() = write(byteArrayOf(0x03))
    fun sendCtrlZ() = write(byteArrayOf(0x1A))
    fun sendCtrlD() = write(byteArrayOf(0x04))
    fun sendEscape() = write(byteArrayOf(0x1B))
    fun sendTab() = write(byteArrayOf(0x09))
    fun sendEnter() = write(byteArrayOf(0x0D))
    fun stop() {
        try { session?.signal(NativePty.SIGTERM) } catch (_: Throwable) {}
        try { session?.close() } catch (_: Throwable) {}
        session = null
    }

    // ================= 字体控制 =================
    fun increaseFont() {
        fontPx = (fontPx + 1f).coerceAtMost(maxFontPx)
        requestLayout(); invalidate()
        post { session?.resize(lastH, lastW, cellW.toInt().coerceAtLeast(1), cellH.toInt().coerceAtLeast(1)) }
        onStatus?.invoke("字体 ${fontPx.toInt()}px")
    }
    fun decreaseFont() {
        fontPx = (fontPx - 1f).coerceAtLeast(minFontPx)
        requestLayout(); invalidate()
        post { session?.resize(lastH, lastW, cellW.toInt().coerceAtLeast(1), cellH.toInt().coerceAtLeast(1)) }
        onStatus?.invoke("字体 ${fontPx.toInt()}px")
    }
    fun setFontSize(px: Float) {
        fontPx = px.coerceIn(minFontPx, maxFontPx)
        requestLayout(); invalidate()
        post { session?.resize(lastH, lastW, cellW.toInt().coerceAtLeast(1), cellH.toInt().coerceAtLeast(1)) }
        onStatus?.invoke("字体 ${fontPx.toInt()}px")
    }
    fun getFontSize(): Float = fontPx
    fun getMinFontSize(): Float = minFontPx
    fun getMaxFontSize(): Float = maxFontPx

    fun selectAllText() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("terminal", terminal.visibleText()))
    }
    fun getVisibleText(): String = terminal.visibleText()

    fun sendCtrlKey(ch: Char) {
        val code = ch.lowercaseChar().code - 'a'.code + 1
        if (code in 1..26) write(byteArrayOf(code.toByte()))
    }
    fun sendAltKey(ch: Char) {
        write(byteArrayOf(0x1B))
        writeText(ch.toString())
    }
    fun sendFunctionKey(n: Int) {
        val seq = when (n) {
            1 -> "\u001bOP"; 2 -> "\u001bOQ"; 3 -> "\u001bOR"; 4 -> "\u001bOS"
            5 -> "\u001b[15~"; 6 -> "\u001b[17~"; 7 -> "\u001b[18~"; 8 -> "\u001b[19~"
            9 -> "\u001b[20~"; 10 -> "\u001b[21~"; 11 -> "\u001b[23~"; 12 -> "\u001b[24~"
            else -> return
        }
        writeText(seq)
    }

    fun scrollToBottom() { terminal.scrollToBottom(); invalidate() }
    fun scrollToTop() { terminal.scrollBy(terminal.scrollbackSize()); invalidate() }

    fun sendArrowKey(direction: String) {
        val seq = when (direction) {
            "up" -> byteArrayOf(0x1B, 0x5B, 0x41)       // ESC [ A
            "down" -> byteArrayOf(0x1B, 0x5B, 0x42)      // ESC [ B
            "right" -> byteArrayOf(0x1B, 0x5B, 0x43)    // ESC [ C
            "left" -> byteArrayOf(0x1B, 0x5B, 0x44)     // ESC [ D
            else -> return
        }
        write(seq)
    }

    /** Scroll page by direction: negative = up, positive = down */
    fun scrollPage(direction: Int) {
        val delta = if (direction > 0) max(1, lastH - 2) else -max(1, lastH - 2)
        terminal.scrollBy(delta)
        invalidate()
    }

    /** Set font size by pixel value (Int overload for convenience) */
    fun setFontSize(px: Int) {
        setFontSize(px.toFloat())
    }

    fun onResume() {
        // Resume rendering; terminal session keeps running in background
        isRunning = true
        invalidate()
    }

    fun onPause() {
        // Pause rendering but keep PTY session alive
        isRunning = false
    }

    private var isRunning = true


    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        paint.textSize = fontPx
        paint.typeface = Typeface.MONOSPACE
        cellW = max(1f, paint.measureText("M"))
        val fm = paint.fontMetrics
        cellH = max(1f, fm.descent - fm.ascent + 2f)
        val cols = max(10, (w / cellW).toInt())
        val rows = max(4, (h / cellH).toInt())
        lastW = cols; lastH = rows
        terminal.resize(cols, rows)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (cellW <= 0f) return
        val cols = max(10, (w / cellW).toInt()); val rows = max(4, (h / cellH).toInt())
        lastW = cols; lastH = rows
        terminal.resize(cols, rows)
        session?.resize(rows, cols, cellW.toInt(), cellH.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bg = 0xFF0D1117.toInt(); canvas.drawColor(bg)
        if (cellW <= 0f) return
        val fm = paint.fontMetrics
        val baseline0 = -fm.ascent + 1f
        for (y in 0 until terminal.rows) {
            var x = 0f
            for (col in 0 until terminal.columns) {
                val c = terminal.visibleCell(col, y)
                if (c.bg != bg) {
                    paint.style = Paint.Style.FILL; paint.color = c.bg
                    canvas.drawRect(x, y * cellH, x + cellW, (y + 1) * cellH, paint)
                }
                if (c.ch != ' ') {
                    paint.style = Paint.Style.FILL; paint.color = c.fg; paint.textSize = fontPx
                    paint.typeface = if (c.bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
                    canvas.drawText(c.ch.toString(), x, y * cellH + baseline0, paint)
                    if (c.underline) {
                        paint.strokeWidth = 1f
                        canvas.drawLine(x, (y + 1) * cellH - 2, x + cellW, (y + 1) * cellH - 2, paint)
                    }
                }
                x += cellW
            }
        }
        if (terminal.cursorY in 0 until terminal.rows && terminal.cursorX in 0 until terminal.columns) {
            paint.color = 0x99D0D7DE.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f
            canvas.drawRect(
                terminal.cursorX * cellW + 1, terminal.cursorY * cellH + 1,
                (terminal.cursorX + 1) * cellW - 1, (terminal.cursorY + 1) * cellH - 1, paint
            )
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val ctrl = event.isCtrlPressed
        val alt = event.isAltPressed
        if (ctrl) when (keyCode) {
            KeyEvent.KEYCODE_C -> { sendCtrlC(); return true }
            KeyEvent.KEYCODE_Z -> { sendCtrlZ(); return true }
            KeyEvent.KEYCODE_D -> { sendCtrlD(); return true }
            KeyEvent.KEYCODE_L -> { writeText("\u000c"); return true }
            KeyEvent.KEYCODE_A -> { write(byteArrayOf(1)); return true }
            KeyEvent.KEYCODE_E -> { write(byteArrayOf(5)); return true }
            KeyEvent.KEYCODE_U -> { write(byteArrayOf(21)); return true }
            KeyEvent.KEYCODE_K -> { write(byteArrayOf(11)); return true }
            KeyEvent.KEYCODE_W -> { write(byteArrayOf(23)); return true }
            KeyEvent.KEYCODE_R -> { write(byteArrayOf(18)); return true }
            KeyEvent.KEYCODE_T -> { write(byteArrayOf(20)); return true }
            KeyEvent.KEYCODE_G -> { write(byteArrayOf(7)); return true }
            KeyEvent.KEYCODE_BACK -> { return false }
        }
        val seq = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "\u001b[A"
            KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001b[C"
            KeyEvent.KEYCODE_DPAD_LEFT -> "\u001b[D"
            KeyEvent.KEYCODE_HOME -> "\u001b[H"
            KeyEvent.KEYCODE_MOVE_END -> "\u001b[F"
            KeyEvent.KEYCODE_PAGE_UP -> "\u001b[5~"
            KeyEvent.KEYCODE_PAGE_DOWN -> "\u001b[6~"
            KeyEvent.KEYCODE_INSERT -> "\u001b[2~"
            KeyEvent.KEYCODE_FORWARD_DEL -> "\u001b[3~"
            KeyEvent.KEYCODE_DEL -> "\u007f"
            KeyEvent.KEYCODE_ENTER -> "\r"
            KeyEvent.KEYCODE_TAB -> "\t"
            KeyEvent.KEYCODE_ESCAPE -> "\u001b"
            else -> null
        }
        if (seq != null) { writeText(seq); return true }
        if (alt) { write(byteArrayOf(0x1B)); return super.onKeyDown(keyCode, event) }
        // 普通字符（硬件键盘 / 部分 IME 经 sendKeyEvent 发送）：按 unicodeChar 写入 PTY，避免字符丢失/不响应。
        val uc = event.unicodeChar
        if (uc != 0) {
            writeText(uc.toChar().toString())
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                writeText(text.toString())
                return true
            }
            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                // \u6784\u6210\u4e2d\u6587\u62fc\u97f3\u7ec4\u5408\u9636\u6bb5\u4e0d\u5e94\u628a\u62fc\u97f3\u62c6\u5206\u53d1\u9001\u5230 PTY\uff0c\u5426\u5219\u4f1a\u4e71\u7801/\u4e22\u5b57\u3002
                // \u53ea\u5728 commitText \u786e\u8ba4\u65f6\u5199\u5165\uff0c\u907f\u514d\u8f93\u5165\u5931\u7075\u3002
                return true
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // Send backspace for each deleted character
                repeat(max(1, beforeLength)) { write(byteArrayOf(0x7f)) }
                if (afterLength > 0) write(byteArrayOf(0x1b, 0x5b, 0x33, 0x7e)) // Delete key
                return true
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    return onKeyDown(event.keyCode, event)
                }
                return super.sendKeyEvent(event)
            }
            override fun finishComposingText(): Boolean { return true }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x; touchDownY = event.y; lastTouchY = event.y
                draggingScroll = false; hasMoved = false
                requestFocus()
                showIME()
                longPress?.let { removeCallbacks(it) }
                longPress = Runnable {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("terminal", terminal.visibleText()))
                    onLongPress?.invoke()
                }
                postDelayed(longPress!!, 550)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                longPress?.let { removeCallbacks(it) }; longPress = null
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) {
                    longPress?.let { removeCallbacks(it) }; longPress = null
                    return true
                }
                val dy = event.y - lastTouchY
                if (abs(event.y - touchDownY) > 12) {
                    longPress?.let { removeCallbacks(it) }; longPress = null
                    draggingScroll = true; hasMoved = true
                }
                if (draggingScroll && !scaleDetector.isInProgress) {
                    val rows = (abs(dy) / cellH.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
                    terminal.scrollBy(if (dy > 0) -rows else rows)
                    invalidate()
                }
                lastTouchY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                longPress?.let { removeCallbacks(it) }; longPress = null
                if (!hasMoved && !scaleDetector.isInProgress) {
                    performClick()
                }
                draggingScroll = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                longPress?.let { removeCallbacks(it) }; longPress = null
                draggingScroll = false
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
