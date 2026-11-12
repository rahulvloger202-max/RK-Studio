package com.termux;
import android.os.Handler;
import android.os.Looper;
import java.io.*;

public class TermuxEngine {
    public interface EngineListener { void onOutput(String text); }
    private int ptyFd = -1;
    private InputStream ptyIn;
    private OutputStream ptyOut;
    private boolean isRunning = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final EngineListener listener;

    public TermuxEngine(EngineListener listener) { this.listener = listener; }

    public void startNativeShell() {
        if (isRunning) return;
        new Thread(() -> {
            try {
                String termuxHome = "/data/data/com.termux/files/home";
                String termuxUsr = "/data/data/com.termux/files/usr";
                String[] env = {
                    "TERM=xterm-256color", 
                    "HOME=" + termuxHome, 
                    "PREFIX=" + termuxUsr,
                    "LD_LIBRARY_PATH=" + termuxUsr + "/lib",
                    "PATH=" + termuxUsr + "/bin:/system/bin:/system/xbin"
                };
                int[] pidArray = new int[1];
                
                // Original bash ka rasta!
                String bashPath = termuxUsr + "/bin/bash";
                FileDescriptor fdObj = TerminalPty.createSubprocess(bashPath, new String[]{"-l"}, env, pidArray);

                if (fdObj != null) {
                    ptyFd = TerminalPty.getFd(fdObj);
                    TerminalPty.setPtyWindowSize(fdObj, 24, 80);
                    ptyIn = new FileInputStream(fdObj);
                    ptyOut = new FileOutputStream(fdObj);
                    isRunning = true;
                    readLoop();
                } else {
                    mainHandler.post(() -> listener.onOutput("\n[ERROR] Bash start nahi hua! Filesystem extract nahi hai.\n"));
                }
            } catch (Exception e) {
                mainHandler.post(() -> listener.onOutput("\n[CRASH PREVENTED] Shell Error: " + e.getMessage() + "\n"));
            }
        }).start();
    }

    private void readLoop() {
        new Thread(() -> {
            byte[] buffer = new byte[4096];
            int read;
            try {
                while (isRunning && ptyIn != null && (read = ptyIn.read(buffer)) != -1) {
                    if (read > 0) {
                        String text = new String(buffer, 0, read, "UTF-8");
                        mainHandler.post(() -> listener.onOutput(text));
                    }
                }
            } catch (Exception e) {}
        }).start();
    }

    public void writeCommand(String cmd) {
        if (!isRunning || ptyOut == null) return;
        new Thread(() -> {
            try {
                ptyOut.write((cmd + "\n").getBytes("UTF-8"));
                ptyOut.flush();
            } catch (Exception e) {}
        }).start();
    }
}
