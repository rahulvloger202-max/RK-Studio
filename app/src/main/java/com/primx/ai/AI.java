package com.primx.ai;

import com.primx.utils.Setting;
import com.primx.ui.mainactivity.MainActivity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.StringReader;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;

public class AI {

    // ===== MODES =====
    // 1) chat(...)               -> SIRF baat-cheet. Koi file touch nahi hoti.
    // 2) executeSuperCommand(..) -> FIX button. Instruction/error ke hisab se code likhta aur project me save karta hai.
    // 3) "saved to project"      -> chat me jo baat hui uska code project me save/change karta hai.

    private static final int MAX_HISTORY_CHARS = 60000;   // chat history ki total limit
    private static final int MAX_ENTRY_CHARS = 25000;     // ek message ki limit
    private static final int MAX_FILE_CHARS = 30000;      // active file ka max hissa prompt me
    private static final int MAX_LISTED_FILES = 150;      // project file list ki limit

    public static final String SAVE_HINT = "\n\nYe code project me apply karne ke liye neeche 'SAVE TO PROJECT' button dabayein.";

    private static final List<String[]> chatHistory = new ArrayList<String[]>();

    private static final String CHAT_PREFS = "PrimXChat";
    private static Context appCtx = null;        // chat save/load ke liye
    private static String historyProject = null; // abhi kis project ki chat memory me hai

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<String> availableModels = new ArrayList<String>();

    public AI(Context context) {
        this.context = context;
        Context app = context.getApplicationContext();
        appCtx = (app != null) ? app : context;
    }

    public interface AIResponseListener {
        void onSuccess(String response);
        void onError(String error);
    }

    private int dpToPx(float dp) { return (int) (dp * context.getResources().getDisplayMetrics().density); }

    // 🔥 THEME CHECKER METHOD
    private boolean isLightTheme() {
        return Setting.isLight(context);
    }

    // ===================== SETTINGS =====================

    private String getApiUrl() {
        SharedPreferences prefs = context.getSharedPreferences("EditorPrefs", Context.MODE_PRIVATE);
        String url = prefs.getString("API_URL", "https://generativelanguage.googleapis.com/v1beta/models/");

        if (url != null) {
            if (url.contains("(") && url.contains(")")) {
                int start = url.lastIndexOf("(") + 1;
                int end = url.lastIndexOf(")");
                if (end > start) {
                    url = url.substring(start, end);
                }
            }
            url = url.replace("[", "").replace("]", "").trim();
        }
        return url;
    }

    private String getApiKey() {
        SharedPreferences prefs = context.getSharedPreferences("EditorPrefs", Context.MODE_PRIVATE);
        return prefs.getString("API_KEY", "").trim();
    }

    private String getTargetModel() {
        SharedPreferences prefs = context.getSharedPreferences("EditorPrefs", Context.MODE_PRIVATE);
        return prefs.getString("MODEL_NAME", "").trim();
    }

