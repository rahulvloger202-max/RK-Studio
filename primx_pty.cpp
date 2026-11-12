#define _GNU_SOURCE
#include <jni.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <sys/types.h>
#include <dirent.h>
#include <android/log.h>

#define LOG_TAG "PrimX_PTY"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static int throwRuntimeException(JNIEnv* env, const char* message) {
    jclass exClass = env->FindClass("java/lang/RuntimeException");
    if (exClass != NULL) {
        env->ThrowNew(exClass, message);
    }
    return -1;
}

static int internalCreateSubprocess(
        JNIEnv* env,
        jstring cmd,
        jstring cwd,
        jobjectArray args,
        jobjectArray envVars,
        jintArray processIdArray,
        jint rows,
        jint columns) {

    if (cmd == NULL) {
        return throwRuntimeException(env, "Command cannot be null");
    }

    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) {
        char errBuf[128];
        snprintf(errBuf, sizeof(errBuf), "Cannot open /dev/ptmx: %s", strerror(errno));
        return throwRuntimeException(env, errBuf);
    }

    if (grantpt(ptm) != 0 || unlockpt(ptm) != 0) {
        close(ptm);
        return throwRuntimeException(env, "Failed grantpt() or unlockpt() on /dev/ptmx");
    }

    char devname[64];
    if (ptsname_r(ptm, devname, sizeof(devname)) != 0) {
        close(ptm);
        return throwRuntimeException(env, "Failed ptsname_r() on /dev/ptmx");
    }

    struct termios tios;
    if (tcgetattr(ptm, &tios) == 0) {
        tios.c_iflag |= IUTF8;
        tios.c_iflag &= ~(IXON | IXOFF);
        tcsetattr(ptm, TCSANOW, &tios);
    }

    struct winsize sz;
    memset(&sz, 0, sizeof(sz));
    sz.ws_row = (rows > 0) ? (unsigned short) rows : 24;
    sz.ws_col = (columns > 0) ? (unsigned short) columns : 80;
    ioctl(ptm, TIOCSWINSZ, &sz);

    const char* cmdUtf8 = env->GetStringUTFChars(cmd, NULL);
    const char* cwdUtf8 = (cwd != NULL) ? env->GetStringUTFChars(cwd, NULL) : NULL;

    jsize argc = (args != NULL) ? env->GetArrayLength(args) : 0;
    char** argv = (char**) calloc(argc + 2, sizeof(char*));
    argv[0] = strdup(cmdUtf8);
    for (jsize i = 0; i < argc; i++) {
        jstring argElement = (jstring) env->GetObjectArrayElement(args, i);
        if (argElement != NULL) {
            const char* argUtf8 = env->GetStringUTFChars(argElement, NULL);
            argv[i + 1] = strdup(argUtf8);
            env->ReleaseStringUTFChars(argElement, argUtf8);
            env->DeleteLocalRef(argElement);
        } else {
            argv[i + 1] = strdup("");
        }
    }
    argv[argc + 1] = NULL;

    jsize envc = (envVars != NULL) ? env->GetArrayLength(envVars) : 0;
    char** envp = NULL;
    if (envc > 0) {
        envp = (char**) calloc(envc + 1, sizeof(char*));
        for (jsize i = 0; i < envc; i++) {
            jstring envElement = (jstring) env->GetObjectArrayElement(envVars, i);
            if (envElement != NULL) {
                const char* envUtf8 = env->GetStringUTFChars(envElement, NULL);
                envp[i] = strdup(envUtf8);
                env->ReleaseStringUTFChars(envElement, envUtf8);
                env->DeleteLocalRef(envElement);
            }
        }
        envp[envc] = NULL;
    }

    pid_t pid = fork();
    if (pid < 0) {
        env->ReleaseStringUTFChars(cmd, cmdUtf8);
        if (cwdUtf8 != NULL) env->ReleaseStringUTFChars(cwd, cwdUtf8);
        for (int i = 0; argv[i] != NULL; i++) free(argv[i]);
        free(argv);
        if (envp != NULL) {
            for (int i = 0; envp[i] != NULL; i++) free(envp[i]);
            free(envp);
        }
        close(ptm);
        return throwRuntimeException(env, "fork() failed");
    }

    if (pid == 0) {
        sigset_t signalsToUnblock;
        sigfillset(&signalsToUnblock);
        sigprocmask(SIG_UNBLOCK, &signalsToUnblock, NULL);

        signal(SIGINT, SIG_DFL);
        signal(SIGQUIT, SIG_DFL);
        signal(SIGPIPE, SIG_DFL);
        signal(SIGTERM, SIG_DFL);
        signal(SIGHUP, SIG_DFL);
        signal(SIGCHLD, SIG_DFL);

        setsid();

        int pts = open(devname, O_RDWR);
        if (pts < 0) {
            _exit(127);
        }

        ioctl(pts, TIOCSCTTY, 0);

        dup2(pts, STDIN_FILENO);
        dup2(pts, STDOUT_FILENO);
        dup2(pts, STDERR_FILENO);

        DIR* selfFdDir = opendir("/proc/self/fd");
        if (selfFdDir != NULL) {
            int dirFd = dirfd(selfFdDir);
            struct dirent* entry;
            while ((entry = readdir(selfFdDir)) != NULL) {
                int fd = atoi(entry->d_name);
                if (fd > 2 && fd != dirFd) {
                    close(fd);
                }
            }
            closedir(selfFdDir);
        } else {
            if (pts > 2) close(pts);
            close(ptm);
        }

        if (envp != NULL) {
            clearenv();
            for (int i = 0; envp[i] != NULL; i++) {
                putenv(envp[i]);
            }
        }

        if (cwdUtf8 != NULL && strlen(cwdUtf8) > 0) {
            chdir(cwdUtf8);
        }

        execvp(cmdUtf8, argv);
        _exit(127);
    }

    env->ReleaseStringUTFChars(cmd, cmdUtf8);
    if (cwdUtf8 != NULL) env->ReleaseStringUTFChars(cwd, cwdUtf8);
    for (int i = 0; argv[i] != NULL; i++) free(argv[i]);
    free(argv);
    if (envp != NULL) {
        for (int i = 0; envp[i] != NULL; i++) free(envp[i]);
        free(envp);
    }

    if (processIdArray != NULL && env->GetArrayLength(processIdArray) > 0) {
        jint childPid = (jint) pid;
        env->SetIntArrayRegion(processIdArray, 0, 1, &childPid);
    }

    LOGI("Spawned PTY subprocess PID=%d MasterFD=%d", (int) pid, ptm);
    return ptm;
}

