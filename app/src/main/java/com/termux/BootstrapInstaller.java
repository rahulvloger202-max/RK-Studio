package com.termux;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

public class BootstrapInstaller {

    // --- PRIMX CLOUD SETUP ---
    public static String lastErrorLog = "";
    private static final String PREF_NAME = "primx_cloud_prefs";
    private static final String KEY_GITHUB_TOKEN = "github_pat";
    private static final String KEY_GITHUB_REPO = "github_repo";
    private static final String KEY_GITHUB_BRANCH = "github_branch";
    private static final String KEY_FORCE_OFFLINE = "force_offline_mode";

    public interface InstallListener { 
        void onOutput(String text); 
    }

    public static File installIfNeeded(Context context) {
        lastErrorLog = "";
        File workspaceDir = new File(context.getFilesDir(), "workspace");
        if (!workspaceDir.exists()) workspaceDir.mkdirs();
        return workspaceDir;
    }

    public static boolean isInternetAvailable(Context context) { return true; }

    public static void setForcedOffline(Context context, boolean offline) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_FORCE_OFFLINE, offline).apply();
    }

    public static boolean isForcedOffline(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_FORCE_OFFLINE, false);
    }

    public static void saveCloudConfig(Context context, String token, String repo, String branch) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_GITHUB_TOKEN, token != null ? token.trim() : "").putString(KEY_GITHUB_REPO, repo != null ? repo.trim() : "").putString(KEY_GITHUB_BRANCH, "main").putBoolean(KEY_FORCE_OFFLINE, false).apply();
    }

    public static String getGithubToken(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_GITHUB_TOKEN, "");
    }

    public static String getGithubRepo(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_GITHUB_REPO, "");
    }

    public static boolean isCloudConfigured(Context context) {
        String token = getGithubToken(context);
        String repo = getGithubRepo(context);
        return !token.isEmpty() && !repo.isEmpty();
    }

    // 1. चेक करने के लिए कि फाइल डाउनलोड फोल्डर (या इंटरनल रूट) में है या नहीं
    public static boolean hasLocalBackup() {
        File f1 = new File("/storage/emulated/0/Download/termux_tools_backup.tar.gz");
        File f2 = new File("/storage/emulated/0/termux_tools_backup.tar.gz");
        return f1.exists() || f2.exists();
    }

    // 2. अगर फाइल मौजूद है, तो Java से प्राइवेट फोल्डर में कॉपी करे और फिर एक्सट्रैक्ट करे (No Permission Error)
    public static void installFromLocal(String destDir, Handler handler, InstallListener listener, Runnable onSuccess) {
        new Thread(() -> {
            try {
                File usrDir = new File(destDir);
                File baseDir = usrDir.getParentFile();
                File homeDir = new File(baseDir, "home");
                if (!homeDir.exists()) homeDir.mkdirs();

                File sourceFile1 = new File("/storage/emulated/0/Download/termux_tools_backup.tar.gz");
                File sourceFile2 = new File("/storage/emulated/0/termux_tools_backup.tar.gz");
                File archiveFile = sourceFile1.exists() ? sourceFile1 : sourceFile2;
                
                // प्राइवेट फोल्डर में फाइल की कॉपी बनाएंगे
                File privateArchiveFile = new File(baseDir, "termux_tools_backup.tar.gz");

                handler.post(() -> listener.onOutput("[SYSTEM] Found local backup. Copying to secure vault (Please wait)...\n"));
                
                // Java से कॉपी कर रहे हैं ताकि SELinux ब्लॉक ना करे
                copyFile(archiveFile, privateArchiveFile);

                handler.post(() -> listener.onOutput("[SYSTEM] Copy complete. Extracting securely...\n"));

                Process process = Runtime.getRuntime().exec(new String[]{
                    "/system/bin/tar", "-xf", privateArchiveFile.getAbsolutePath(), "-C", baseDir.getAbsolutePath()
                });
                process.waitFor();

                // एक्सट्रैक्ट होने के बाद प्राइवेट ज़िप फाइल को डिलीट कर दें
                if (privateArchiveFile.exists()) {
                    privateArchiveFile.delete();
                }

                Runtime.getRuntime().exec(new String[]{"/system/bin/chmod", "-R", "755", destDir}).waitFor();

                handler.post(() -> {
                    listener.onOutput("[SUCCESS] Environment Sealed! Starting Shell...\n\n");
                    onSuccess.run();
                });
            } catch (Exception e) {
                handler.post(() -> listener.onOutput("\n[ERROR]: " + e.getMessage() + "\n"));
            }
        }).start();
    }

    // 3. अगर यूज़र 'install-core' कमांड चलाता है, तो क्लाउड से डाउनलोड करे
    public static void installFromCloud(String destDir, Handler handler, InstallListener listener, Runnable onSuccess) {
        new Thread(() -> {
            try {
                File usrDir = new File(destDir);
                File baseDir = usrDir.getParentFile();
                File homeDir = new File(baseDir, "home");
                if (!homeDir.exists()) homeDir.mkdirs();

                String downloadUrl = "https://github.com/rahulvloger202-max/RK-Studio/releases/download/v1.0.0/termux_tools_backup.tar.gz"; 
                File archiveFile = new File(baseDir, "termux_tools_backup.tar.gz");

                handler.post(() -> listener.onOutput("[SYSTEM] Downloading Core Environment (~150MB)...\n"));

                java.net.URL url = new java.net.URL(downloadUrl);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.connect();

                final int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                     handler.post(() -> listener.onOutput("[ERROR] Download failed! HTTP " + responseCode + "\n"));
                     return;
                }

                int fileLength = conn.getContentLength();
                InputStream input = new java.io.BufferedInputStream(url.openStream(), 8192);
                OutputStream output = new FileOutputStream(archiveFile);

                byte data[] = new byte[8192];
                long total = 0;
                int count;
                int lastPct = -1;

                while ((count = input.read(data)) != -1) {
                    total += count;
                    output.write(data, 0, count);
                    if (fileLength > 0) {
                        int pct = (int) (total * 100 / fileLength);
                        if (pct >= lastPct + 10 || pct == 100) {
                            lastPct = pct;
                            handler.post(() -> listener.onOutput("[Download] " + pct + "% completed...\n"));
                        }
                    }
                }
                output.flush();
                output.close();
                input.close();

                handler.post(() -> listener.onOutput("[SYSTEM] Download complete. Extracting securely...\n"));

                Process process = Runtime.getRuntime().exec(new String[]{
                    "/system/bin/tar", "-xf", archiveFile.getAbsolutePath(), "-C", baseDir.getAbsolutePath()
                });
                process.waitFor();

                if (archiveFile.exists()) archiveFile.delete(); 
                Runtime.getRuntime().exec(new String[]{"/system/bin/chmod", "-R", "755", destDir}).waitFor();

                handler.post(() -> {
                    listener.onOutput("[SUCCESS] Environment Sealed! Starting Shell...\n\n");
                    onSuccess.run();
                });
            } catch (Exception e) {
                handler.post(() -> listener.onOutput("\n[ERROR]: " + e.getMessage() + "\n"));
            }
        }).start();
    }

    private static void copyFile(File src, File dst) throws IOException {
        if (!src.exists()) return;
        InputStream in = new FileInputStream(src);
        OutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        in.close();
        out.close();
    }
}
