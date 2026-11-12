package com.primx.ui.terminal;

import android.content.Context;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;

public class TerminalPty {
    private static boolean libraryLoaded = false;

    public static boolean initNative(Context context) {
        if (libraryLoaded) return true;
        try {
            System.loadLibrary("primx_pty");
            libraryLoaded = true;
            return true;
        } catch (UnsatisfiedLinkError e) {
            try {
                File externalSo = new File("/storage/emulated/0/Download/libprimx_pty.so");
                if (externalSo.exists()) {
                    File internalSo = new File(context.getFilesDir(), "libprimx_pty.so");
                    copyFile(externalSo, internalSo);
                    System.load(internalSo.getAbsolutePath());
                    libraryLoaded = true;
                    return true;
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
        return false;
    }

    private static void copyFile(File src, File dst) throws IOException {
        InputStream in = new FileInputStream(src);
        OutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        in.close();
        out.close();
    }

    public static native int createSubprocess(String cmd, String cwd, String[] args, String[] envVars, int[] processIdArray, int rows, int columns);
    public static native void setPtyWindowSize(int fd, int rows, int cols, int xpixel, int ypixel);
    public static native int waitFor(int pid);
    public static native void closePty(int fd);
    public static native int sendSignal(int pid, int sig);

    public static FileDescriptor wrapFileDescriptor(int rawFd) {
        FileDescriptor fdObj = new FileDescriptor();
        try {
            Field descriptorField;
            try { descriptorField = FileDescriptor.class.getDeclaredField("descriptor"); }
            catch (NoSuchFieldException e) { descriptorField = FileDescriptor.class.getDeclaredField("fd"); }
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