static void internalSetPtyWindowSize(jint fd, jint rows, jint cols, jint xpixel, jint ypixel) {
    if (fd < 0) return;
    struct winsize sz;
    memset(&sz, 0, sizeof(sz));
    sz.ws_row = (unsigned short) rows;
    sz.ws_col = (unsigned short) cols;
    sz.ws_xpixel = (unsigned short) xpixel;
    sz.ws_ypixel = (unsigned short) ypixel;
    ioctl(fd, TIOCSWINSZ, &sz);
}

static jint internalWaitFor(jint pid) {
    int status = 0;
    if (waitpid((pid_t) pid, &status, 0) < 0) {
        return -1;
    }
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        return -WTERMSIG(status);
    }
    return 0;
}

static void internalClosePty(jint fd) {
    if (fd >= 0) {
        close(fd);
    }
}

static jint internalSendSignal(jint pid, jint sig) {
    if (pid <= 0) return -1;
    if (kill(-((pid_t) pid), sig) == 0) {
        return 0;
    }
    return kill((pid_t) pid, sig);
}

#define EXPORT_PTY_JNI(PKG_PREFIX) \
extern "C" JNIEXPORT jint JNICALL Java_##PKG_PREFIX##_TerminalPty_createSubprocess( \
        JNIEnv* env, jclass clazz, jstring cmd, jstring cwd, jobjectArray args, \
        jobjectArray envVars, jintArray processIdArray, jint rows, jint columns) { \
    return internalCreateSubprocess(env, cmd, cwd, args, envVars, processIdArray, rows, columns); \
} \
extern "C" JNIEXPORT void JNICALL Java_##PKG_PREFIX##_TerminalPty_setPtyWindowSize( \
        JNIEnv* env, jclass clazz, jint fd, jint rows, jint cols, jint xpixel, jint ypixel) { \
    internalSetPtyWindowSize(fd, rows, cols, xpixel, ypixel); \
} \
extern "C" JNIEXPORT jint JNICALL Java_##PKG_PREFIX##_TerminalPty_waitFor( \
        JNIEnv* env, jclass clazz, jint pid) { \
    return internalWaitFor(pid); \
} \
extern "C" JNIEXPORT void JNICALL Java_##PKG_PREFIX##_TerminalPty_closePty( \
        JNIEnv* env, jclass clazz, jint fd) { \
    internalClosePty(fd); \
} \
extern "C" JNIEXPORT jint JNICALL Java_##PKG_PREFIX##_TerminalPty_sendSignal( \
        JNIEnv* env, jclass clazz, jint pid, jint sig) { \
    return internalSendSignal(pid, sig); \
}

EXPORT_PTY_JNI(com_hastag)
EXPORT_PTY_JNI(com_primx_ui_terminal)
EXPORT_PTY_JNI(com_primx_terminal)
EXPORT_PTY_JNI(com_primx_ide)
