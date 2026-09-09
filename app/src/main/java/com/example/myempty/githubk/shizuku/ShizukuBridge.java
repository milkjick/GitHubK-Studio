package com.example.myempty.githubk.shizuku;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import com.example.myempty.githubk.terminal.IUserShellService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import moe.shizuku.api.BinderContainer;

/**
 * Dependency-free Shizuku client used by GitHubK Studio.
 *
 * Important: the app uses the public Shizuku v13 wire protocol.  The
 * transaction numbers are the generated IShizukuService numbers, not the
 * ordinal position of the methods in an AIDL file.  In particular:
 * attachApplication=18, requestPermission=14, checkSelfPermission=15.
 *
 * The permission path is deliberately asynchronous.  Older versions of this
 * client could block the UI thread while requesting permission and could issue
 * the request repeatedly from TerminalPage/SettingsPage.  That produced the
 * long "请求授权..." state visible in the terminal.
 */
public final class ShizukuBridge {
    private static final String TAG = "GitHubK.Shizuku";
    private static final String DESCRIPTOR = "moe.shizuku.server.IShizukuService";
    private static final String APP_DESCRIPTOR = "moe.shizuku.server.IShizukuApplication";
    private static final String CONNECTION_DESCRIPTOR = "moe.shizuku.server.IShizukuServiceConnection";
    private static final String SHIZUKU_MANAGER = "moe.shizuku.privileged.api";
    private static final String DHIZUKU_MANAGER = "com.rosan.dhizuku";
    private static volatile String activeManager = null;  // "shizuku" or "dhizuku"
    private static final String PACKAGE_NAME = "com.example.myempty.githubk";

    // Shizuku v13 generated IShizukuService transaction codes.
    private static final int TRANSACTION_GET_VERSION = 1;
    private static final int TRANSACTION_GET_UID = 2;
    private static final int TRANSACTION_NEW_PROCESS = 7;
    private static final int TRANSACTION_ADD_USER_SERVICE = 8;
    private static final int TRANSACTION_REMOVE_USER_SERVICE = 9;
    private static final int TRANSACTION_REQUEST_PERMISSION = 14;
    private static final int TRANSACTION_CHECK_SELF_PERMISSION = 15;
    private static final int TRANSACTION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE = 16;
    private static final int TRANSACTION_ATTACH_APPLICATION = 18;

    private static final int PERMISSION_REQUEST_CODE = 0x4753;
    private static final long PERMISSION_REQUEST_TIMEOUT_MS = 8000L;
    private static final long REBIND_RETRY_MS = 1500L;
    private static volatile boolean rebindScheduled;
    private static volatile Context appContext;
    private static final Handler MAIN = new Handler(android.os.Looper.getMainLooper());

    private static volatile IBinder binder;
    private static volatile boolean attached;
    private static volatile boolean permissionKnown;
    private static volatile boolean permissionGranted;
    private static volatile int serverVersion = -1;
    private static volatile int serverUid = -1;
    private static volatile int serverPatchVersion = -1;
    private static volatile IUserShellService userService;
    private static volatile IBinder userServiceBinder;
    private static volatile boolean permissionRequestInFlight;
    private static final List<Runnable> permissionCallbacks = new ArrayList<>();
    private static UserServiceConnection userServiceConnection;
    private static long binderGeneration;

    private ShizukuBridge() {}

    /** Returns true only when the Shizuku Binder is alive. */
    public static boolean isServiceAvailable() {
        IBinder b = binder;
        return b != null && b.pingBinder();
    }

    /** Returns whether attachApplication has completed. */
    public static boolean isReady() {
        return isServiceAvailable() && attached;
    }