    private void fetchModelsIfEmpty() throws Exception {
        if (!availableModels.isEmpty()) return;
        if (!getApiUrl().contains("googleapis.com")) return;

        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(90000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("x-goog-api-key", getApiKey());

        int responseCode = conn.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) response.append(line);
            in.close();

            JSONObject jsonResponse = new JSONObject(response.toString());
            JSONArray models = jsonResponse.getJSONArray("models");

            for (int i = 0; i < models.length(); i++) {
                JSONObject model = models.getJSONObject(i);
                String name = model.getString("name").replace("models/", "");
                JSONArray methods = model.optJSONArray("supportedGenerationMethods");
                if (methods != null) {
                    for (int j = 0; j < methods.length(); j++) {
                        if (methods.getString(j).equals("generateContent") && name.contains("gemini")) {
                            availableModels.add(name);
                            break;
                        }
                    }
                }
            }
        }
    }

    // ===================== CORE: MODEL CALL =====================

    private String callModel(String prompt) throws Exception {
        return callModel(prompt, false);
    }

    private String callModel(String prompt, boolean precise) throws Exception {
        if (getApiKey().length() == 0) {
            throw new Exception("API key set nahi hai. Settings me apni API key daalo.");
        }
        fetchModelsIfEmpty();

        String finalResponse = null;
        String lastErrorMsg = "";
        String baseUrl = getApiUrl();
        boolean isOpenAiFormat = !baseUrl.contains("googleapis.com");

        List<String> modelsToTry = new ArrayList<String>();
        String savedModel = getTargetModel();
        if (!savedModel.isEmpty()) {
            modelsToTry.add(savedModel);
        } else if (!isOpenAiFormat) {
            modelsToTry.addAll(availableModels);
        } else {
            modelsToTry.add("gpt-3.5-turbo");
        }

        for (int m = 0; m < modelsToTry.size(); m++) {
            String modelName = modelsToTry.get(m);
            try {
                URL url;
                JSONObject payload = new JSONObject();

                if (isOpenAiFormat) {
                    String fullUrl = baseUrl;
                    if (!fullUrl.endsWith("/chat/completions")) {
                        if (!fullUrl.endsWith("/")) fullUrl += "/";
                        fullUrl += "chat/completions";
                    }
                    url = new URL(fullUrl);

                    payload.put("model", modelName);
                    JSONArray messages = new JSONArray();
                    JSONObject msg = new JSONObject();
                    msg.put("role", "user");
                    msg.put("content", prompt);
                    messages.put(msg);
                    payload.put("messages", messages);
                } else {
                    String fullUrl = baseUrl;
                    if (!fullUrl.endsWith("/")) fullUrl += "/";
                    url = new URL(fullUrl + modelName + ":generateContent");

                    JSONObject textPart = new JSONObject();
                    textPart.put("text", prompt);
                    JSONArray parts = new JSONArray();
                    parts.put(textPart);
                    JSONObject content = new JSONObject();
                    content.put("parts", parts);
                    JSONArray contents = new JSONArray();
                    contents.put(content);
                    payload.put("contents", contents);
                    if (precise) {
                        JSONObject gen = new JSONObject();
                        gen.put("temperature", 0.2);
                        payload.put("generationConfig", gen);
                    }
                }

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(90000);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");

                if (isOpenAiFormat) {
                    conn.setRequestProperty("Authorization", "Bearer " + getApiKey());
                } else {
                    conn.setRequestProperty("x-goog-api-key", getApiKey());
                }
                conn.setDoOutput(true);

                OutputStream os = conn.getOutputStream();
                os.write(payload.toString().getBytes("UTF-8"));
                os.close();

                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder responseStr = new StringBuilder();
                    String lineRes;
                    while ((lineRes = in.readLine()) != null) responseStr.append(lineRes);
                    in.close();

                    JSONObject jsonResponse = new JSONObject(responseStr.toString());

                    if (isOpenAiFormat) {
                        finalResponse = jsonResponse.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim();
                    } else {
                        JSONArray respParts = jsonResponse.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts");
                        StringBuilder joined = new StringBuilder();
                        for (int p = 0; p < respParts.length(); p++) {
                            joined.append(respParts.getJSONObject(p).optString("text", ""));
                        }
                        finalResponse = joined.toString().trim();
                    }
                    break;
                } else {
                    InputStream errorStream = conn.getErrorStream();
                    String errBody = "";
                    if (errorStream != null) {
                        BufferedReader errReader = new BufferedReader(new InputStreamReader(errorStream, "UTF-8"));
                        StringBuilder errBuilder = new StringBuilder();
                        String errLine;
                        while ((errLine = errReader.readLine()) != null) errBuilder.append(errLine);
                        errReader.close();
                        errBody = errBuilder.toString();
                        if (errBody.length() > 300) errBody = errBody.substring(0, 300);
                    }
                    lastErrorMsg = "Model [" + modelName + "] Failed (" + responseCode + "): " + errBody;
                }
            } catch (Exception e) {
                lastErrorMsg = "Exception: " + e.getMessage();
            }
        }

        if (finalResponse == null) {
            throw new Exception(lastErrorMsg.length() == 0 ? "Koi model available nahi mila." : lastErrorMsg);
        }
        return finalResponse;
    }

    // ===================== CHAT HISTORY =====================

    private static void addHistory(String role, String text) {
        if (text == null) return;
        if (text.length() > MAX_ENTRY_CHARS) text = text.substring(0, MAX_ENTRY_CHARS);
        synchronized (chatHistory) {
            chatHistory.add(new String[]{role, text});
            int total = 0;
            for (int i = 0; i < chatHistory.size(); i++) total += chatHistory.get(i)[1].length();
            while (total > MAX_HISTORY_CHARS && chatHistory.size() > 1) {
                total -= chatHistory.get(0)[1].length();
                chatHistory.remove(0);
            }
            persistHistory();
        }
    }

    private static String historyKey(String projectPath) {
        String k = projectPath == null ? "" : projectPath;
        return "history_" + Integer.toHexString(k.hashCode());
    }

    private static void useProjectInternal(String projectPath) {
        synchronized (chatHistory) {
            String key = projectPath == null ? "" : projectPath;
            if (key.equals(historyProject)) return;
            chatHistory.clear();
            historyProject = key;
            if (appCtx == null) return;
            try {
                SharedPreferences prefs = appCtx.getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE);
                String json = prefs.getString(historyKey(key), "");
                if (json != null && json.length() > 0) {
                    JSONArray arr = new JSONArray(json);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        chatHistory.add(new String[]{o.optString("r", "user"), o.optString("t", "")});
                    }
                }
            } catch (Exception e) {
            }
        }
    }

    private static void persistHistory() {
        if (appCtx == null || historyProject == null) return;
        try {
            JSONArray arr = new JSONArray();
            synchronized (chatHistory) {
                for (int i = 0; i < chatHistory.size(); i++) {
                    JSONObject o = new JSONObject();
                    o.put("r", chatHistory.get(i)[0]);
                    o.put("t", chatHistory.get(i)[1]);
                    arr.put(o);
                }
            }
            appCtx.getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE)
            .edit().putString(historyKey(historyProject), arr.toString()).apply();
        } catch (Exception e) {
        }
    }

    public List<String[]> getHistory(String projectPath) {
        useProjectInternal(projectPath);
        List<String[]> copy = new ArrayList<String[]>();
        synchronized (chatHistory) {
            for (int i = 0; i < chatHistory.size(); i++) copy.add(chatHistory.get(i));
        }
        return copy;
    }

    private static String historyAsText() {
        StringBuilder sb = new StringBuilder();
        synchronized (chatHistory) {
            for (int i = 0; i < chatHistory.size(); i++) {
                String[] h = chatHistory.get(i);
                sb.append(h[0].equals("user") ? "USER: " : "ASSISTANT: ").append(h[1]).append("\n\n");
            }
        }
        return sb.toString();
    }

    private static boolean hasHistory() {
        synchronized (chatHistory) {
            return !chatHistory.isEmpty();
        }
    }

    // ===================== PROJECT TYPE DETECTION =====================

    public static String detectProjectType(File root) {
        if (root == null || !root.exists()) return "UNKNOWN";
        File manifest1 = new File(root, "app/src/main/AndroidManifest.xml");
        File manifest2 = new File(root, "src/main/AndroidManifest.xml");
        File manifest3 = new File(root, "AndroidManifest.xml");
        if (manifest1.exists() || manifest2.exists() || manifest3.exists()) {
            return "ANDROID_APP";
        }
        File pyFile = new File(root, "main.py");
        if (pyFile.exists()) {
            return "PYTHON_CONSOLE";
        }
        File htmlFile = new File(root, "index.html");
        if (htmlFile.exists()) {
            return "WEB_PROJECT";
        }
        return "JAVA_CONSOLE";
    }

    public static boolean isAndroidXProject(File root) {
        if (root == null || !root.exists()) return true;
        File gradle = new File(root, "app/build.gradle");
        if (!gradle.exists()) gradle = new File(root, "build.gradle");
        if (gradle.exists()) {
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(gradle), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains("androidx.") || line.contains("com.google.android.material")) {
                        br.close();
                        return true;
                    }
                    if (line.contains("com.android.support")) {
                        br.close();
                        return false;
                    }
                }
                br.close();
            } catch (Exception ignored) {}
        }
        return true;
    }

    public static void clearProjectChat(String projectPath) {
        if (projectPath == null) return;
        synchronized (chatHistory) {
            String key = projectPath;
            if (key.equals(historyProject)) {
                chatHistory.clear();
            }
            if (appCtx != null) {
                appCtx.getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE)
                .edit().remove(historyKey(key)).apply();
            }
        }
    }

    private static String getLastAssistantMessage() {
        synchronized (chatHistory) {
            for (int i = chatHistory.size() - 1; i >= 0; i--) {
                if ("assistant".equals(chatHistory.get(i)[0])) {
                    return chatHistory.get(i)[1];
                }
            }
        }
        return null;
    }

    public static void clearChat() {
        synchronized (chatHistory) {
            chatHistory.clear();
            if (appCtx != null && historyProject != null) {
                appCtx.getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE)
                .edit().remove(historyKey(historyProject)).apply();
            }
        }
    }

    // ===================== HELPERS =====================

    private static boolean isSaveTrigger(String msg) {
        if (msg == null) return false;
        String s = msg.toLowerCase().replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
        return s.equals("saved to project") || s.equals("save to project")
        || s.startsWith("saved to project ") || s.startsWith("save to project ");
    }

    private static boolean needsSaveHint(String reply) {
        if (reply == null) return false;
        return !reply.toLowerCase().contains("saved to project");
    }

    private String readFileText(File f, int maxChars) {
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
                if (sb.length() > maxChars) {
                    sb.append("\n...[file truncated]...\n");
                    break;
                }
            }
            br.close();
        } catch (Exception e) {
        }
        return sb.toString();
    }

    private void listProjectFiles(File dir, String prefix, StringBuilder sb, int[] count) {
        if (dir == null || count[0] >= MAX_LISTED_FILES) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (count[0] >= MAX_LISTED_FILES) return;
            String name = f.getName();
            if (f.isDirectory()) {
                if (!name.equals("build") && !name.equals(".idea") && !name.equals("gradle") && !name.equals(".git")) {
                    listProjectFiles(f, prefix + name + "/", sb, count);
                }
            } else if (name.endsWith(".java") || name.endsWith(".xml") || name.endsWith(".gradle")) {
                sb.append(prefix).append(name).append("\n");
                count[0]++;
            }
        }
    }

    private String projectFilesText(String projectPath) {
        if (projectPath == null || projectPath.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        listProjectFiles(new File(projectPath), "", sb, new int[]{0});
        return sb.toString();
    }

    private boolean isReadableProjectFile(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".java") || n.endsWith(".xml") || n.endsWith(".gradle")
        || n.endsWith(".properties") || n.endsWith(".txt") || n.endsWith(".md")
        || n.endsWith(".json") || n.endsWith(".pro");
    }

    private void collectAllFiles(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            String name = f.getName();
            if (f.isDirectory()) {
                if (!name.equals("build") && !name.equals(".idea") && !name.equals("gradle") && !name.equals(".git") && !name.equals(".gradle")) {
                    collectAllFiles(f, out);
                }
            } else if (isReadableProjectFile(name)) {
                out.add(f);
            }
        }
    }

    private int filePriority(File f, String userMessage) {
        String name = f.getName();
        String msg = userMessage == null ? "" : userMessage.toLowerCase();
        String lower = name.toLowerCase();
        if (msg.length() > 0 && (msg.contains(lower) || msg.contains(lower.replace(".java", "")))) return 0;
        if (name.equals("AndroidManifest.xml") || lower.startsWith("build.gradle") || lower.equals("settings.gradle")
        || lower.equals("strings.xml") || lower.equals("themes.xml") || lower.equals("styles.xml")) return 1;
        if (lower.endsWith(".java")) return 2;
        if (lower.endsWith(".xml")) return 3;
        return 4;
    }

    private String buildProjectContext(String projectPath, final String userMessage) {
        return buildProjectContext(projectPath, userMessage, -1);
    }

    private String buildProjectContext(String projectPath, final String userMessage, int budgetOverride) {
        if (projectPath == null || projectPath.length() == 0) return "";
        try {
            File root = new File(projectPath);
            String rootCanon = root.getCanonicalPath();
            List<File> all = new ArrayList<File>();
            collectAllFiles(root, all);

            final AI self = this;
            Collections.sort(all, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    int pa = self.filePriority(a, userMessage);
                    int pb = self.filePriority(b, userMessage);
                    if (pa != pb) return pa < pb ? -1 : 1;
                    return a.getPath().compareTo(b.getPath());
                }
            });

            boolean openAi = !getApiUrl().contains("googleapis.com");
            int budget = openAi ? 30000 : 200000;
            if (budgetOverride > 0 && budgetOverride < budget) budget = budgetOverride;

            StringBuilder body = new StringBuilder();
            StringBuilder skipped = new StringBuilder();
            int included = 0;

            for (int i = 0; i < all.size(); i++) {
                File f = all.get(i);
                String rel = f.getCanonicalPath();
                if (rel.startsWith(rootCanon)) rel = rel.substring(rootCanon.length());
                rel = rel.replaceAll("^/+", "");

                if (body.length() >= budget) {
                    skipped.append(rel).append("\n");
                    continue;
                }
                String text = readFileText(f, 25000);
                if (body.length() + text.length() > budget && included > 0) {
                    skipped.append(rel).append("\n");
                    continue;
                }
                body.append("--- FILE: ").append(rel).append(" ---\n").append(text).append("\n");
                included++;
            }

            StringBuilder out = new StringBuilder();
            out.append("FULL PROJECT CONTENT (").append(included).append(" of ").append(all.size()).append(" files included below; you CAN read all of them):\n");
            out.append(body);
            if (skipped.length() > 0) {
                out.append("\nFILES NOT INCLUDED (too big for the size limit; if you need one, ask the user to mention its file name in the message):\n");
                out.append(skipped).append("\n");
            }
            return out.toString();
        } catch (Exception e) {
            return projectFilesText(projectPath);
        }
    }

    private Context findActivityContext() {
        Context act = context;
        while (act instanceof android.content.ContextWrapper) {
            if (act instanceof android.app.Activity) break;
            act = ((android.content.ContextWrapper) act).getBaseContext();
        }
        return act;
    }

    private void reflectToast(Context act, String msg) {
        try {
            java.lang.reflect.Method toastMethod = act.getClass().getDeclaredMethod("showCustomToast", String.class);
            toastMethod.setAccessible(true);
            toastMethod.invoke(act, msg);
        } catch (Exception e) {
        }
    }

    // ===================== DEEP SCAN =====================

    public void startAnalysis(final String consoleLogs) {
        final Context finalActContext = findActivityContext();
        reflectToast(finalActContext, "Performing Deep Architecture Scan...");

        final String prompt =
        "You are an elite, highly precise Android architecture analyzer. Your task is to perform a root-cause analysis on the provided build logs.\n\n" +
        "LOGS:\n" + consoleLogs + "\n\n" +
        "CRITICAL INSTRUCTIONS:\n" +
        "1. DEDUCE ARCHITECTURE: Automatically detect if this is a 'Legacy Android (No AndroidX)' or 'Modern Android (AndroidX)' project based on the errors.\n" +
        "2. NO CHAT: Do not say 'Hello' or 'Here is the analysis'.\n" +
        "3. FORMAT: Output exactly in this machine-readable pinpoint format:\n" +
        "PROJECT TYPE: [Legacy/Modern]\n" +
        "FILE: [Exact File Name]\n" +
        "LINE: [Exact Line Number]\n" +
        "ISSUE: [1-sentence deep technical root cause]\n" +
        "FIX: [1-sentence exact solution]";

        askAI(prompt, new AIResponseListener() {
            @Override
            public void onSuccess(String response) {
                reflectToast(finalActContext, "Deep Scan Complete!");
            }
            @Override
            public void onError(String error) {
                reflectToast(finalActContext, "Scan Error:\n" + error);
            }
        });
    }

    public void askAI(final String prompt, final AIResponseListener listener) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final String result = callModel(prompt);
                    mainHandler.post(new Runnable() { @Override public void run() { listener.onSuccess(result); } });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() { @Override public void run() { listener.onError("API Error:\n" + e.getMessage()); } });
                }
            }
        });
    }

    public void chat(final String projectPath, final File targetFile, final String userMessage, final AIResponseListener listener) {
        useProjectInternal(projectPath);
        if (isUndoTrigger(userMessage)) {
            undoLast(projectPath, listener);
            return;
        }
        if (isSaveTrigger(userMessage)) {
            executeSuperCommand(projectPath, targetFile, "", userMessage, "", listener);
            return;
        }

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File root = new File(projectPath);
                    String projType = detectProjectType(root);
                    boolean isAndroidX = isAndroidXProject(root);

                    StringBuilder prompt = new StringBuilder();
                    prompt.append("You are PrimX Copilot, an elite software architect and developer embedded inside the PrimX IDE.\n");
                    prompt.append("MODE: ARCHITECTURAL CHAT & FEATURE BUILDER.\n\n");
                    prompt.append("DETECTED PROJECT TYPE: ").append(projType).append("\n\n");

                    if ("ANDROID_APP".equals(projType)) {
                        prompt.append("--- ANDROID APPLICATION SPECIFICATIONS ---\n");
                        prompt.append("Flavour: ").append(isAndroidX ? "AndroidX (use androidx.* packages)" : "Support Library (use android.support.* packages)").append("\n");
                        prompt.append("Capabilities: Native Android Application compiled to APK.\n\n");
                        prompt.append("CRITICAL INSTRUCTIONS FOR FEATURE IMPLEMENTATION:\n");
                        prompt.append("When the user asks to implement or convert the project into a feature (e.g., 'is project ko music player banado', 'calculator bana do', 'notes app', 'video player', etc.):\n");
                        prompt.append("1. COMPREHENSIVE ARCHITECTURE: You MUST identify ALL files needed to make the feature 100% complete and working without missing pieces:\n");
                        prompt.append("   - AndroidManifest.xml: Add all required permissions (e.g. for music player: READ_EXTERNAL_STORAGE, READ_MEDIA_AUDIO, FOREGROUND_SERVICE, WAKE_LOCK) and register any Activity or Service.\n");
                        prompt.append("   - Layout XML: Create/update layouts in app/src/main/res/layout/ (e.g. activity_main.xml) with complete UI views, proper IDs, and clean layouts.\n");
                        prompt.append("   - Java source files: In app/src/main/java/<package_dir>/, write MainActivity.java with complete logic (MediaPlayer initialization, playback controls, runtime permissions, etc.) and any helper/service/model classes.\n");
                        prompt.append("   - Resources: Strings in app/src/main/res/values/strings.xml if needed.\n");
                        prompt.append("2. EXACT FILE HEADER FORMAT:\n");
                        prompt.append("   Every file MUST be output in its own ``` code block.\n");
                        prompt.append("   Immediately BEFORE each code block, write ONLY:\n");
                        prompt.append("   FILE: <relative/path/from/project/root>\n");
                        prompt.append("   Example:\n");
                        prompt.append("   FILE: app/src/main/AndroidManifest.xml\n   ```xml\n   ...\n   ```\n\n");
                        prompt.append("   FILE: app/src/main/res/layout/activity_main.xml\n   ```xml\n   ...\n   ```\n\n");
                        prompt.append("   FILE: app/src/main/java/com/primx/project/MainActivity.java\n   ```java\n   ...\n   ```\n");
                        prompt.append("3. NO PLACEHOLDERS: Output 100% complete files. NEVER write '// ... rest of code ...'. Write all imports, methods, tags.\n");
                        prompt.append("4. SAVE BUTTON INTEGRATION: The user has a dedicated 'SAVE TO PROJECT' button in the IDE. When clicked, all files with the FILE: headers will be automatically saved to their respective folders!\n\n");
                    } else if ("JAVA_CONSOLE".equals(projType)) {
                        prompt.append("--- JAVA CONSOLE APPLICATION SPECIFICATIONS ---\n");
                        prompt.append("Capabilities: Pure Standard Java Console / CLI Application running in terminal.\n\n");
                        prompt.append("CRITICAL CONSTRAINTS FOR JAVA CONSOLE:\n");
                        prompt.append("- THIS IS A TERMINAL JAVA CONSOLE PROGRAM, NOT AN ANDROID APP!\n");
                        prompt.append("- STRICTLY FORBIDDEN: NEVER output AndroidManifest.xml, res/layout XML, Activity, View, Bundle, or android.* imports! Those do NOT exist here and will fail to compile!\n");
                        prompt.append("- The entry point MUST be 'public static void main(String[] args)'.\n");
                        prompt.append("- User interaction must use java.util.Scanner and System.out.println.\n");
                        prompt.append("- If asked to build a feature (e.g. 'music player', 'calculator', 'game'):\n");
                        prompt.append("  Implement it as a full, rich Java Console application with interactive menus, commands, and logic (for music player: Scanner commands for play, pause, queue, playlist, and playback simulation or javax.sound.sampled.AudioSystem).\n");
                        prompt.append("- Output using FILE: <relative/path> (e.g. FILE: app/src/main/java/Main.java or FILE: Main.java) followed by ```java block.\n\n");
                    } else if ("PYTHON_CONSOLE".equals(projType)) {
                        prompt.append("--- PYTHON 3 CONSOLE PROGRAM ---\n");
                        prompt.append("Output standard Python 3 code in main.py with FILE: main.py followed by ```python block.\n\n");
                    } else if ("WEB_PROJECT".equals(projType)) {
                        prompt.append("--- WEB APPLICATION (HTML/CSS/JS) ---\n");
                        prompt.append("Output index.html, style.css, script.js using respective FILE: paths and code blocks.\n\n");
                    }

                    prompt.append("GENERAL CHAT GUIDELINES:\n");
                    prompt.append("1. Reply in the same language/style the user writes (Hindi, Hinglish or English). Keep it clear and concise.\n");
                    prompt.append("2. When asked to implement a feature, give a 1-2 sentence overview of what files you will create/modify, then immediately provide the complete files with their FILE: paths.\n");
                    prompt.append("3. Do NOT output @@FILE@@ blocks in chat mode (use normal ``` blocks with FILE: on the line right before each block).\n");
                    prompt.append("4. Every ``` code block must contain the code of EXACTLY ONE file.\n");
                    prompt.append("5. Do NOT write 'saved to project' in your text; the IDE provides a dedicated button for that.\n\n");
                    prompt.append("6. You are given the FULL PROJECT CONTENT below. You CAN read every file in it. NEVER say you can only see file names.\n\n");

                    prompt.append(buildProjectContext(projectPath, userMessage)).append("\n");
                    if (targetFile != null && targetFile.exists()) {
                        prompt.append("CURRENTLY OPEN FILE: ").append(targetFile.getName()).append("\n\n");
                    }
                    if (hasHistory()) {
                        prompt.append("CONVERSATION SO FAR:\n").append(historyAsText());
                    }
                    prompt.append("USER: ").append(userMessage).append("\n\nASSISTANT:");

                    final String reply = callModel(prompt.toString());

                    addHistory("user", userMessage);
                    addHistory("assistant", reply);

                    final String shown = needsSaveHint(reply) ? reply + SAVE_HINT : reply;
                    mainHandler.post(new Runnable() { @Override public void run() { listener.onSuccess(shown); } });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() { @Override public void run() { listener.onError("Chat Error:\n" + e.getMessage()); } });
                }
            }
        });
    }

    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_BACKUPS = 10;

    public void executeSuperCommand(final String projectPath, final File targetFile, final String errorMessage, final String userInstruction, final String deepScanContext, final AIResponseListener listener) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    useProjectInternal(projectPath);
                    final boolean saveMode = isSaveTrigger(userInstruction);

                    if (saveMode && !hasHistory()) {
                        postError(listener, "Abhi koi chat nahi hui. Pehle chat me batao kya banana/badalna hai, phir 'saved to project' likho.");
                        return;
                    }
                    if (projectPath == null || projectPath.length() == 0) {
                        postError(listener, "Project path nahi mila.");
                        return;
                    }

                    File root = new File(projectPath);
                    String basePrompt = buildFixPrompt(root, targetFile, errorMessage, userInstruction, deepScanContext, saveMode);

                    Map<String, String> good = null;
                    List<String> problems = new ArrayList<String>();
                    String prompt = basePrompt;

                    if (saveMode && hasHistory()) {
                        String lastAssistantMsg = getLastAssistantMessage();
                        if (lastAssistantMsg != null && lastAssistantMsg.length() > 0) {
                            Map<String, String> chatFiles = parseFiles(lastAssistantMsg);
                            if (!chatFiles.isEmpty()) {
                                List<String> chatProblems = new ArrayList<String>();
                                Map<String, String> chatGood = resolveAndValidate(root, chatFiles, chatProblems);
                                if (chatProblems.isEmpty() && chatGood != null && !chatGood.isEmpty()) {
                                    good = chatGood;
                                }
                            }
                        }
                    }

                    if (good == null) {
                        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                            String resp = callModel(prompt, true);
                            problems.clear();

                            Map<String, String> files = parseFiles(resp);
                            if (files.isEmpty() && !saveMode && targetFile != null && targetFile.exists() && looksLikeCode(resp)) {
                                String rel = relativePath(root, targetFile);
                                if (rel != null) files.put(rel, stripFences(resp));
                            }

                            if (files.isEmpty()) {
                                problems.add("Your answer had NO @@FILE:path@@ ... @@ENDFILE@@ block. Output nothing except such blocks.");
                            } else {
                                good = resolveAndValidate(root, files, problems);
                            }

                            if (problems.isEmpty() && good != null && !good.isEmpty()) break;

                            good = null;
                            StringBuilder retry = new StringBuilder(basePrompt);
                            retry.append("\n\n--- YOUR PREVIOUS ANSWER WAS REJECTED BY THE IDE VALIDATOR (attempt ").append(attempt).append(" of ").append(MAX_ATTEMPTS).append(") ---\n");
                            for (int i = 0; i < problems.size(); i++) retry.append("- ").append(problems.get(i)).append("\n");
                            retry.append("Fix ALL of these problems. Output ONLY the corrected @@FILE:path@@ blocks (complete files, no placeholders, no explanation). ");
                            retry.append("If the answer was cut off, output ONLY the files that really must change.\n");
                            prompt = retry.toString();
                        }
                    }

                    if (good == null || good.isEmpty()) {
                        StringBuilder err = new StringBuilder("AI sahi code nahi likh paya, isliye koi file nahi badli (aapka code safe hai).\n");
                        for (int i = 0; i < problems.size() && i < 6; i++) err.append("\u2022 ").append(problems.get(i)).append("\n");
                        err.append("RETRY dabao ya instruction me thoda aur detail likho.");
                        postError(listener, err.toString().trim());
                        return;
                    }

                    File backupDir = createBackup(root, good.keySet());
                    final List<String> savedPaths = new ArrayList<String>();
                    try {
                        for (Map.Entry<String, String> e : good.entrySet()) {
                            File nf = new File(root, e.getKey());
                            File parent = nf.getParentFile();
                            if (parent != null && !parent.exists()) parent.mkdirs();
                            writeFileText(nf, e.getValue());
                            savedPaths.add(e.getKey());
                        }
                    } catch (Exception we) {
                        restoreBackup(root, backupDir);
                        postError(listener, "File save nahi ho payi, purana code wapas rakh diya: " + we.getMessage());
                        return;
                    }

                    StringBuilder summary = new StringBuilder("\u2705 Saved to project:\n");
                    for (int i = 0; i < savedPaths.size(); i++) summary.append("\u2022 ").append(savedPaths.get(i)).append("\n");
                    summary.append("\n(Galat laga? Chat me 'undo' likho, purana code wapas aa jayega.)");
                    final String summaryText = summary.toString().trim();

                    if (saveMode) {
                        addHistory("user", userInstruction);
                        addHistory("assistant", summaryText);
                    }

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Context act = findActivityContext();
                            if (act instanceof android.app.Activity) {
                                try {
                                    if (targetFile != null) {
                                        java.lang.reflect.Method method = act.getClass().getDeclaredMethod("openFileInEditor", File.class);
                                        method.setAccessible(true);
                                        method.invoke(act, targetFile);
                                    }
                                } catch (Exception e) {
                                }
                                reflectToast(act, "AI Code Applied & Editor Reloaded!");
                            }
                            listener.onSuccess(summaryText);
                        }
                    });
                } catch (final Exception e) {
                    postError(listener, "Processor Error: " + e.getMessage());
                }
            }
        });
    }

    private void postError(final AIResponseListener listener, final String msg) {
        mainHandler.post(new Runnable() { @Override public void run() { listener.onError(msg); } });
    }

    private String buildFixPrompt(File root, File targetFile, String errorMessage, String userInstruction, String deepScanContext, boolean saveMode) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("You are the PRIMX AI Copilot - a HEADLESS code generation engine inside the PrimX Android IDE. ");
        prompt.append("You are NOT a chatbot. You are a senior Android developer working silently. Your output is parsed by a program.\n\n");

        if (saveMode) {
            prompt.append("TASK: Implement ALL code and file changes that were discussed/agreed in the CONVERSATION below. ");
            prompt.append("Create new files and fully rewrite every file that must change. Use the latest version of the code from the conversation.\n");
            if (userInstruction != null && userInstruction.trim().length() > 0) {
                prompt.append("USER NOTE: ").append(userInstruction).append("\n");
            }
            prompt.append("\nCONVERSATION:\n").append(historyAsText()).append("\n");
        } else if (userInstruction != null && !userInstruction.isEmpty()) {
            prompt.append("USER COMMAND:\n").append(userInstruction).append("\n\n");
        }

        boolean hasErrors = false;
        if (deepScanContext != null && !deepScanContext.isEmpty()) {
            prompt.append("DEEP SCAN ISSUES TO FIX FOR ENTIRE PROJECT:\n").append(deepScanContext).append("\nFix every logical and architectural issue. Trace all linked methods.\n\n");
        } else if (errorMessage != null && !errorMessage.isEmpty() && targetFile != null) {
            hasErrors = true;
            prompt.append("COMPILER ERROR(S) TO FIX in file ").append(targetFile.getName()).append(":\n").append(errorMessage).append("\n\n");
            prompt.append("IMPORTANT: Compiler errors can be a SYMPTOM. If the error lines do not match the file type (for example Java code inside an .xml file), ");
            prompt.append("the file content is corrupted: rewrite the WHOLE file correctly for its type, and use the other project files to know what it must contain.\n\n");
        }

        boolean focused = hasErrors || (userInstruction != null && !userInstruction.isEmpty() && !saveMode);
        int budget = focused ? 60000 : 120000;
        String uMsg = (userInstruction == null ? "" : userInstruction) + (targetFile != null ? " " + targetFile.getName() : "");
        prompt.append(buildProjectContext(root.getPath(), uMsg, budget));
        prompt.append("\n(Use the exact relative paths shown above when modifying existing files.)\n\n");

        if (targetFile != null && targetFile.exists()) {
            String content = readFileText(targetFile, 200000);
            String tn = targetFile.getName().toLowerCase();
            String trimmed = content.trim();
            boolean corrupted = (tn.endsWith(".xml") && trimmed.length() > 0 && !trimmed.startsWith("<"))
            || (tn.endsWith(".java") && trimmed.startsWith("<"));
            if (corrupted) {
                prompt.append("WARNING: ").append(targetFile.getName()).append(" is CORRUPTED - its content is the wrong kind of code for a ")
                .append(tn.endsWith(".xml") ? ".xml" : ".java").append(" file. Rewrite it completely as valid ")
                .append(tn.endsWith(".xml") ? "XML" : "Java").append(".\n\n");
            }
            if (hasErrors) {
                String snip = errorSnippets(content, errorMessage);
                if (snip.length() > 0) prompt.append("CODE AROUND THE ERROR LINES (line numbers are for reference only, do NOT write them in your answer):\n").append(snip).append("\n");
            }
            prompt.append("ACTIVE FILE CONTENT (").append(targetFile.getName()).append("):\n").append(content).append("\n\n");

            String hints = layoutHints(root, targetFile);
            if (hints.length() > 0) prompt.append(hints).append("\n\n");
        }

        prompt.append("--- CRITICAL BEHAVIORAL RULES (FAILURE IS NOT AN OPTION) ---\n");
        prompt.append("RULE #1: ZERO CHAT. Your output is 100% code in @@FILE@@ blocks. NO greetings, NO explanations, NO markdown fences around the whole answer.\n\n");
        prompt.append("RULE #2: EXACT FILE FORMAT. Every file MUST be wrapped exactly like this:\n");
        prompt.append("@@FILE:app/src/main/java/com/primx/project/MyClass.java@@\n");
        prompt.append("package com.primx.project;\n");
        prompt.append("// ...COMPLETE file content...\n");
        prompt.append("@@ENDFILE@@\n");
        prompt.append("One block = ONE file. The content must match the file extension: .java = only Java, .xml = only XML (starts with '<'), .gradle = only Gradle.\n\n");
        prompt.append("RULE #3: MULTI-FILE SYNC. If you create an Activity, ALSO output its XML layout AND the updated AndroidManifest.xml. If you rename a view id, update every Java file that uses it.\n\n");
        prompt.append("RULE #4: LIBRARIES. The IDE already has AndroidX, Material, RecyclerView, CardView etc. pre-bundled. DO NOT add `implementation` lines for them; just import and use. ");
        prompt.append("Keep the project's existing style (check whether the existing code uses androidx or android.support and stay consistent).\n\n");
        prompt.append("RULE #5: NO BLOAT. Never copy library source code or huge style files. Keep XML resources minimal.\n\n");
        prompt.append("RULE #6: NO PLACEHOLDERS. NEVER write '...rest of the code...' or '// existing code'. Every file you output must be the ENTIRE file. Output ONLY files that really need to change.\n\n");
        prompt.append("RULE #7: IMPORTS & PACKAGE. Every Java file starts with the correct `package` (same as its folder) and has all imports. The public class name must equal the file name. Prefer anonymous classes over lambdas.\n\n");
        prompt.append("RULE #8: SELF-CHECK before answering: braces/parentheses balanced, XML tags closed, every R.id used in Java exists in the layout, every Activity is in AndroidManifest.xml.\n\n");
        prompt.append("NOW OUTPUT ONLY THE @@FILE@@ BLOCKS:\n");
        return prompt.toString();
    }

    private static String errorSnippets(String content, String err) {
        if (content == null || err == null) return "";
        String[] lines = content.split("\n", -1);
        java.util.TreeSet<Integer> hits = new java.util.TreeSet<Integer>();
        Matcher m = Pattern.compile("(?i)(?:line\\s*[:#]?\\s*|:)(\\d{1,5})(?=\\D|$)").matcher(err);
        while (m.find() && hits.size() < 6) {
            int n = Integer.parseInt(m.group(1));
            if (n >= 1 && n <= lines.length) hits.add(n);
        }
        StringBuilder sb = new StringBuilder();
        for (Integer n : hits) {
            int from = Math.max(1, n - 5), to = Math.min(lines.length, n + 5);
            sb.append("[around line ").append(n).append("]\n");
            for (int i = from; i <= to; i++) {
                sb.append(i == n ? ">> " : "   ").append(i).append(" | ").append(lines[i - 1]).append("\n");
            }
        }
        return sb.toString();
    }

    private String layoutHints(File root, File target) {
        try {
            String path = target.getPath().replace('\\', '/');
            String name = target.getName();
            if (!name.endsWith(".xml") || !path.contains("/res/layout")) return "";
            String layoutName = name.substring(0, name.length() - 4);
            List<File> all = new ArrayList<File>();
            collectAllFiles(root, all);
            java.util.TreeSet<String> ids = new java.util.TreeSet<String>();
            StringBuilder users = new StringBuilder();
            Pattern idP = Pattern.compile("R\\.id\\.([A-Za-z0-9_]+)");
            for (int i = 0; i < all.size(); i++) {
                File f = all.get(i);
                if (!f.getName().endsWith(".java")) continue;
                String txt = readFileText(f, 100000);
                if (txt.contains("R.layout." + layoutName)) {
                    users.append(f.getName()).append(" ");
                    Matcher m = idP.matcher(txt);
                    while (m.find() && ids.size() < 80) ids.add(m.group(1));
                }
            }
            if (users.length() == 0) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("LAYOUT CONTRACT: ").append(name).append(" is used by: ").append(users.toString().trim()).append(".\n");
            if (!ids.isEmpty()) {
                sb.append("The layout MUST contain views with these android:id values (the Java code uses them): ");
                boolean first = true;
                for (String id : ids) { if (!first) sb.append(", "); sb.append(id); first = false; }
                sb.append("\nUse a compatible view type for each id (e.g. a Button for click handlers, TextView for setText).");
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static Map<String, String> parseFiles(String resp) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (resp == null) return out;

        Matcher m = Pattern.compile("@@FILE:\\s*(.*?)\\s*@@(.*?)(?:@@ENDFILE@@|(?=@@FILE:)|\\z)", Pattern.DOTALL).matcher(resp);
        while (m.find()) {
            String path = m.group(1).trim();
            String code = stripFences(m.group(2).trim());
            if (path.length() > 0 && code.length() > 0) out.put(path, code);
        }
        if (!out.isEmpty()) return out;

        Matcher m2 = Pattern.compile("(?ms)^[ \\t>*#\\-]*(?:FILE|File|file)\\s*:\\s*[`*\\s]*([^\\s`*:]+)[`*\\s:]*\\r?\\n+```[a-zA-Z]*\\r?\\n(.*?)```").matcher(resp);
        while (m2.find()) {
            String code = m2.group(2).trim();
            if (code.length() > 0) out.put(m2.group(1).trim(), code);
        }
        return out;
    }

    private static String relativePath(File root, File f) {
        try {
            String rc = root.getCanonicalPath();
            String fc = f.getCanonicalPath();
            if (!fc.startsWith(rc)) return null;
            return fc.substring(rc.length()).replaceAll("^/+", "");
        } catch (Exception e) {
            return null;
        }
    }

    private File findUniqueByName(File root, String name) {
        List<File> all = new ArrayList<File>();
        collectAllFiles(root, all);
        File found = null;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getName().equals(name)) {
                if (found != null) return null;
                found = all.get(i);
            }
        }
        return found;
    }

    private static boolean isAllowedExt(String lower) {
        return lower.endsWith(".java") || lower.endsWith(".xml") || lower.endsWith(".gradle")
        || lower.endsWith(".properties") || lower.endsWith(".json") || lower.endsWith(".txt")
        || lower.endsWith(".md") || lower.endsWith(".pro");
    }

    private Map<String, String> resolveAndValidate(File root, Map<String, String> files, List<String> problems) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        try {
            String rootCanon = root.getCanonicalPath();
            for (Map.Entry<String, String> e : files.entrySet()) {
                String p = e.getKey().replace('\\', '/').replaceAll("[`*\"']", "").trim();
                while (p.startsWith("./")) p = p.substring(2);
                p = p.replaceAll("^/+", "");
                String code = e.getValue();

                if (p.length() == 0 || !isAllowedExt(p.toLowerCase())) {
                    problems.add("Bad or unsupported file path '" + e.getKey() + "'. Use a full relative path like app/src/main/java/...");
                    continue;
                }

                File f = new File(root, p);
                if (!f.exists() && p.indexOf('/') < 0) {
                    File byName = findUniqueByName(root, p);
                    if (byName != null) {
                        String rel = relativePath(root, byName);
                        if (rel != null) { p = rel; f = byName; }
                    } else if (p.endsWith(".java")) {
                        String pkg = packageOf(code);
                        String jr = javaRootOf(root);
                        if (pkg != null && jr != null) {
                            p = jr + pkg.replace('.', '/') + "/" + p;
                            f = new File(root, p);
                        } else if (jr != null) {
                            p = jr + p;
                            f = new File(root, p);
                        }
                    } else if (p.endsWith(".xml")) {
                        if (p.startsWith("activity_") || p.startsWith("fragment_") || p.startsWith("item_") || p.startsWith("dialog_") || p.startsWith("view_")) {
                            File layoutDir = new File(root, "app/src/main/res/layout");
                            if (!layoutDir.exists()) layoutDir = new File(root, "src/main/res/layout");
                            if (!layoutDir.exists()) layoutDir = new File(root, "res/layout");
                            String rel = relativePath(root, layoutDir);
                            if (rel != null) {
                                p = rel + "/" + p;
                                f = new File(root, p);
                            }
                        } else if (p.equals("AndroidManifest.xml")) {
                            File mFile = new File(root, "app/src/main/AndroidManifest.xml");
                            if (!mFile.exists()) mFile = new File(root, "src/main/AndroidManifest.xml");
                            if (!mFile.exists()) mFile = new File(root, "AndroidManifest.xml");
                            String rel = relativePath(root, mFile);
                            if (rel != null) {
                                p = rel;
                                f = mFile;
                            }
                        } else if (p.startsWith("ic_") || p.startsWith("bg_")) {
                            File drawDir = new File(root, "app/src/main/res/drawable");
                            if (!drawDir.exists()) drawDir = new File(root, "src/main/res/drawable");
                            if (!drawDir.exists()) drawDir = new File(root, "res/drawable");
                            String rel = relativePath(root, drawDir);
                            if (rel != null) {
                                p = rel + "/" + p;
                                f = new File(root, p);
                            }
                        }
                    }
                }
                if (p.indexOf('/') < 0 && !f.exists()) {
                    problems.add("Path '" + p + "' is ambiguous. Write the FULL relative path (e.g. app/src/main/res/layout/" + p + ").");
                    continue;
                }
                if (!f.getCanonicalPath().startsWith(rootCanon)) {
                    problems.add("Path '" + p + "' is outside the project.");
                    continue;
                }

                String bad = validateCode(p, code);
                if (bad == null && f.exists() && f.length() > 3000 && code.length() < f.length() * 0.3) {
                    String old = readFileText(f, 400000);
                    if (validateCode(p, old) == null) {
                        bad = "Output is much shorter than the existing file - looks incomplete. Output the COMPLETE file.";
                    }
                }
                if (bad != null) {
                    problems.add(p + ": " + bad);
                    continue;
                }
                out.put(p, code);
            }
        } catch (Exception ex) {
            problems.add("Validation error: " + ex.getMessage());
        }
        return out;
    }

    private static String packageOf(String code) {
        Matcher m = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z0-9_.]+)\\s*;").matcher(code);
        return m.find() ? m.group(1) : null;
    }

    private String javaRootOf(File root) {
        List<File> all = new ArrayList<File>();
        collectAllFiles(root, all);
        for (int i = 0; i < all.size(); i++) {
            String rel = relativePath(root, all.get(i));
            if (rel != null && rel.endsWith(".java")) {
                int idx = rel.indexOf("/java/");
                if (idx >= 0) return rel.substring(0, idx + 6);
            }
        }
        return null;
    }

    private static final Pattern PLACEHOLDER = Pattern.compile(
    "(?im)^\\s*(?://|/\\*|\\*|<!--|#)\\s*(?:\\.\\.\\.|\\u2026|(?:rest|remaining|existing|previous|other|same)\\b.*\\b(?:code|methods?|file|content|as before)\\b|unchanged\\b)");

    private static String validateCode(String path, String code) {
        String lower = path.toLowerCase();
        String t = code == null ? "" : code.trim();
        if (t.length() == 0) return "File is empty.";
        if (t.startsWith("\uFEFF")) t = t.substring(1).trim();
        if (PLACEHOLDER.matcher(code).find()) return "Contains a placeholder like '...rest of code' or '// existing code'. Write the full file.";

        if (lower.endsWith(".xml")) {
            if (!t.startsWith("<")) return "This is an .xml file but the content is not XML (it must start with '<'). Do not put Java code in an XML file.";
            if (t.contains("```")) return "Contains markdown fences (```).";
            try {
                DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                javax.xml.parsers.DocumentBuilder b = f.newDocumentBuilder();
                b.setErrorHandler(null);
                b.parse(new InputSource(new StringReader(t)));
            } catch (org.xml.sax.SAXParseException se) {
                return "XML is not well-formed at line " + se.getLineNumber() + ": " + se.getMessage();
            } catch (Exception ex) {
                return "XML is not well-formed: " + ex.getMessage();
            }
            return null;
        }

        if (lower.endsWith(".java")) {
            if (t.startsWith("<")) return "This is a .java file but the content looks like XML.";
            if (t.contains("```")) return "Contains markdown fences (```).";
            if (!Pattern.compile("\\b(class|interface|enum|@interface)\\s+\\w+").matcher(t).find())
            return "No class/interface/enum declaration found.";
            String br = checkBraces(t);
            if (br != null) return br;

            String name = path.substring(path.lastIndexOf('/') + 1).replace(".java", "");
            Matcher pc = Pattern.compile("public\\s+(?:final\\s+|abstract\\s+|static\\s+)*(?:class|interface|enum)\\s+(\\w+)").matcher(t);
            if (pc.find() && !pc.group(1).equals(name))
            return "Public class is named '" + pc.group(1) + "' but the file is " + name + ".java. They must match.";

            String pkg = packageOf(t);
            int idx = path.lastIndexOf("/java/");
            if (idx >= 0) {
                String dir = path.substring(idx + 6, path.lastIndexOf('/') < idx + 6 ? idx + 6 : path.lastIndexOf('/')).replace('/', '.');
                if (pkg == null && dir.length() > 0) return "Missing `package " + dir + ";` line at the top.";
                if (pkg != null && !pkg.equals(dir)) return "Package '" + pkg + "' does not match the folder (should be '" + dir + "').";
            }
            return null;
        }

        if (lower.endsWith(".gradle") || lower.endsWith(".pro")) {
            if (t.contains("```")) return "Contains markdown fences (```).";
            if (lower.endsWith(".gradle")) return checkBraces(t);
        }
        return null;
    }

    private static String checkBraces(String s) {
        char[] stack = new char[512];
        int[] stackLine = new int[512];
        int sp = 0, line = 1, i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\n') { line++; i++; continue; }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) {
                    if (s.charAt(i) == '\n') line++;
                    i++;
                }
                i += 2;
                continue;
            }
            if (c == '"' || c == '\'') {
                char q = c;
                i++;
                while (i < n && s.charAt(i) != q) {
                    if (s.charAt(i) == '\\') { i += 2; continue; }
                    if (s.charAt(i) == '\n') break;
                    i++;
                }
                i++;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                if (sp >= stack.length) return null;
                stack[sp] = c; stackLine[sp] = line; sp++;
            } else if (c == ')' || c == ']' || c == '}') {
                char open = c == ')' ? '(' : (c == ']' ? '[' : '{');
                if (sp == 0) return "Extra '" + c + "' at line " + line + ".";
                if (stack[sp - 1] != open) return "Mismatched '" + c + "' at line " + line + " (expected closing for '" + stack[sp - 1] + "' opened at line " + stackLine[sp - 1] + ").";
                sp--;
            }
            i++;
        }
        if (sp > 0) return "Missing closing for '" + stack[sp - 1] + "' opened at line " + stackLine[sp - 1] + " - the code looks cut off or incomplete.";
        return null;
    }

    // ===================== BACKUP + UNDO =====================

    private File backupBase(File root) {
        File base = new File(appCtx != null ? appCtx.getFilesDir() : context.getFilesDir(), "primx_backup/" + Integer.toHexString(root.getAbsolutePath().hashCode()));
        if (!base.exists()) base.mkdirs();
        return base;
    }

    private File createBackup(File root, java.util.Collection<String> relPaths) {
        File dir = new File(backupBase(root), String.valueOf(System.currentTimeMillis()));
        dir.mkdirs();
        StringBuilder created = new StringBuilder();
        for (String rel : relPaths) {
            try {
                File src = new File(root, rel);
                if (src.exists()) {
                    File dst = new File(dir, "files/" + rel);
                    if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
                    writeFileText(dst, readFileText(src, 2000000));
                } else {
                    created.append(rel).append("\n");
                }
            } catch (Exception e) {
            }
        }
        try { writeFileText(new File(dir, "_created.txt"), created.toString()); } catch (Exception e) { }

        File[] all = backupBase(root).listFiles();
        if (all != null && all.length > MAX_BACKUPS) {
            Arrays.sort(all, new Comparator<File>() {
                @Override public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
            });
            for (int i = 0; i < all.length - MAX_BACKUPS; i++) deleteRecursive(all[i]);
        }
        return dir;
    }

    private static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (int i = 0; i < kids.length; i++) deleteRecursive(kids[i]);
        f.delete();
    }

    private void collectBackupFiles(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (int i = 0; i < kids.length; i++) {
            if (kids[i].isDirectory()) collectBackupFiles(kids[i], out); else out.add(kids[i]);
        }
    }

    private List<String> restoreBackup(File root, File backupDir) {
        List<String> restored = new ArrayList<String>();
        try {
            File filesDir = new File(backupDir, "files");
            List<File> saved = new ArrayList<File>();
            collectBackupFiles(filesDir, saved);
            String prefix = filesDir.getAbsolutePath() + "/";
            for (int i = 0; i < saved.size(); i++) {
                String rel = saved.get(i).getAbsolutePath().substring(prefix.length());
                File dst = new File(root, rel);
                if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
                writeFileText(dst, readFileText(saved.get(i), 2000000));
                restored.add(rel);
            }
            File createdList = new File(backupDir, "_created.txt");
            if (createdList.exists()) {
                String[] lines = readFileText(createdList, 100000).split("\n");
                for (int i = 0; i < lines.length; i++) {
                    String rel = lines[i].trim();
                    if (rel.length() > 0) {
                        File nf = new File(root, rel);
                        if (nf.exists() && nf.delete()) restored.add(rel + " (removed)");
                    }
                }
            }
        } catch (Exception e) {
        }
        return restored;
    }

    private static boolean isUndoTrigger(String msg) {
        if (msg == null) return false;
        String s = msg.toLowerCase().replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
        return s.equals("undo") || s.equals("undo last") || s.equals("undo last change") || s.equals("undo changes");
    }

    private void undoLast(final String projectPath, final AIResponseListener listener) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File root = new File(projectPath);
                    File[] all = backupBase(root).listFiles();
                    if (all == null || all.length == 0) {
                        postError(listener, "Wapas karne ke liye koi backup nahi mila.");
                        return;
                    }
                    Arrays.sort(all, new Comparator<File>() {
                        @Override public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
                    });
                    File last = all[all.length - 1];
                    List<String> restored = restoreBackup(root, last);
                    deleteRecursive(last);
                    final StringBuilder sb = new StringBuilder("\u21A9\uFE0F Undo ho gaya. Wapas aayi files:\n");
                    for (int i = 0; i < restored.size(); i++) sb.append("\u2022 ").append(restored.get(i)).append("\n");
                    final String text = sb.toString().trim();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Context act = findActivityContext();
                            reflectToast(act, "Undo done!");
                            listener.onSuccess(text);
                        }
                    });
                } catch (Exception e) {
                    postError(listener, "Undo Error: " + e.getMessage());
                }
            }
        });
    }

    private static String stripFences(String code) {
        code = code.replaceAll("^```[a-zA-Z]*\\s*", "");
        code = code.replaceAll("```\\s*$", "");
        return code.trim();
    }

    private static boolean looksLikeCode(String text) {
        return text != null && (text.contains("{") || text.contains("<"));
    }

    private static void writeFileText(File f, String text) throws Exception {
        Writer writer = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        writer.write(text);
        writer.flush();
        writer.close();
    }
}

