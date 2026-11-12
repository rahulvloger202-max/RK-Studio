#include <jni.h>
#include <string.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <stdlib.h>
#include <android/log.h>
#include <errno.h>

#define LOG_TAG "PrimXTermuxNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" {

// 1. Asli Shell/Process ko PTY ke sath Start karne ka function
JNIEXPORT jobject JNICALL
Java_com_primx_ui_terminal_TerminalPty_createSubprocess(JNIEnv *env, jclass clazz, jstring cmd,
                                                        jobjectArray args, jobjectArray envVars,
                                                        jintArray processIdArray) {
    // Master PTY open karo (Yeh Termux ki jaan hai)
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) {
        LOGE("Error: Cannot open /dev/ptmx (errno: %d)", errno);
        return nullptr;
    }

    fcntl(ptm, F_SETFD, FD_CLOEXEC);

    // PTY ko unlock aur permission grant karo
    if (grantpt(ptm) || unlockpt(ptm)) {
        LOGE("Error: Cannot grantpt or unlockpt (errno: %d)", errno);
        close(ptm);
        return nullptr;
    }

    // Slave PTY ka naam nikalo (jaise /dev/pts/1)
    const char *pts_name = ptsname(ptm);
    if (pts_name == nullptr) {
        LOGE("Error: ptsname failed (errno: %d)", errno);
        close(ptm);
        return nullptr;
    }

    // Process ko do hisso mein banto (Fork)
    pid_t pid = fork();
    if (pid < 0) {
        LOGE("Error: Fork failed (errno: %d)", errno);
        close(ptm);
        return nullptr;
    }

    if (pid == 0) {
        // ================= CHILD PROCESS =================
        // Yahan asli shell (sh, bash) chalta hai
        
        setsid(); // Naya session start karo
        
        int pts = open(pts_name, O_RDWR);
        if (pts < 0) {
            LOGE("Error: Cannot open slave PTY (errno: %d)", errno);
            exit(-1);
        }

        // Standard Input/Output/Error ko PTY se jod do
        dup2(pts, STDIN_FILENO);
        dup2(pts, STDOUT_FILENO);
        dup2(pts, STDERR_FILENO);
        
        close(ptm);
        close(pts);

        // 1. Environment variables set karo
        int env_len = env->GetArrayLength(envVars);
        char *envp[env_len + 1];
        for (int i = 0; i < env_len; ++i) {
            jstring env_str = (jstring) env->GetObjectArrayElement(envVars, i);
            envp[i] = strdup(env->GetStringUTFChars(env_str, nullptr));
        }
        envp[env_len] = nullptr;

        // 2. Command ke Arguments set karo
        int arg_len = env->GetArrayLength(args);
        char *argv[arg_len + 2];
        const char *cmd_str = env->GetStringUTFChars(cmd, nullptr);
        argv[0] = strdup(cmd_str);
        for (int i = 0; i < arg_len; ++i) {
            jstring arg_str = (jstring) env->GetObjectArrayElement(args, i);
            argv[i + 1] = strdup(env->GetStringUTFChars(arg_str, nullptr));
        }
        argv[arg_len + 1] = nullptr;

        // 3. BOOM! Shell execute karo
        execve(cmd_str, argv, envp);
        
        // Agar execve fail ho gaya toh yahan aayega
        LOGE("Error: execve failed for %s (errno: %d)", cmd_str, errno);
        exit(-1);
        
    } else {
        // ================= PARENT PROCESS =================
        // Java ko Process ID aur File Descriptor lautana
        
        LOGI("Successfully started shell with PID: %d", pid);
        
        // PID wapas Java array mein daalo
        jint *pId = env->GetIntArrayElements(processIdArray, nullptr);
        pId[0] = pid;
        env->ReleaseIntArrayElements(processIdArray, pId, 0);

        // Java ka FileDescriptor object banakar usme PTY master ka integer (ptm) chupao
        jclass fdClass = env->FindClass("java/io/FileDescriptor");
        jmethodID fdCons = env->GetMethodID(fdClass, "<init>", "()V");
        jobject fdObj = env->NewObject(fdClass, fdCons);

        jfieldID fdField = env->GetFieldID(fdClass, "descriptor", "I");
        env->SetIntField(fdObj, fdField, ptm);

        return fdObj;
    }
}

// 2. Terminal ki Size (Rows & Columns) C++ se control karna
JNIEXPORT void JNICALL
Java_com_primx_ui_terminal_TerminalPty_setPtyWindowSize(JNIEnv *env, jclass clazz, jobject fdObj, jint rows, jint cols) {
    jclass fdClass = env->FindClass("java/io/FileDescriptor");
    jfieldID fdField = env->GetFieldID(fdClass, "descriptor", "I");
    int fd = env->GetIntField(fdObj, fdField);

    struct winsize sz;
    sz.ws_row = rows;
    sz.ws_col = cols;
    sz.ws_xpixel = 0;
    sz.ws_ypixel = 0;
    
    // Asli ioctl call jo kernel ko size batata hai
    ioctl(fd, TIOCSWINSZ, &sz);
}

// 3. Process kab band hua, uska status check karna
JNIEXPORT jint JNICALL
Java_com_primx_ui_terminal_TerminalPty_waitFor(JNIEnv *env, jclass clazz, jint pid) {
    int status;
    waitpid(pid, &status, 0);
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        return WTERMSIG(status);
    }
    return -1;
}

// 4. PTY File descriptor ko cleanly close karna
JNIEXPORT void JNICALL
Java_com_primx_ui_terminal_TerminalPty_close(JNIEnv *env, jclass clazz, jobject fdObj) {
    jclass fdClass = env->FindClass("java/io/FileDescriptor");
    jfieldID fdField = env->GetFieldID(fdClass, "descriptor", "I");
    int fd = env->GetIntField(fdObj, fdField);
    close(fd);
}

} // extern "C"
