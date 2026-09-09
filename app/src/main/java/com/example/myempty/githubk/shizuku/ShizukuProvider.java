package com.example.myempty.githubk.shizuku;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import moe.shizuku.api.BinderContainer;

/**
 * Dependency-free equivalent of the official ShizukuProvider's binder receive
 * endpoint.  The provider is intentionally exported and protected by a
 * permission granted to the shell/Shizuku server, matching the official model.
 */
public final class ShizukuProvider extends ContentProvider {
    public static final String METHOD_SEND_BINDER = "sendBinder";
    public static final String METHOD_GET_BINDER = "getBinder";
    private static final String EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";
    private static final String TAG = "GitHubK.ShizukuProvider";

    @Override public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);
        if (info.multiprocess) throw new IllegalStateException("ShizukuProvider must use multiprocess=false");
        if (!info.exported) throw new IllegalStateException("ShizukuProvider must be exported");
    }

    @Override public boolean onCreate() {
        return true;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Context ctx = getContext();
        if (METHOD_GET_BINDER.equals(method)) {
            Bundle reply = new Bundle();
            try {
                IBinder b = ShizukuBridge.binder();
                if (b != null && b.pingBinder()) {
                    reply.putParcelable(EXTRA_BINDER, new BinderContainer(b));
                }
            } catch (Throwable e) {
                Log.w(TAG, "get binder failed", e);
            }
            return reply;
        }
        if (!METHOD_SEND_BINDER.equals(method) || extras == null) return new Bundle();
        try {
            extras.setClassLoader(BinderContainer.class.getClassLoader());
            BinderContainer c = extras.getParcelable(EXTRA_BINDER);
            if (c != null && c.binder != null && c.binder.pingBinder()) {
                ShizukuBridge.onBinderReceived(c.binder, ctx == null ? "com.example.myempty.githubk" : ctx.getPackageName());
                Log.i(TAG, "Shizuku binder received");
            }
        } catch (Throwable e) {
            Log.w(TAG, "receive binder failed", e);
        }
        return new Bundle();
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
