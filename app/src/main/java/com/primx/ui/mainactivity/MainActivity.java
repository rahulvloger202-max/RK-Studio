package com.primx.ui.mainactivity;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.primx.services.EngineService;
import com.primx.ui.explorer.Filesview;
import com.primx.ui.console.Console;
import com.primx.ui.editor.EditorView;
import com.primx.utils.Setting;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    public LinearLayout leftPanel, layoutDefaultActions, layoutTypingActions, floatingSelectionBar;
    public LinearLayout layoutTitle, layoutSearchBar, tabContainer, emptyStateLayout;
    public Filesview filesViewPanel;
    public View editorBlocker;
    public EditorView codeCanvas;
    public Console consoleView;

    public EditText inputEditorSearch;
    public ImageView btnOpenMenu, btnRun, btnEdit;
    public TextView tvHeaderTitle, tvProjectName;

    public List<File> openedFiles = new ArrayList<>();
    public File currentEditingFile = null;

    public boolean isExplorerOpen = false, hasLoadedFiles = false;
    public boolean isSelectionModeActive = false, isSearchMode = false;
    public float drawerWidthPx;
    public SharedPreferences prefs;

    public static String currentProjectPath = "", currentProjectName = "", currentPackageName = "";
    public boolean isFileModified = false;
    public boolean openFailed = false;

    public boolean isConsoleOpen = false;
    public float swipeStartX;
    public boolean isSwipingConsole = false;

    public MainUIHandler uiHandler;
    public MainFileManager fileManager;
    public MainProjectRunner projectRunner;
    public MainToolsSetup toolsSetup;
    public MainPreviewHelper previewHelper;

    private volatile boolean isCleaning = false;
    private boolean isAppInitialized = false;

    // Terminal Variables
    public View bottomTerminalSheet = null;
    public View terminalDimOverlay = null;

    // 🔥 Ensures background terminal extraction runs fully
    public static boolean isTerminalExtracted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = getSharedPreferences("EditorPrefs", Context.MODE_PRIVATE);
        setTheme(getAppThemeStyleId());
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setupSystemBars();

        int layoutId = getResources().getIdentifier("activity_main", "layout", getPackageName());
        if (layoutId != 0) setContentView(layoutId);

        drawerWidthPx = 280 * getResources().getDisplayMetrics().density;

        uiHandler = new MainUIHandler(this);
        fileManager = new MainFileManager(this);
        projectRunner = new MainProjectRunner(this);
        toolsSetup = new MainToolsSetup(this);
        previewHelper = new MainPreviewHelper(this);

        uiHandler.setupViews();
        fileManager.setupEmptyState();
        uiHandler.setupKeyboardDetector();
        uiHandler.hideKeyboard();

        checkPermissionsAndStart();

        if (btnRun != null) {
            btnRun.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (isCleaning) {
                        showCustomToast("⏳ Cleaning in progress, wait...");
                        return;
                    }
                    if (consoleView != null) {
                        openConsole();
                        consoleView.startBuildLog();
                    }
                    projectRunner.runProject();
                }
            });

            btnRun.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (isCleaning || com.primx.compiler.Compiler.isCompiling) {
                        showCustomToast("Wait for current process to finish");
                        return true;
                    }
                    if (consoleView != null) {
                        openConsole();
                        consoleView.openBuildLogTab();
                        consoleView.log("[INFO] Starting safe clean...");
                    }
                    showCustomToast("Cleaning cache...");
                    safeCleanProject();
                    return true;
                }
            });
      }
   }
    private void checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE}, 100);
                return;
            }
        }
        startAppOperations();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startAppOperations();
            } else {
                showCustomToast("App requires storage permission to run files and terminal.");
            }
        }
    }

    private void startAppOperations() {
        if (isAppInitialized) return;
        isAppInitialized = true;

        if (fileManager != null) fileManager.restoreLastSession();
        if (filesViewPanel != null) {
            filesViewPanel.initFileManager();
            hasLoadedFiles = true;
        }

        if (toolsSetup != null) toolsSetup.installToolsIfNeeded();
        try { EngineService.startService(this); } catch (Exception ignored) {}

        // 🔥 GUARANTEED BACKGROUND EXTRACTION 🔥
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String termuxUsr = getFilesDir().getAbsolutePath() + "/usr";
                    File bashFile = new File(termuxUsr + "/bin/bash");

                    if (!bashFile.exists() && com.termux.BootstrapInstaller.hasLocalBackup()) {
                        isTerminalExtracted = false; // Extraction chal rahi hai

                        com.termux.BootstrapInstaller.installFromLocal(
                        termuxUsr,
                        new Handler(Looper.getMainLooper()),
                        new com.termux.BootstrapInstaller.InstallListener() {
                            @Override public void onOutput(String text) {} // Silent
                        },
                        new Runnable() {
                            @Override public void run() {
                                isTerminalExtracted = true; // Done
                            }
                        }
                        );
                    } else if (bashFile.exists()) {
                        isTerminalExtracted = true; // Pehle se hai
                    }
                } catch (Exception e) {}
            }
        }).start();

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (consoleView != null && consoleView.liveAnalyzer != null) {
                    consoleView.liveAnalyzer.verifyEngineSetup(MainActivity.this);
                }
            }
        }, 1500);

        if (com.primx.compiler.Compiler.isCompiling) {
            String toolsPath = new File(getFilesDir(), "build_tools").getAbsolutePath();
            com.primx.compiler.Compiler compiler = new com.primx.compiler.Compiler(this, toolsPath);
            openConsole();
            compiler.attachUI(projectRunner.getDefaultCompileListener());
        }
    }


    public void openTerminal() {
        if (bottomTerminalSheet != null) return;

        if (!isTerminalExtracted) {
            showCustomToast("Please wait, configuring engine in background...");
            // Optional: Return kar sakte ho taaki crash na ho agar abhi extract nahi hua hai
            // return;
        }

        ViewGroup root = findViewById(android.R.id.content);
        try {
            new com.termux.Terminal(this, root, currentProjectPath);
            bottomTerminalSheet = new View(this);
            uiHandler.hideKeyboard();
            if (floatingSelectionBar != null) floatingSelectionBar.setVisibility(View.GONE);
        } catch (Throwable t) {
            Toast.makeText(this, "Terminal UI Error: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public void closeTerminal() {
        bottomTerminalSheet = null;
    }

    public boolean isTerminalOpen() {
        return bottomTerminalSheet != null;
    }

        private void safeCleanProject() {
        isCleaning = true;

        String projPath = currentProjectPath;
        if (projPath == null || projPath.isEmpty()) {
            try { projPath = prefs.getString("projectPath", ""); } catch (Exception ignored) {}
        }

        if (projPath == null || projPath.isEmpty()) {
            isCleaning = false;
            logUi("[ERROR] CLEAN FAILED: No active project path set");
            showToastUi("Clean Failed: No active project");
            return;
        }

        final String finalProjPath = projPath;
        final File projDir = new File(finalProjPath);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!projDir.exists()) {
                        logUi("[ERROR] CLEAN FAILED: Project dir not found: " + finalProjPath);
                        showToastUi("Clean Failed: Project not found");
                        isCleaning = false;
                        return;
                    }

                    long totalFreed = 0;
                    int deletedCount = 0;

                    File buildDir = new File(projDir, "build");
                    if (buildDir.exists() && buildDir.isDirectory()) {
                        File[] children = buildDir.listFiles();
                        if (children != null) {
                            for (File child : children) {
                                if (child.getName().equals("primx_libs")) continue;
                                long sz = dirSize(child);
                                if (deleteRecursive(child)) {
                                    totalFreed += sz; deletedCount++;
                                }
                            }
                        }
                    }

                    File appBuild = new File(projDir, "app/build");
                    if (appBuild.exists()) {
                        long sz = dirSize(appBuild);
                        if (deleteRecursive(appBuild)) { totalFreed += sz; deletedCount++; }
                    }

                    String[] legacyDirs = {"bin", "gen", "obj", "dex", "res_compiled"};
                    for (String d : legacyDirs) {
                        File f = new File(projDir, d);
                        if (f.exists()) {
                            long sz = dirSize(f);
                            if (deleteRecursive(f)) { totalFreed += sz; deletedCount++; }
                        }
                    }

                    String[] legacyFiles = {"classes.jar", "classes.dex", "linked_res.ap_", "unsigned.apk", "aligned.apk", "sources.txt"};
                    for (String fname : legacyFiles) {
                        File f = new File(projDir, fname);
                        if (f.exists() && f.isFile()) {
                            long sz = f.length();
                            if (f.delete()) { totalFreed += sz; deletedCount++; }
                        }
                    }

                    final String freedStr = humanSize(totalFreed);
                    final int deletedFinal = deletedCount;

                    if (deletedFinal == 0) {
                        logUi("[SUCCESS] CLEAN FINISHED: Nothing to clean — cache already empty  $");
                        showToastUi("Cleaned: Cache already empty");
                    } else {
                        logUi("[SUCCESS] CLEAN SUCCESSFUL: Removed " + deletedFinal + " items, freed " + freedStr + "  $");
                        showToastUi("Cleaned: " + freedStr + " freed");
                    }
                } catch (Exception e) {
                    logUi("[ERROR] CLEAN FAILED: " + e.getMessage());
                } finally {
                    isCleaning = false;
                }
            }
        }, "PrimX-SafeClean").start();
    }

    private void logUi(final String msg) {
        runOnUiThread(new Runnable() { @Override public void run() { if (consoleView != null) consoleView.log(msg); } });
    }

    private void showToastUi(final String msg) {
        runOnUiThread(new Runnable() { @Override public void run() { showCustomToast(msg); } });
    }

    private static boolean deleteRecursive(File fileOrDir) {
        if (fileOrDir == null || !fileOrDir.exists()) return true;
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        return fileOrDir.delete();
    }

    private static long dirSize(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long total = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) total += dirSize(k);
        return total;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void setupSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            int dialogBgColor = resolveThemeColor("ide_dialog_bg_color");
            int bgColor = resolveThemeColor("ide_bg_color");
            window.setStatusBarColor(dialogBgColor);
            window.setNavigationBarColor(bgColor);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                View decor = window.getDecorView();
                int flags = decor.getSystemUiVisibility();
                if (Setting.isLight(this)) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                decor.setSystemUiVisibility(flags);
            }
        }
    }

    public int dpToPx(float dp) { return (int) (dp * getResources().getDisplayMetrics().density); }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
            swipeStartX = event.getRawX();
            isSwipingConsole = false;
        } else if (event.getAction() == android.view.MotionEvent.ACTION_MOVE) {
            float diffX = event.getRawX() - swipeStartX;
            if (!isConsoleOpen && swipeStartX > screenWidth - 150 && diffX < -40) {
                openConsole(); isSwipingConsole = true; return true;
            }
            if (isConsoleOpen && diffX > 80) {
                closeConsole(); isSwipingConsole = true; return true;
            }
        }
        if (isSwipingConsole) return true;
        return super.dispatchTouchEvent(event);
    }

    public void openConsole() {
        if (consoleView == null || isConsoleOpen) return;
        uiHandler.hideKeyboard();
        consoleView.bringToFront();
        consoleView.setVisibility(View.VISIBLE);
        consoleView.animate().translationX(0).setDuration(250).start();
        isConsoleOpen = true;
    }

    public void closeConsole() {
        if (!isConsoleOpen || consoleView == null) return;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        consoleView.animate().translationX(screenWidth).setDuration(250).withEndAction(new Runnable() {
            @Override public void run() { consoleView.setVisibility(View.GONE); }
        }).start();
        isConsoleOpen = false;
    }

    public void openExplorer() {
        uiHandler.hideKeyboard();
        if (leftPanel != null) {
            if (leftPanel.getLayoutParams().width == ViewGroup.LayoutParams.MATCH_PARENT)
            leftPanel.setTranslationX(-getResources().getDisplayMetrics().widthPixels);
            else leftPanel.setTranslationX(-drawerWidthPx);
            leftPanel.setVisibility(View.VISIBLE);
            leftPanel.animate().translationX(0).setDuration(250).start();
        }
        if (editorBlocker != null) {
            editorBlocker.setVisibility(View.VISIBLE);
            editorBlocker.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { closeExplorer(); } });
            editorBlocker.setAlpha(0f);
            editorBlocker.animate().alpha(1f).setDuration(250).start();
        }
        isExplorerOpen = true;
    }

    public void closeExplorer() {
        if (leftPanel != null) {
            float hideWidth = (leftPanel.getLayoutParams().width == ViewGroup.LayoutParams.MATCH_PARENT)
            ? -getResources().getDisplayMetrics().widthPixels : -drawerWidthPx;
            leftPanel.animate().translationX(hideWidth).setDuration(250).start();
        }
        if (editorBlocker != null) {
            editorBlocker.animate().alpha(0f).setDuration(250).withEndAction(new Runnable() {
                @Override public void run() {
                    if (editorBlocker != null) editorBlocker.setVisibility(View.GONE);
                    if (leftPanel != null) leftPanel.setVisibility(View.GONE);
                }
            }).start();
        }
        isExplorerOpen = false;
    }

    public void showCustomToast(String message) {
        try {
            View layout = getLayoutInflater().inflate(
            getResources().getIdentifier("layout_custom_toast", "layout", getPackageName()), null);
            TextView text = layout.findViewById(
            getResources().getIdentifier("tvToastMessage", "id", getPackageName()));
            text.setText(message);
            Toast toast = new Toast(getApplicationContext());
            toast.setDuration(Toast.LENGTH_SHORT);
            toast.setView(layout);
            toast.show();
        } catch (Exception e) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }

    public int getAppThemeStyleId() {
        if (prefs == null) prefs = getSharedPreferences("EditorPrefs", Context.MODE_PRIVATE);
        String themeName = prefs.getString("appTheme", "Dark");
        int themeId = getResources().getIdentifier(
        themeName.equalsIgnoreCase("Light") ? "Theme.PrimX.Light" : "Theme.PrimX.Dark",
        "style", getPackageName());
        return (themeId != 0) ? themeId : android.R.style.Theme_NoTitleBar;
    }

    public int resolveThemeColor(String attrName) {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        int attrId = getResources().getIdentifier(attrName, "attr", getPackageName());
        if (attrId != 0) {
            getTheme().resolveAttribute(attrId, typedValue, true);
            return typedValue.data;
        }
        return android.graphics.Color.TRANSPARENT;
    }

    @Override
    protected void onDestroy() {
        try { EngineService.stopService(this); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
