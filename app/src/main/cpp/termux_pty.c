#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <linux/limits.h>

static int throw_rt(JNIEnv *env, const char *msg) {
    jclass c = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (c) (*env)->ThrowNew(env, c, msg);
    return -1;
}

static char *dup_jstring(JNIEnv *env, jstring s) {
    if (!s) return NULL;
    const char *p = (*env)->GetStringUTFChars(env, s, NULL);
    if (!p) return NULL;
    char *out = strdup(p);
    (*env)->ReleaseStringUTFChars(env, s, p);
    return out;
}

JNIEXPORT jint JNICALL
Java_com_example_myempty_githubk_terminal_pty_NativePty_nativeCreate(
        JNIEnv *env, jclass clazz, jstring cmd, jstring cwd,
        jobjectArray argvJava, jobjectArray envJava, jintArray pidOut,
        jint rows, jint cols, jint cellW, jint cellH) {
    (void)clazz;
    int master = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (master < 0) return throw_rt(env, "open(/dev/ptmx) failed");
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        close(master); return throw_rt(env, "grantpt/unlockpt failed");
    }
    char slaveName[128];
    if (ptsname_r(master, slaveName, sizeof(slaveName)) != 0) {
        close(master); return throw_rt(env, "ptsname_r failed");
    }

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)(rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short)(cols > 0 ? cols : 80);
    ws.ws_xpixel = (unsigned short)((cols > 0 ? cols : 80) * (cellW > 0 ? cellW : 10));
    ws.ws_ypixel = (unsigned short)((rows > 0 ? rows : 24) * (cellH > 0 ? cellH : 20));
    ioctl(master, TIOCSWINSZ, &ws);

    jsize argc = argvJava ? (*env)->GetArrayLength(env, argvJava) : 0;
    char **argv = calloc((size_t)argc + 2, sizeof(char*));
    if (!argv) { close(master); return throw_rt(env, "argv allocation failed"); }
    char *cmdUtf = dup_jstring(env, cmd);
    if (!cmdUtf) { free(argv); close(master); return throw_rt(env, "command conversion failed"); }
    if (argc == 0) {
        argv[0] = strdup(cmdUtf);
    } else {
        for (jsize i = 0; i < argc; ++i) {
            jstring a = (jstring)(*env)->GetObjectArrayElement(env, argvJava, i);
            argv[i] = dup_jstring(env, a);
            (*env)->DeleteLocalRef(env, a);
        }
        argv[argc] = NULL;
    }

    jsize envc = envJava ? (*env)->GetArrayLength(env, envJava) : 0;
    char **envp = calloc((size_t)envc + 1, sizeof(char*));
    if (!envp) { free(cmdUtf); for(char **p=argv; *p; ++p) free(*p); free(argv); close(master); return throw_rt(env, "env allocation failed"); }
    for (jsize i = 0; i < envc; ++i) {
        jstring e = (jstring)(*env)->GetObjectArrayElement(env, envJava, i);
        envp[i] = dup_jstring(env, e);
        (*env)->DeleteLocalRef(env, e);
    }

    pid_t pid = fork();
    if (pid < 0) {
        free(cmdUtf); for(char **p=argv; *p; ++p) free(*p); free(argv); for(char **p=envp; *p; ++p) free(*p); free(envp); close(master);
        return throw_rt(env, "fork failed");
    }
    if (pid == 0) {
        sigset_t set; sigfillset(&set); sigprocmask(SIG_UNBLOCK, &set, NULL);
        setsid();
        int slave = open(slaveName, O_RDWR);
        if (slave < 0) _exit(127);
#ifdef TIOCSCTTY
        ioctl(slave, TIOCSCTTY, 0);
#endif
        dup2(slave, STDIN_FILENO); dup2(slave, STDOUT_FILENO); dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        if (cwd) {
            char *cwdUtf = dup_jstring(env, cwd);
            if (cwdUtf) { if (chdir(cwdUtf) != 0) { /* shell will report */ } free(cwdUtf); }
        }
        clearenv();
        for (char **p = envp; p && *p; ++p) putenv(*p);
        execvp(cmdUtf, argv);
        dprintf(STDERR_FILENO, "exec(%s): %s\n", cmdUtf, strerror(errno));
        _exit(127);
    }

    if (pidOut) {
        jint *p = (*env)->GetIntArrayElements(env, pidOut, NULL);
        if (p) { p[0] = (jint)pid; (*env)->ReleaseIntArrayElements(env, pidOut, p, 0); }
    }
    free(cmdUtf); for(char **p=argv; *p; ++p) free(*p); free(argv); for(char **p=envp; *p; ++p) free(*p); free(envp);
    return master;
}

JNIEXPORT jint JNICALL
Java_com_example_myempty_githubk_terminal_pty_NativePty_nativeRead(JNIEnv *env, jclass clazz, jint fd, jbyteArray buffer, jint offset, jint length) {
    (void)clazz;
    if (fd < 0 || !buffer || offset < 0 || length <= 0) return -EINVAL;
    jsize cap = (*env)->GetArrayLength(env, buffer);
    if (offset > cap || length > cap - offset) return -EINVAL;
    jbyte *dst = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (!dst) return -ENOMEM;
    ssize_t n;
    do { n = read(fd, dst + offset, (size_t)length); } while (n < 0 && errno == EINTR);
    (*env)->ReleaseByteArrayElements(env, buffer, dst, 0);
    if (n >= 0) return (jint)n;
    if (errno == EIO || errno == EBADF) return -1;
    return -errno;
}

JNIEXPORT jint JNICALL
Java_com_example_myempty_githubk_terminal_pty_NativePty_nativeWrite(JNIEnv *env, jclass clazz, jint fd, jbyteArray buffer, jint offset, jint length) {
    (void)clazz;
    if (fd < 0 || !buffer || offset < 0 || length <= 0) return -EINVAL;
    jsize cap = (*env)->GetArrayLength(env, buffer);
    if (offset > cap || length > cap - offset) return -EINVAL;
    jbyte *src = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (!src) return -ENOMEM;
    ssize_t n;
    do { n = write(fd, src + offset, (size_t)length); } while (n < 0 && errno == EINTR);
    (*env)->ReleaseByteArrayElements(env, buffer, src, JNI_ABORT);
    if (n >= 0) return (jint)n;
    return -errno;
}

JNIEXPORT jint JNICALL
Java_com_example_myempty_githubk_terminal_pty_NativePty_nativeResize(JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols, jint cellW, jint cellH) {
    (void)env; (void)clazz;
    if (fd < 0) return -EBADF;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)(rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short)(cols > 0 ? cols : 80);
    ws.ws_xpixel = (unsigned short)((cols > 0 ? cols : 80) * (cellW > 0 ? cellW : 9));
    ws.ws_ypixel = (unsigned short)((rows > 0 ? rows : 24) * (cellH > 0 ? cellH : 18));
    return ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_com_example_myempty_githubk_terminal_pty_NativePty_nativeWait(JNIEnv *env, jclass clazz, jint pid) {
    (void)env; (void)clazz;
    int status = 0;
    if (waitpid((pid_t)pid, &status, 0) < 0) return -errno;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_example_myempty_githubk_terminal_pty_NativePty_nativeSignal(JNIEnv *env, jclass clazz, jint pid, jint signal) {
    (void)env; (void)clazz;
    return kill((pid_t)pid, signal);
}

JNIEXPORT void JNICALL
Java_com_example_myempty_githubk_terminal_pty_NativePty_nativeClose(JNIEnv *env, jclass clazz, jint fd) {
    (void)env; (void)clazz;
    if (fd >= 0) close(fd);
}
