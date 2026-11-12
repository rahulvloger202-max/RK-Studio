package com.hastag;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;

public class TerminalPty {

    public static final int SIGINT = 2;
    public static final int SIGQUIT = 3;
    public static final int SIGKILL = 9;
    public static final int SIGTERM = 15;
    public static final int SIGCONT = 18;
    public static final int SIGTSTP = 20;

    private static boolean libraryLoaded = false;

    static {
        try {
            System.loadLibrary("primx_pty");
            libraryLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            libraryLoaded = false;
        }
    }

    public static boolean isNativeAvailable() {
        return libraryLoaded;
    }

    public static native int createSubprocess(
            String cmd,
            String cwd,
            String[] args,
            String[] envVars,
            int[] processIdArray,
            int rows,
            int columns
    );

    public static native void setPtyWindowSize(
            int fd,
            int rows,
            int cols,
            int xpixel,
            int ypixel
    );

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
            throw new RuntimeException("Unable to wrap native PTY fd: " + e.getMessage(), e);
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
