// AIDL interface for the Shizuku-hosted user service used by GitHubK Studio.
//
// This is intentionally minimal and dependency-free (no Shizuku AAR): the
// generated IUserShellService.Stub / IUserShellService.Stub.Proxy classes are
// used directly by ShizukuUserService (server side, extends Stub) and
// ShizukuBridge (client side, via Stub.asInterface on the returned IBinder).
package com.example.myempty.githubk.terminal;

interface IUserShellService {
    int getUid();
    String identity();
    String exec(String command, in String[] env, String dir, long timeoutMs);
    void cancelCurrent();
    void destroy();
}
