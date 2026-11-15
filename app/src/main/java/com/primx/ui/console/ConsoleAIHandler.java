package com.primx.ui.console;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.primx.ai.AI;
import com.primx.utils.Setting;
import com.primx.ui.mainactivity.MainActivity;
import com.primx.R;

public class ConsoleAIHandler {

    private Console console;

    private static final int C_KEYWORD = 0xFF569CD6;
    private static final int C_CONTROL = 0xFFC586C0;
    private static final int C_STRING  = 0xFFCE9178;
    private static final int C_COMMENT = 0xFF6A9955;
    private static final int C_NUMBER  = 0xFFB5CEA8;
    private static final int C_TYPE    = 0xFF4EC9B0;
    private static final int C_METHOD  = 0xFFDCDCAA;
    private static final int C_ANNOT   = 0xFFDCDCAA;
    private static final int C_ATTR    = 0xFF9CDCFE;
    private static final int C_PLAIN   = 0xFFD4D4D4;
    private static final int C_BLUE    = 0xFF2196F3;

    // ✅ FIX: anonymous inner class हटा दी — अब static block से initialize
    private static final HashSet<String> JAVA_CONTROL = new HashSet<String>();
    private static final HashSet<String> JAVA_KEYWORDS = new HashSet<String>();

    static {
        // JAVA_CONTROL
        JAVA_CONTROL.add("if");       JAVA_CONTROL.add("else");
        JAVA_CONTROL.add("for");      JAVA_CONTROL.add("while");
        JAVA_CONTROL.add("do");       JAVA_CONTROL.add("switch");
        JAVA_CONTROL.add("case");     JAVA_CONTROL.add("default");
        JAVA_CONTROL.add("break");    JAVA_CONTROL.add("continue");
        JAVA_CONTROL.add("return");   JAVA_CONTROL.add("try");
        JAVA_CONTROL.add("catch");    JAVA_CONTROL.add("finally");
        JAVA_CONTROL.add("throw");    JAVA_CONTROL.add("throws");
        JAVA_CONTROL.add("import");   JAVA_CONTROL.add("package");
        JAVA_CONTROL.add("assert");

        // JAVA_KEYWORDS
        JAVA_KEYWORDS.add("abstract");     JAVA_KEYWORDS.add("boolean");
        JAVA_KEYWORDS.add("byte");         JAVA_KEYWORDS.add("char");
        JAVA_KEYWORDS.add("class");        JAVA_KEYWORDS.add("const");
        JAVA_KEYWORDS.add("double");       JAVA_KEYWORDS.add("enum");
        JAVA_KEYWORDS.add("extends");      JAVA_KEYWORDS.add("final");
        JAVA_KEYWORDS.add("float");        JAVA_KEYWORDS.add("goto");
        JAVA_KEYWORDS.add("implements");   JAVA_KEYWORDS.add("instanceof");
        JAVA_KEYWORDS.add("int");          JAVA_KEYWORDS.add("interface");
        JAVA_KEYWORDS.add("long");         JAVA_KEYWORDS.add("native");
        JAVA_KEYWORDS.add("new");          JAVA_KEYWORDS.add("private");
        JAVA_KEYWORDS.add("protected");    JAVA_KEYWORDS.add("public");
        JAVA_KEYWORDS.add("short");        JAVA_KEYWORDS.add("static");
        JAVA_KEYWORDS.add("strictfp");     JAVA_KEYWORDS.add("super");
        JAVA_KEYWORDS.add("synchronized"); JAVA_KEYWORDS.add("this");
        JAVA_KEYWORDS.add("transient");    JAVA_KEYWORDS.add("void");
        JAVA_KEYWORDS.add("volatile");     JAVA_KEYWORDS.add("true");
        JAVA_KEYWORDS.add("false");        JAVA_KEYWORDS.add("null");
        JAVA_KEYWORDS.add("var");
    }

    private static final Pattern JAVA_TOKEN = Pattern.compile(
    "(/\\*[\\s\\S]*?(?:\\*/|$))|(//[^\\n]*)|(\"(?:\\\\.|[^\"\\\\\\n])*\"?)|('(?:\\\\.|[^'\\\\\\n])*'?)"
    + "|(@[A-Za-z_]\\w*)|(\\b0[xX][0-9a-fA-F]+[lL]?\\b|\\b\\d+(?:\\.\\d+)?[fFdDlL]?\\b)|([A-Za-z_$][\\w$]*)");

    private static final Pattern XML_TOKEN = Pattern.compile(
    "(<!--[\\s\\S]*?(?:-->|$))|(</?[A-Za-z_][\\w:.\\-]*|\\?>|/?>|<\\?[A-Za-z]+)|(\"[^\"]*\"?|'[^']*'?)|([A-Za-z_][\\w:.\\-]*)(?=\\s*=)");

    private static final Pattern FILE_LINE = Pattern.compile(
    "(?i)^[\\s*`#>_\\-]*file\\s*:\\s*[*`\\s\"']*(.*?)[*`\\s\"':]*$");
    private static final Pattern NAME_ONLY_LINE = Pattern.compile(
    "(?i)^[\\s*`#>_\\-]*([\\w./\\\\\\-]+\\.(?:java|xml|gradle|kt|json|txt|md|properties|pro))[\\s*`:)\\-]*$");
    private static final Pattern CLASS_DECL = Pattern.compile("\\b(?:class|interface|enum)\\s+([A-Za-z_]\\w*)");

