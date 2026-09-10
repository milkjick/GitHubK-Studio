package com.example.myempty.githubk.terminal.pty

import java.io.InputStream
import java.io.OutputStream

/**
 * Real Linux PTY bridge.
 *
 * File descriptors are kept entirely in JNI. We intentionally do not use
 * reflection on java.io.FileDescriptor (which is blocked on some Android
 * releases) and we do not emulate a terminal with ProcessBuilder pipes.
 */
object NativePty {
    init { System.loadLibrary("githubkpty") }

    @JvmStatic private external fun nativeCreate(cmd: String, cwd: String, argv: Array<String>, env: Array<String>, pidOut: IntArray, rows: Int, cols: Int, cellW: Int, cellH: Int): Int
    @JvmStatic private external fun nativeRead(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int
    @JvmStatic private external fun nativeWrite(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int
    @JvmStatic private external fun nativeResize(fd: Int, rows: Int, cols: Int, cellW: Int, cellH: Int): Int
    @JvmStatic private external fun nativeWait(pid: Int): Int
    @JvmStatic private external fun nativeSignal(pid: Int, signal: Int): Int
    @JvmStatic private external fun nativeClose(fd: Int)

    private class PtyInput(private val fd: Int) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val n = nativeRead(fd, one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xff
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            return nativeRead(fd, b, off, len)
        }
        override fun close() = Unit
    }

    private class PtyOutput(private val fd: Int) : OutputStream() {
        override fun write(b: Int) {
            val one = byteArrayOf(b.toByte())
            write(one, 0, 1)
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            var pos = off
            var remaining = len
            while (remaining > 0) {
                val n = nativeWrite(fd, b, pos, remaining)
                if (n <= 0) throw java.io.IOException("PTY write failed: $n")
                pos += n
                remaining -= n
            }
        }
        override fun flush() = Unit
        override fun close() = Unit
    }

    data class Session(val fd: Int, val pid: Int) {
        val input: InputStream = PtyInput(fd)
        val output: OutputStream = PtyOutput(fd)
        @Volatile private var closed = false

        fun resize(rows: Int, cols: Int, cellW: Int, cellH: Int) {
            nativeResize(fd, rows, cols, cellW, cellH)
        }
        fun signal(signal: Int) = nativeSignal(pid, signal)
        fun close() {
            if (closed) return
            closed = true
            nativeClose(fd)
        }
        fun waitFor(): Int = nativeWait(pid)
    }

    fun start(command: String, cwd: String, argv: List<String>, environment: Map<String, String>, rows: Int = 24, cols: Int = 80, cellW: Int = 10, cellH: Int = 20): Session {
        val pid = IntArray(1)
        val args = if (argv.isEmpty()) arrayOf(command) else argv.toTypedArray()
        val env = environment.entries.map { "${it.key}=${it.value}" }.toTypedArray()
        val fd = nativeCreate(command, cwd, args, env, pid, rows, cols, cellW, cellH)
        if (fd < 0) error("PTY creation failed")
        return Session(fd, pid[0])
    }

    const val SIGINT = 2
    const val SIGTSTP = 20
    const val SIGTERM = 15
    const val SIGKILL = 9
}
