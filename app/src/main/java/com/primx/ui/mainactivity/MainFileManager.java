package com.primx.ui.mainactivity;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.Canvas;

import com.primx.ui.editor.EditorView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

public class MainFileManager {

    private MainActivity activity;

    public MainFileManager(MainActivity activity) {
        this.activity = activity;
    }

    public void setupEmptyState() {
        activity.emptyStateLayout = new LinearLayout(activity);
        activity.emptyStateLayout.setOrientation(LinearLayout.VERTICAL);
        activity.emptyStateLayout.setGravity(Gravity.CENTER);

        View placeholderGraphic = new View(activity) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void onDraw(Canvas canvas) {
                int iconColor = activity.resolveThemeColor("ide_icon_tint");
                p.setColor(iconColor);
                p.setAlpha(128);

                float w = getWidth(), h = getHeight();
                float startX = (w / 2f) - activity.dpToPx(80), startY = (h / 2f) - activity.dpToPx(40), r = activity.dpToPx(18);
                canvas.drawCircle(startX + r, startY + r, r, p);
                float lineX = startX + (r * 2) + activity.dpToPx(16);
                canvas.drawRect(lineX, startY + activity.dpToPx(6), lineX + activity.dpToPx(80), startY + activity.dpToPx(14), p);
                canvas.drawRect(lineX, startY + activity.dpToPx(20), lineX + activity.dpToPx(130), startY + activity.dpToPx(28), p);
                canvas.drawRect(lineX, startY + activity.dpToPx(34), lineX + activity.dpToPx(45), startY + activity.dpToPx(42), p);
            }
        };

        LinearLayout.LayoutParams graphicParams = new LinearLayout.LayoutParams((int)activity.dpToPx(200), (int)activity.dpToPx(150));
        activity.emptyStateLayout.addView(placeholderGraphic, graphicParams);

        TextView tvNoFiles = new TextView(activity);
        tvNoFiles.setText("No open files");
        tvNoFiles.setTextColor(activity.resolveThemeColor("ide_text_secondary"));
        tvNoFiles.setTextSize(16f);

        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        textParams.setMargins(0, (int)activity.dpToPx(10), 0, 0);
        activity.emptyStateLayout.addView(tvNoFiles, textParams);

