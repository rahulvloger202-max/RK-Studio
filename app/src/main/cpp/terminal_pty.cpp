#include <jni.h>
#include <string>
#include <fcntl.h>
#include <unistd.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <stdlib.h>
#include <vector>

extern "C" JNIEXPORT jint JNICALL
Java_com_termux_TerminalPty_createSubprocess(JNIEnv *env, jclass clazz, jstring cmd, jobjectArray args, jobjectArray envVars, jintArray processIdArray) {
    
    const char *c_cmd = env->GetStringUTFChars(cmd, nullptr);
    
    // Environment variables
    int envCount = env->GetArrayLength(envVars);
    std::vector<std::string> env_strings;
    std::vector<const char*> envp;
    for (int i = 0; i < envCount; i++) {
        jstring envStr = (jstring)env->GetObjectArrayElement(envVars, i);
        const char *rawStr = env->GetStringUTFChars(envStr, nullptr);
        env_strings.push_back(std::string(rawStr));
        env->ReleaseStringUTFChars(envStr, rawStr);
    }
    for (auto& s : env_strings) {
        envp.push_back(s.c_str());
    }
    envp.push_back(nullptr);

    // PTY setup
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) {
        env->ReleaseStringUTFChars(cmd, c_cmd);
        return -1;
    }

    grantpt(ptm);
    unlockpt(ptm);
    char *devname = ptsname(ptm);
    int pts = open(devname, O_RDWR);

    pid_t pid = fork();
    
    if (pid == 0) {
        setsid();
        
        ioctl(pts, TIOCSCTTY, 0);
        dup2(pts, 0); 
        dup2(pts, 1); 
        dup2(pts, 2);
        close(ptm); 
        close(pts);

        // 🔥 FIX: Java ke arguments ko decode karke execve mein dalna
        int argCount = env->GetArrayLength(args);
        std::vector<std::string> arg_strings;
        std::vector<const char*> argv_ptrs;

        for (int i = 0; i < argCount; i++) {
            jstring argStr = (jstring)env->GetObjectArrayElement(args, i);
            const char *rawArg = env->GetStringUTFChars(argStr, nullptr);
            arg_strings.push_back(std::string(rawArg));
            env->ReleaseStringUTFChars(argStr, rawArg);
        }

        for (auto& s : arg_strings) {
            argv_ptrs.push_back(s.c_str());
        }
        argv_ptrs.push_back(nullptr);
        
        execve(c_cmd, (char* const*)argv_ptrs.data(), (char* const*)envp.data());
        
        _exit(127); 
    } else {
        close(pts);
        
        jint *pids = env->GetIntArrayElements(processIdArray, nullptr);
        pids[0] = pid;
        env->ReleaseIntArrayElements(processIdArray, pids, 0);
        
        env->ReleaseStringUTFChars(cmd, c_cmd);
        return ptm;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_TerminalPty_setPtyWindowSize(JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols) {
    struct winsize sz = { (unsigned short)rows, (unsigned short)cols, 0, 0 };
    ioctl(fd, TIOCSWINSZ, &sz);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_termux_TerminalPty_waitFor(JNIEnv *env, jclass clazz, jint pid) {
    int status;
    waitpid(pid, &status, 0);
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    return -1;
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_TerminalPty_closePty(JNIEnv *env, jclass clazz, jint fd) {
    close(fd);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_termux_TerminalPty_sendSignal(JNIEnv *env, jclass clazz, jint pid, jint sig) {
    return kill(pid, sig);
}
