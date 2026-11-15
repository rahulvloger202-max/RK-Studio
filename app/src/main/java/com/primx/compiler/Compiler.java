package com.primx.compiler;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Compiler {

    public interface CompileListener {
        void onProgress(String message, boolean isError);
        void onSuccess(String apkPath);
        void onError(String error);
        void onLiveSyntaxError(String filePath, int lineNumber, String errorMessage);
    }

    public Context context;

    private static final ExecutorService cleanExecutor = Executors.newSingleThreadExecutor();
    private static final HashSet<String> consoleCache = new HashSet<>();

    // ---- Build state ----
    public static volatile boolean isCompiling = false;
    public static volatile int buildGeneration = 0;
    public static volatile int runningGen = 0;
    public static volatile int cancelledGen = -1;
    public static volatile Process activeProcess = null;
    public static volatile Thread activeBuildThread = null;
    public static volatile String currentBuildingProject = null;
    public static CompileListener activeListener;

    // ThreadLocal — कौन सी generation इस thread पर चल रही है
    private static final ThreadLocal<Integer> currentGen = new ThreadLocal<>();

    private Handler mainHandler;
    public String toolsDirPath, dynamicXmx;
    public long totalRamMB;

    public CompilerUtils utils;
    public CompilerTasks tasks;

    public Compiler(Context context, String toolsDirPath) {
        this.context = context;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.toolsDirPath = toolsDirPath;
        calculateOptimalMemory(context);
        this.utils = new CompilerUtils(this);
        this.tasks = new CompilerTasks(this, this.utils);
    }

    public void attachUI(CompileListener listener) {
        activeListener = listener;
        if (isCompiling) {
            listener.onProgress("#PROGRESS:10:Resuming background build...", false);
        }
    }

    private void calculateOptimalMemory(Context context) {
        ActivityManager actManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        actManager.getMemoryInfo(memInfo);
        this.totalRamMB = memInfo.totalMem / (1024 * 1024);
        if (totalRamMB >= 8000) dynamicXmx = "-Xmx2048m";
        else if (totalRamMB >= 6000) dynamicXmx = "-Xmx1536m";
        else if (totalRamMB >= 4000) dynamicXmx = "-Xmx1024m";
        else if (totalRamMB >= 2500) dynamicXmx = "-Xmx768m";
        else dynamicXmx = "-Xmx512m";
    }

    /** क्या वर्तमान thread का build cancel हो चुका है? */
    public static boolean isCancelled() {
        Integer gen = currentGen.get();
        return gen != null && cancelledGen == gen;
    }

    public static void checkCancellation() throws Exception {
        if (isCancelled()) throw new Exception("BUILD_CANCELLED");
    }

    /** Build cancel — streams बंद, process kill, thread interrupt, state reset */
    public static synchronized void cancelCurrentBuild() {
        cancelledGen = buildGeneration;
        buildGeneration++;

        Process p = activeProcess;
        activeProcess = null;
        if (p != null) {
            try { p.getInputStream().close(); } catch (Throwable ignored) {}
            try { p.getErrorStream().close(); } catch (Throwable ignored) {}
            try { p.getOutputStream().close(); } catch (Throwable ignored) {}
            try { p.destroy(); } catch (Throwable ignored) {}
            if (Build.VERSION.SDK_INT >= 26) {
                try { p.destroyForcibly(); } catch (Throwable ignored) {}
            }
        }

        Thread t = activeBuildThread;
        if (t != null && t.isAlive()) {
            try { t.interrupt(); } catch (Throwable ignored) {}
        }

        runningGen = 0;
        isCompiling = false;
        currentBuildingProject = null;
    }

    public void compileProject(final String projectPath, final CompileListener originalListener) {
        if (isCompiling) {
            cancelCurrentBuild();
        }

        final int myGen = ++buildGeneration;
        consoleCache.clear();
        currentBuildingProject = projectPath;
        isCompiling = true;
        runningGen = myGen;

        final CompileListener listener = new CompileListener() {
            private boolean valid() { return buildGeneration == myGen && cancelledGen != myGen; }
            @Override public void onProgress(String m, boolean e) { if (valid()) originalListener.onProgress(m, e); }
            @Override public void onSuccess(String p) { if (valid()) originalListener.onSuccess(p); }
            @Override public void onError(String err) { if (valid()) originalListener.onError(err); }
            @Override public void onLiveSyntaxError(String fp, int ln, String m) { if (valid()) originalListener.onLiveSyntaxError(fp, ln, m); }
        };
        activeListener = listener;

        if (projectPath == null || projectPath.isEmpty()) {
            listener.onError("Project path is empty!");
            currentBuildingProject = null;
            isCompiling = false;
            runningGen = 0;
            return;
        }
        final File projectDir = new File(projectPath);
        if (!projectDir.exists()) {
            listener.onError("Project folder not found!");
            currentBuildingProject = null;
            isCompiling = false;
            runningGen = 0;
            return;
        }

        Thread buildThread = new Thread(new Runnable() {
            @Override
            public void run() {
                currentGen.set(myGen);
                try {
                    if (cancelledGen == myGen) return;
                    postProgress(listener, "#PROGRESS:5:Scanning Source Code...", false);
                    checkCancellation();

                    boolean isConsoleApp = !utils.getSmartDir(projectDir, "manifest").exists();

                    if (isConsoleApp) {
                        postProgress(listener, "#PROGRESS:40:Checking Java Changes...", false);
                        checkCancellation();
                        if (!tasks.runJavaCompiler(projectDir, null, listener)) throw new Exception("Java Compilation Failed!");
                        checkCancellation();

                        postProgress(listener, "#PROGRESS:80:Checking DEX...", false);
                        checkCancellation();
                        if (!tasks.runDexer(projectDir, null, listener)) throw new Exception("DEX Conversion Failed!");
                        checkCancellation();

                        File dexFile = new File(projectDir, "build/dex/classes.dex");
                        if (!dexFile.exists()) throw new Exception("classes.dex not generated!");

                        postProgress(listener, "#PROGRESS:100:Done!", false);
                        postProgress(listener, "#SUCCESS_NO_ERRORS", false);
                        postSuccess(listener, dexFile.getAbsolutePath());
                    } else {
                        String pkgName = utils.extractPackageName(utils.getSmartDir(projectDir, "manifest"));
                        if (pkgName == null) pkgName = "com.primx.app";

                        List<File> libraryDirs = tasks.resolveGradleDependencies(projectDir, listener);
                        checkCancellation();

                        postProgress(listener, "#PROGRESS:30:Checking Resources...", false);
                        checkCancellation();
                        if (!tasks.runAapt(projectDir, libraryDirs, listener)) throw new Exception("AAPT2 Compilation Failed!");
                        checkCancellation();

                        postProgress(listener, "#PROGRESS:55:Checking Java Changes...", false);
                        if (!tasks.runJavaCompiler(projectDir, libraryDirs, listener)) throw new Exception("Java Compilation Failed!");
                        checkCancellation();

                        postProgress(listener, "#PROGRESS:75:Checking DEX...", false);
                        if (!tasks.runDexer(projectDir, libraryDirs, listener)) throw new Exception("DEX Conversion Failed!");
                        checkCancellation();

                        postProgress(listener, "#PROGRESS:90:Packaging APK...", false);
                        String apkPath = tasks.buildAndSignApk(projectDir, listener);
                        if (apkPath == null) throw new Exception("APK Packaging or Signing Failed!");

                        postProgress(listener, "#PROGRESS:100:Build Complete!", false);
                        postProgress(listener, "#SUCCESS_NO_ERRORS", false);
                        postSuccess(listener, apkPath);
                    }
                } catch (final Exception e) {
                    String msg = e.getMessage();
                    boolean cancelled = "BUILD_CANCELLED".equals(msg)
                            || cancelledGen == myGen
                            || Thread.currentThread().isInterrupted();
                    if (!cancelled) {
                        postProgress(listener, "#PROGRESS:-1:Error", true);
                        postError(listener, msg);
                    }
                } finally {
                    currentGen.remove();
                    Thread.interrupted(); // clear interrupt flag
                    if (runningGen == myGen) {
                        runningGen = 0;
                        isCompiling = false;
                        currentBuildingProject = null;
                    }
                }
            }
        }, "PrimX-Build-" + myGen);
        activeBuildThread = buildThread;
        buildThread.start();
    }

    public void cleanProject(String projectPath, final CompileListener listener) {
        if (projectPath == null || projectPath.isEmpty()) return;
        final File buildDir = new File(projectPath, "build");
        cleanExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    consoleCache.clear();
                    postProgress(listener, "#PROGRESS:50:Cleaning Memory...", false);
                    if (buildDir.exists()) utils.deleteRecursively(buildDir);
                    postProgress(listener, "#PROGRESS:100:Done!", false);
                } finally {}
            }
        });
    }

    public void postProgress(final CompileListener listener, final String msg, final boolean isError) {
        if (msg == null || msg.trim().isEmpty()) return;
        if (isCancelled()) return;
        synchronized (consoleCache) {
            if (consoleCache.contains(msg)) return;
            consoleCache.add(msg);
        }
        if (listener != null) mainHandler.post(new Runnable() {
            @Override public void run() { listener.onProgress(msg, isError); }
        });
    }

    public void postLiveSyntaxError(final CompileListener listener, final String filePath, final int lineNumber, final String errorMessage) {
        if (isCancelled()) return;
        if (listener != null) mainHandler.post(new Runnable() {
            @Override public void run() { listener.onLiveSyntaxError(filePath, lineNumber, errorMessage); }
        });
    }

    public void postError(final CompileListener listener, final String error) {
        if (listener != null) mainHandler.post(new Runnable() {
            @Override public void run() { listener.onError(error); }
        });
    }

    public void postSuccess(final CompileListener listener, final String apkPath) {
        if (listener != null) mainHandler.post(new Runnable() {
            @Override public void run() { listener.onSuccess(apkPath); }
        });
    }
}