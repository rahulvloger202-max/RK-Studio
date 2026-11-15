package com.termux;

import android.app.Activity;
import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import com.primx.compiler.Compiler;

public class TerminalSession {

    public interface TerminalUiListener {
        void onSessionBufferUpdated(SpannableStringBuilder buffer, int lockedLength, String sessionTitle);
        void onSessionListUpdated(List<SessionInfo> sessionInfos, int activeIndex);
        void onRequestNetworkDialog();
        void onRequestCloudSetupDialog();
    }

    public static class SessionInfo {
        public final int id;
        public final String title;
        public SessionInfo(int id, String title) { this.id = id; this.title = title; }
    }

    public static class ShellState {
        final int id;
        String title;
        File workingDir;
        int processId = -1;
        int ptyFd = -1;
        InputStream stdinReader;
        OutputStream stdin;
        final SpannableStringBuilder buffer = new SpannableStringBuilder();
        SpannableStringBuilder savedMainBuffer = null;
        boolean alive = false;
        int lockedLength = 0;
        boolean onlineMode = false;
        ShellState(int id, String title, File workingDir, boolean onlineMode) {
            this.id = id; this.title = title; this.workingDir = workingDir; this.onlineMode = onlineMode;
        }
    }

    private static final List<ShellState> sessions = new ArrayList<>();
    private static int activeSessionIndex = 0;
    private static int sessionCounter = 1;

    private static TerminalUiListener activeUiListener;
    private static Activity activeActivity;

    private final Activity activity;
    private boolean isLightTheme;
    private int defaultTextColor;
    private File defaultWorkingDir;
    private File workspaceDir;
    private boolean currentOnlineMode = false;

    private static volatile boolean commandInProgress = false;
    private static volatile boolean isBuilding = false;

    public TerminalSession(Activity activity, String initialProjectPath, boolean isLightTheme, int defaultTextColor, TerminalUiListener uiListener) {
        this.activity = activity;
        this.isLightTheme = isLightTheme;
        this.defaultTextColor = defaultTextColor;

        TerminalSession.activeActivity = activity;
        TerminalSession.activeUiListener = uiListener;

        File mainStorage = new File("/storage/emulated/0");
        if (mainStorage.exists()) {
            this.defaultWorkingDir = mainStorage;
        } else {
            this.defaultWorkingDir = activity.getFilesDir();
        }

        android.content.SharedPreferences prefs = activity.getSharedPreferences("primx_terminal_prefs", Context.MODE_PRIVATE);
        this.currentOnlineMode = prefs.getBoolean("is_cloud_mode", false);
    }

    public boolean isCommandInProgress() { return commandInProgress; }
    public void updateThemeColors(boolean lightTheme, int textColor) { this.isLightTheme = lightTheme; this.defaultTextColor = textColor; }
    public boolean isOnlineMode() { return currentOnlineMode; }

    public void initOrResumeSession() {
        try {
            if (sessions.isEmpty()) {
                createNewSession();
            } else {
                ShellState active = getActiveSession();
                if (active != null && activeUiListener != null) {
                    activeUiListener.onSessionBufferUpdated(active.buffer, active.lockedLength, active.title + " #" + active.id);
                    notifySessionListChanged();
                }
            }
        } catch (Exception e) {
            android.util.Log.e("TerminalSession", "Init Error: " + e.getMessage());
        }
    }

