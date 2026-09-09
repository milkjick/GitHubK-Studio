package com.example.myempty.githubk.terminal

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.example.myempty.githubk.shizuku.ShizukuBridge
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * GitHubK Shizuku shell facade.
 *
 * Two execution paths are intentionally kept separate:
 * 1. UserService: preferred for IDE/build operations (persistent privileged worker).
 * 2. Shizuku newProcess wire protocol: compatibility path for an interactive shell.
 *
 * This mirrors the real Shizuku architecture: rish and UserService are alternative
 * clients of the Shizuku server, not a mandatory serial chain.
 */
object ShizukuShell {
    private const val DESCRIPTOR = "moe.shizuku.server.IShizukuService"
    private const val REMOTE_PROCESS_DESCRIPTOR = "moe.shizuku.server.IRemoteProcess"
    private const val TRANSACTION_NEW_PROCESS = 7
    private const val PKG = "com.example.myempty.githubk"

    fun isServiceAvailable(): Boolean = ShizukuBridge.isServiceAvailable()
    fun isReady(): Boolean = ShizukuBridge.isReady()
    fun hasPermission(): Boolean = ShizukuBridge.hasPermission()
    fun getUid(): Int = ShizukuBridge.getUid()
    fun backendLabel(): String = ShizukuBridge.backendLabel()

