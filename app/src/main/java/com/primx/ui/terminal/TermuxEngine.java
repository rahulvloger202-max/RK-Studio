package com.primx.ui.terminal;

import android.os.Handler;
import android.os.Looper;
import java.io.InputStream;
import java.io.OutputStream;

public class TermuxEngine {
    public interface EngineListener {
        void onOutput(String text);
    }

    private int ptyFd = -1;
    private int shellPid = -1;
    private InputStream ptyIn;
    private OutputStream ptyOut;
    private boolean isRunning = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final EngineListener listener;

    public TermuxEngine(EngineListener listener) {
        this.listener = listener;
    }

    public void startNativeShell(String workingDir) {
        if (isRunning) return;
        new Thread(() -> {
            try {
                String[] env = {"TERM=xterm-256color", "HOME=" + workingDir, "PATH=/system/bin:/system/xbin"};
                int[] pidArray = new int[1];
                
                ptyFd = TerminalPty.createSubprocess("/system/bin/sh", workingDir, new String[]{"-l"}, env, pidArray, 24, 80);
                shellPid = pidArray[0];

                if (ptyFd > 0) {
                    ptyIn = TerminalPty.openInputStream(ptyFd);
                    ptyOut = TerminalPty.openOutputStream(ptyFd);
                    isRunning = true;
                    readLoop();
                } else {
                    mainHandler.post(() -> listener.onOutput("\n[ERROR] Asli shell chalu nahi hua. .so file check karo.\n"));
                }
            } catch (Exception e) {
                mainHandler.post(() -> listener.onOutput("\n[CRASH PREVENTED] " + e.getMessage() + "\n"));
            }
        }).start();
    }

    private void readLoop() {
        new Thread(() -> {
            byte[] buffer = new byte[4096];
            int read;
            try {
                while (isRunning && (read = ptyIn.read(buffer)) != -1) {
                    String text = new String(buffer, 0, read, "UTF-8");
                    mainHandler.post(() -> listener.onOutput(text));
                }
            } catch (Exception e) {
                mainHandler.post(() -> listener.onOutput("\n[STREAM BROKEN]\n"));
            }
        }).start();
    }

    public void writeCommand(String cmd) {
        if (!isRunning || ptyOut == null) return;
        new Thread(() -> {
            try {
                ptyOut.write((cmd + "\n").getBytes("UTF-8"));
                ptyOut.flush();
            } catch (Exception ignored) {}
        }).start();
    }
}
