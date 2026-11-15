package com.primx.ui.mainactivity;


import android.os.Environment;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.app.Dialog;

import com.primx.compiler.Compiler;
import com.primx.ui.console.Console;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainProjectRunner {

    private MainActivity activity;

    public MainProjectRunner(MainActivity activity) {
        this.activity = activity;
    }

    public void runProject() {
        activity.uiHandler.hideKeyboard();
        if (activity.fileManager != null) {
            activity.fileManager.saveCurrentFile(false);
        }

        if (MainActivity.currentProjectPath == null || MainActivity.currentProjectPath.isEmpty()) {
            activity.showCustomToast("Please open a project first!");
            return;
        }

        File webIndex = new File(MainActivity.currentProjectPath, "index.html");
        if (webIndex.exists()) {
            if (activity.previewHelper != null) {
                activity.previewHelper.showWebPreview(webIndex);
            }
            return;
        }

        File pyMain = new File(MainActivity.currentProjectPath, "main.py");
        if (!pyMain.exists() && activity.currentEditingFile != null && activity.currentEditingFile.getName().endsWith(".py")) {
            pyMain = activity.currentEditingFile;
        }
        if (pyMain.exists()) {
            runPythonScript(pyMain);
            return;
        }

        File pxMain = new File(MainActivity.currentProjectPath, "main.px");
        if (!pxMain.exists() && activity.currentEditingFile != null && activity.currentEditingFile.getName().endsWith(".px")) {
            pxMain = activity.currentEditingFile;
        }

        if (pxMain.exists()) {
            if (activity.consoleView != null) {
                activity.consoleView.clearLogs();
                activity.openConsole();
                activity.consoleView.log("🚀 PrimX Engine: Generating UI Blueprint...");
            }
            File jsonBlueprint = new File(MainActivity.currentProjectPath, "ui_blueprint.json");
            PrimxLiveUI.showLivePreview(activity, jsonBlueprint);
            return;
        }

                if(activity.toolsSetup != null) activity.toolsSetup.ensureToolsExtractedFromAssets();

        if (activity.consoleView != null && !pxMain.exists()) {
            activity.consoleView.clearLogs();
            activity.openConsole();
        }

        int stopIconId = activity.getResources().getIdentifier("ic_stop", "drawable", activity.getPackageName());
        if (stopIconId != 0 && activity.btnRun != null) activity.btnRun.setImageResource(stopIconId);

        String toolsPath = new File(activity.getFilesDir(), "build_tools").getAbsolutePath();
        Compiler compiler = new Compiler(activity, toolsPath);

        compiler.compileProject(MainActivity.currentProjectPath, getDefaultCompileListener());
    }

    // 🔥 NAYA: Terminal se APK build karne ke liye
    public void runProjectOffline(Compiler.CompileListener listener) {
        if (activity.fileManager != null) activity.fileManager.saveCurrentFile(false);
        if (MainActivity.currentProjectPath == null || MainActivity.currentProjectPath.isEmpty()) {
            listener.onError("No project open");
            return;
        }
        boolean isAndroid = "Android".equals(MainFileManager.detectProjectType(new File(MainActivity.currentProjectPath)));
                if(activity.toolsSetup != null) activity.toolsSetup.ensureToolsExtractedFromAssets();
        String toolsPath = new File(activity.getFilesDir(), "build_tools").getAbsolutePath();
        Compiler compiler = new Compiler(activity, toolsPath);
        compiler.compileProject(MainActivity.currentProjectPath, listener);
    }

    public void cleanProject() {
        if (MainActivity.currentProjectPath != null && !MainActivity.currentProjectPath.isEmpty()) {
            if (activity.consoleView != null) {
                activity.consoleView.clearLogs();
                activity.consoleView.log("Cleaning Build Cache...");
                activity.openConsole();
            } else {
                activity.showCustomToast("Cleaning Build Cache...");
            }

            String toolsPath = new File(activity.getFilesDir(), "build_tools").getAbsolutePath();
            new Compiler(activity, toolsPath).cleanProject(MainActivity.currentProjectPath, new Compiler.CompileListener() {
                @Override public void onProgress(String message, boolean isError) {}
                @Override public void onSuccess(String apkPath) {
                    activity.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (activity.consoleView != null) activity.consoleView.log("Cache Cleaned Successfully!");
                            else activity.showCustomToast("Cache Cleaned Successfully!");
                        }
                    });
                }
                @Override public void onError(final String error) {
                    activity.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (activity.consoleView != null) activity.consoleView.log("#ERROR:|System|-1|Clean Failed: " + error);
                            else activity.showCustomToast("Clean Failed: " + error);
                        }
                    });
                }
                @Override public void onLiveSyntaxError(String f, int l, String e) {}
            });
        } else {
            activity.showCustomToast("No project open to clean!");
        }
    }

    public Compiler.CompileListener getDefaultCompileListener() {
        return new Compiler.CompileListener() {
            @Override
            public void onProgress(final String message, final boolean isError) {
                activity.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (message.startsWith("#PROGRESS:")) {
                            String[] parts = message.split(":", 3);
                            if (parts.length >= 3) {
                                if (activity.consoleView != null) activity.consoleView.log(parts[2]);
                            }
                        } else {
                            if (activity.consoleView != null) activity.consoleView.log(message);
                        }
                    }
                });
            }

            @Override
            public void onSuccess(final String filePath) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        int runIconId = activity.getResources().getIdentifier("ic_run", "drawable", activity.getPackageName());
                        if (runIconId != 0 && activity.btnRun != null) activity.btnRun.setImageResource(runIconId);

                        if (activity.consoleView != null) activity.consoleView.clearLogs();
                        if (activity.codeCanvas != null) activity.codeCanvas.clearLiveErrors();

                        if (filePath != null && filePath.endsWith(".dex")) {
                            showTerminalScreen(filePath, (MainActivity.currentPackageName == null || MainActivity.currentPackageName.isEmpty() || MainActivity.currentPackageName.equals("No package")) ? "Main" : MainActivity.currentPackageName + ".Main");
                        } else {
                            Console.installApk(activity, filePath);
                        }
                    }
                });
            }

            @Override
            public void onError(final String error) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        int runIconId = activity.getResources().getIdentifier("ic_run", "drawable", activity.getPackageName());
                        if (runIconId != 0 && activity.btnRun != null) activity.btnRun.setImageResource(runIconId);

                        if (activity.consoleView != null) {
                            String cleanError = error != null ? error : "Unknown Build Error Occurred";
                            activity.consoleView.log("#ERROR:|Build System|-1|" + cleanError);
                        }
                        activity.openConsole();
                    }
                });
            }

            @Override public void onLiveSyntaxError(String filePath, int lineNumber, String errorMessage) { }
        };
    }

    public void runPythonScript(final File scriptFile) {
        File toolsDir = new File(activity.getFilesDir(), "build_tools");
        if (!toolsDir.exists()) toolsDir.mkdirs();

        File foundBinary = null;
        File foundHome = null;

        File[] searchPaths = {
            toolsDir,
            new File(toolsDir, "usr"),
            new File(toolsDir, "python"),
            new File(toolsDir, "python3"),
            new File(Environment.getExternalStorageDirectory(), "python")
        };

        for (File base : searchPaths) {
            if (!base.exists()) continue;
            File binDir = new File(base, "bin");
            File libDir = new File(base, "lib");
            if (binDir.exists() && libDir.exists()) {
                File pyBin = findRealPythonBinary(binDir);
                if (pyBin != null) {
                    foundBinary = pyBin;
                    foundHome = base;
                    break;
                }
            }
        }

        if (foundBinary != null && foundHome != null) {
            foundBinary.setExecutable(true, false);
            executeResolvedPython(foundBinary, scriptFile, foundHome);
            return;
        }

        if(activity.toolsSetup != null) activity.toolsSetup.startPythonDownload(scriptFile);
    }

    private void executeResolvedPython(File execBinary, File scriptFile, File pythonHome) {
        File libDir = new File(pythonHome, "lib");
        File pythonStdLib = libDir;

        if (libDir.exists() && libDir.isDirectory()) {
            File[] libs = libDir.listFiles();
            if (libs != null) {
                for (File f : libs) {
                    if (f.isDirectory() && f.getName().startsWith("python3.")) {
                        pythonStdLib = f;
                        break;
                    }
                }
            }
        }

        File targetScript = scriptFile;
        if (targetScript == null || targetScript.isDirectory()) {
            File baseDir = (targetScript != null && targetScript.isDirectory()) ? targetScript : new File(MainActivity.currentProjectPath);
            File mainPy = new File(baseDir, "main.py");
            if (!mainPy.exists()) {
                try {
                    baseDir.mkdirs();
                    FileOutputStream fos = new FileOutputStream(mainPy);
                    fos.write("#!/usr/bin/env python3\nprint(\"Hello from PrimX Python!\")\n".getBytes());
                    fos.flush();
                    fos.close();
                } catch (Exception ignored) {}
            }
            targetScript = mainPy;
        }

        showPythonTerminal(execBinary, targetScript, pythonHome, pythonStdLib, pythonHome);
    }

    private File findRealPythonBinary(File binDir) {
        if (binDir == null || !binDir.exists()) return null;
        File[] candidates = binDir.listFiles();
        if (candidates == null) return null;

        File elfCandidate = null;
        for (File f : candidates) {
            if (f.isFile() && f.getName().startsWith("python3") && isElf(f)) {
                if (f.getName().matches("python3\\.[0-9]+")) {
                    return f;
                }
                elfCandidate = f;
            }
        }
        if (elfCandidate != null) return elfCandidate;

        File p3 = new File(binDir, "python3");
        if (p3.exists()) return p3;
        for (File f : candidates) {
            if (f.getName().startsWith("python3")) return f;
        }
        return null;
    }

    private boolean isElf(File f) {
        if (f == null || !f.exists() || f.length() < 4) return false;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(f);
            byte[] magic = new byte[4];
            if (fis.read(magic) == 4) {
                return magic[0] == 0x7F && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F';
            }
        } catch (Exception ignored) {}
        finally {
            if (fis != null) { try { fis.close(); } catch (Exception ignored) {} }
        }
        return false;
    }

    private void showPythonTerminal(final File pythonBin, final File scriptFile, final File pythonHome, final File pythonLib, final File toolsDir) {
        final Dialog terminalDialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        int layoutId = activity.getResources().getIdentifier("layout_terminal_screen", "layout", activity.getPackageName());
        if (layoutId == 0) return;
        terminalDialog.setContentView(layoutId);

        ImageView btnClose = terminalDialog.findViewById(activity.getResources().getIdentifier("btnTerminalClose", "id", activity.getPackageName()));
        TextView tvTitle = terminalDialog.findViewById(activity.getResources().getIdentifier("tvTerminalTitle", "id", activity.getPackageName()));
        final ScrollView scroll = terminalDialog.findViewById(activity.getResources().getIdentifier("terminalScroll", "id", activity.getPackageName()));
        final TextView tvOutput = terminalDialog.findViewById(activity.getResources().getIdentifier("tvTerminalOutput", "id", activity.getPackageName()));
        final EditText inputTerminal = terminalDialog.findViewById(activity.getResources().getIdentifier("inputTerminal", "id", activity.getPackageName()));
        ImageView btnEnter = terminalDialog.findViewById(activity.getResources().getIdentifier("btnTerminalEnter", "id", activity.getPackageName()));

        if (tvTitle != null) tvTitle.setText(" Python Console: " + scriptFile.getName());
        if (btnClose != null) btnClose.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { terminalDialog.dismiss(); } });

        final Process[] process = new Process[1];
        final OutputStream[] stdIn = new OutputStream[1];

        terminalDialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface dialog) { if (process[0] != null) process[0].destroy(); }
        });

        if (btnEnter != null && inputTerminal != null && tvOutput != null && scroll != null) {
            btnEnter.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String inText = inputTerminal.getText().toString();
                    inputTerminal.setText("");
                    if (stdIn[0] != null) {
                        try {
                            stdIn[0].write((inText + "\n").getBytes());
                            stdIn[0].flush();
                            tvOutput.append("< " + inText + "\n");
                            scroll.post(new Runnable() { @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); } });
                        } catch (Exception e) {}
                    }
                }
            });
        }

        terminalDialog.show(); com.primx.utils.UIEffectsManager.applyPremiumBlur(terminalDialog);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<String> cmdList = new ArrayList<String>();
                    cmdList.add(pythonBin.getAbsolutePath());
                    cmdList.add("-u");
                    cmdList.add(scriptFile.getAbsolutePath());

                    ProcessBuilder pb = new ProcessBuilder(cmdList);
                    File workDir = scriptFile.getParentFile();
                    if (workDir != null && workDir.exists()) {
                        pb.directory(workDir);
                    }
                    pb.environment().put("PYTHONHOME", pythonHome.getAbsolutePath());
                    pb.environment().put("PYTHONPATH", pythonLib.getAbsolutePath() + ":" + pythonLib.getAbsolutePath() + "/site-packages");
                    String ldPath = toolsDir.getAbsolutePath() + ":" + new File(pythonHome, "lib").getAbsolutePath() + ":" + new File(pythonHome, "bin").getAbsolutePath() + ":/system/lib64:/system/lib";
                    pb.environment().put("LD_LIBRARY_PATH", ldPath);
                    pb.environment().put("PYTHONUNBUFFERED", "1");
                    pb.environment().put("TEMP", activity.getCacheDir().getAbsolutePath());
                    pb.environment().put("TMPDIR", activity.getCacheDir().getAbsolutePath());
                    pb.redirectErrorStream(true);

                    process[0] = pb.start();
                    stdIn[0] = process[0].getOutputStream();

                    BufferedReader reader = new BufferedReader(new InputStreamReader(process[0].getInputStream()));
                    char[] buf = new char[1024];
                    int n;
                    while ((n = reader.read(buf)) != -1) {
                        final String outLine = new String(buf, 0, n);
                        activity.runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (tvOutput != null) tvOutput.append(outLine);
                                if (scroll != null) scroll.post(new Runnable() { @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); } });
                            }
                        });
                    }
                    final int exitCode = process[0].waitFor();
                    activity.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (tvOutput != null) tvOutput.append("\n[Process finished with exit code " + exitCode + "]\n");
                            if (scroll != null) scroll.post(new Runnable() { @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); } });
                        }
                    });
                } catch (final Exception e) {
                    activity.runOnUiThread(new Runnable() {
                        @Override public void run() { if (tvOutput != null) tvOutput.append("\n[Execution Error: " + e.getMessage() + "]\n"); }
                    });
                }
            }
        }).start();
    }

    private void showTerminalScreen(final String dexPath, final String mainClass) {
        final Dialog terminalDialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        int layoutId = activity.getResources().getIdentifier("layout_terminal_screen", "layout", activity.getPackageName());
        if (layoutId == 0) return;
        terminalDialog.setContentView(layoutId);

        ImageView btnClose = terminalDialog.findViewById(activity.getResources().getIdentifier("btnTerminalClose", "id", activity.getPackageName()));
        TextView tvTitle = terminalDialog.findViewById(activity.getResources().getIdentifier("tvTerminalTitle", "id", activity.getPackageName()));
        final ScrollView scroll = terminalDialog.findViewById(activity.getResources().getIdentifier("terminalScroll", "id", activity.getPackageName()));
        final TextView tvOutput = terminalDialog.findViewById(activity.getResources().getIdentifier("tvTerminalOutput", "id", activity.getPackageName()));
        final EditText inputTerminal = terminalDialog.findViewById(activity.getResources().getIdentifier("inputTerminal", "id", activity.getPackageName()));
        ImageView btnEnter = terminalDialog.findViewById(activity.getResources().getIdentifier("btnTerminalEnter", "id", activity.getPackageName()));

        if (tvTitle != null) tvTitle.setText(" Java Console: " + mainClass);
        if (btnClose != null) btnClose.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { terminalDialog.dismiss(); } });

        final Process[] process = new Process[1];
        final OutputStream[] stdIn = new OutputStream[1];

        terminalDialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface dialog) { if (process[0] != null) process[0].destroy(); }
        });

        if (btnEnter != null && inputTerminal != null && tvOutput != null && scroll != null) {
            btnEnter.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String inText = inputTerminal.getText().toString();
                    inputTerminal.setText("");
                    if (stdIn[0] != null) {
                        try {
                            stdIn[0].write((inText + "\n").getBytes());
                            stdIn[0].flush();
                            tvOutput.append("< " + inText + "\n");
                            scroll.post(new Runnable() { @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); } });
                        } catch (Exception e) {}
                    }
                }
            });
        }

        terminalDialog.show(); com.primx.utils.UIEffectsManager.applyPremiumBlur(terminalDialog);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ProcessBuilder pb = new ProcessBuilder("dalvikvm", "-cp", dexPath, mainClass);
                    pb.redirectErrorStream(true);
                    process[0] = pb.start();
                    stdIn[0] = process[0].getOutputStream();

                    BufferedReader reader = new BufferedReader(new InputStreamReader(process[0].getInputStream()));
                    char[] buf = new char[1024];
                    int n;
                    while ((n = reader.read(buf)) != -1) {
                        final String outLine = new String(buf, 0, n);
                        activity.runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (tvOutput != null) tvOutput.append(outLine);
                                if (scroll != null) scroll.post(new Runnable() { @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); } });
                            }
                        });
                    }
                    final int exitCode = process[0].waitFor();
                    activity.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (tvOutput != null) tvOutput.append("\n[Process finished with exit code " + exitCode + "]\n");
                            if (scroll != null) scroll.post(new Runnable() { @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); } });
                        }
                    });
                } catch (final Exception e) {
                    activity.runOnUiThread(new Runnable() {
                        @Override public void run() { if (tvOutput != null) tvOutput.append("\n[Execution Error: " + e.getMessage() + "]\n"); }
                    });
                }
            }
        }).start();
    }
}