package com.termux;

import android.content.Context;
import java.io.FileDescriptor;
import java.lang.reflect.Field;

public class TerminalPty {
    
    private static boolean libraryLoaded = false;

    public static boolean initNative(Context context) {
        if (libraryLoaded) return true;
        try {
            // 🔥 FIX: Wapas wahi naam daal diye jo APK ke andar actually maujood hain
            System.loadLibrary("c++_shared"); 
            System.loadLibrary("super_pty"); 
            
            libraryLoaded = true;
            return true;
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return false;
    }

    public static native int createSubprocess(String cmd, String[] args, String[] envVars, int[] processId);
    public static native void setPtyWindowSize(int fd, int rows, int cols);
    
    // Zombie processes aur memory leak rokne ke liye
    public static native int waitFor(int pid);
    public static native void closePty(int fd);
    public static native int sendSignal(int pid, int sig);

    public static FileDescriptor wrapFileDescriptor(int rawFd) {
        FileDescriptor fdObj = new FileDescriptor();
        try {
            Field descriptorField;
            try {
                descriptorField = FileDescriptor.class.getDeclaredField("descriptor");
            } catch (NoSuchFieldException e) {
                descriptorField = FileDescriptor.class.getDeclaredField("fd");
            }
            descriptorField.setAccessible(true);
            descriptorField.setInt(fdObj, rawFd);
        } catch (Exception e) {}
        return fdObj;
    }
}