    fun requestPermission(context: Context, onResult: ((Boolean) -> Unit)? = null) {
        ShizukuBridge.ensureBinder(context)
        Thread({
            // Wait longer for binder attach (up to 3s)
            val deadline = System.currentTimeMillis() + 3000L
            while (!ShizukuBridge.isReady() && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(50L) } catch (_: InterruptedException) { break }
            }
            if (!ShizukuBridge.isReady()) {
                android.util.Log.w("GitHubK.Shizuku", "Binder not ready after 3s, requesting permission anyway")
            }
            ShizukuBridge.requestPermission { onResult?.invoke(ShizukuBridge.hasPermission()) }
        }, "githubk-shizuku-permission-start").apply { isDaemon = true }.start()
    }

    // Backward-compatible overload for existing callers that already have a connected Binder.
    fun requestPermission(onResult: ((Boolean) -> Unit)? = null) {
        ShizukuBridge.requestPermission { onResult?.invoke(hasPermission()) }
    }


    /**
     * 同步请求 Shizuku 授权（供安装流程在后台线程阻塞等待用户点击授权弹窗）。
     * 若已授权直接返回 true；超时或用户拒绝返回 false。
     */
    fun requestPermissionSync(context: Context, timeoutMs: Long = 45_000L): Boolean {
        ShizukuBridge.ensureBinder(context)
        val deadline = System.currentTimeMillis() + 3000L
        while (!ShizukuBridge.isReady() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50L) } catch (_: InterruptedException) { break }
        }
        if (ShizukuBridge.hasPermission()) return true
        if (!ShizukuBridge.isServiceAvailable()) return false
        val latch = java.util.concurrent.CountDownLatch(1)
        val granted = java.util.concurrent.atomic.AtomicBoolean(false)
        ShizukuBridge.requestPermission {
            granted.set(ShizukuBridge.hasPermission())
            latch.countDown()
        }
        try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
        return granted.get()
    }

    /** Execute a build/tool command through the Shizuku UserService. */
    fun execUserService(
        context: Context,
        command: String,
        env: Map<String, String> = emptyMap(),
        dir: String? = null,
        timeoutSec: Long = 30
    ): String? = ShizukuBridge.execAsUserService(context, command, env, dir, timeoutSec * 1000L)

    /**
     * Start a process using the public Shizuku IShizukuService.newProcess transaction.
     * This remains available for interactive terminal compatibility.
     */
    @Synchronized
    fun createInteractiveProcess(
        command: String,
        env: Map<String, String>? = null,
        dir: String? = null
    ): Process? {
        val b = ShizukuBridge.binder() ?: return null
        if (!b.pingBinder() || !hasPermission()) return null

        val argv = arrayOf("/system/bin/sh", "-c", command)
        val envArray = env?.entries?.map { "${it.key}=${it.value}" }?.toTypedArray()
        val pfd = remoteProcess(b, argv, envArray, dir) ?: return null
        return pfd
    }

    fun execCommand(command: String, env: Map<String, String>? = null, dir: String? = null, timeoutSec: Long = 30): String {
        val p = createInteractiveProcess(command, env, dir) ?: return ""
        return try {
            val out = p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val err = p.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            p.waitFor(timeoutSec, TimeUnit.SECONDS)
            (out + err).trim()
        } catch (_: Throwable) { "" } finally { try { p.destroy() } catch (_: Throwable) {} }
    }

    fun cancelUserService() = ShizukuBridge.cancelUserService()

    fun execAsApp(command: String, env: Map<String, String>? = null, dir: String? = null, timeoutSec: Long = 30): String {
        val safe = shellQuote(command)
        return execCommand("run-as $PKG sh -c $safe", env, null, timeoutSec)
    }

    fun startBashAsApp(env: Map<String, String>, dir: String? = null): Process? {
        val prefix = "/data/data/$PKG/files/runtime"
        val envMap = linkedMapOf<String, String>().apply {
            put("LD_LIBRARY_PATH", "$prefix/lib")
            put("PATH", "$prefix/bin:$prefix/bin/applets:/system/bin:/system/xbin")
            put("PREFIX", prefix)
            put("HOME", "$prefix/home")
            put("TMPDIR", "$prefix/tmp")
            putAll(env)
        }
        val exports = envMap.entries.joinToString("; ") { "export ${it.key}=${shellQuote(it.value)}" }
        val cd = if (!dir.isNullOrBlank()) "cd ${shellQuote(dir)} && " else ""
        val inner = "$exports; $cd exec ${shellQuote("$prefix/bin/bash")} --noprofile --norc -i"
        return createInteractiveProcess("run-as $PKG sh -c ${shellQuote(inner)}", null, null)
    }

    fun fixPermissions(runtimePath: String): Boolean {
        val cmd = "chmod -R 755 ${shellQuote(runtimePath)} 2>/dev/null; " +
                "chmod 644 ${shellQuote("$runtimePath/lib")}/*.so 2>/dev/null; echo __done__"
        return execCommand(cmd).contains("__done__")
    }

    private fun remoteProcess(
        binder: IBinder,
        argv: Array<String>,
        env: Array<String>?,
        dir: String?
    ): Process? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeStringArray(argv)
            data.writeStringArray(env)
            data.writeString(dir)
            binder.transact(TRANSACTION_NEW_PROCESS, data, reply, 0)
            reply.readException()
            val rp = reply.readStrongBinder() ?: return null
            return RemoteProcess(rp)
        } catch (_: Throwable) {
            return null
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\\"'\\\"'") + "'"

    private class RemoteProcess(private val binder: IBinder) : Process() {
        private var input: InputStream? = null
        private var output: OutputStream? = null
        private var error: InputStream? = null
        private var exit: Int? = null

        init {
            input = openPfd(2)?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
            output = openPfd(1)?.let { ParcelFileDescriptor.AutoCloseOutputStream(it) }
            error = openPfd(3)?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
        }

        override fun getInputStream(): InputStream = input ?: InputStream.nullInputStream()
        override fun getOutputStream(): OutputStream = output ?: OutputStream.nullOutputStream()
        override fun getErrorStream(): InputStream = error ?: InputStream.nullInputStream()

        override fun waitFor(): Int {
            try {
                val d = Parcel.obtain(); val r = Parcel.obtain()
                try { d.writeInterfaceToken(REMOTE_PROCESS_DESCRIPTOR); binder.transact(4, d, r, 0); r.readException(); exit = r.readInt() }
                finally { d.recycle(); r.recycle() }
            } catch (_: Throwable) { if (exit == null) exit = 1 }
            return exit ?: 1
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            val ms = unit.toMillis(timeout)
            return try {
                val d = Parcel.obtain(); val r = Parcel.obtain()
                try {
                    d.writeInterfaceToken(REMOTE_PROCESS_DESCRIPTOR); d.writeLong(ms); d.writeString("MILLISECONDS")
                    binder.transact(8, d, r, 0); r.readException(); val ok = r.readInt() != 0
                    if (ok) exit = exitValueSafe(); ok
                } finally { d.recycle(); r.recycle() }
            } catch (_: Throwable) { false }
        }

        override fun exitValue(): Int = exit ?: exitValueSafe().also { exit = it }

        private fun exitValueSafe(): Int {
            val d = Parcel.obtain(); val r = Parcel.obtain()
            return try { d.writeInterfaceToken(REMOTE_PROCESS_DESCRIPTOR); binder.transact(5, d, r, 0); r.readException(); r.readInt() }
            catch (_: Throwable) { 1 } finally { d.recycle(); r.recycle() }
        }

        override fun destroy() {
            try {
                val d = Parcel.obtain(); val r = Parcel.obtain()
                try { d.writeInterfaceToken(REMOTE_PROCESS_DESCRIPTOR); binder.transact(6, d, r, 0); r.readException() }
                finally { d.recycle(); r.recycle() }
            } catch (_: Throwable) {}
            try { input?.close() } catch (_: Throwable) {}
            try { output?.close() } catch (_: Throwable) {}
            try { error?.close() } catch (_: Throwable) {}
            exit = 0
        }

        private fun openPfd(code: Int): ParcelFileDescriptor? {
            val d = Parcel.obtain(); val r = Parcel.obtain()
            return try { d.writeInterfaceToken(REMOTE_PROCESS_DESCRIPTOR); binder.transact(code, d, r, 0); r.readException(); r.readParcelable(ParcelFileDescriptor::class.java.classLoader) }
            catch (_: Throwable) { null } finally { d.recycle(); r.recycle() }
        }
    }
}