    /** Check if Dhizuku (com.rosan.dhizuku) is installed. */
    public static boolean isDhizukuInstalled(Context context) {
        if (context == null) return false;
        try {
            context.getPackageManager().getPackageInfo(DHIZUKU_MANAGER, 0);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Check if Shizuku (moe.shizuku.privileged.api) is installed. */
    public static boolean isShizukuInstalled(Context context) {
        if (context == null) return false;
        try {
            context.getPackageManager().getPackageInfo(SHIZUKU_MANAGER, 0);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Returns which manager is active: "shizuku", "dhizuku", or null. */
    public static String getActiveManager() {
        return activeManager;
    }

    /**
     * Fast permission query.  Once attachApplication has supplied the
     * permission bit, no Binder round-trip is performed for every UI redraw.
     */
    public static boolean hasPermission() {
        if (!isServiceAvailable()) return false;
        if (permissionKnown) return permissionGranted;
        if (!attached) return false;
        // Do not do a Binder transaction on the main thread; return cached value.
        // refreshPermission() will be called from a background thread to update.
        return permissionGranted;
    }

    private static boolean refreshPermissionSync() {
        IBinder b = binder;
        if (b == null || !b.pingBinder()) return false;
        try {
            Parcel d = Parcel.obtain();
            Parcel r = Parcel.obtain();
            try {
                d.writeInterfaceToken(DESCRIPTOR);
                boolean ok = b.transact(TRANSACTION_CHECK_SELF_PERMISSION, d, r, 0);
                if (!ok) throw new RemoteException("checkSelfPermission rejected");
                r.readException();
                permissionGranted = r.readInt() != 0;
                permissionKnown = true;
                return permissionGranted;
            } finally {
                d.recycle();
                r.recycle();
            }
        } catch (Throwable e) {
            Log.w(TAG, "checkSelfPermission failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return permissionGranted;
        }
    }

    public static IBinder binder() { return binder; }

    public static int getUid() {
        int cached = serverUid;
        if (cached != -1) return cached;
        IBinder b = binder;
        if (b == null) return -1;
        try {
            serverUid = transactInt(b, TRANSACTION_GET_UID);
            return serverUid;
        } catch (Throwable e) {
            return -1;
        }
    }

    public static int getServerVersion() {
        int cached = serverVersion;
        if (cached != -1) return cached;
        IBinder b = binder;
        if (b == null) return -1;
        try {
            serverVersion = transactInt(b, TRANSACTION_GET_VERSION);
            return serverVersion;
        } catch (Throwable e) {
            return -1;
        }
    }

    public static int getServerPatchVersion() { return serverPatchVersion; }

    public static String backendLabel() {
        int uid = getUid();
        String prefix = "dhizuku".equals(activeManager) ? "Dhizuku" : "Shizuku";
        if (uid == 0) return prefix + " / ROOT";
        if (uid == 2000) return prefix + " / ADB shell";
        return uid > 0 ? prefix + " / uid=" + uid : prefix;
    }

    /** Receive the Binder delivered by ShizukuProvider and attach exactly once. */
    public static synchronized void onBinderReceived(IBinder newBinder, String packageName) {
        if (newBinder == null || !newBinder.pingBinder()) {
            Log.w(TAG, "onBinderReceived: null or dead binder");
            return;
        }
        if (binder == newBinder && attached) {
            Log.d(TAG, "onBinderReceived: binder already attached");
            return;
        }
        if (activeManager == null) activeManager = "shizuku"; // default

        binderGeneration++;
        final long generation = binderGeneration;
        binder = newBinder;
        attached = false;
        permissionKnown = false;
        permissionGranted = false;
        serverVersion = -1;
        serverUid = -1;
        serverPatchVersion = -1;
        userService = null;
        userServiceBinder = null;
        userServiceConnection = null;
        permissionRequestInFlight = false;
        permissionCallbacks.clear();

        try {
            newBinder.linkToDeath(() -> {
                synchronized (ShizukuBridge.class) {
                    if (generation != binderGeneration) return;
                    Log.i(TAG, "Binder death detected, resetting state");
                    binder = null;
                    attached = false;
                    permissionKnown = false;
                    permissionGranted = false;
                    permissionRequestInFlight = false;
                    permissionCallbacks.clear();
                    serverVersion = -1;
                    serverUid = -1;
                    serverPatchVersion = -1;
                    userService = null;
                    userServiceBinder = null;
                    userServiceConnection = null;
                    permissionRequestInFlight = false;
                    rebindScheduled = false;
                }
                Log.w(TAG, "Shizuku binder died; scheduling automatic reconnect");
                Context ctx = appContext;
                if (ctx != null) {
                    MAIN.postDelayed(() -> ensureBinder(ctx), 400L);
                }
            }, 0);
        } catch (Throwable e) {
            Log.w(TAG, "linkToDeath failed", e);
        }

        // Shizuku's official client attaches synchronously when the binder is
        // received. This is important: bindApplication delivers the current
        // permission bit during attach, so the app must not expose a false
        // "not authorized" state between binder receipt and attach completion.
        // The Binder transaction is short and runs on the provider callback
        // thread, matching the official lifecycle.
        attachApplication(newBinder, packageName, generation);
    }

    private static void attachApplication(IBinder b, String packageName, long generation) {
        boolean success = false;
        try {
            Parcel d = Parcel.obtain();
            Parcel r = Parcel.obtain();
            try {
                d.writeInterfaceToken(DESCRIPTOR);
                d.writeStrongBinder(APP_CALLBACK);
                d.writeInt(1); // Bundle present
                Bundle args = new Bundle();
                args.putInt("shizuku:attach-api-version", 13);
                args.putString("shizuku:attach-package-name", packageName == null ? PACKAGE_NAME : packageName);
                args.writeToParcel(d, 0);
                boolean ok = b.transact(TRANSACTION_ATTACH_APPLICATION, d, r, 0);
                if (!ok) throw new RemoteException("attachApplication rejected");
                r.readException();
                success = true;
                Log.i(TAG, "attachApplication(v13) succeeded, awaiting bindApplication callback");
            } finally {
                d.recycle();
                r.recycle();
            }
        } catch (Throwable e) {
            Log.w(TAG, "attachApplication(v13) failed: " + e.getMessage(), e);
        }

        if (!success) {
            // Shizuku v11/v12 legacy attach used a different transaction number.
            // The previous code incorrectly used transact(14) which is
            // requestPermission, not attachApplication.  Modern Shizuku (v13+)
            // is the only supported version, so we skip the broken fallback.
            Log.w(TAG, "attachApplication(v13) failed; legacy fallback disabled (was using wrong transaction code)");
        }

        synchronized (ShizukuBridge.class) {
            if (generation == binderGeneration && b == binder && b.pingBinder()) {
                attached = success;
            }
        }
        Log.i(TAG, "attachApplication result=" + success + " package=" + (packageName == null ? PACKAGE_NAME : packageName));

        // If the callback was not delivered by a particular server build,
        // perform one delayed permission refresh instead of polling for seconds.
        if (success) {
            MAIN.postDelayed(() -> {
                if (generation == binderGeneration && isServiceAvailable() && !permissionKnown) {
                    Log.i(TAG, "Performing delayed permission refresh (callback may have been lost)");
                    new Thread(() -> refreshPermissionSync(), "githubk-shizuku-delayed-perm").start();
                }
            }, 250L);
        }
    }

    /**
     * Recover the Shizuku Binder if the provider push was missed (for example
     * after Shizuku was restarted while GitHubK Studio was already alive).
     * This first asks our provider for a cached Binder, then uses the official
     * REQUEST_BINDER broadcast path as a second recovery route.
     */
    public static void ensureBinder(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
        final Context ctx = appContext;
        if (isServiceAvailable() && attached) {
            Log.d(TAG, "ensureBinder: already attached");
            return;
        }
        Log.i(TAG, "ensureBinder: recovering Shizuku/Dhizuku binder");
        // Run binder recovery on a background thread to avoid ANR
        new Thread(() -> ensureBinderInternal(ctx), "githubk-ensure-binder").start();
    }

    private static void ensureBinderInternal(Context context) {

        // Step 1: Try cached binder from our own provider
        try {
            android.net.Uri uri = android.net.Uri.parse("content://" + context.getPackageName() + ".shizuku");
            Bundle reply = context.getContentResolver().call(uri, ShizukuProvider.METHOD_GET_BINDER, null, new Bundle());
            if (reply != null) {
                reply.setClassLoader(BinderContainer.class.getClassLoader());
                BinderContainer c = reply.getParcelable("moe.shizuku.privileged.api.intent.extra.BINDER");
                if (c != null && c.binder != null && c.binder.pingBinder()) {
                    onBinderReceived(c.binder, context.getPackageName());
                    return;
                }
            }
        } catch (Throwable e) {
            Log.d(TAG, "provider binder recovery unavailable: " + e.getMessage());
        }

        // Step 2: Try Shizuku manager's own content provider
        // Authority: moe.shizuku.privileged.api.shizuku
        if (isShizukuInstalled(context)) {
            try {
                android.net.Uri shizukuMgrUri = android.net.Uri.parse("content://moe.shizuku.privileged.api.shizuku");
                Bundle reply = context.getContentResolver().call(shizukuMgrUri, "getBinder", null, new Bundle());
                if (reply != null) {
                    reply.setClassLoader(BinderContainer.class.getClassLoader());
                    BinderContainer c = reply.getParcelable("moe.shizuku.privileged.api.intent.extra.BINDER");
                    if (c == null) c = reply.getParcelable("binder");
                    if (c != null && c.binder != null && c.binder.pingBinder()) {
                        activeManager = "shizuku";
                        Log.i(TAG, "Binder recovered from Shizuku manager provider");
                        onBinderReceived(c.binder, context.getPackageName());
                        return;
                    }
                }
            } catch (Throwable e) {
                Log.d(TAG, "Shizuku manager provider binder recovery failed: " + e.getMessage());
            }
        }

        // Dhizuku is intentionally NOT handled as an IShizukuService binder.
        // Dhizuku exposes its own API/DevicePolicy binder and is integrated by
        // DhizukuBridge. Treating it as Shizuku causes false "connected" states.

        // Step 3: Ask the official Shizuku manager to resend its binder.
        requestBinderFromManager(context);
    }

    /**
     * Broadcasts the standard Shizuku REQUEST_BINDER intent to the manager
     * app. On receipt, the Shizuku server calls back into our own
     * ShizukuProvider's "sendBinder" endpoint (see ShizukuProvider.call) with
     * a fresh binder, which routes into onBinderReceived. This is the same
     * recovery path the official ShizukuProvider uses for non-provider
     * processes; it is re-implemented here without the Shizuku AAR to keep
     * this class dependency-free.
     */
    private static void requestBinderFromManager(Context context) {
        if (context == null || !isShizukuInstalled(context)) return;
        try {
            Intent intent = new Intent("moe.shizuku.manager.action.REQUEST_BINDER");
            intent.setPackage(SHIZUKU_MANAGER);
            context.sendBroadcast(intent);
            Log.i(TAG, "requestBinderFromManager: REQUEST_BINDER broadcast sent");
        } catch (Throwable e) {
            Log.w(TAG, "requestBinderFromManager failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Force one authoritative permission query against the current Shizuku server. */
    public static boolean refreshPermission() {
        if (!isServiceAvailable() || !attached) return false;
        // Run on background thread to avoid ANR
        new Thread(() -> refreshPermissionSync(), "githubk-refresh-perm").start();
        return permissionGranted;
    }

    /**
     * Request permission without blocking the caller.  Multiple clicks/pages
     * share the same in-flight request and therefore cannot create a request
     * storm.
     */
    public static void requestPermission(Runnable callback) {
        // Dhizuku has its own API and is handled by DhizukuBridge.
        // This method is exclusively for the Shizuku permission channel.
        if (!isServiceAvailable() || !attached) {
            postCallback(callback);
            return;
        }
        // Force one authoritative permission query when the cached state says
        // denied. This prevents a stale false value after Shizuku restarts.
        if (hasPermission()) {
            Log.i(TAG, "requestPermission: already granted, skipping");
            postCallback(callback);
            return;
        }
        Log.i(TAG, "requestPermission: permission not granted, will request");

        synchronized (ShizukuBridge.class) {
            if (callback != null) permissionCallbacks.add(callback);
            if (permissionRequestInFlight) return;
            permissionRequestInFlight = true;
        }

        Thread t = new Thread(() -> {
            boolean sent = false;
            try {
                // Do not race requestPermission against attachApplication.
                // Binder delivery and attach are asynchronous; wait only a
                // short bounded period for the attach callback.
                long deadline = System.currentTimeMillis() + 2000L;
                while (!attached && isServiceAvailable() && System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(25L); } catch (InterruptedException ignored) { break; }
                }
                IBinder b = binder;
                if (b == null || !b.pingBinder()) throw new RemoteException("Shizuku binder unavailable");
                if (!attached) throw new RemoteException("Shizuku application attach timeout");
                if (!permissionKnown) refreshPermissionSync();
                if (hasPermission()) {
                    finishPermissionRequest(true);
                    return;
                }
                Parcel d = Parcel.obtain();
                Parcel r = Parcel.obtain();
                try {
                    d.writeInterfaceToken(DESCRIPTOR);
                    d.writeInt(PERMISSION_REQUEST_CODE);
                    boolean ok = b.transact(TRANSACTION_REQUEST_PERMISSION, d, r, 0);
                    if (!ok) throw new RemoteException("requestPermission rejected");
                    r.readException();
                    sent = true;
                } finally {
                    d.recycle();
                    r.recycle();
                }
                Log.i(TAG, "requestPermission sent, requestCode=" + PERMISSION_REQUEST_CODE + " server=" + getServerVersion() + "." + getServerPatchVersion());
            } catch (Throwable e) {
                Log.w(TAG, "requestPermission failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            // The manager normally answers through dispatchRequestPermissionResult.
            // Give it a bounded window, then refresh once and finish callbacks.
            final boolean requestSent = sent;
            MAIN.postDelayed(() -> finishPermissionRequest(requestSent), PERMISSION_REQUEST_TIMEOUT_MS);
        }, "githubk-shizuku-permission");
        t.setDaemon(true);
        t.start();
    }

    private static void finishPermissionRequest(boolean requestSent) {
        synchronized (ShizukuBridge.class) {
            if (!permissionRequestInFlight) return;
        }
        // Do one final sync check before giving up, off the main thread.
        new Thread(() -> finishPermissionRequestBackground(requestSent), "githubk-shizuku-permission-final").start();
    }

    private static void finishPermissionRequestBackground(boolean requestSent) {
        boolean granted = refreshPermissionSync();
        List<Runnable> callbacks = null;
        synchronized (ShizukuBridge.class) {
            if (!permissionRequestInFlight) return;
            // If a positive callback already arrived, this simply drains it.
            permissionRequestInFlight = false;
            callbacks = new ArrayList<>(permissionCallbacks);
            permissionCallbacks.clear();
        }
        if (!granted && requestSent) {
            Log.w(TAG, "permission request timed out; no grant result from Shizuku manager");
        } else if (granted) {
            Log.i(TAG, "permission granted (via callback or final refresh)");
        }
        if (callbacks != null) {
            Log.i(TAG, "Invoking " + callbacks.size() + " permission callbacks");
            for (Runnable r : callbacks) postCallback(r);
        }
    }

    private static void dispatchPermissionResult(int requestCode, boolean allowed) {
        if (requestCode != PERMISSION_REQUEST_CODE) return;
        permissionGranted = allowed;
        permissionKnown = true;
        List<Runnable> callbacks;
        synchronized (ShizukuBridge.class) {
            permissionRequestInFlight = false;
            callbacks = new ArrayList<>(permissionCallbacks);
            permissionCallbacks.clear();
        }
        Log.i(TAG, "permission result requestCode=" + requestCode + " allowed=" + allowed);
        for (Runnable r : callbacks) postCallback(r);
    }

    private static void postCallback(Runnable r) {
        if (r != null) MAIN.post(r);
    }

    public static IUserShellService bindUserService(Context context) {
        if (!isReady() || !hasPermission()) return null;
        IUserShellService existing = userService;
        if (existing != null && userServiceBinder != null && userServiceBinder.pingBinder()) return existing;

        final UserServiceConnection conn = new UserServiceConnection();
        userServiceConnection = conn;
        try {
            Bundle options = new Bundle();
            options.putParcelable("shizuku:user-service-arg-component",
                    new ComponentName(context, "com.example.myempty.githubk.terminal.ShizukuUserService"));
            options.putBoolean("shizuku:user-service-arg-debuggable", false);
            options.putInt("shizuku:user-service-arg-version-code", 2);
            options.putBoolean("shizuku:user-service-arg-daemon", true);
            options.putBoolean("shizuku:user-service-arg-use-32-bit-app-process", false);
            options.putString("shizuku:user-service-arg-process-name", "toolchain");
            options.putString("shizuku:user-service-arg-tag", "githubk-toolchain");

            Parcel d = Parcel.obtain();
            Parcel r = Parcel.obtain();
            try {
                d.writeInterfaceToken(DESCRIPTOR);
                d.writeStrongBinder(conn);
                d.writeInt(1); // Bundle present
                options.writeToParcel(d, 0);
                boolean ok = binder.transact(TRANSACTION_ADD_USER_SERVICE, d, r, 0);
                if (!ok) throw new RemoteException("addUserService rejected");
                r.readException();
            } finally {
                d.recycle();
                r.recycle();
            }

            // UserService is asynchronous.  Wait at most 2 seconds instead of
            // the old 3 seconds; normal devices connect in a few hundred ms.
            for (int i = 0; i < 40; i++) {
                IUserShellService s = userService;
                if (s != null && userServiceBinder != null && userServiceBinder.pingBinder()) return s;
                try { Thread.sleep(50L); } catch (InterruptedException ignored) { break; }
            }
        } catch (Throwable e) {
            Log.w(TAG, "bindUserService failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return userService;
    }

    public static String execAsUserService(Context context, String command, Map<String,String> env, String dir, long timeoutMs) {
        IUserShellService s = bindUserService(context);
        if (s == null) return null;
        String[] e = null;
        if (env != null) {
            e = new String[env.size()];
            int i = 0;
            for (Map.Entry<String,String> x : env.entrySet()) e[i++] = x.getKey() + "=" + x.getValue();
        }
        try {
            return s.exec(command, e, dir, timeoutMs);
        } catch (Throwable ex) {
            return "ERROR: " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
    }

    public static void cancelUserService() {
        IUserShellService s = userService;
        if (s == null) return;
        try { s.cancelCurrent(); } catch (Throwable e) { Log.w(TAG, "cancelUserService failed", e); }
    }

    private static int transactInt(IBinder b, int code) throws RemoteException {
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            boolean ok = b.transact(code, d, r, 0);
            if (!ok) throw new RemoteException("transaction " + code + " rejected");
            r.readException();
            return r.readInt();
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    /** IShizukuApplication callback. */
    private static final IBinder APP_CALLBACK = new android.os.Binder() {
        { attachInterface(null, APP_DESCRIPTOR); }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == 1) { // bindApplication
                data.enforceInterface(APP_DESCRIPTOR);
                Bundle b = data.readInt() != 0 ? data.readBundle(ShizukuBridge.class.getClassLoader()) : null;
                if (b != null) {
                    serverUid = b.getInt("shizuku:attach-reply-uid", -1);
                    serverVersion = b.getInt("shizuku:attach-reply-version", -1);
                    serverPatchVersion = b.getInt("shizuku:attach-reply-patch-version", -1);
                    permissionGranted = b.getBoolean("shizuku:attach-reply-permission-granted", false);
                    permissionKnown = b.containsKey("shizuku:attach-reply-permission-granted");
                    Log.i(TAG, "bindApplication uid=" + serverUid + " version=" + serverVersion + "." + serverPatchVersion + " permission=" + permissionGranted);
                }
                return true;
            }
            if (code == 2) { // dispatchRequestPermissionResult
                data.enforceInterface(APP_DESCRIPTOR);
                int requestCode = data.readInt();
                Bundle b = data.readInt() != 0 ? data.readBundle(ShizukuBridge.class.getClassLoader()) : null;
                boolean allowed = b != null && b.getBoolean("shizuku:request-permission-reply-allowed", false);
                dispatchPermissionResult(requestCode, allowed);
                return true;
            }
            if (code == 3) { // showPermissionConfirmation (non-app client path)
                data.enforceInterface(APP_DESCRIPTOR);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    /** IShizukuServiceConnection callback used for UserService. */
    private static final class UserServiceConnection extends android.os.Binder {
        UserServiceConnection() { attachInterface(null, CONNECTION_DESCRIPTOR); }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == 1) {
                data.enforceInterface(CONNECTION_DESCRIPTOR);
                IBinder b = data.readStrongBinder();
                userServiceBinder = b;
                userService = IUserShellService.Stub.asInterface(b);
                Log.i(TAG, "UserService connected");
                return true;
            }
            if (code == 2) {
                data.enforceInterface(CONNECTION_DESCRIPTOR);
                userService = null;
                userServiceBinder = null;
                Log.w(TAG, "UserService disconnected");
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }
}
