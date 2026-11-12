#include <jni.h>
#include <string>
#include <fcntl.h>
#include <unistd.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <stdlib.h>

extern "C" JNIEXPORT jobject JNICALL
Java_com_termux_TerminalPty_createSubprocess(JNIEnv *env, jclass clazz, jstring cmd, jobjectArray args, jobjectArray envVars, jintArray processIdArray) {
    int ptm, pts;
    ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) return nullptr;

    grantpt(ptm);
    unlockpt(ptm);
    char *devname = ptsname(ptm);
    pts = open(devname, O_RDWR);

    pid_t pid = fork();
    if (pid == 0) {
        setsid();
        dup2(pts, 0);
        dup2(pts, 1);
        dup2(pts, 2);
        close(ptm);
        close(pts);

        const char *c_cmd = env->GetStringUTFChars(cmd, nullptr);
        setenv("TERM", "xterm-256color", 1);
        execl(c_cmd, c_cmd, "-l", nullptr);
        exit(1);
    } else {
        close(pts);
        jint *pids = env->GetIntArrayElements(processIdArray, nullptr);
        pids[0] = pid;
        env->ReleaseIntArrayElements(processIdArray, pids, 0);

        jclass fdClass = env->FindClass("java/io/FileDescriptor");
        jmethodID fdCons = env->GetMethodID(fdClass, "<init>", "()V");
        jobject fdObj = env->NewObject(fdClass, fdCons);
        jfieldID fdField = env->GetFieldID(fdClass, "descriptor", "I");
        env->SetIntField(fdObj, fdField, ptm);

        return fdObj;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_TerminalPty_setPtyWindowSize(JNIEnv *env, jclass clazz, jobject fdObj, jint rows, jint cols) {
    jclass fdClass = env->FindClass("java/io/FileDescriptor");
    jfieldID fdField = env->GetFieldID(fdClass, "descriptor", "I");
    int fd = env->GetIntField(fdObj, fdField);

    struct winsize sz = { (unsigned short)rows, (unsigned short)cols, 0, 0 };
    ioctl(fd, TIOCSWINSZ, &sz);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_termux_TerminalPty_waitFor(JNIEnv *env, jclass clazz, jint pid) {
    int status;
    waitpid(pid, &status, 0);
    return status;
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_TerminalPty_close(JNIEnv *env, jclass clazz, jobject fdObj) {
    jclass fdClass = env->FindClass("java/io/FileDescriptor");
    jfieldID fdField = env->GetFieldID(fdClass, "descriptor", "I");
    int fd = env->GetIntField(fdObj, fdField);
    close(fd);
}
