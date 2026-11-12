package com.termux;
import java.io.FileDescriptor;
import java.lang.reflect.Field;

public class TerminalPty {
    static { System.loadLibrary("primx_pty"); }
    public static native FileDescriptor createSubprocess(String cmd, String[] args, String[] envVars, int[] processId);
    public static native void setPtyWindowSize(FileDescriptor fd, int rows, int cols);
    public static native int waitFor(int processId);
    public static native void close(FileDescriptor fd);
    
    public static int getFd(FileDescriptor fileDescriptor) {
        try {
            Field descriptorField = FileDescriptor.class.getDeclaredField("descriptor");
            descriptorField.setAccessible(true);
            return descriptorField.getInt(fileDescriptor);
        } catch (Exception e) { return -1; }
    }
}
