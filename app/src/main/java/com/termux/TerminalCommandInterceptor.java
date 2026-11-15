package com.termux;

import android.content.Context;
import android.util.TypedValue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class TerminalCommandInterceptor {

    public static final String ATTR_ERROR = "ide_log_error_color";
    public static final String ATTR_WARNING = "ide_log_warning_color";
    public static final String ATTR_SUCCESS = "ide_log_success_color";
    public static final String ATTR_INFO = "ide_log_info_color";

    private static final String WORKFLOW_NAME = "Android Build";

    public interface ShellCallback {
        void onLog(String text, String colorAttrName);
        File getWorkingDir();
        void updateWorkingDir(File newDir);
        void sendToNativeShell(String command);
        void onCommandChainComplete();
        void onRequestCloudDialog();
        void onRequestNetworkDialog();
        void onSwitchMode(boolean online);
        void buildOfflineApk();
    }

    public static int resolveAttrColor(Context context, String attrName, boolean isLightTheme) {
        if (context != null && attrName != null && !attrName.isEmpty()) {
            try {
                int attrId = context.getResources().getIdentifier(attrName, "attr", context.getPackageName());
                if (attrId != 0) {
                    TypedValue tv = new TypedValue();
                    if (context.getTheme().resolveAttribute(attrId, tv, true)) {
                        if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                            return tv.data;
                        } else if (tv.resourceId != 0) {
                            return context.getResources().getColor(tv.resourceId);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (ATTR_ERROR.equals(attrName)) return isLightTheme ? 0xFFC62828 : 0xFFF44336;
        if (ATTR_WARNING.equals(attrName)) return isLightTheme ? 0xFFEF6C00 : 0xFFFFEB3B;
        if (ATTR_SUCCESS.equals(attrName)) return isLightTheme ? 0xFF2E7D32 : 0xFF4CAF50;
        if (ATTR_INFO.equals(attrName)) return isLightTheme ? 0xFF1565C0 : 0xFF00BCD4;
        return isLightTheme ? 0xFF212121 : 0xFFFFFFFF;
    }

    public static void executeChain(final Context context, final String rawCommandLine,
    final File termuxPrefix, final boolean isOnline,
    final ShellCallback callback) {
        new Thread(() -> {
            try {
                String[] subCommands = rawCommandLine.split("&&");
                for (String rawSub : subCommands) {
                    String cmd = rawSub.trim();
                    if (cmd.isEmpty()) continue;
                    boolean success = processSingleCommand(context, cmd, termuxPrefix, isOnline, callback);
                    if (!success) break;
                }
            } catch (Throwable t) {
                callback.onLog("[Interceptor Error] " + t.toString() + "\n", ATTR_ERROR);
            } finally {
                callback.onCommandChainComplete();
            }
        }).start();
    }

    private static boolean processSingleCommand(Context context, String cmd, File termuxPrefix,
    boolean isOnline, ShellCallback callback) {
        File currentDir = callback.getWorkingDir();

        if (cmd.equals("cloud setup")) {
            callback.onRequestCloudDialog();
            return true;
        }

        if (cmd.equals("build") || cmd.equals("apk build") || cmd.equals("gradle build")) {
            callback.buildOfflineApk();
            return true;
        }

        if (cmd.equals("online") || cmd.equals("mode online")) {
            if (!BootstrapInstaller.isCloudConfigured(context)) {
                callback.onLog("[Warning] GitHub credentials missing. Tap SETUP first.\n", ATTR_WARNING);
                callback.onRequestCloudDialog();
            } else {
                callback.onSwitchMode(true);
            }
            return true;
        }

        if (cmd.equals("offline") || cmd.equals("mode offline")) {
            callback.onSwitchMode(false);
            return true;
        }

        if (cmd.startsWith("cloud ") || cmd.startsWith("codespace ")) {
            if (!isOnline) {
                callback.onLog("[Strict Mode] You are OFFLINE. Switch to CLOUD first.\n", ATTR_ERROR);
                return false;
            }
            return handleCloudCommands(context, cmd, callback);
        }

        if (cmd.startsWith("cd ")) {
            String targetPath = cmd.substring(3).trim().replace("\"", "").replace("'", "");
            File targetDir = targetPath.startsWith("/") ? new File(targetPath) : new File(currentDir, targetPath);
            if (targetDir.exists() && targetDir.isDirectory()) {
                callback.updateWorkingDir(targetDir);
                callback.sendToNativeShell("cd \"" + targetDir.getAbsolutePath() + "\"");
                return true;
            } else {
                callback.onLog("cd: no such directory: " + targetPath + "\n", ATTR_ERROR);
                return false;
            }
        }

        if (cmd.startsWith("mkdir ")) {
            String arg = cmd.substring(6).replace("-p", "").replace("\"", "").replace("'", "").trim();
            if (arg.isEmpty()) {
                callback.onLog("mkdir: missing operand\n", ATTR_ERROR);
                return false;
            }
            File newDir = arg.startsWith("/") ? new File(arg) : new File(currentDir, arg);
            if (newDir.exists()) return true;
            newDir.mkdirs();
            callback.onLog("[Created] " + newDir.getAbsolutePath() + "\n", ATTR_SUCCESS);
            return true;
        }

        if (cmd.startsWith("curl ") || cmd.startsWith("wget ")) {
            return downloadFileGeneric(cmd, currentDir, callback);
        }

        callback.sendToNativeShell(cmd);
        return true;
    }

    // ============================================================
    // 🔥 CLOUD COMMANDS (REAL GITHUB ACTIONS API)
    // ============================================================
    private static boolean handleCloudCommands(Context context, String cmd, ShellCallback callback) {
        String rawToken = BootstrapInstaller.getGithubToken(context);
        String rawRepo = BootstrapInstaller.getGithubRepo(context);

        final String repo = (rawRepo != null) ? rawRepo.replaceAll("[^a-zA-Z0-9_./-]", "") : "";
        final String token = (rawToken != null) ? rawToken.replaceAll("\\s+", "") : "";

        if (token.isEmpty() || repo.isEmpty()) {
            callback.onLog("[Error] GitHub cloud not configured. Tap SETUP.\n", ATTR_ERROR);
            return false;
        }

        try {
            if (cmd.equals("cloud status") || cmd.equals("cloud info")) {
                return cloudStatus(context, token, repo, callback);

            } else if (cmd.equals("cloud init")) {
                return cloudInit(context, token, repo, callback);

            } else if (cmd.equals("cloud push") || cmd.equals("cloud sync")) {
                callback.onLog("[Cloud Sync] Connecting to GitHub API...\n", ATTR_INFO);
                File rootDir = callback.getWorkingDir();

                new Thread(() -> {
                    try {
                        if(!rootDir.exists() || !rootDir.isDirectory()) {
                            callback.onLog("[Error] Current project folder not found.\n", ATTR_ERROR);
                            return;
                        }
                        callback.onLog("[Git] Pushing files from '" + rootDir.getName() + "' to " + repo + "...\n", ATTR_INFO);
                        boolean pushed = pushDirectoryToGitHub(rootDir, "", repo, token, callback);

                        if (pushed) {
                            callback.onLog("[Success] 🚀 Code synced to cloud! Run 'cloud build' to compile.\n", ATTR_SUCCESS);
                        } else {
                            callback.onLog("[Error] Push failed. Check token permissions (contents:write).\n", ATTR_ERROR);
                        }
                    } catch (Exception e) {
                        callback.onLog("[Error] Sync failed: " + e.getMessage() + "\n", ATTR_ERROR);
                    }
                }).start();
                return true;

            } else if (cmd.equals("cloud build") || cmd.equals("cloud compile")) {
                return cloudBuild(context, token, repo, callback);

            } else if (cmd.equals("cloud runs") || cmd.equals("cloud history")) {
                return cloudRuns(context, token, repo, callback);

            } else if (cmd.equals("cloud download") || cmd.startsWith("cloud download ")) {
                String filter = cmd.length() > "cloud download".length()
                ? cmd.substring("cloud download".length()).trim()
                : "";
                return cloudDownload(context, token, repo, filter, callback);

            // 🔥 UPGRADED CLOUD DELETE (FILE OR FOLDER) 🔥
            } else if (cmd.startsWith("cloud delete ")) {
                String targetFile = cmd.substring("cloud delete ".length()).trim();
                if (targetFile.isEmpty()) {
                    callback.onLog("[Error] Specify file/folder to delete. Ex: cloud delete myfolder\n", ATTR_ERROR);
                    return false;
                }
                return cloudDelete(context, token, repo, targetFile, callback);
            }

            callback.onLog("Unknown cloud command.\n", ATTR_ERROR);
            callback.onLog("Try: cloud build | cloud push | cloud delete <file/folder> | cloud download | cloud status\n", ATTR_INFO);
            return false;

        } catch (Throwable t) {
            callback.onLog("[Cloud Error] " + t.getMessage() + "\n", ATTR_ERROR);
            return false;
        }
    }

    // ---------- UPGRADED CLOUD DELETE (Recursive Folder Deletion) ----------
    private static boolean cloudDelete(Context context, String token, String repo, String targetPath, ShellCallback callback) {
        callback.onLog("[Cloud] Scanning '" + targetPath + "' for deletion...\n", ATTR_INFO);
        
        new Thread(() -> {
            try {
                boolean success = deleteFromGitHubRecursive(repo, token, targetPath, callback);
                if (success) {
                    callback.onLog("[Success] 🗑️ Deletion process completed for '" + targetPath + "'!\n", ATTR_SUCCESS);
                } else {
                    callback.onLog("[Warning] ⚠️ Deletion finished with some errors.\n", ATTR_WARNING);
                }
            } catch (Exception e) {
                callback.onLog("[Error] Delete Exception: " + e.getMessage() + "\n", ATTR_ERROR);
            }
        }).start();
        return true;
    }

    private static boolean deleteFromGitHubRecursive(String repo, String token, String path, ShellCallback callback) {
        String apiUrl = "https://api.github.com/repos/" + repo + "/contents/" + path;
        boolean allSuccess = true;
        try {
            String rawJson = httpGetRaw(apiUrl, token);
            Object json = new JSONTokener(rawJson).nextValue();

            if (json instanceof JSONArray) {
                // Yeh ek Folder hai, iske andar ki files aur folders ko nikal kar loop lagayenge
                JSONArray arr = (JSONArray) json;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.getJSONObject(i);
                    String itemPath = item.optString("path");
                    String type = item.optString("type");

                    if ("dir".equals(type)) {
                        boolean res = deleteFromGitHubRecursive(repo, token, itemPath, callback);
                        if (!res) allSuccess = false;
                    } else {
                        boolean res = deleteSingleFile(repo, token, itemPath, item.optString("sha"), callback);
                        if (!res) allSuccess = false;
                    }
                }
            } else if (json instanceof JSONObject) {
                // Yeh ek single File hai
                JSONObject obj = (JSONObject) json;
                allSuccess = deleteSingleFile(repo, token, path, obj.optString("sha"), callback);
            }
        } catch (Exception e) {
            callback.onLog("   -> ❌ Failed to scan/find '" + path + "': " + e.getMessage() + "\n", ATTR_ERROR);
            allSuccess = false;
        }
        return allSuccess;
    }

    private static boolean deleteSingleFile(String repo, String token, String path, String sha, ShellCallback callback) {
        try {
            URL url = new URL("https://api.github.com/repos/" + repo + "/contents/" + path);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("DELETE");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            JSONObject body = new JSONObject();
            body.put("message", "Deleted " + path + " via PrimX Terminal");
            body.put("sha", sha);

            conn.getOutputStream().write(body.toString().getBytes("UTF-8"));
            int code = conn.getResponseCode();
            if (code == 200 || code == 201) {
                callback.onLog("   -> 🗑️ Deleted: " + path + "\n", ATTR_SUCCESS);
                return true;
            } else {
                callback.onLog("   -> ❌ Failed to delete: " + path + " (HTTP " + code + ")\n", ATTR_ERROR);
                return false;
            }
        } catch (Exception e) {
            callback.onLog("   -> ❌ Error deleting '" + path + "': " + e.getMessage() + "\n", ATTR_ERROR);
            return false;
        }
    }

    // ---------- CLOUD STATUS ----------
    private static boolean cloudStatus(Context context, String token, String repo, ShellCallback callback) {
        callback.onLog("=== GitHub Cloud Status ===\n", ATTR_INFO);
        callback.onLog("Repository : " + repo + "\n", ATTR_INFO);
        callback.onLog("Token      : " + (token.length() > 8 ? token.substring(0, 8) + "****" : "(not set)") + "\n", ATTR_INFO);

        try {
            JSONObject user = httpGetJson("https://api.github.com/user", token);
            callback.onLog("Authenticated as: " + user.optString("login", "unknown") + "\n", ATTR_SUCCESS);
            callback.onLog("To deploy your project, make sure to run 'cloud push' first!\n", ATTR_INFO);
        } catch (Exception e) {
            callback.onLog("Token check FAILED: " + e.getMessage() + "\n", ATTR_ERROR);
        }
        return true;
    }

    // ---------- CLOUD INIT ----------
    private static boolean cloudInit(Context context, String token, String repo, ShellCallback callback) {
        callback.onLog("[Cloud] Please use 'cloud push' directly to sync your workflow files.\n", ATTR_INFO);
        return true;
    }

    // ---------- CLOUD BUILD (Dynamic Engine) ----------
    private static boolean cloudBuild(Context context, String token, String repo, ShellCallback callback) throws Exception {
        callback.onLog("[Cloud] Querying GitHub for active workflows...\n", ATTR_INFO);

        String repoInfoUrl = "https://api.github.com/repos/" + repo;
        JSONObject repoJson = httpGetJson(repoInfoUrl, token);
        String branch = repoJson.optString("default_branch", "main");

        String workflowsUrl = "https://api.github.com/repos/" + repo + "/actions/workflows";
        JSONObject wfJson = httpGetJson(workflowsUrl, token);
        JSONArray workflows = wfJson.optJSONArray("workflows");

        if (workflows == null || workflows.length() == 0) {
            callback.onLog("[Cloud Error] No workflows found on GitHub yet.\n", ATTR_ERROR);
            callback.onLog("[Hint] Make sure you ran 'cloud push' inside your project folder first.\n", ATTR_WARNING);
            return false;
        }

        JSONObject activeWf = workflows.getJSONObject(0);
        long workflowId = activeWf.optLong("id");
        String wfName = activeWf.optString("name", "Build");

        callback.onLog("[Cloud] Detected Workflow: " + wfName + "\n", ATTR_SUCCESS);
        callback.onLog("[Cloud] Triggering run on branch '" + branch + "'...\n", ATTR_INFO);

        String dispatchUrl = "https://api.github.com/repos/" + repo + "/actions/workflows/" + workflowId + "/dispatches";
        JSONObject payload = new JSONObject();
        payload.put("ref", branch);

        long dispatchTimeMs = System.currentTimeMillis();
        int code = makePostRequest(dispatchUrl, token, payload.toString());

        if (code == 204 || code == 200) {
            callback.onLog("[Cloud] ✅ Workflow dispatched successfully!\n", ATTR_SUCCESS);
        } else {
            callback.onLog("[Cloud Error] Dispatch failed. HTTP " + code + "\n", ATTR_ERROR);
            return false;
        }

        callback.onLog("[Cloud] Waiting for run to start...\n", ATTR_INFO);
        long runId = -1;
        for (int i = 0; i < 12; i++) {
            Thread.sleep(2500);
            try {
                String runsUrl = "https://api.github.com/repos/" + repo + "/actions/runs?per_page=5";
                JSONObject runsJson = httpGetJson(runsUrl, token);
                JSONArray arr = runsJson.optJSONArray("workflow_runs");
                if (arr != null && arr.length() > 0) {
                    for (int j = 0; j < arr.length(); j++) {
                        JSONObject r = arr.getJSONObject(j);
                        long createdMs = parseIsoTime(r.optString("created_at", ""));
                        if (createdMs >= dispatchTimeMs - 15_000) {
                            runId = r.optLong("id", -1);
                            break;
                        }
                    }
                }
                if (runId > 0) break;
            } catch (Exception ignored) {}
        }

        if (runId <= 0) {
            callback.onLog("[Cloud] Build triggered! Use 'cloud runs' to check progress.\n", ATTR_SUCCESS);
            return true;
        }

        callback.onLog("[Cloud] Run started: #" + runId + "\n", ATTR_SUCCESS);
        callback.onLog("[Cloud] Watching build logs...\n", ATTR_INFO);

        String runUrl = "https://api.github.com/repos/" + repo + "/actions/runs/" + runId;
        String lastStatus = "";

        while (true) {
            Thread.sleep(4000);
            try {
                JSONObject run = httpGetJson(runUrl, token);
                String status = run.optString("status", "unknown");
                String conclusion = run.optString("conclusion", "");

                if (!status.equals(lastStatus)) {
                    callback.onLog("[Cloud] status → " + status + "\n", ATTR_INFO);
                    lastStatus = status;
                }

                if ("completed".equals(status)) {
                    if ("success".equals(conclusion)) {
                        callback.onLog("\n[Cloud] ✅ BUILD SUCCESSFUL!\n", ATTR_SUCCESS);
                        callback.onLog("[Cloud] Run 'cloud download' to fetch your compiled files.\n", ATTR_INFO);
                        return true;
                    } else {
                        callback.onLog("\n[Cloud] ❌ BUILD FAILED (" + conclusion + ")\n", ATTR_ERROR);
                        callback.onLog("[Cloud] Check logs on GitHub Actions tab.\n", ATTR_WARNING);
                        return false;
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    // ---------- CLOUD RUNS ----------
    private static boolean cloudRuns(Context context, String token, String repo, ShellCallback callback) throws Exception {
        callback.onLog("[Cloud] Fetching recent runs...\n", ATTR_INFO);
        String url = "https://api.github.com/repos/" + repo + "/actions/runs?per_page=10";
        JSONObject json = httpGetJson(url, token);
        JSONArray runs = json.optJSONArray("workflow_runs");

        if (runs == null || runs.length() == 0) {
            callback.onLog("[Info] No workflow runs yet. Run 'cloud build'.\n", ATTR_WARNING);
            return true;
        }

        callback.onLog("=== Recent Runs ===\n", ATTR_INFO);
        for (int i = 0; i < runs.length(); i++) {
            JSONObject r = runs.getJSONObject(i);
            long id = r.optLong("id", 0);
            String name = r.optString("name", "workflow");
            String branch = r.optString("head_branch", "?");
            String status = r.optString("status", "?");
            String conclusion = r.optString("conclusion", "");

            String color = "success".equals(conclusion) ? ATTR_SUCCESS
            : "failure".equals(conclusion) ? ATTR_ERROR
            : ATTR_WARNING;

            String label = conclusion.isEmpty() ? status : conclusion;
            callback.onLog("  #" + id + " [" + label.toUpperCase() + "] " + name + " (" + branch + ")\n", color);
        }
        return true;
    }

    // ---------- CLOUD DOWNLOAD ----------
    private static boolean cloudDownload(Context context, String token, String repo, String filter, ShellCallback callback) throws Exception {
        callback.onLog("[Cloud] Looking for the latest successful run...\n", ATTR_INFO);

        String runsUrl = "https://api.github.com/repos/" + repo + "/actions/runs?status=success&per_page=5";
        JSONObject runsJson = httpGetJson(runsUrl, token);
        JSONArray runs = runsJson.optJSONArray("workflow_runs");

        if (runs == null || runs.length() == 0) {
            callback.onLog("[Error] No successful build found.\n", ATTR_ERROR);
            return false;
        }

        long runId = runs.getJSONObject(0).optLong("id", -1);
        if (runId < 0) {
            callback.onLog("[Error] Could not read latest run.\n", ATTR_ERROR);
            return false;
        }

        callback.onLog("[Cloud] Listing artifacts...\n", ATTR_INFO);
        String artifactsUrl = "https://api.github.com/repos/" + repo + "/actions/runs/" + runId + "/artifacts";
        JSONObject artifactsJson = httpGetJson(artifactsUrl, token);
        JSONArray artifacts = artifactsJson.optJSONArray("artifacts");

        if (artifacts == null || artifacts.length() == 0) {
            callback.onLog("[Error] No artifacts in this run.\n", ATTR_ERROR);
            return false;
        }

        long artifactId = -1;
        String artifactName = "";
        for (int i = 0; i < artifacts.length(); i++) {
            JSONObject a = artifacts.getJSONObject(i);
            String name = a.optString("name", "");
            if (filter.isEmpty() || name.toLowerCase().contains(filter.toLowerCase())) {
                artifactId = a.optLong("id", -1);
                artifactName = name;
                break;
            }
        }

        if (artifactId < 0) {
            callback.onLog("[Error] No matching artifact found.\n", ATTR_ERROR);
            return false;
        }

        callback.onLog("[Cloud] Artifact: " + artifactName + "\n", ATTR_SUCCESS);

        File downloadDir = new File("/storage/emulated/0/Download");
        if (!downloadDir.exists() || !downloadDir.canWrite()) {
            File ext = context.getExternalFilesDir(null);
            if (ext != null) downloadDir = ext;
        }
        if (!downloadDir.exists()) downloadDir.mkdirs();

        String safe = artifactName.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safe.isEmpty()) safe = "artifact";
        File zipFile = new File(downloadDir, safe + ".zip");

        callback.onLog("[Cloud] Downloading zip to:\n  " + zipFile.getAbsolutePath() + "\n", ATTR_INFO);
        String downloadUrl = "https://api.github.com/repos/" + repo + "/actions/artifacts/" + artifactId + "/zip";

        if (!downloadStreamToFile(downloadUrl, token, zipFile, callback)) {
            callback.onLog("[Error] Download failed.\n", ATTR_ERROR);
            return false;
        }

        callback.onLog("[Cloud] Extracting APK / Library from zip...\n", ATTR_INFO);
        File extractedFile = extractApkFromZip(zipFile, downloadDir);

        if (extractedFile != null) {
            callback.onLog("[Cloud] ✅ File saved to:\n  " + extractedFile.getAbsolutePath() + "\n", ATTR_SUCCESS);
            try { zipFile.delete(); } catch (Exception ignored) {}
            return true;
        } else {
            callback.onLog("[Warning] Extraction failed. Zip kept at:\n  " + zipFile.getAbsolutePath() + "\n", ATTR_WARNING);
            return false;
        }
    }

    // ============================================================
    // HTTP / IO HELPERS
    // ============================================================

    // Naya helper method banaya hai jo API se Array/Object check karne ki suvidha deta hai
    private static String httpGetRaw(String urlStr, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        conn.setRequestProperty("User-Agent", "PrimX/1.0");

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            String err = readStream(conn.getErrorStream());
            throw new Exception("HTTP " + code + (err.isEmpty() ? "" : " — " + err.trim()));
        }
        return readStream(conn.getInputStream());
    }

    private static JSONObject httpGetJson(String urlStr, String token) throws Exception {
        return new JSONObject(httpGetRaw(urlStr, token));
    }

    private static int makePostRequest(String urlStr, String token, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "PrimX/1.0");

        java.io.OutputStream os = conn.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.flush();
        os.close();
        return conn.getResponseCode();
    }

    private static int makePutRequest(String urlStr, String token, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("PUT");
        conn.setDoOutput(true);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "PrimX/1.0");

        java.io.OutputStream os = conn.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.flush();
        os.close();
        return conn.getResponseCode();
    }

    private static String readStream(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    private static boolean downloadStreamToFile(String urlStr, String token, File outFile, ShellCallback callback) {
        HttpURLConnection conn = null;
        try {
            for (int redirects = 0; redirects < 6; redirects++) {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "PrimX/1.0");

                if (redirects == 0) {
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                    conn.setRequestProperty("Accept", "application/vnd.github+json");
                }

                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    conn = null;
                    if (loc == null) return false;
                    urlStr = loc;
                    continue;
                }

                if (code != 200) {
                    callback.onLog("[Download Error] HTTP " + code + "\n", ATTR_ERROR);
                    return false;
                }

                long len = conn.getContentLength();
                InputStream in = new BufferedInputStream(conn.getInputStream(), 16384);
                FileOutputStream fos = new FileOutputStream(outFile);
                byte[] buf = new byte[16384];
                int n;
                long total = 0;
                int lastPct = -1;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    fos.write(buf, 0, n);
                    if (len > 0) {
                        int pct = (int) ((total * 100) / len);
                        if (pct >= lastPct + 20 || pct == 100) {
                            lastPct = pct;
                            callback.onLog("[Download] " + pct + "% (" + (total / 1024) + " KB)\n", ATTR_INFO);
                        }
                    }
                }
                fos.flush();
                fos.close();
                in.close();
                return true;
            }
            return false;
        } catch (Exception e) {
            callback.onLog("[Download Error] " + e.getMessage() + "\n", ATTR_ERROR);
            return false;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    private static File extractApkFromZip(File zipFile, File outputDir) {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    File file = new File(outputDir, new File(entry.getName()).getName());
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                    return file;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static long parseIsoTime(String iso) {
        if (iso == null || iso.isEmpty()) return 0;
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = fmt.parse(iso);
            return d != null ? d.getTime() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean downloadFileGeneric(String cmd, File workingDir, ShellCallback callback) {
        try {
            String urlStr = "";
            String outName = "";
            String[] parts = cmd.split("\\s+");
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].startsWith("http")) urlStr = parts[i];
                if (parts[i].equals("-o") && i + 1 < parts.length) {
                    outName = parts[i + 1];
                }
            }

            if (urlStr.isEmpty()) return false;
            
            if (outName.isEmpty()) {
                int slash = urlStr.lastIndexOf('/');
                outName = (slash >= 0 && slash < urlStr.length() - 1) ? urlStr.substring(slash + 1) : "download.bin";
            }

            File out = outName.startsWith("/") ? new File(outName) : new File(workingDir, outName);
            callback.onLog("[Download] " + urlStr + "\n", ATTR_INFO);
            
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "PrimX/1.0");

            if (conn.getResponseCode() != 200) {
                callback.onLog("[Download Error] HTTP " + conn.getResponseCode() + "\n", ATTR_ERROR);
                return false;
            }

            try (InputStream in = conn.getInputStream();
            FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    total += n;
                }
                callback.onLog("[Download] Saved " + (total / 1024) + " KB → " + out.getAbsolutePath() + "\n", ATTR_SUCCESS);
            }
            return true;
        } catch (Exception e) {
            callback.onLog("[Download Error] " + e.getMessage() + "\n", ATTR_ERROR);
            return false;
        }
    }

    private static boolean pushDirectoryToGitHub(File dir, String currentPath, String repo, String token, ShellCallback callback) {
        boolean anySuccess = false;
        File[] files = dir.listFiles();
        if (files == null) return false;

        for (File f : files) {
            if (f.isDirectory()) {
                if (f.getName().equals("build") || f.getName().equals(".git") || f.getName().equals("build_tools")) continue;
                if (pushDirectoryToGitHub(f, currentPath + f.getName() + "/", repo, token, callback)) {
                    anySuccess = true;
                }
            } else {
                try {
                    callback.onLog("[Upload] " + currentPath + f.getName() + "...\n", ATTR_INFO);

                    byte[] bytes = new byte[(int) f.length()];
                    try (FileInputStream fis = new FileInputStream(f)) {
                        fis.read(bytes);
                    }

                    String encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
                    String apiUrl = "https://api.github.com/repos/" + repo + "/contents/" + currentPath + f.getName();

                    String existingSha = null;
                    try {
                        JSONObject existing = httpGetJson(apiUrl, token);
                        existingSha = existing.optString("sha", null);
                    } catch (Exception ignored) {}

                    URL url = new URL(apiUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("PUT");
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                    conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);

                    JSONObject body = new JSONObject();
                    body.put("message", "Auto-sync " + f.getName() + " from PrimX IDE");
                    body.put("content", encoded);
                    if (existingSha != null) body.put("sha", existingSha);

                    conn.getOutputStream().write(body.toString().getBytes("UTF-8"));

                    int code = conn.getResponseCode();
                    if(code == 201 || code == 200) {
                        callback.onLog("   -> Uploaded Successfully\n", ATTR_SUCCESS);
                        anySuccess = true;
                    } else {
                        String errBody = "";
                        try { errBody = readStream(conn.getErrorStream()); } catch(Exception ignored){}
                        callback.onLog("   -> Failed: HTTP " + code + " " + errBody + "\n", ATTR_ERROR);
                    }
                } catch(Exception e) {
                    callback.onLog("   -> Error: " + e.getMessage() + "\n", ATTR_ERROR);
                }
            }
        }
        return anySuccess;
    }
}