        if (activity.codeCanvas != null && activity.codeCanvas.getParent() != null) {
            ViewGroup parentContainer = (ViewGroup) activity.codeCanvas.getParent();
            parentContainer.addView(activity.emptyStateLayout, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        if (activity.codeCanvas != null) activity.codeCanvas.setVisibility(View.GONE);
        activity.emptyStateLayout.setVisibility(View.VISIBLE);
        if (activity.tvProjectName != null) activity.tvProjectName.setText("");
    }

    public void updateHeaderLabels() {
        if (activity.tvHeaderTitle != null) {
            activity.tvHeaderTitle.setText(MainActivity.currentProjectName.isEmpty() ? "No project" : MainActivity.currentProjectName);
        }
        if (activity.tvProjectName != null) {
            if (MainActivity.currentPackageName == null || MainActivity.currentPackageName.isEmpty() || MainActivity.currentPackageName.equals("No package")) {
                activity.tvProjectName.setVisibility(View.GONE);
                activity.tvProjectName.setText("");
            } else {
                activity.tvProjectName.setVisibility(View.VISIBLE);
                activity.tvProjectName.setText(MainActivity.currentPackageName);
            }
        }
    }

    public void loadProjectDirectly(File projectFolder) {
        activity.openedFiles.clear();
        saveOpenedTabs(); // 🔥 Project load hone par purani tabs clear hoti hain
        if (activity.tabContainer != null) activity.tabContainer.removeAllViews();
        if (activity.codeCanvas != null) { activity.codeCanvas.setText(""); activity.codeCanvas.setFileInfo("", ""); }
        activity.currentEditingFile = null;

        MainActivity.currentProjectPath = projectFolder.getAbsolutePath();
        MainActivity.currentProjectName = projectFolder.getName();
        String projType = detectProjectType(projectFolder);

        if ("Android".equals(projType)) {
            MainActivity.currentPackageName = getPackageNameFromProject(projectFolder);
        } else {
            MainActivity.currentPackageName = "";
        }

        updateHeaderLabels();

        SharedPreferences.Editor editor = activity.prefs.edit();
        editor.putBoolean("isProject", true);
        editor.putString("projectName", MainActivity.currentProjectName);
        editor.putString("projectPackage", MainActivity.currentPackageName);
        editor.putString("projectPath", MainActivity.currentProjectPath);
        editor.apply();

        activity.emptyStateLayout.setVisibility(View.VISIBLE);
        if (activity.codeCanvas != null) activity.codeCanvas.setVisibility(View.GONE);
        if(activity.uiHandler != null) activity.uiHandler.updateEditButtonState();

        if (activity.consoleView != null && MainActivity.currentProjectPath != null && !MainActivity.currentProjectPath.isEmpty()) {
            activity.consoleView.clearLogs();
            if ("Android".equals(projType)) {
                activity.showCustomToast("Scanning entire project for errors...");
                String toolsPath = new File(activity.getFilesDir(), "build_tools").getAbsolutePath();
                File targetXml = new File(MainActivity.currentProjectPath, "app/src/main/AndroidManifest.xml");
                if (activity.consoleView.liveAnalyzer != null) {
                    activity.consoleView.liveAnalyzer.startLiveProjectAnalysis(new File(MainActivity.currentProjectPath), toolsPath, "-Xmx256m", targetXml);
                }
            }
        }
    }

    // 🔥 FIX: Background me tabs ki list save karne ka logic
    private void saveOpenedTabs() {
        StringBuilder sb = new StringBuilder();
        for (File f : activity.openedFiles) {
            sb.append(f.getAbsolutePath()).append(";");
        }
        activity.prefs.edit().putString("savedTabs", sb.toString()).apply();
    }

    public void openFileInEditor(final File file) {
        if (file == null || !file.exists()) return;
        boolean isInsideCurrentProject = (!MainActivity.currentProjectPath.isEmpty() &&
			(file.getAbsolutePath().startsWith(MainActivity.currentProjectPath + File.separator) || file.getAbsolutePath().equals(MainActivity.currentProjectPath)));
        if (!isInsideCurrentProject) {
            File projRoot = findProjectRoot(file);
            if (projRoot != null && !projRoot.getAbsolutePath().equals(MainActivity.currentProjectPath)) {
                loadProjectDirectly(projRoot);
            } else if (MainActivity.currentProjectPath.isEmpty() && projRoot == null) {
                MainActivity.currentProjectPath = ""; MainActivity.currentProjectName = "No project"; MainActivity.currentPackageName = "";
                activity.openedFiles.clear();
                if (activity.tabContainer != null) activity.tabContainer.removeAllViews();
                SharedPreferences.Editor editor = activity.prefs.edit();
                editor.putBoolean("isProject", false);
                editor.putString("projectName", MainActivity.currentProjectName);
                editor.putString("projectPackage", MainActivity.currentPackageName);
                editor.putString("projectPath", MainActivity.currentProjectPath);
                editor.apply();
            }
        }

        if (activity.currentEditingFile != null && activity.isFileModified && !activity.openFailed && activity.codeCanvas != null && activity.currentEditingFile.exists()) {
            saveCurrentFile(false);
        }

        activity.currentEditingFile = file;
        activity.emptyStateLayout.setVisibility(View.GONE);

        if (activity.codeCanvas != null) {
            activity.codeCanvas.setVisibility(View.VISIBLE);
            activity.codeCanvas.setFileInfo(file.getName(), file.getAbsolutePath());
        }

        updateHeaderLabels();

        StringBuilder text = new StringBuilder();
        boolean readOk = false;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(file));
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) != -1) text.append(buf, 0, n);
            readOk = true;
        } catch (Exception e) {
            readOk = false;
        } finally {
            if (br != null) { try { br.close(); } catch (Exception ignored) {} }
        }
        activity.openFailed = !readOk;

        if (activity.codeCanvas != null) {
            if (readOk) {
                activity.codeCanvas.setText(text.toString().replace("\r\n", "\n").replace('\r', '\n'));
            } else {
                activity.codeCanvas.setText("");
                activity.showCustomToast("File padh nahi paaye: " + file.getName());
            }
        }

        if (activity.consoleView != null && activity.codeCanvas != null && file != null) {
            java.util.List<com.primx.ui.console.Console.ErrorItem> errs = activity.consoleView.getErrorsForFile(file.getAbsolutePath());
            activity.codeCanvas.clearLiveErrors();
            if (errs != null) {
                for (com.primx.ui.console.Console.ErrorItem err : errs) {
                    activity.codeCanvas.setDiagnostic(err.line, err.colStart, err.colLen, err.message, err.isWarning);
                }
            }
        }

        if (activity.openedFiles.contains(file)) {
            activity.openedFiles.remove(file);
        }
        activity.openedFiles.add(file);

        // 🔥 FIX: 10 Tabs ki limit! Agar 10 se zyada hongi toh sabse purani band ho jayegi
        while (activity.openedFiles.size() > 10) {
            activity.openedFiles.remove(0);
        }

        refreshTabs();
        saveOpenedTabs(); // 🔥 Tabs memory mein save!

        SharedPreferences.Editor editor = activity.prefs.edit();
        editor.putString("currentFile", file.getAbsolutePath());
        editor.apply();
        if(activity.uiHandler != null) activity.uiHandler.updateSaveButtonState(false);

        if (activity.codeCanvas != null) activity.codeCanvas.requestLiveCheck();
        if(activity.uiHandler != null) activity.uiHandler.updateEditButtonState();
    }

    public void saveCurrentFile(boolean showToast) {
        if (activity.currentEditingFile == null || activity.codeCanvas == null || activity.openFailed) return;
        try {
            FileWriter writer = new FileWriter(activity.currentEditingFile);
            writer.append(activity.codeCanvas.getText()); writer.flush(); writer.close();
            if(activity.uiHandler != null) activity.uiHandler.updateSaveButtonState(false);
            if(showToast) activity.showCustomToast("File Saved Successfully!");
        } catch (Exception e) {}
    }

    public void closeTab(File file) {
        if (file == null) return;
        if (file.equals(activity.currentEditingFile) && activity.isFileModified && !activity.openFailed && activity.codeCanvas != null && file.exists()) {
            saveCurrentFile(false);
        }
        activity.openedFiles.remove(file);
        if (file.equals(activity.currentEditingFile)) {
            if (!activity.openedFiles.isEmpty()) {
                openFileInEditor(activity.openedFiles.get(activity.openedFiles.size() - 1));
            } else {
                activity.currentEditingFile = null;
                if (activity.codeCanvas != null) {
                    activity.codeCanvas.setText("");
                    activity.codeCanvas.setFileInfo("", "");
                    activity.codeCanvas.setVisibility(View.GONE);
                }
                activity.emptyStateLayout.setVisibility(View.VISIBLE);
                if(activity.uiHandler != null) activity.uiHandler.updateEditButtonState();
                
                // Sab kuch band hone par currentFile clear kar do
                activity.prefs.edit().putString("currentFile", "").apply();
            }
        }
        refreshTabs();
        saveOpenedTabs(); // 🔥 Tab band hone ke baad bachi hui tabs save kar do
    }

    public void refreshTabs() {
        if (activity.tabContainer == null) return;
        activity.tabContainer.removeAllViews();
        for (final File file : activity.openedFiles) {
            TextView tabText = new TextView(activity);
            tabText.setText(file.getName());

            int paddingH = (int) activity.dpToPx(18); int paddingV = (int) activity.dpToPx(10);
            tabText.setPadding(paddingH, paddingV, paddingH, paddingV);
            tabText.setTextSize(13f); tabText.setGravity(Gravity.CENTER);
            tabText.setCompoundDrawablePadding((int) activity.dpToPx(8));

            GradientDrawable bgShape = new GradientDrawable();
            bgShape.setShape(GradientDrawable.RECTANGLE);
            bgShape.setCornerRadius(100f);

            int fileColor = EditorView.colorForFile(file.getName());
            android.graphics.drawable.Drawable icon = EditorView.loadFileIconDrawable(activity, file.getName());

            if (file.equals(activity.currentEditingFile)) {
                int tintColor = Color.argb(25, Color.red(fileColor), Color.green(fileColor), Color.blue(fileColor));
                bgShape.setColor(tintColor); bgShape.setStroke(4, fileColor);
                tabText.setTextColor(fileColor); tabText.setTypeface(null, Typeface.BOLD);
            } else {
                bgShape.setColor(Color.TRANSPARENT); bgShape.setStroke(2, fileColor);
                tabText.setTextColor(fileColor); tabText.setTypeface(null, Typeface.NORMAL);
            }

            if (icon != null) {
                int iconSize = (int) activity.dpToPx(20);
                icon.setBounds(0, 0, iconSize, iconSize);
                tabText.setCompoundDrawables(icon, null, null, null);
            }

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(15, 10, 15, 10);
            tabText.setLayoutParams(params); tabText.setBackground(bgShape); tabText.setTag(file);

            tabText.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openFileInEditor((File) v.getTag()); } });
            tabText.setOnLongClickListener(new View.OnLongClickListener() {
					@Override
					public boolean onLongClick(View v) {
						closeTab((File) v.getTag());
						return true;
					}
				});
            activity.tabContainer.addView(tabText);
        }
        
        // Horizontal Scroll ko end (active tab) tak khiskana
        if(activity.tabContainer.getParent() instanceof android.widget.HorizontalScrollView) {
            final android.widget.HorizontalScrollView hsv = (android.widget.HorizontalScrollView) activity.tabContainer.getParent();
            hsv.post(new Runnable() {
                @Override
                public void run() {
                    hsv.fullScroll(View.FOCUS_RIGHT);
                }
            });
        }
    }

    public void restoreLastSession() {
        boolean isProject = activity.prefs.getBoolean("isProject", false);
        MainActivity.currentProjectName = activity.prefs.getString("projectName", "");
        MainActivity.currentPackageName = activity.prefs.getString("projectPackage", "");
        MainActivity.currentProjectPath = activity.prefs.getString("projectPath", "");
        String lastOpenedFilePath = activity.prefs.getString("currentFile", "");
        String savedTabs = activity.prefs.getString("savedTabs", "");

        if (!MainActivity.currentProjectPath.isEmpty() && isProject) {
            File projDir = new File(MainActivity.currentProjectPath);
            if(projDir.exists()) {
                updateHeaderLabels();
            }
        } else {
            updateHeaderLabels();
        }

        // 🔥 FIX: App open hote hi saari purani tabs memory se wapas nikalna
        activity.openedFiles.clear();
        if (!savedTabs.isEmpty()) {
            String[] paths = savedTabs.split(";");
            for (String p : paths) {
                if (!p.trim().isEmpty()) {
                    File f = new File(p);
                    if (f.exists() && !activity.openedFiles.contains(f)) {
                        activity.openedFiles.add(f);
                    }
                }
            }
        }

        if (!lastOpenedFilePath.isEmpty()) {
            File lastFile = new File(lastOpenedFilePath);
            if(lastFile.exists()) {
                openFileInEditor(lastFile);
            } else if (!activity.openedFiles.isEmpty()) {
                // Agar aakhiri file delete ho gayi ho, par baaki tabs bachi hon
                openFileInEditor(activity.openedFiles.get(activity.openedFiles.size() - 1));
            }
        } else if (!activity.openedFiles.isEmpty()) {
            openFileInEditor(activity.openedFiles.get(activity.openedFiles.size() - 1));
        } else {
            refreshTabs();
        }
    }

    public void handleFileDeleted(File deletedFile) {
        String deletedPath = deletedFile.getAbsolutePath();
        List<File> toRemove = new ArrayList<>();
        for (File f : activity.openedFiles) {
            if (f.getAbsolutePath().startsWith(deletedPath)) {
                toRemove.add(f);
            }
        }
        if (!toRemove.isEmpty()) {
            activity.openedFiles.removeAll(toRemove);
            refreshTabs();
            saveOpenedTabs(); // 🔥 Delete hone par tabs memory update
        }

        if (activity.currentEditingFile != null && activity.currentEditingFile.getAbsolutePath().startsWith(deletedPath)) {
            activity.currentEditingFile = null;
            if (activity.codeCanvas != null) {
                activity.codeCanvas.setText("");
                activity.codeCanvas.setFileInfo("", "");
                activity.codeCanvas.setVisibility(View.GONE);
            }
            if (activity.emptyStateLayout != null) { activity.emptyStateLayout.setVisibility(View.VISIBLE); }
            if (activity.tvProjectName != null) activity.tvProjectName.setText("");
            if(activity.uiHandler != null) activity.uiHandler.updateEditButtonState();
            
            activity.prefs.edit().putString("currentFile", "").apply();

            if (!MainActivity.currentProjectPath.isEmpty() && MainActivity.currentProjectPath.startsWith(deletedPath)) {
                MainActivity.currentProjectPath = ""; MainActivity.currentProjectName = ""; MainActivity.currentPackageName = "";
                SharedPreferences.Editor editor = activity.prefs.edit();
                editor.putBoolean("isProject", false); editor.putString("projectName", "");
                editor.putString("projectPackage", ""); editor.putString("projectPath", ""); editor.apply();
                if (activity.tvHeaderTitle != null) activity.tvHeaderTitle.setText("No project");
            } else {
                if (activity.tvHeaderTitle != null) activity.tvHeaderTitle.setText(MainActivity.currentProjectName.isEmpty() ? "No project" : MainActivity.currentProjectName);
            }
        }
    }

    public static String detectProjectType(File dir) {
        if (dir == null || !dir.isDirectory()) return null;

        String name = dir.getName();
        if (name.equals("app") || name.equals("src") || name.equals("main") ||
			name.equals("java") || name.equals("res") || name.equals("layout") ||
			name.equals("values") || name.equals("mipmap") || name.equals("build") ||
			name.equals("bin") || name.equals("gen") || name.equals(".git") ||
			name.equals(".idea") || name.equals("__pycache__")) {
            return null;
        }

        File parent = dir.getParentFile();
        while (parent != null) {
            if (new File(parent, "build.gradle").exists() ||
				new File(parent, "AndroidManifest.xml").exists() ||
				new File(parent, "app").exists() ||
				new File(parent, "src").exists()) {
                return null;
            }
            String pPath = parent.getAbsolutePath();
            if (pPath.equals("/storage/emulated/0") || pPath.equals("/sdcard") || pPath.equals("/")) break;
            parent = parent.getParentFile();
        }

        boolean hasGradle = new File(dir, "build.gradle").exists() || new File(dir, "settings.gradle").exists() || new File(dir, "build.gradle.kts").exists();
        boolean hasAppFolder = new File(dir, "app").exists() && (new File(dir, "app/build.gradle").exists() || new File(dir, "app/src").exists());
        boolean isLegacyAIDE = new File(dir, "AndroidManifest.xml").exists() && new File(dir, "src").exists();
        if (hasGradle || hasAppFolder || isLegacyAIDE) return "Android";

        if (new File(dir, "main.py").exists() || new File(dir, "requirements.txt").exists()) return "Python";
        if (new File(dir, "index.html").exists() || new File(dir, "index.htm").exists()) return "Web";
        if (new File(dir, "Main.java").exists() || new File(dir, "src/Main.java").exists()) return "Java";

        return null;
    }

    public static boolean isProjectFolder(File dir) {
        return detectProjectType(dir) != null;
    }

    public static File findProjectRoot(File file) {
        if (file == null) return null;
        if (MainActivity.currentProjectPath != null && !MainActivity.currentProjectPath.isEmpty()) {
            if (file.getAbsolutePath().startsWith(MainActivity.currentProjectPath + File.separator) || file.getAbsolutePath().equals(MainActivity.currentProjectPath)) {
                return new File(MainActivity.currentProjectPath);
            }
        }
        File cur = file.isDirectory() ? file : file.getParentFile();
        File rootCandidate = null;
        while (cur != null) {
            if (isProjectFolder(cur)) rootCandidate = cur;
            File parent = cur.getParentFile();
            if (parent == null || cur.equals(android.os.Environment.getExternalStorageDirectory())) break;
            cur = parent;
        }
        return rootCandidate;
    }

    private String getPackageNameFromProject(File projectDir) {
        try {
            File buildGradle = new File(projectDir, "app/build.gradle");
            if (buildGradle.exists()) {
                BufferedReader br = new BufferedReader(new FileReader(buildGradle));
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains("applicationId") || line.contains("namespace")) {
                        String[] parts = line.split("['\"]");
                        if (parts.length > 1) { br.close(); return parts[1]; }
                    }
                }
                br.close();
            }
        } catch (Exception e) {}
        return "";
    }
}