    public void setOnlineMode(boolean enableOnline) {
        try { 
            if (enableOnline) {
                boolean netAvailable = BootstrapInstaller.isInternetAvailable(activity);
                boolean isConfigured = BootstrapInstaller.isCloudConfigured(activity);

                if (!netAvailable || !isConfigured) {
                    if (!netAvailable) {
                        if (activeUiListener != null) activeUiListener.onRequestNetworkDialog();
                    } else {
                        if (activeUiListener != null) activeUiListener.onRequestCloudSetupDialog();
                    }
                    return;
                }
            }
            BootstrapInstaller.setForcedOffline(activity, !enableOnline);
            this.currentOnlineMode = enableOnline;

            activity.getSharedPreferences("primx_terminal_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("is_cloud_mode", this.currentOnlineMode).apply();

            ShellState active = getActiveSession();
            if (active != null) {
                active.onlineMode = this.currentOnlineMode;
                active.title = this.currentOnlineMode ? "cloud-sh" : "termux-sh";
                notifySessionListChanged();

                String modeBanner = this.currentOnlineMode ?
                "\n\u001B[38;5;39m→ CLOUD BASE\u001B[0m  ·  GitHub Codespaces & Actions\n\n" :
                "\n\u001B[38;5;208m→ LOCAL TERMUX\u001B[0m  ·  Native C++ Engine (Online/Offline working)\n\n";

                appendLog(active, modeBanner, defaultTextColor);
                printPrompt(active);
            }
        } catch (Exception e) {
            if (getActiveSession() != null) {
                appendLog(getActiveSession(), "\n[Mode Switch Error] " + e.getMessage() + "\n", 0xFFF44336);
            }
        }
    }

    public void createNewSession() {
        try {
            String sessionTitle = this.currentOnlineMode ? "cloud-sh" : "termux-sh";
            ShellState session = new ShellState(sessionCounter++, sessionTitle, defaultWorkingDir, this.currentOnlineMode);
            sessions.add(session);
            activeSessionIndex = sessions.size() - 1;
            notifySessionListChanged();
            startTermuxEngineProcess(session);
        } catch (Exception e) {
            android.util.Log.e("TerminalSession", "Create Session Error: " + e.getMessage());
        }
    }

    public void switchSession(int index) {
        try {
            if (index < 0 || index >= sessions.size()) return;
            activeSessionIndex = index;
            ShellState active = sessions.get(activeSessionIndex);
            this.currentOnlineMode = active.onlineMode;
            notifySessionListChanged();
            if (activeUiListener != null) {
                activeUiListener.onSessionBufferUpdated(active.buffer, active.lockedLength, active.title + " #" + active.id);
            }
        } catch (Exception e) {
            android.util.Log.e("TerminalSession", "Switch Session Error: " + e.getMessage());
        }
    }

    public void clearActiveSessionBuffer() {
        try {
            ShellState active = getActiveSession();
            if (active != null) {
                active.buffer.clear();
                active.lockedLength = 0;
                printPrompt(active);
            }
        } catch (Exception e) {
            android.util.Log.e("TerminalSession", "Clear Buffer Error: " + e.getMessage());
        }
    }

    public void sendControlByteToActive(int controlByte) {
        ShellState active = getActiveSession();
        if (active != null && active.stdin != null) {
            try {
                active.stdin.write(controlByte);
                active.stdin.flush();
                if (controlByte == 3) {
                    appendLog(active, "^C\n", TerminalCommandInterceptor.resolveAttrColor(activity, TerminalCommandInterceptor.ATTR_WARNING, isLightTheme));
                    printPrompt(active);
                }
            } catch (Throwable ignored) {}
        }
    }

    public void sendRawSequence(String seq) {
        ShellState active = getActiveSession();
        if (active != null && active.stdin != null) {
            try { active.stdin.write(seq.getBytes("UTF-8")); active.stdin.flush(); } catch (Throwable ignored) {}
        }
    }

    private static ShellState getActiveSession() { return sessions.isEmpty() ? null : sessions.get(activeSessionIndex); }

    private void notifySessionListChanged() {
        if (activeUiListener == null || activeActivity == null) return;
        try {
            List<SessionInfo> list = new ArrayList<>();
            for (ShellState s : sessions) list.add(new SessionInfo(s.id, s.title));

            activeActivity.runOnUiThread(() -> {
                if (activeUiListener != null) activeUiListener.onSessionListUpdated(list, activeSessionIndex);
            });
        } catch (Exception ignored) {}
    }

    private void startTermuxEngineProcess(final ShellState session) {
        new Thread(() -> {
            try { 
                String termuxUsr = activity.getFilesDir().getAbsolutePath() + "/usr";
                String hardcodedPath = "/storage/emulated/0";

                if (!new File(termuxUsr + "/bin/bash").exists()) {
                    if (BootstrapInstaller.hasLocalBackup()) {
                        appendLog(session, "\n[SYSTEM] Found local backup in Download folder.\n", TerminalCommandInterceptor.resolveAttrColor(activity, TerminalCommandInterceptor.ATTR_INFO, isLightTheme));
                        BootstrapInstaller.installFromLocal(termuxUsr, new Handler(Looper.getMainLooper()), text -> appendLog(session, text, defaultTextColor), () -> startTermuxEngineProcess(session));
                    } else {
                        appendLog(session, "\n[SYSTEM] Core Environment is missing.\n", TerminalCommandInterceptor.resolveAttrColor(activity, TerminalCommandInterceptor.ATTR_WARNING, isLightTheme));
                        appendLog(session, "[SYSTEM] Type 'install-core' and press Enter.\n\n", TerminalCommandInterceptor.resolveAttrColor(activity, TerminalCommandInterceptor.ATTR_INFO, isLightTheme));
                        session.alive = false;
                        printPrompt(session);
                    }
                    return;
                }

                if (!TerminalPty.initNative(activity)) {
                    appendLog(session, "\n[FATAL] Native C++ Libraries failed to load!\n", 0xFFF44336);
                    return;
                }

                String prootPath = termuxUsr + "/bin/proot";
                String bashPath = termuxUsr + "/bin/bash";
                boolean hasProot = new File(prootPath).exists();

                String execPath;
                String[] args;
                String[] env;

                if (hasProot) {
                    // 🔥 THE MASTER JUGAAD: Dynamic Boot Script
                    // Ab aapko baar-baar APK build nahi karna padega.
                    File bootScript = new File(activity.getFilesDir(), "run_proot.sh");
                    
                    if (!bootScript.exists()) {
                        try {
                            FileOutputStream fos = new FileOutputStream(bootScript);
                            // Yeh script file aapke files folder me ban jayegi. Aap ise kabhi bhi IDE me edit kar sakte hain.
                            String scriptContent = "#!/system/bin/sh\n" +
                                "export TERM=xterm-256color\n" +
                                "export HOME=" + hardcodedPath + "\n" +
                                "export PREFIX=" + termuxUsr + "\n" +
                                "export LD_LIBRARY_PATH=" + termuxUsr + "/lib:/data/data/com.termux/files/usr/lib\n" +
                                "export PATH=" + termuxUsr + "/bin:/system/bin:/system/xbin\n" +
                                "export TMPDIR=" + termuxUsr + "/tmp\n" +
                                "export PROOT_NO_SECCOMP=1\n\n" +
                                "# 👇 Yahan aap PRoot ki command live change kar sakte hain bina APK rebuild kiye!\n" +
                                termuxUsr + "/bin/proot -0 -b " + termuxUsr + ":/data/data/com.termux/files/usr -b " + hardcodedPath + ":/data/data/com.termux/files/home -w " + hardcodedPath + " " + termuxUsr + "/bin/bash -c \"source /data/data/com.termux/files/usr/etc/bash.bashrc && bash\"\n";
                            
                            fos.write(scriptContent.getBytes());
                            fos.close();
                            bootScript.setExecutable(true, false);
                        } catch (Exception e) {}
                    }

                    execPath = "/system/bin/sh";
                    args = new String[]{ "/system/bin/sh", bootScript.getAbsolutePath() };
                    env = new String[]{ "PROOT_NO_SECCOMP=1" }; 
                } else {
                    execPath = bashPath;
                    args = new String[]{ "--rcfile", termuxUsr + "/etc/bash.bashrc" };
                    env = new String[]{
                        "TERM=xterm-256color",
                        "HOME=" + hardcodedPath,
                        "PREFIX=" + termuxUsr,
                        "LD_LIBRARY_PATH=" + termuxUsr + "/lib", 
                        "LD_PRELOAD=" + termuxUsr + "/lib/libtermux-exec.so", 
                        "PATH=" + termuxUsr + "/bin:/system/bin:/system/xbin",
                        "TMPDIR=" + termuxUsr + "/tmp",
                        "LANG=en_US.UTF-8"
                    };
                }

                int[] pidArray = new int[1];
                int fd = TerminalPty.createSubprocess(execPath, args, env, pidArray);

                if (fd > 0) {
                    session.processId = pidArray[0];
                    session.ptyFd = fd;
                    TerminalPty.setPtyWindowSize(session.ptyFd, 24, 80);

                    FileDescriptor fdObj = TerminalPty.wrapFileDescriptor(session.ptyFd);
                    session.stdinReader = new FileInputStream(fdObj);
                    session.stdin = new FileOutputStream(fdObj);
                    session.alive = true;
                    session.buffer.clear();

                    session.stdin.write(("cd '" + hardcodedPath + "'\nclear\n").getBytes());
                    session.stdin.flush();

                    appendLog(session, buildWelcomeBanner(session, hardcodedPath, hasProot), defaultTextColor);
                    printPrompt(session);
                    startStreamReader(session);
                } else {
                    appendLog(session, "\n[ERROR] Engine failed to start (FD: " + fd + ")\n", 0xFFF44336);
                }
            } catch (Throwable e) {
                session.alive = false;
                appendLog(session, "\n[CRASH] " + e.getMessage() + "\n", 0xFFF44336);
            }
        }).start();
    }

    private String buildWelcomeBanner(ShellState session, String cwd, boolean usingProot) {
        String modeShort = session.onlineMode ? "cloud-sh" : "termux-sh";
        String engineType = usingProot ? "\u001B[33m(PRoot Engine)\u001B[0m" : "\u001B[32m(Native Engine)\u001B[0m";
        String repo = BootstrapInstaller.getGithubRepo(activity);
        boolean configured = BootstrapInstaller.isCloudConfigured(activity);

        StringBuilder line1 = new StringBuilder();
        line1.append("\u001B[32mPRIMX Hybrid Terminal\u001B[0m  \u001B[90m·\u001B[0m  \u001B[36m").append(modeShort).append("\u001B[0m");
        if (configured && !repo.isEmpty()) line1.append("  \u001B[90m·\u001B[0m  \u001B[34m").append(repo).append("\u001B[0m");
        return line1.toString() + "\n\u001B[90mcwd: " + cwd + " " + engineType + "\u001B[0m\n\n";
    }

    private void startStreamReader(final ShellState session) {
        new Thread(() -> {
            try {
                byte[] buffer = new byte[4096];
                int readCount;
                while (session.alive && session.stdinReader != null && (readCount = session.stdinReader.read(buffer)) != -1) {
                    if(readCount > 0) {
                        String chunk = new String(buffer, 0, readCount, "UTF-8");
                        appendLog(session, chunk, defaultTextColor);
                    }
                }
            } catch (Throwable e) {
                appendLog(session, "\n[STREAM CLOSED]\n", TerminalCommandInterceptor.resolveAttrColor(activity, TerminalCommandInterceptor.ATTR_WARNING, isLightTheme));
            }
        }).start();
    }

    public void printPrompt(ShellState session) {
        if (session == null) return;
        try { 
            String dirName = (session.workingDir != null) ? session.workingDir.getName() : "~";
            if (dirName.isEmpty()) dirName = "/";
            if (dirName.equals("0")) dirName = "MainStorage";

            String prefix = session.onlineMode ? "\u001B[38;5;39mprimx@cloud" : "\u001B[38;5;208mprimx@termux";
            appendLog(session, prefix + "\u001B[0m:\u001B[34m" + dirName + "\u001B[0m$ ", defaultTextColor);
        } catch (Exception e) {
            appendLog(session, "\n[Prompt Error] " + e.getMessage() + "\n", 0xFFF44336);
        }
    }

    public void buildApkOffline(final ShellState session) {
        try {
            isBuilding = true;
            commandInProgress = true;
            if (!(activity instanceof com.primx.ui.mainactivity.MainActivity)) return;
            com.primx.ui.mainactivity.MainActivity mainAct = (com.primx.ui.mainactivity.MainActivity) activity;

            appendLog(session, "\n[System] Starting offline APK build...\n", TerminalCommandInterceptor.resolveAttrColor(activity, TerminalCommandInterceptor.ATTR_INFO, isLightTheme));

            mainAct.projectRunner.runProjectOffline(new Compiler.CompileListener() {
                @Override public void onProgress(String message, boolean isError) {
                    if (isError) appendLog(session, "[Error] " + message + "\n", 0xFFF44336);
                    else appendLog(session, message + "\n", defaultTextColor);
                }
                @Override public void onSuccess(String apkPath) {
                    appendLog(session, "\n[Success] APK Generated: " + apkPath + "\n", 0xFF4CAF50);
                    isBuilding = false; commandInProgress = false; printPrompt(session);
                }
                @Override public void onError(String error) {
                    appendLog(session, "\n[Build Failed] " + error + "\n", 0xFFF44336);
                    isBuilding = false; commandInProgress = false; printPrompt(session);
                }
                @Override public void onLiveSyntaxError(String filePath, int lineNumber, String errorMessage) {}
            });
        } catch (Exception e) {
            appendLog(session, "\n[Build System Error] " + e.getMessage() + "\n", 0xFFF44336);
            isBuilding = false; commandInProgress = false; printPrompt(session);
        }
    }

    public void executeCommandInActiveSession(String rawCommand) {
        try { 
            final ShellState session = getActiveSession();
            if (session == null) return;
            final String command = rawCommand.trim();

            if (command.isEmpty()) { appendLog(session, command + "\n", defaultTextColor); printPrompt(session); return; }
            if ("clear".equals(command) || "cls".equals(command)) { clearActiveSessionBuffer(); return; }

                        if (!session.alive) {
                // 🔥 FIX: Cloud commands aur install-core ko dead shell mein bhi chalne do
                if (command.startsWith("cloud ") || "install-core".equals(command) || "online".equals(command)) {
                    // Yahan kuch return nahi karna, command ko interceptor ke paas jane do
                } else {
                    appendLog(session, command + "\n", defaultTextColor);
                    appendLog(session, "[SYSTEM] Shell not active. Type 'install-core' to setup environment.\n", TerminalCommandInterceptor.resolveAttrColor(activity, TerminalCommandInterceptor.ATTR_WARNING, isLightTheme));
                    printPrompt(session);
                    return; // Baaki normal Linux commands ko block kar do
                }
            }

            commandInProgress = true;
            appendLog(session, command + "\n", defaultTextColor);

            File prefix = workspaceDir != null ? workspaceDir : activity.getFilesDir();

            TerminalCommandInterceptor.executeChain(activity, command, prefix, session.onlineMode,
            new TerminalCommandInterceptor.ShellCallback() {
                @Override public void onLog(String text, String colorAttrName) { appendLog(session, text, TerminalCommandInterceptor.resolveAttrColor(activity, colorAttrName, isLightTheme)); }
                @Override public File getWorkingDir() { return session.workingDir; }
                @Override public void updateWorkingDir(File newDir) { session.workingDir = newDir; }
                @Override public void sendToNativeShell(String nativeCmd) {
                    try { if (session.stdin != null) { session.stdin.write((nativeCmd + "\n").getBytes("UTF-8")); session.stdin.flush(); }
                    } catch (Throwable e) {}
                }
                @Override public void onCommandChainComplete() { if (!isBuilding) { commandInProgress = false; printPrompt(session); } }
                @Override public void onRequestCloudDialog() { if (activeUiListener != null) activeUiListener.onRequestCloudSetupDialog(); }
                @Override public void onRequestNetworkDialog() { if (activeUiListener != null) activeUiListener.onRequestNetworkDialog(); }
                @Override public void onSwitchMode(boolean online) { setOnlineMode(online); }
                @Override public void buildOfflineApk() { buildApkOffline(session); }
            });
        } catch (Exception e) {
            if (getActiveSession() != null) {
                appendLog(getActiveSession(), "\n[Execution Error] " + e.getMessage() + "\n", 0xFFF44336);
                commandInProgress = false;
                printPrompt(getActiveSession());
            }
        }
    }

    private static synchronized void appendLog(ShellState session, String text, int color) {
        try {
            if (session == null) return;

            if (text.contains("\u001B[?1049h") || text.contains("\u001B[?47h")) {
                if (session.savedMainBuffer == null) session.savedMainBuffer = new SpannableStringBuilder(session.buffer);
                session.buffer.clear();
            }
            else if (text.contains("\u001B[?1049l") || text.contains("\u001B[?47l")) {
                if (session.savedMainBuffer != null) {
                    session.buffer.clear();
                    session.buffer.append(session.savedMainBuffer);
                    session.savedMainBuffer = null;
                }
            }

            TerminalAnsiParser.parseAndAppend(session.buffer, text, color);
            session.lockedLength = session.buffer.length();

            if (activeUiListener != null && activeActivity != null && session == getActiveSession()) {
                activeActivity.runOnUiThread(() -> {
                    try {
                        if (activeUiListener != null) {
                            activeUiListener.onSessionBufferUpdated(session.buffer, session.lockedLength, session.title + " #" + session.id);
                        }
                    } catch (Exception ignored) {}
                });
            }
        } catch (Exception e) {
            android.util.Log.e("TerminalSession", "Append Log Error: " + e.getMessage());
        }
    }
}
