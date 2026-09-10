package com.example.myempty.githubk.terminal

import com.example.myempty.githubk.terminal.pty.NativePty
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Compatibility controller for non-UI callers.
 * Interactive execution is always backed by a real PTY now; ProcessBuilder pipes
 * are deliberately not used here.
 */
class InteractiveTerminal(
    private val onLog: (String) -> Unit = {},
    private val workingDir: File? = null,
    private val shellPathOverride: String? = null,
    private val extraEnv: Map<String, String>? = null
) {
    private var session: NativePty.Session? = null
    private val running = AtomicBoolean(false)
    var shellPath: String = shellPathOverride ?: "/system/bin/sh"; private set
    var backend: String = "native-pty"; private set
    var lastError: String = ""; private set
    val isAlive: Boolean get() = running.get()

    fun start(): Boolean {
        if (isAlive) return true
        val shell = shellPathOverride?.takeIf { File(it).canExecute() } ?: "/system/bin/sh"
        shellPath = shell
        val dir = workingDir?.takeIf { it.isDirectory } ?: File("/")
        val env = linkedMapOf<String,String>()
        extraEnv?.let { env.putAll(it) }
        env["TERM"] = env["TERM"] ?: "xterm-256color"
        env["COLORTERM"] = env["COLORTERM"] ?: "truecolor"
        env["HOME"] = env["HOME"] ?: File(dir, "home").absolutePath
        return try {
            session = NativePty.start(shell, dir.absolutePath, listOf(shell, "-l"), env)
            running.set(true)
            backend = "native-pty"
            onLog("[✓] Native PTY connected (pid=${session!!.pid})\n")
            thread(isDaemon=true, name="githubk-compat-reader") {
                try {
                    val buf=ByteArray(8192)
                    while (running.get()) {
                        val n=session?.input?.read(buf) ?: -1
                        if(n<0) break
                        if(n>0) onLog(String(buf,0,n,Charsets.UTF_8))
                    }
                } catch(_:Throwable){} finally { running.set(false) }
            }
            true
        } catch(t:Throwable) {
            lastError=t.message ?: t.javaClass.simpleName
            onLog("[PTY 启动失败] $lastError\n")
            false
        }
    }

    fun write(command: String): Boolean {
        if (!isAlive && !start()) return false
        return try { session!!.output.write(command.toByteArray(Charsets.UTF_8)); session!!.output.flush(); true }
        catch(t:Throwable){ lastError=t.message.orEmpty(); false }
    }

    fun sendBytes(bytes: ByteArray) { try { session?.output?.write(bytes); session?.output?.flush() } catch(_:Throwable){} }
    fun stop() { running.set(false); try { session?.signal(NativePty.SIGTERM) } catch(_:Throwable){}; try { session?.close() } catch(_:Throwable){}; session=null }
    fun await(timeoutSeconds: Long=60) { try { session?.waitFor() } catch(_:Throwable){} }
}
