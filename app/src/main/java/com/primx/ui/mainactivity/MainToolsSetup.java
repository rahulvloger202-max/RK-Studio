package com.primx.ui.mainactivity;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Environment;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainToolsSetup {

    private MainActivity activity;

    public MainToolsSetup(MainActivity activity) {
        this.activity = activity;
    }

    // 🔥 नया प्रीमियम लोडिंग डायलॉग
    private Dialog showLoadingDialog() {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false); // यूज़र इसे खुद बंद नहीं कर पाएगा
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        float density = activity.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = (int)(24 * density);
        root.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(activity.resolveThemeColor("ide_dialog_bg_color") == 0 ? Color.parseColor("#2D2D2D") : activity.resolveThemeColor("ide_dialog_bg_color"));
        bg.setCornerRadius(16 * density);
        root.setBackground(bg);
        root.setLayoutParams(new ViewGroup.LayoutParams((int)(300 * density), ViewGroup.LayoutParams.WRAP_CONTENT));

        ProgressBar pb = new ProgressBar(activity);
        root.addView(pb);

        TextView tv = new TextView(activity);
        tv.setText("Preparing Ecosystem...\nExtracting Build Tools");
        tv.setTextColor(activity.resolveThemeColor("ide_text_primary") == 0 ? Color.WHITE : activity.resolveThemeColor("ide_text_primary"));
        tv.setTextSize(15f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, (int)(16 * density), 0, 0);
        root.addView(tv);

        dialog.setContentView(root);
        dialog.show();
        return dialog;
    }

    public void installToolsIfNeeded() {
        // अगर पहले से टूल्स मौजूद हैं, तो पॉपअप दिखाने की ज़रूरत नहीं है
        if (areBuildToolsInstalled() && new File(activity.getFilesDir(), "usr/bin/bash").exists()) {
            return;
        }

        final Dialog loadingDialog = showLoadingDialog();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 1. Android Build Tools (aapt2, dx, android.jar) एक्सट्रैक्ट करो
                    ensureToolsExtractedFromAssets();

                    // 2. Termux Core (Offline Backup) एक्सट्रैक्ट करो
                    String termuxUsr = activity.getFilesDir().getAbsolutePath() + "/usr";
                    if (!new File(termuxUsr, "bin/bash").exists()) {
                        if (com.termux.BootstrapInstaller.hasLocalBackup()) {
                            // सीधा बैकग्राउंड में एक्सट्रैक्ट होगा, कोई लिसनर की ज़रूरत नहीं क्योंकि डायलॉग चल रहा है
                            com.termux.BootstrapInstaller.installFromLocal(termuxUsr, new android.os.Handler(android.os.Looper.getMainLooper()), new com.termux.BootstrapInstaller.InstallListener() {
                                @Override public void onOutput(String text) {} // Ignore text output here
                            }, new Runnable() {
                                @Override public void run() {}
                            });
                            // एक्सट्रैक्ट होने का थोड़ा इंतज़ार करें
                            Thread.sleep(5000);
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (loadingDialog != null && loadingDialog.isShowing()) {
                                loadingDialog.dismiss();
                                activity.showCustomToast("Ecosystem Ready!");
                            }
                        }
                    });
                }
            }
        }).start();
    }

    public boolean ensureToolsExtractedFromAssets() {
        File toolsDir = new File(activity.getFilesDir(), "build_tools");
        if (!toolsDir.exists()) toolsDir.mkdirs();

        String[] toolNames = {
            "aapt2", "zipalign", "android.jar", "ecj.jar",
            "ecj-jdt-core.jar", "d8_1.dex", "d8_2.dex",
            "apksigner.jar", "testkey.pk8", "testkey.x509.pem",
            "messages.properties"
        };

        for (String tool : toolNames) {
            File outFile = new File(toolsDir, tool);
            // Agar file nahi hai ya 100 bytes se choti (corrupt) hai, tabhi assets se copy karega
            if (!outFile.exists() || outFile.length() < 100L) {
                try {
                    InputStream is = activity.getAssets().open(tool);
                    FileOutputStream fos = new FileOutputStream(outFile);
                    byte[] buffer = new byte[32768];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, len);
                    }
                    fos.flush();
                    fos.close();
                    is.close();
                } catch (Exception ignored) {
                    // Agar koi optional file miss ho toh error na de
                }
            }

            if (tool.equals("aapt2") || tool.equals("zipalign")) {
                if (outFile.exists()) {
                    outFile.setExecutable(true, false);
                }
            }
        }

        return areBuildToolsInstalled();
    }

    public boolean areBuildToolsInstalled() {
        File toolsDir = new File(activity.getFilesDir(), "build_tools");
        if (!toolsDir.exists()) return false;

        File aapt2 = new File(toolsDir, "aapt2");
        File androidJar = new File(toolsDir, "android.jar");
        File ecj = new File(toolsDir, "ecj.jar");
        File zipalign = new File(toolsDir, "zipalign");

        File d81 = new File(toolsDir, "d8_1.dex");
        File d82 = new File(toolsDir, "d8_2.dex");

        boolean hasD8 = (d81.exists() && d81.length() > 0 && d82.exists() && d82.length() > 0);

        return aapt2.exists() && aapt2.length() > 0
        && androidJar.exists() && androidJar.length() > 0
        && ecj.exists() && ecj.length() > 0
        && zipalign.exists() && zipalign.length() > 0
        && hasD8;
    }

    private void logToConsole(final String msg) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (activity.consoleView != null) {
                    activity.consoleView.log(msg);
                }
            }
        });
    }

    public void startBuildToolsDownload() {
        logToConsole("#ERROR:|System|-1|Build tools extraction failed. Ensure files exist in app/assets.");
    }

        public void startPythonDownload(final File scriptFile) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (activity.consoleView != null) {
                    activity.consoleView.clearLogs();
                    activity.openConsole();
                }
            }
        });

        logToConsole("Starting Python Component Installation...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                File toolsDir = new File(activity.getFilesDir(), "build_tools");
                if (!toolsDir.exists()) toolsDir.mkdirs();

                File offlineZip = new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "python_component.zip");
                File tempZip = new File(activity.getCacheDir(), "python_component.zip");
                File zipToExtract = null;

                try {
                    if (offlineZip.exists() && offlineZip.length() > 1000) {
                        logToConsole("Found offline Python zip. Extracting...");
                        zipToExtract = offlineZip;
                    } else {
                        logToConsole("Downloading Python from Cloud... (Please wait, ~147MB)");
                        
                        // 🔥 NAYA CODE: Yahan aapka GitHub ka asli link lag gaya hai
                        String downloadUrl = "https://github.com/rahulvloger202-max/RK-Studio/releases/download/v1.0.0/python_component.zip";
                        java.net.URL url = new java.net.URL(downloadUrl);
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                        conn.connect();

                        if (conn.getResponseCode() != java.net.HttpURLConnection.HTTP_OK) {
                            logToConsole("#ERROR:|System|-1|Server returned HTTP " + conn.getResponseCode());
                            return;
                        }

                        int fileLength = conn.getContentLength();
                        java.io.InputStream input = conn.getInputStream();
                        java.io.FileOutputStream output = new java.io.FileOutputStream(tempZip);

                        byte data[] = new byte[8192];
                        long total = 0;
                        int count;
                        int lastProgress = 0;

                        // 🔥 NAYA CODE: Live Progress Percentage dikhane ke liye
                        while ((count = input.read(data)) != -1) {
                            total += count;
                            if (fileLength > 0) {
                                int progress = (int) (total * 100 / fileLength);
                                if (progress - lastProgress >= 10) { // Har 10% par console me update aayega
                                    logToConsole("Downloading: " + progress + "% (" + (total / (1024 * 1024)) + " MB)");
                                    lastProgress = progress;
                                }
                            }
                            output.write(data, 0, count);
                        }
                        output.flush();
                        output.close();
                        input.close();
                        
                        zipToExtract = tempZip;
                        logToConsole("Download Complete! Extracting files...");
                    }

                    if (zipToExtract != null) extractZipFile(zipToExtract, toolsDir);
                    if (tempZip.exists()) tempZip.delete();

                    logToConsole("✅ Python Component Installed Successfully! Running script now...");

                } catch (final Exception e) {
                    if (tempZip.exists()) tempZip.delete();
                    logToConsole("#ERROR:|System|-1|Python Installation Failed: " + e.getMessage());
                }
            }
        }).start();
    }
    private void extractZipFile(File zipFile, File targetDir) throws Exception {
        ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile));
        ZipEntry entry;
        byte[] buffer = new byte[16384];
        while ((entry = zis.getNextEntry()) != null) {
            String entryName = entry.getName();
            if (entryName.startsWith("assets/")) {
                entryName = entryName.substring("assets/".length());
            }
            if (entryName.isEmpty()) {
                zis.closeEntry();
                continue;
            }
            File outFile = new File(targetDir, entryName);
            if (entry.isDirectory()) {
                outFile.mkdirs();
            } else {
                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                FileOutputStream fos = new FileOutputStream(outFile);
                int count;
                while ((count = zis.read(buffer)) != -1) {
                    fos.write(buffer, 0, count);
                }
                fos.flush();
                fos.close();
            }
            zis.closeEntry();
        }
        zis.close();
    }
}
