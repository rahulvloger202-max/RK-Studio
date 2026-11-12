package com.primx.ui.terminal;

import android.content.Context;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;

public class TerminalPty {
    private static boolean libraryLoaded = false;

    public static boolean initNative(Context context) {
        if (libraryLoaded) return true;
        try {
            // Yeh seedha APK ke andar se .so file ko load karega bina kisi permission ke!
            System.loadLibrary("primx_pty");
            libraryLoaded = true;
            return true;
        } catch (UnsatisfiedLinkError e) {
            e.printStackTrace();
            return false;
        }
    }

    // Native Methods (C++ se connect hone ke liye)
    public static native int createSubprocess(String cmd, String cwd, String[] args, String[] envVars, int[] processIdArray, int rows, int columns);
    public static native void setPtyWindowSize(int fd, int rows, int cols, int xpixel, int ypixel);
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
        } catch (Exception e) {
            throw new RuntimeException("Unable to wrap native PTY fd", e);
        }
        return fdObj;
    }

    public static InputStream openInputStream(int masterFd) {
        return new FileInputStream(wrapFileDescriptor(masterFd));
    }

    public static OutputStream openOutputStream(int masterFd) {
        return new FileOutputStream(wrapFileDescriptor(masterFd));
    }
}
