#include <jni.h>
#include <fcntl.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/wait.h>
#include <sys/ioctl.h>
#include <termios.h>

extern "C" JNIEXPORT jint JNICALL
Java_com_primx_ui_terminal_TerminalPty_createSubprocess(
        JNIEnv *env, jclass clazz, jstring cmd, jstring cwd,
        jobjectArray args, jobjectArray envVars, jintArray processIdArray,
        jint rows, jint columns) {
    
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) return -1;
    grantpt(ptm);
    unlockpt(ptm);
    char pts_name[64];
    ptsname_r(ptm, pts_name, sizeof(pts_name));
    
    int pid = fork();
    if (pid == 0) { 
        setsid();
        int pts = open(pts_name, O_RDWR);
        dup2(pts, 0); dup2(pts, 1); dup2(pts, 2);
        close(ptm); close(pts);
        
        const char *c_cmd = env->GetStringUTFChars(cmd, nullptr);
        const char *c_cwd = env->GetStringUTFChars(cwd, nullptr);
        chdir(c_cwd);
        execlp(c_cmd, c_cmd, "-l", nullptr);
        exit(1);
    } else if (pid > 0) {
        jint *pids = env->GetIntArrayElements(processIdArray, nullptr);
        pids[0] = pid;
        env->ReleaseIntArrayElements(processIdArray, pids, 0);
        return ptm;
    }
    return -1;
}

extern "C" JNIEXPORT void JNICALL
Java_com_primx_ui_terminal_TerminalPty_setPtyWindowSize(
        JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols, jint xpixel, jint ypixel) {
    struct winsize sz = { (unsigned short)rows, (unsigned short)cols, (unsigned short)xpixel, (unsigned short)ypixel };
    ioctl(fd, TIOCSWINSZ, &sz);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_primx_ui_terminal_TerminalPty_waitFor(JNIEnv *env, jclass clazz, jint pid) {
    int status;
    waitpid(pid, &status, 0);
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    return -1;
}

extern "C" JNIEXPORT void JNICALL
Java_com_primx_ui_terminal_TerminalPty_closePty(JNIEnv *env, jclass clazz, jint fd) {
    close(fd);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_primx_ui_terminal_TerminalPty_sendSignal(JNIEnv *env, jclass clazz, jint pid, jint sig) {
    return kill(pid, sig);
}
