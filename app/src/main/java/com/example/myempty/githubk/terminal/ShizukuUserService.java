package com.example.myempty.githubk.terminal;

import android.os.Binder;
import android.os.Build;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

/**
 * Shizuku UserService backend.
 *
 * The service itself runs as the Shizuku identity (normally shell UID 2000
 * when Shizuku was started through ADB/wireless debugging, or root when the
 * backend is root).  For GitHubK's private runtime we deliberately enter the
 * app UID through run-as before launching the Termux-compatible runtime.
 */
public class ShizukuUserService extends IUserShellService.Stub {
    private final AtomicReference<Process> currentProcess = new AtomicReference<>();
    private static final String TAG = "GitHubK.UserService";
    private static final String PACKAGE = "com.example.myempty.githubk";

    public ShizukuUserService() {
        android.util.Log.i(TAG, "created uid=" + Binder.getCallingUid());
    }

    public ShizukuUserService(android.content.Context context) {
        android.util.Log.i(TAG, "created with context");
    }

    @Override public int getUid() { return android.os.Process.myUid(); }

    @Override public String identity() {
        return "uid=" + android.os.Process.myUid()
                + " pid=" + android.os.Process.myPid()
                + " sdk=" + Build.VERSION.SDK_INT;
    }

    @Override public String exec(String command, String[] env, String dir, long timeoutMs) {
        if (command == null || command.trim().isEmpty()) return "ERROR: empty command";
        long timeout = Math.max(1000L, Math.min(timeoutMs <= 0 ? 300000L : timeoutMs, 30 * 60 * 1000L));
        Process p = null;
        try {
            String runtime = "/data/data/" + PACKAGE + "/files/runtime";
            StringBuilder shell = new StringBuilder();
            shell.append("run-as ").append(PACKAGE).append(" sh -c '");
            if (dir != null && !dir.trim().isEmpty()) {
                shell.append("cd '").append(quoteSingle(dir)).append("' && ");
            }
            if (env != null) {
                for (String item : env) {
                    if (item == null) continue;
                    int eq = item.indexOf('=');
                    if (eq > 0) {
                        String k = item.substring(0, eq);
                        String v = item.substring(eq + 1);
                        shell.append("export ").append(k).append("='")
                                .append(quoteSingle(v)).append("'; ");
                    }
                }
            }
            shell.append(command).append("'");

            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", shell.toString());
            pb.redirectErrorStream(true);
            p = pb.start();
            currentProcess.set(p);
            StringBuilder out = new StringBuilder();
            Process process = p;
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) out.append(line).append('\n');
                } catch (Throwable ignored) { }
            }, "gk-user-service-output");
            reader.setDaemon(true);
            reader.start();
            boolean done = p.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroyForcibly();
                reader.join(1000);
                return out.append("\nTIMEOUT after ").append(timeout).append("ms").toString();
            }
            reader.join(1500);
            int code = p.exitValue();
            out.append("\n[exit=").append(code).append("]");
            return out.toString();
        } catch (Throwable e) {
            return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            currentProcess.compareAndSet(p, null);
            if (p != null) try { p.destroy(); } catch (Throwable ignored) { }
        }
    }

    @Override public void cancelCurrent() {
        Process p = currentProcess.getAndSet(null);
        if (p != null) {
            try { p.destroy(); } catch (Throwable ignored) { }
            try { if (p.isAlive()) p.destroyForcibly(); } catch (Throwable ignored) { }
        }
    }

    private static String quoteSingle(String s) {
        return s.replace("'", "'\\\"'\\\"'");
    }

    @Override public void destroy() {
        android.util.Log.i(TAG, "destroy");
        System.exit(0);
    }
}
