package com.example.myempty.githubk.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener

/**
 * Real Dhizuku API adapter.
 *
 * Dhizuku is not a Shizuku shell backend: it shares DeviceOwner/ProfileOwner
 * capabilities through DevicePolicyManager. Therefore it is deliberately kept
 * separate from ShizukuBridge instead of pretending a Dhizuku binder is an
 * IShizukuService binder.
 */
object DhizukuBridge {
    private const val TAG = "GitHubK.Dhizuku"
    private const val PACKAGE = "com.rosan.dhizuku"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var initialized = false
    @Volatile private var initInFlight = false
    private val callbacks = mutableListOf<(Boolean) -> Unit>()

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: Throwable) { false }

    fun init(context: Context): Boolean {
        if (!isInstalled(context)) return false
        return try {
            val ok = Dhizuku.init(context.applicationContext)
            initialized = ok
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "Dhizuku.init failed", t)
            initialized = false
            false
        }
    }

    fun isPermissionGranted(context: Context): Boolean {
        if (!initialized) return false
        return try {
            Dhizuku.isPermissionGranted()
        } catch (t: Throwable) {
            // Dhizuku may answer before its service process has finished starting.
            // The upstream issue is known; retrying after a short delay is safer
            // than presenting a permanent "denied" state.
            Log.w(TAG, "permission check failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    fun requestPermission(context: Context, callback: (Boolean) -> Unit) {
        if (!isInstalled(context)) { main.post { callback(false) }; return }
        synchronized(this) {
            callbacks += callback
            if (initInFlight) return
            initInFlight = true
        }
        Thread({
            var granted = false
            try {
                if (!init(context)) {
                    complete(false)
                    return@Thread
                }
                // Dhizuku can publish its binder slightly after init().
                try { Thread.sleep(350L) } catch (_: InterruptedException) {}
                if (Dhizuku.isPermissionGranted()) {
                    granted = true
                    return@Thread
                }
                Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
                    @Throws(RemoteException::class)
                    override fun onRequestPermission(grantResult: Int) {
                        complete(grantResult == PackageManager.PERMISSION_GRANTED)
                    }
                })
                main.postDelayed({ complete(isPermissionGranted(context)) }, 10000L)
                return@Thread
            } catch (t: Throwable) {
                Log.w(TAG, "requestPermission failed", t)
            }
            complete(granted)
        }, "githubk-dhizuku-permission").apply { isDaemon = true }.start()
    }

    private fun complete(granted: Boolean) {
        val pending = synchronized(this) {
            if (!initInFlight) return
            initInFlight = false
            val list = callbacks.toList()
            callbacks.clear()
            list
        }
        main.post { pending.forEach { it(granted) } }
    }

    fun openManager(context: Context): Boolean = try {
        val i: Intent? = context.packageManager.getLaunchIntentForPackage(PACKAGE)
        if (i != null) { context.startActivity(i); true } else false
    } catch (_: Throwable) { false }
}