    private static class ChatSeg {
        boolean code = false;
        String text = "";
        String shown = "";
        String name = "";
        String lang = "java";
    }

    public ConsoleAIHandler(Console console) {
        this.console = console;
    }

    public void triggerAutoFixByAI(final Context context) {
        final boolean hasErrors = !console.errorMap.isEmpty();
        File tempFile = null;
        String tempError = "";
        String projectPath = "";
        File activeEditorFile = null;

        Context actContext = context;
        while (actContext instanceof ContextWrapper) {
            if (actContext instanceof Activity) break;
            actContext = ((ContextWrapper) actContext).getBaseContext();
        }

        try {
            java.lang.reflect.Field fieldFile = actContext.getClass().getDeclaredField("currentEditingFile");
            fieldFile.setAccessible(true);
            activeEditorFile = (File) fieldFile.get(actContext);

            java.lang.reflect.Field fieldPath = actContext.getClass().getDeclaredField("currentProjectPath");
            fieldPath.setAccessible(true);
            projectPath = (String) fieldPath.get(actContext);
        } catch (Exception e) {}

        if (hasErrors) {
            for (String path : console.errorMap.keySet()) {
                if (path.equals("Compiler Error") || path.equals("Build System") || path.startsWith("RAW ERROR")) {
                    tempError = console.errorMap.get(path).get(0).message;
                    continue;
                }
                List<Console.ErrorItem> list = console.errorMap.get(path);
                for (Console.ErrorItem item : list) {
                    if (!item.isWarning) {
                        tempFile = new File(path);
                        tempError = item.message;
                        break;
                    }
                }
                if (tempFile != null) break;
            }
        }

        if (tempFile == null || !tempFile.exists()) {
            tempFile = activeEditorFile;
        }

        final File fileToAnalyze = tempFile;
        final String knownError = tempError;

        if (projectPath == null || projectPath.isEmpty()) {
            projectPath = actContext.getFilesDir().getAbsolutePath();
        }
        final String finalProjectPath = projectPath;

        boolean isLight = Setting.isLight(actContext);
        int themeStyleId = actContext.getResources().getIdentifier(isLight ? "Theme.PrimX.Light" : "Theme.PrimX.Dark", "style", actContext.getPackageName());
        if (themeStyleId == 0) themeStyleId = android.R.style.Theme_NoTitleBar_Fullscreen;

        final Dialog dialog = new Dialog(actContext, themeStyleId);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        int layoutId = actContext.getResources().getIdentifier("dialog_ai_analysis", "layout", actContext.getPackageName());
        if(layoutId == 0) return;

        dialog.setContentView(layoutId);
        dialog.setCancelable(false);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            android.view.WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
            lp.dimAmount = 0.75f;
            dialog.getWindow().setAttributes(lp);
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT);
        }

        int contentId = actContext.getResources().getIdentifier("tvAiContent", "id", actContext.getPackageName());
        final TextView tvAiContent = dialog.findViewById(contentId);

        int copyBtnId = actContext.getResources().getIdentifier("btnAiCopy", "id", actContext.getPackageName());
        View btnAiCopy = dialog.findViewById(copyBtnId);

        if (btnAiCopy != null && tvAiContent != null) {
            final Context finalContext = actContext;
            btnAiCopy.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String aiText = tvAiContent.getText().toString().trim();
                    if (!aiText.isEmpty() && !aiText.equals("Welcome! Ask me to fix errors or build a feature...")) {
                        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) finalContext.getSystemService(Context.CLIPBOARD_SERVICE);
                        android.content.ClipData clip = android.content.ClipData.newPlainText("AI_Response", aiText);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(clip);
                            if (finalContext instanceof MainActivity) {
                                ((MainActivity) finalContext).showCustomToast("Copied to clipboard!");
                            } else {
                                Toast.makeText(finalContext, "Copied!", Toast.LENGTH_SHORT).show();
                            }
                        }
                    }
                }
            });
        }

        int inputId = actContext.getResources().getIdentifier("inputAiCommand", "id", actContext.getPackageName());
        final android.widget.EditText finalAiInputBox = dialog.findViewById(inputId);

        int sendBtnId = actContext.getResources().getIdentifier("btnSendAiCommand", "id", actContext.getPackageName());
        final View sendBtn = dialog.findViewById(sendBtnId);

        int saveToProjId = actContext.getResources().getIdentifier("btnAiSaveToProject", "id", actContext.getPackageName());
        final TextView btnAiSaveToProject = dialog.findViewById(saveToProjId);

        int cancelId = actContext.getResources().getIdentifier("btnAiCancel", "id", actContext.getPackageName());
        TextView btnAiCancel = dialog.findViewById(cancelId);

        int fixId = actContext.getResources().getIdentifier("btnAiFix", "id", actContext.getPackageName());
        final TextView btnAiAction = dialog.findViewById(fixId);

        if(btnAiCancel != null){
            btnAiCancel.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { dialog.dismiss(); }
            });
        }

        final AI aiInstance = new AI(actContext);
        final Context safeContext = actContext;

        if (hasErrors && fileToAnalyze != null && !knownError.isEmpty()) {
            if(tvAiContent != null) tvAiContent.setText("Error in " + fileToAnalyze.getName() + ":\n" + knownError);
            if(btnAiAction != null) btnAiAction.setText("FIX");
            setupFixAction(actContext, dialog, btnAiAction, tvAiContent, finalAiInputBox, aiInstance, fileToAnalyze, knownError, finalProjectPath);
        } else {
            if(tvAiContent != null) tvAiContent.setText("Welcome! Ask me to fix errors or build a feature...");
            if(btnAiAction != null) btnAiAction.setText("SCAN");

            if(btnAiAction != null){
                btnAiAction.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (finalAiInputBox != null && !finalAiInputBox.getText().toString().trim().isEmpty()) {
                            setupFixAction(safeContext, dialog, btnAiAction, tvAiContent, finalAiInputBox, aiInstance, fileToAnalyze, "", finalProjectPath);
                            btnAiAction.performClick();
                            return;
                        }

                        btnAiAction.setText("SCANNING");
                        tvAiContent.setText("Reading all project files...");

                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                final StringBuilder projectCode = new StringBuilder();
                                List<File> allFiles = new ArrayList<>();
                                collectProjectFiles(new File(finalProjectPath), allFiles);

                                int count = 0;
                                for (File f : allFiles) {
                                    if (count > 20) break;
                                    projectCode.append("--- FILE: ").append(f.getName()).append(" ---\n");
                                    try {
                                        BufferedReader br = new BufferedReader(new java.io.FileReader(f));
                                        String line;
                                        while ((line = br.readLine()) != null) projectCode.append(line).append("\n");
                                        br.close();
                                    } catch (Exception e){}
                                    projectCode.append("\n");
                                    count++;
                                }

                                new Handler(Looper.getMainLooper()).post(new Runnable() {
                                    @Override
                                    public void run() {
                                        tvAiContent.setText("Analyzing architecture and logic...");
                                        String prompt = "Act as an expert Android Developer. Here is the code for my entire Android project:\n\n" + projectCode.toString() + "\n\nDeeply scan this project for logical bugs, missing configurations, or runtime crashes. If the project is perfect, reply ONLY with 'PERFECT'. If there are issues, explain them.";

                                        aiInstance.askAI(prompt, new AI.AIResponseListener() {
                                            @Override
                                            public void onSuccess(String response) {
                                                if (response.trim().toUpperCase().contains("PERFECT") && response.length() < 25) {
                                                    tvAiContent.setText("Project looks perfect! Safe to run.");
                                                    btnAiAction.setVisibility(View.GONE);
                                                } else {
                                                    tvAiContent.setText("Issues Found:\n" + response);
                                                    btnAiAction.setText("FIX ALL");
                                                    final String scanIssuesContext = response;

                                                    btnAiAction.setOnClickListener(new View.OnClickListener() {
                                                        @Override
                                                        public void onClick(View v) {
                                                            btnAiAction.setText("FIXING");
                                                            tvAiContent.setText("Repairing architecture and fixing files...");

                                                            aiInstance.executeSuperCommand(finalProjectPath, null, "", "", scanIssuesContext, new AI.AIResponseListener() {
                                                                @Override
                                                                public void onSuccess(String fixResponse) {
                                                                    dialog.dismiss();
                                                                    if (safeContext instanceof MainActivity) {
                                                                        ((MainActivity) safeContext).showCustomToast("Project Repaired!");
                                                                    } else {
                                                                        Toast.makeText(safeContext, "Project Repaired!", Toast.LENGTH_SHORT).show();
                                                                    }
                                                                    console.clearLogs();
                                                                }
                                                                @Override
                                                                public void onError(String fixError) {
                                                                    tvAiContent.setText("Failed: " + fixError);
                                                                    btnAiAction.setText("RETRY");
                                                                }
                                                            });
                                                        }
                                                    });
                                                }
                                            }
                                            @Override
                                            public void onError(String error) {
                                                tvAiContent.setText("Scan Failed: " + error);
                                                btnAiAction.setText("RETRY");
                                            }
                                        });
                                    }
                                });
                            }
                        }).start();
                    }
                });
            }
        }

        final LinearLayout chatViews = installChatContainer(tvAiContent);
        final ScrollView chatScroll = tvAiContent != null ? findParentScroll(tvAiContent) : null;
        final boolean[] internalChange = {false};

        List<String[]> savedChat = aiInstance.getHistory(finalProjectPath);
        if (chatViews != null && !savedChat.isEmpty()) {
            TextView sep = addTextView(chatViews, "\u2500\u2500\u2500\u2500 Saved chat \u2500\u2500\u2500\u2500");
            sep.setGravity(Gravity.CENTER);
            sep.setTextColor(0xFF78909C);
            renderHistory(chatViews, savedChat, true);
            scrollToBottom(chatScroll);
        }

        if (tvAiContent != null && chatViews != null) {
            tvAiContent.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence cs, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence cs, int st, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable e) {
                    if (internalChange[0]) return;
                    if (e.length() > 0) {
                        chatViews.setVisibility(View.GONE);
                        tvAiContent.setVisibility(View.VISIBLE);
                    }
                }
            });
        }

        if (btnAiCopy != null) {
            final Context copyCtx = actContext;
            btnAiCopy.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String aiText = "";
                    if (chatViews != null && chatViews.getVisibility() == View.VISIBLE && chatViews.getChildCount() > 0) {
                        aiText = chatTranscript(aiInstance.getHistory(finalProjectPath));
                    }
                    if (aiText.isEmpty() && tvAiContent != null) aiText = tvAiContent.getText().toString().trim();
                    if (!aiText.isEmpty() && !aiText.equals("Welcome! Ask me to fix errors or build a feature...")) {
                        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) copyCtx.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AI_Response", aiText));
                            if (copyCtx instanceof MainActivity) ((MainActivity) copyCtx).showCustomToast("Copied to clipboard!");
                            else Toast.makeText(copyCtx, "Copied!", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            });
        }

        final boolean[] chatBusy = {false};
        updateSendState(sendBtn, finalAiInputBox, chatBusy[0], btnAiSaveToProject);
        if (finalAiInputBox != null) {
            finalAiInputBox.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence cs, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence cs, int st, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable e) {
                    updateSendState(sendBtn, finalAiInputBox, chatBusy[0], btnAiSaveToProject);
                }
            });
        }

        if (sendBtn != null) {
            sendBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (chatBusy[0]) return;
                    if (chatViews == null || tvAiContent == null) return;
                    final String userMsg = finalAiInputBox != null ? finalAiInputBox.getText().toString().trim() : "";
                    if (userMsg.isEmpty()) return;

                    chatBusy[0] = true;

                    internalChange[0] = true;
                    tvAiContent.setText("");
                    internalChange[0] = false;
                    tvAiContent.setVisibility(View.GONE);
                    chatViews.setVisibility(View.VISIBLE);
                    chatViews.removeAllViews();

                    renderHistory(chatViews, aiInstance.getHistory(finalProjectPath), false);
                    addUserBubble(chatViews, userMsg);
                    final TextView thinkingView = addTextView(chatViews, "\u23F3 Thinking...");
                    thinkingView.setTextColor(0xFF90CAF9);

                    if (finalAiInputBox != null) finalAiInputBox.setText("");
                    updateSendState(sendBtn, finalAiInputBox, chatBusy[0], btnAiSaveToProject);
                    scrollToBottom(chatScroll);

                    aiInstance.chat(finalProjectPath, fileToAnalyze, userMsg, new AI.AIResponseListener() {
                        @Override
                        public void onSuccess(final String chatResponse) {
                            if (chatResponse != null && chatResponse.startsWith("\u2705")) console.clearLogs();
                            chatViews.removeView(thinkingView);
                            typeAiMessage(chatViews, chatResponse, chatScroll, new Runnable() {
                                @Override public void run() {
                                    chatBusy[0] = false;
                                    updateSendState(sendBtn, finalAiInputBox, chatBusy[0], btnAiSaveToProject);
                                }
                            });
                        }

                        @Override
                        public void onError(String chatError) {
                            chatViews.removeView(thinkingView);
                            TextView errView = addTextView(chatViews, "\u26A0\uFE0F " + chatError);
                            errView.setTextColor(0xFFFF8A80);
                            scrollToBottom(chatScroll);
                            chatBusy[0] = false;
                            updateSendState(sendBtn, finalAiInputBox, chatBusy[0], btnAiSaveToProject);
                        }
                    });
                }
            });
        }

        if (btnAiSaveToProject != null) {
            btnAiSaveToProject.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (chatBusy[0]) return;
                    List<String[]> curHistory = aiInstance.getHistory(finalProjectPath);
                    if (curHistory.isEmpty()) {
                        if (safeContext instanceof MainActivity) {
                            ((MainActivity) safeContext).showCustomToast("Chat me pehle feature ya changes batayein.");
                        } else {
                            Toast.makeText(safeContext, "Chat me pehle feature ya changes batayein.", Toast.LENGTH_SHORT).show();
                        }
                        return;
                    }

                    chatBusy[0] = true;
                    updateSendState(sendBtn, finalAiInputBox, chatBusy[0], btnAiSaveToProject);

                    internalChange[0] = true;
                    if (tvAiContent != null) tvAiContent.setText("");
                    internalChange[0] = false;
                    if (tvAiContent != null) tvAiContent.setVisibility(View.GONE);
                    if (chatViews != null) {
                        chatViews.setVisibility(View.VISIBLE);
                        chatViews.removeAllViews();
                        renderHistory(chatViews, aiInstance.getHistory(finalProjectPath), false);
                        addUserBubble(chatViews, "saved to project");
                        final TextView savingView = addTextView(chatViews, "\u23F3 Saving files to project...");
                        savingView.setTextColor(0xFF00E676);
                        scrollToBottom(chatScroll);

                        aiInstance.executeSuperCommand(finalProjectPath, fileToAnalyze, "", "saved to project", "", new AI.AIResponseListener() {
                            @Override
                            public void onSuccess(final String saveResponse) {
                                console.clearLogs();
                                chatViews.removeView(savingView);
                                typeAiMessage(chatViews, saveResponse, chatScroll, new Runnable() {
                                    @Override public void run() {
                                        chatBusy[0] = false;
                                        updateSendState(sendBtn, finalAiInputBox, chatBusy[0], btnAiSaveToProject);
                                    }
                                });
                            }

                            @Override
                            public void onError(String saveError) {
                                chatViews.removeView(savingView);
                                TextView errView = addTextView(chatViews, "\u26A0\uFE0F " + saveError);
                                errView.setTextColor(0xFFFF8A80);
                                scrollToBottom(chatScroll);
                                chatBusy[0] = false;
                                updateSendState(sendBtn, finalAiInputBox, chatBusy[0], btnAiSaveToProject);
                            }
                        });
                    }
                }
            });
        }

        int settingsId = actContext.getResources().getIdentifier("btnAiSettings", "id", actContext.getPackageName());
        View btnSettings = dialog.findViewById(settingsId);
        if (btnSettings != null) {
            btnSettings.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showAiSettings(safeContext);
                }
            });
        }

        dialog.show(); com.primx.utils.UIEffectsManager.applyPremiumBlur(dialog);
    }

    private void updateSendState(View sendBtn, android.widget.EditText input, boolean busy) {
        updateSendState(sendBtn, input, busy, null);
    }

    private void updateSendState(View sendBtn, android.widget.EditText input, boolean busy, View saveBtn) {
        if (sendBtn != null) {
            boolean hasText = input != null && input.getText().toString().trim().length() > 0;
            boolean enabled = hasText && !busy;
            sendBtn.setEnabled(enabled);
            sendBtn.setClickable(enabled);
            sendBtn.setAlpha(enabled ? 1f : 0.35f);
        }
        if (saveBtn != null) {
            saveBtn.setEnabled(!busy);
            saveBtn.setClickable(!busy);
            saveBtn.setAlpha(busy ? 0.4f : 1.0f);
        }
    }

    private static char nextNonSpace(String s, int idx) {
        while (idx < s.length() && (s.charAt(idx) == ' ' || s.charAt(idx) == '\t')) idx++;
        return idx < s.length() ? s.charAt(idx) : 0;
    }

    private static String trimNl(String s) {
        int a = 0, b = s.length();
        while (a < b && (s.charAt(a) == '\n' || s.charAt(a) == '\r')) a++;
        while (b > a && (s.charAt(b - 1) == '\n' || s.charAt(b - 1) == '\r' || s.charAt(b - 1) == ' ')) b--;
        return s.substring(a, b);
    }

    private CharSequence highlightCode(String code, String lang) {
        try {
            if ("xml".equals(lang)) return highlightXml(code);
            return highlightJava(code);
        } catch (Throwable t) {
            return new SpannableStringBuilder(code);
        }
    }

    private SpannableStringBuilder highlightJava(String code) {
        SpannableStringBuilder sb = new SpannableStringBuilder(code);
        Matcher m = JAVA_TOKEN.matcher(code);
        while (m.find()) {
            int color = 0;
            if (m.group(1) != null || m.group(2) != null) color = C_COMMENT;
            else if (m.group(3) != null || m.group(4) != null) color = C_STRING;
            else if (m.group(5) != null) color = C_ANNOT;
            else if (m.group(6) != null) color = C_NUMBER;
            else if (m.group(7) != null) {
                String w = m.group(7);
                if (JAVA_CONTROL.contains(w)) color = C_CONTROL;
                else if (JAVA_KEYWORDS.contains(w)) color = C_KEYWORD;
                else if (nextNonSpace(code, m.end()) == '(') color = C_METHOD;
                else if (Character.isUpperCase(w.charAt(0))) color = C_TYPE;
            }
            if (color != 0) sb.setSpan(new ForegroundColorSpan(color), m.start(), m.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    private SpannableStringBuilder highlightXml(String code) {
        SpannableStringBuilder sb = new SpannableStringBuilder(code);
        Matcher m = XML_TOKEN.matcher(code);
        while (m.find()) {
            int color = 0;
            if (m.group(1) != null) color = C_COMMENT;
            else if (m.group(2) != null) color = C_KEYWORD;
            else if (m.group(3) != null) color = C_STRING;
            else if (m.group(4) != null) color = C_ATTR;
            if (color != 0) sb.setSpan(new ForegroundColorSpan(color), m.start(), m.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    private String detectLang(String fileName, String fence, String code) {
        String n = fileName == null ? "" : fileName.toLowerCase();
        String f = fence == null ? "" : fence.toLowerCase();
        if (n.endsWith(".xml") || n.endsWith(".html")) return "xml";
        if (n.endsWith(".java") || n.endsWith(".gradle") || n.endsWith(".kt")) return "java";
        if (f.equals("xml") || f.equals("html")) return "xml";
        if (code.trim().startsWith("<")) return "xml";
        return "java";
    }

    private String extractFileName(String line) {
        if (line == null) return null;
        String p = null;
        Matcher m = FILE_LINE.matcher(line);
        if (m.matches()) {
            p = m.group(1).trim();
        } else {
            Matcher m2 = NAME_ONLY_LINE.matcher(line);
            if (m2.matches()) p = m2.group(1).trim();
        }
        if (p == null || p.length() == 0) return null;
        String[] toks = p.split("\\s+");
        if (toks.length > 1 && toks[0].indexOf('.') > 0) p = toks[0];
        p = p.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        if (slash >= 0 && slash < p.length() - 1) p = p.substring(slash + 1);
        return p.length() == 0 ? null : p;
    }

    private void addTextSeg(List<ChatSeg> out, String raw) {
        if (raw == null || raw.trim().length() == 0) return;
        ChatSeg s = new ChatSeg();
        s.text = trimNl(raw);
        s.shown = s.text;
        out.add(s);
    }

    private List<ChatSeg> parseAiSegments(String text) {
        List<ChatSeg> out = new ArrayList<ChatSeg>();
        if (text == null) return out;
        int pos = 0;
        while (pos < text.length()) {
            int fence = text.indexOf("```", pos);
            if (fence < 0) { addTextSeg(out, text.substring(pos)); break; }
            String before = text.substring(pos, fence);

            int nl = text.indexOf("\n", fence + 3);
            String fenceLang = "";
            int codeStart = fence + 3;
            if (nl >= 0) {
                String l = text.substring(fence + 3, nl).trim();
                if (l.length() <= 15 && l.matches("[A-Za-z0-9_+#.\\-]*")) { fenceLang = l; codeStart = nl + 1; }
            }
            int end = text.indexOf("```", codeStart);
            String code;
            if (end < 0) { code = text.substring(Math.min(codeStart, text.length())); pos = text.length(); }
            else { code = text.substring(codeStart, end); pos = end + 3; }
            if (pos < text.length() && text.charAt(pos) == '\n') pos++;

            int e = before.length();
            while (e > 0 && Character.isWhitespace(before.charAt(e - 1))) e--;
            int ls = before.lastIndexOf('\n', e - 1) + 1;
            String fileName = extractFileName(before.substring(ls, e));
            if (fileName != null) before = before.substring(0, ls);
            addTextSeg(out, before);

            code = code.replace("\r", "");
            int ce = code.length();
            while (ce > 0 && Character.isWhitespace(code.charAt(ce - 1))) ce--;
            code = code.substring(0, ce);

            ChatSeg s = new ChatSeg();
            s.code = true;
            s.text = code;
            s.shown = code.replace("\t", "    ");
            s.lang = detectLang(fileName, fenceLang, code);
            if (fileName == null) {
                Matcher cm = CLASS_DECL.matcher(code);
                if ("java".equals(s.lang) && cm.find()) fileName = cm.group(1) + ".java";
                else if ("xml".equals(s.lang)) fileName = "XML";
                else fileName = fenceLang.length() > 0 ? fenceLang : "Code";
            }
            s.name = fileName;
            out.add(s);
        }
        return out;
    }

    @SuppressWarnings("deprecation")
    private void setBg(View v, android.graphics.drawable.Drawable d) {
        if (Build.VERSION.SDK_INT >= 16) v.setBackground(d); else v.setBackgroundDrawable(d);
    }

    private void scrollToBottom(final ScrollView sv) {
        if (sv == null) return;
        Runnable r = new Runnable() {
            @Override public void run() {
                View c = sv.getChildAt(0);
                if (c != null) sv.scrollTo(0, c.getBottom());
            }
        };
        sv.post(r);
        sv.postDelayed(r, 80);
    }

    private ScrollView findParentScroll(View v) {
        android.view.ViewParent p = v.getParent();
        while (p != null) {
            if (p instanceof ScrollView) return (ScrollView) p;
            p = p.getParent();
        }
        return null;
    }

    private LinearLayout installChatContainer(TextView anchor) {
        if (anchor == null) return null;
        Context ctx = console.getContext();
        LinearLayout chatViews = new LinearLayout(ctx);
        chatViews.setOrientation(LinearLayout.VERTICAL);
        chatViews.setPadding(Math.max(anchor.getPaddingLeft(), console.dpToPx(10)), 0, Math.max(anchor.getPaddingRight(), console.dpToPx(10)), console.dpToPx(12));

        LinearLayout holder = new LinearLayout(ctx);
        holder.setOrientation(LinearLayout.VERTICAL);

        android.view.ViewParent p = anchor.getParent();
        if (p instanceof ScrollView) {
            ScrollView sv = (ScrollView) p;
            sv.removeView(anchor);
            holder.addView(anchor, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            holder.addView(chatViews, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            sv.addView(holder, new android.widget.FrameLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        } else if (p instanceof android.view.ViewGroup) {
            android.view.ViewGroup parent = (android.view.ViewGroup) p;
            int idx = parent.indexOfChild(anchor);
            android.view.ViewGroup.LayoutParams lp = anchor.getLayoutParams();
            parent.removeView(anchor);
            holder.addView(anchor, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            holder.addView(chatViews, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            parent.addView(holder, idx, lp);
        }
        return chatViews;
    }

    private TextView addTextView(LinearLayout parent, CharSequence text) {
        TextView tv = new TextView(console.getContext());
        tv.setText(text);
        int textColor = getThemeColor(console.getContext(), R.attr.ide_text_primary, 0xFFE0E0E0);
        tv.setTextColor(textColor);
        tv.setTextSize(14f);
        tv.setLineSpacing(0f, 1.25f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, console.dpToPx(6), 0, console.dpToPx(6));
        parent.addView(tv, lp);
        return tv;
    }
    private int getThemeColor(Context context, int attrResId, int defaultColor) {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        if (context.getTheme().resolveAttribute(attrResId, typedValue, true)) {
            return typedValue.data;
        }
        return defaultColor;
    }

    private void addUserBubble(LinearLayout parent, String msg) {
        TextView tv = new TextView(console.getContext());
        tv.setText(msg);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(14.5f);
        tv.setLineSpacing(0f, 1.2f);
        tv.setPadding(console.dpToPx(14), console.dpToPx(10), console.dpToPx(14), console.dpToPx(10));
        tv.setMaxWidth((int) (console.getResources().getDisplayMetrics().widthPixels * 0.78f));

        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF2F80FF, 0xFF1565D8});
        float r = console.dpToPx(18), s = console.dpToPx(4);
        bg.setCornerRadii(new float[]{r, r, r, r, s, s, r, r});
        setBg(tv, bg);
        tv.setTextIsSelectable(true);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        lp.setMargins(console.dpToPx(40), console.dpToPx(10), 0, console.dpToPx(10));
        parent.addView(tv, lp);
    }

    private void copyCodeToClipboard(String label, String code) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) console.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText(label, code));
        } catch (Exception e) {}
    }

    private TextView addCodeBox(LinearLayout parent, final ChatSeg seg) {
        Context ctx = console.getContext();
        int stroke = console.dpToPx(1.5f);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setColor(0xFF0D1117);
        boxBg.setCornerRadius(console.dpToPx(10));
        boxBg.setStroke(stroke, C_BLUE);
        setBg(box, boxBg);
        box.setPadding(stroke, stroke, stroke, stroke);

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(console.dpToPx(12), console.dpToPx(7), console.dpToPx(8), console.dpToPx(7));
        GradientDrawable hBg = new GradientDrawable();
        hBg.setColor(0xFF161B22);
        float hr = console.dpToPx(8.5f);
        hBg.setCornerRadii(new float[]{hr, hr, hr, hr, 0, 0, 0, 0});
        setBg(header, hBg);

        TextView name = new TextView(ctx);
        name.setText(seg.name);
        name.setTextColor(0xFF82B1FF);
        name.setTextSize(12.5f);
        name.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        header.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView copy = new TextView(ctx);
        copy.setText("Copy");
        copy.setTextColor(Color.WHITE);
        copy.setTextSize(12f);
        copy.setTypeface(null, Typeface.BOLD);
        copy.setGravity(Gravity.CENTER);
        copy.setPadding(console.dpToPx(14), console.dpToPx(5), console.dpToPx(14), console.dpToPx(5));
        GradientDrawable cBg = new GradientDrawable();
        cBg.setColor(0xFF1E88E5);
        cBg.setCornerRadius(console.dpToPx(7));
        setBg(copy, cBg);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                copyCodeToClipboard(seg.name, seg.text);
                copy.setText("Copied \u2713");
                copy.postDelayed(new Runnable() {
                    @Override public void run() { copy.setText("Copy"); }
                }, 1500);
            }
        });
        header.addView(copy, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(header, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        View divider = new View(ctx);
        divider.setBackgroundColor(0x662196F3);
        box.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, console.dpToPx(1))));

        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        TextView codeTv = new TextView(ctx);
        codeTv.setTypeface(Typeface.MONOSPACE);
        codeTv.setTextSize(12f);
        codeTv.setTextColor(C_PLAIN);
        codeTv.setLineSpacing(0f, 1.15f);
        codeTv.setPadding(console.dpToPx(12), console.dpToPx(10), console.dpToPx(14), console.dpToPx(12));
        hs.addView(codeTv, new android.widget.FrameLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(hs, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, console.dpToPx(8), 0, console.dpToPx(8));
        parent.addView(box, lp);
        return codeTv;
    }

    private void renderAiMessage(LinearLayout parent, String text) {
        List<ChatSeg> segs = parseAiSegments(text);
        for (int i = 0; i < segs.size(); i++) {
            ChatSeg s = segs.get(i);
            if (s.code) {
                TextView codeTv = addCodeBox(parent, s);
                codeTv.setText(highlightCode(s.shown, s.lang));
            } else {
                TextView tv = addTextView(parent, s.shown);
                tv.setTextIsSelectable(true);
            }
        }
    }

    private void renderHistory(LinearLayout parent, List<String[]> history, boolean addSaveHint) {
        for (int i = 0; i < history.size(); i++) {
            String[] h = history.get(i);
            if (h[0].equals("user")) addUserBubble(parent, h[1]);
            else renderAiMessage(parent, h[1]);
        }
        if (addSaveHint && !history.isEmpty()) {
            String[] last = history.get(history.size() - 1);
            if (last[0].equals("assistant") && !last[1].startsWith("\u2705") && !last[1].toLowerCase().contains("saved to project")) {
                TextView hint = addTextView(parent, AI.SAVE_HINT.trim());
                hint.setTextColor(0xFF90A4AE);
                hint.setTypeface(null, Typeface.ITALIC);
            }
        }
    }

    private String chatTranscript(List<String[]> history) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < history.size(); i++) {
            String[] h = history.get(i);
            sb.append(h[0].equals("user") ? "You: " : "AI: ").append(h[1]).append("\n\n");
        }
        return sb.toString().trim();
    }

    private void typeAiMessage(final LinearLayout parent, final String text, final ScrollView sv, final Runnable onComplete) {
        final List<ChatSeg> segs = parseAiSegments(text);
        if (segs.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        int total = 0;
        for (int i = 0; i < segs.size(); i++) total += segs.get(i).shown.length();
        final int step = Math.max(3, total / 350);

        final Handler h = new Handler(Looper.getMainLooper());
        final int[] segIdx = {0};
        final int[] pos = {0};
        final TextView[] cur = {null};

        h.post(new Runnable() {
            @Override
            public void run() {
                if (segIdx[0] >= segs.size()) {
                    if (onComplete != null) onComplete.run();
                    return;
                }
                ChatSeg s = segs.get(segIdx[0]);
                if (cur[0] == null) cur[0] = s.code ? addCodeBox(parent, s) : addTextView(parent, "");

                int len = s.shown.length();
                pos[0] = Math.min(len, pos[0] + step);
                if (pos[0] < len && pos[0] > 0 && Character.isHighSurrogate(s.shown.charAt(pos[0] - 1))) pos[0]++;
                cur[0].setText(s.shown.substring(0, pos[0]));
                scrollToBottom(sv);

                if (pos[0] >= len) {
                    if (s.code) cur[0].setText(highlightCode(s.shown, s.lang));
                    else cur[0].setTextIsSelectable(true);
                    cur[0] = null;
                    segIdx[0]++;
                    pos[0] = 0;
                    h.postDelayed(this, 40);
                } else {
                    h.postDelayed(this, 15);
                }
            }
        });
    }

    private void collectProjectFiles(File dir, List<File> list) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    if (!f.getName().equals("build") && !f.getName().equals(".idea") && !f.getName().equals("gradle")) {
                        collectProjectFiles(f, list);
                    }
                } else if (f.getName().endsWith(".java") || f.getName().endsWith(".xml") || f.getName().endsWith(".gradle")) {
                    list.add(f);
                }
            }
        }
    }

    private void simulateTyping(final TextView textView, final String text, final ScrollView scrollView, final Runnable onComplete) {
        if (textView == null || text == null) return;
        textView.setText("");
        final Handler typingHandler = new Handler(Looper.getMainLooper());
        final int[] index = {0};

        typingHandler.post(new Runnable() {
            @Override
            public void run() {
                if (index[0] < text.length()) {
                    textView.append(String.valueOf(text.charAt(index[0])));
                    index[0]++;
                    if (scrollView != null) {
                        scrollView.post(new Runnable() { @Override public void run() { scrollView.fullScroll(View.FOCUS_DOWN); } });
                    }
                    typingHandler.postDelayed(this, 15);
                } else {
                    if (onComplete != null) {
                        typingHandler.postDelayed(onComplete, 1000);
                    }
                }
            }
        });
    }

    private void showAiSettings(final Context context) {
        boolean isLight = Setting.isLight(context);
        int themeStyleId = context.getResources().getIdentifier(isLight ? "Theme.PrimX.Light" : "Theme.PrimX.Dark", "style", context.getPackageName());
        if (themeStyleId == 0) themeStyleId = android.R.style.Theme_NoTitleBar_Fullscreen;

        final Dialog sDialog = new Dialog(context, themeStyleId);
        sDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        int layoutId = context.getResources().getIdentifier("dialog_ai_settings", "layout", context.getPackageName());
        if (layoutId == 0) return;

        sDialog.setContentView(layoutId);

        if (sDialog.getWindow() != null) {
            sDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            android.view.WindowManager.LayoutParams lp = sDialog.getWindow().getAttributes();
            lp.dimAmount = 0.75f;
            sDialog.getWindow().setAttributes(lp);
            sDialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            sDialog.getWindow().setLayout((int)(context.getResources().getDisplayMetrics().widthPixels * 0.90), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        int idUrl = context.getResources().getIdentifier("inputApiUrl", "id", context.getPackageName());
        int idKey = context.getResources().getIdentifier("inputApiKey", "id", context.getPackageName());
        int idModel = context.getResources().getIdentifier("inputAiModel", "id", context.getPackageName());
        int idSave = context.getResources().getIdentifier("btnSaveAiSettings", "id", context.getPackageName());

        final android.widget.EditText editApiUrl = sDialog.findViewById(idUrl);
        final android.widget.EditText editApiKey = sDialog.findViewById(idKey);
        final android.widget.EditText editModel = sDialog.findViewById(idModel);
        View saveBtn = sDialog.findViewById(idSave);

        android.content.SharedPreferences prefs = context.getSharedPreferences("EditorPrefs", Context.MODE_PRIVATE);
        if (editApiUrl != null) editApiUrl.setText(prefs.getString("API_URL", "https://generativelanguage.googleapis.com/v1beta/models/"));
        if (editApiKey != null) editApiKey.setText(prefs.getString("API_KEY", ""));
        if (editModel != null) editModel.setText(prefs.getString("MODEL_NAME", ""));

        if (saveBtn != null) {
            saveBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    context.getSharedPreferences("EditorPrefs", Context.MODE_PRIVATE).edit()
                    .putString("API_URL", editApiUrl != null ? editApiUrl.getText().toString().trim() : "")
                    .putString("API_KEY", editApiKey != null ? editApiKey.getText().toString().trim() : "")
                    .putString("MODEL_NAME", editModel != null ? editModel.getText().toString().trim() : "")
                    .apply();

                    if (context instanceof MainActivity) {
                        ((MainActivity) context).showCustomToast("AI Settings Saved!");
                    } else {
                        Toast.makeText(context, "AI Settings Saved!", Toast.LENGTH_SHORT).show();
                    }
                    sDialog.dismiss();
                }
            });
        }

        sDialog.show(); com.primx.utils.UIEffectsManager.applyPremiumBlur(sDialog);
    }

    private void setupFixAction(final Context context, final Dialog dialog, final TextView btnAction, final TextView tvContent, final android.widget.EditText aiInputBox, final AI aiInstance, final File fileToFix, final String errorContext, final String projectPath) {
        btnAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String userInstruction = "";
                if (aiInputBox != null) { userInstruction = aiInputBox.getText().toString().trim(); }

                if (userInstruction.isEmpty() && (errorContext == null || errorContext.isEmpty())) {
                    if(tvContent != null) tvContent.setText("Please write a command...");
                    return;
                }

                if (aiInputBox != null) { aiInputBox.setText(""); }
                if(btnAction != null) btnAction.setText("WORKING");
                if(tvContent != null) tvContent.setText("Brainstorming logic...\nAnalyzing dependencies...");
            }
        });
    }
}