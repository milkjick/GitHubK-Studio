package com.example.myempty.githubk.terminal

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/** Non-interactive command runner for IDE actions. Interactive sessions use InteractiveTerminal. */
data class TerminalResult(val success: Boolean, val output: String, val exitCode: Int)

class TerminalManager(private val onLog: (String) -> Unit = {}) {
    @Volatile private var appContext: Context? = null

    fun attachContext(context: Context) { appContext = context.applicationContext }
    @Volatile private var current: Process? = null

    fun cancel() { try { current?.destroyForcibly() } catch (_: Throwable) {} }

    fun run(project: File, command: String, env: Map<String,String> = emptyMap(), timeoutMinutes: Long = 30): TerminalResult {
        if (command.isBlank()) return TerminalResult(false, "空命令", 2)
        return try {
            val ctx = appContext
            if (ctx != null && ShizukuShell.isServiceAvailable() && ShizukuShell.hasPermission()) {
                onLog("[Shizuku/UserService] $command")
                val result = ShizukuShell.execUserService(ctx, command, env, project.absolutePath, timeoutMinutes * 60)
                if (result != null) {
                    result.lineSequence().forEach { line -> onLog(line) }
                    val code = Regex("\\[exit=(\\d+)\\]").find(result)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: if (result.startsWith("ERROR:")) 1 else 0
                    return TerminalResult(code == 0, result, code)
                }
                onLog("[Shizuku/UserService] 不可用，回退到应用进程 shell")
            }

            val pb = ProcessBuilder("/system/bin/sh", "-c", command).directory(project).redirectErrorStream(true)
            pb.environment().putAll(env)
            current = pb.start()
            val p=current!!; val sb=StringBuilder()
            val t=Thread { p.inputStream.bufferedReader(Charsets.UTF_8).useLines { it.forEach { line -> sb.append(line).append('\n'); onLog(line) } } }
            t.start()
            val done=p.waitFor(timeoutMinutes,TimeUnit.MINUTES)
            if(!done){p.destroyForcibly();return TerminalResult(false,sb.toString()+"\nTIMEOUT",124)}
            t.join(2000)
            TerminalResult(p.exitValue()==0,sb.toString(),p.exitValue())
        } catch(e:Throwable){ TerminalResult(false,e.message ?: "执行失败",1) } finally { current=null }
    }
}
