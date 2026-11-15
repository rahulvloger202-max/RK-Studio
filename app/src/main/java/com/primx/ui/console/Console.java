package com.primx.ui.console;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.primx.services.EngineService;
import com.primx.ui.mainactivity.MainActivity;
import com.primx.R;

public class Console extends LinearLayout {

    private static final Pattern STD_ECJ_HEADER = Pattern.compile(
    "^\\d+\\.\\s+(ERROR|WARNING)\\s+in\\s+(.+?)\\s+\\(at line\\s+(\\d+)\\)\\s*$"
    );
    private static final Pattern FALLBACK_ECJ_HEADER = Pattern.compile(
    "^(?:Missing message:\\s*requestor\\.(warning|error).*|(\\d+))\\s*\\(at line\\s+(\\d+)\\)\\s*$"
    );

    public static boolean isWarningMessage(String msg) {
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("warning") || lower.contains("never used") || lower.contains("deprecated") || lower.contains("is not used") || lower.contains("unused");
    }

    public interface ConsoleListener {
        void onErrorClicked(String filePath, int lineNumber, int columnStart, int errorLength, String errorMessage);
        void onLiveDiagnosticsClear();
        void onLiveDiagnosticError(String filePath, int lineNumber, int columnStart, int errorLength, String errorMessage, boolean isWarning);
    }

    public ConsoleListener consoleListener;
    public ScrollView scrollView;
    public TextView tvOutput;
    public TextView btnAiFix;
    public LinearLayout statusHeader;
    public StatusIconView statusIcon;
    public TextView tvStatusText;
    public View statusDivider;
    public LinearLayout successLayout;

    public int currentTab = 0; // 0 = Errors, 1 = Warnings, 2 = Build Logs, 3 = Logcat
    public StringBuilder rawLogs = new StringBuilder();
    public TextView tabError, tabWarning, tabLog, tabLogcat;
    public View tabLogcatWrapper;
    public StringBuilder logcatLogs = new StringBuilder();
    public Process logcatProcess;

    public static class ErrorItem {
        public String filePath;
        public int line;
        public int colStart;
        public int colLen;
        public String message;
        public boolean isWarning;
        public boolean isLiveAst;
        public long fileSignature;

        public ErrorItem(String fp, int l, int cs, int cl, String m, boolean warn, boolean ast) {
            filePath = fp;
            line = l;
            colStart = cs;
            colLen = cl;
            message = m;
            isWarning = warn;
            isLiveAst = ast;
            fileSignature = getFileSignature(fp);
        }
    }

    public static long getFileSignature(String fp) {
        if (fp == null || fp.isEmpty() || !fp.startsWith("/")) return 0L;
        try {
            File f = new File(fp);
            if (!f.exists()) return 0L;
            return (f.lastModified() * 31L) ^ f.length();
        } catch (Exception e) {
            return 0L;
        }
    }

    public String getActiveProjectPath() {
        if (MainActivity.currentProjectPath != null && !MainActivity.currentProjectPath.isEmpty()) {
            return MainActivity.currentProjectPath;
        }
        if (liveAnalyzer != null && liveAnalyzer.lastProjectDir != null) {
            return liveAnalyzer.lastProjectDir.getAbsolutePath();
        }
        try {
            android.content.SharedPreferences prefs = getContext().getSharedPreferences("EditorPrefs", Context.MODE_PRIVATE);
            return prefs.getString("projectPath", "");
        } catch (Exception e) {
            return "";
        }
    }

    public boolean isFileInActiveProject(String filePath) {
        if (filePath == null || filePath.isEmpty()) return false;
        if (!filePath.startsWith("/")) return true;
        File f = new File(filePath);
        if (!f.exists()) return false;
        String activeProj = getActiveProjectPath();
        if (activeProj == null || activeProj.isEmpty()) return true;
        String canonFile = canon(filePath);
        String canonProj = canon(activeProj);
        return canonFile.equals(canonProj) || canonFile.startsWith(canonProj + File.separator);
    }

    public void pruneForeignProjectErrors() {
        List<String> toRemove = new ArrayList<>();
        for (String k : errorMap.keySet()) {
            if (!isFileInActiveProject(k)) toRemove.add(k);
        }
        for (String k : toRemove) errorMap.remove(k);
    }

    public HashMap<String, List<ErrorItem>> errorMap = new HashMap<>();
    public int totalErrorCount = 0;
    public File lastActiveFile;

    private ConsoleAIHandler aiHandler;
    public ConsoleLiveAnalyzer liveAnalyzer;
    public ConsoleErrorRenderer errorRenderer;

    public String formatErrorMessage(String msg) {
        if (msg == null || msg.trim().isEmpty()) return "Unknown Error";
        return msg.replaceAll("problem id:\\s*\\d+", "").trim();
    }

    public class StatusIconView extends View {
        private boolean isSuccess = true;
        private boolean isWarning = false;
        private Paint paint;
        private Path path;

        public StatusIconView(Context context) {
            super(context);
            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dpToPx(3));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            path = new Path();
        }

        public void setStatus(boolean success, boolean warning) {
            this.isSuccess = success;
            this.isWarning = warning;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            path.reset();
            if (isSuccess) {
                paint.setColor(Color.parseColor("#4CAF50"));
                path.moveTo(w * 0.25f, h * 0.5f);
                path.lineTo(w * 0.45f, h * 0.7f);
                path.lineTo(w * 0.75f, h * 0.3f);
                canvas.drawPath(path, paint);
            } else if (isWarning) {
                paint.setColor(Color.parseColor("#FFC107"));
                path.moveTo(w * 0.5f, h * 0.15f);
                path.lineTo(w * 0.9f, h * 0.85f);
                path.lineTo(w * 0.1f, h * 0.85f);
                path.close();
                canvas.drawPath(path, paint);
                canvas.drawLine(w * 0.5f, h * 0.4f, w * 0.5f, h * 0.6f, paint);
                canvas.drawPoint(w * 0.5f, h * 0.73f, paint);
            } else {
                paint.setColor(Color.parseColor("#F44336"));
                path.moveTo(w * 0.3f, h * 0.3f);
                path.lineTo(w * 0.7f, h * 0.7f);
                path.moveTo(w * 0.7f, h * 0.3f);
                path.lineTo(w * 0.3f, h * 0.7f);
                canvas.drawPath(path, paint);
            }
        }
    }

    public Console(Context context) {
        super(context);
        init(context);
    }

    public Console(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public void setConsoleListener(ConsoleListener listener) {
        this.consoleListener = listener;
    }

    public int dpToPx(float dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private void init(final Context context) {
        setOrientation(VERTICAL);
        setClickable(true);

        aiHandler = new ConsoleAIHandler(this);
        liveAnalyzer = new ConsoleLiveAnalyzer(this);
        errorRenderer = new ConsoleErrorRenderer(this);

        int layoutId = getResources().getIdentifier("layout_console_view", "layout", getContext().getPackageName());
        if (layoutId != 0) {
            LayoutInflater.from(context).inflate(layoutId, this, true);
        }

        int tvConsoleTitleId = getResources().getIdentifier("tvConsoleTitle", "id", getContext().getPackageName());
        TextView tvConsoleTitle = tvConsoleTitleId != 0 ? (TextView) findViewById(tvConsoleTitleId) : null;
        if (tvConsoleTitle != null) {
            tvConsoleTitle.setBackground(null);
            tvConsoleTitle.setText("PRIMX CONSOLE");
            tvConsoleTitle.setTextSize(19f);
            tvConsoleTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

            // 🔥 FIX: टेक्स्ट को हमेशा एक ही लाइन में रखने के लिए (टूटेगा नहीं)
            tvConsoleTitle.setSingleLine(true);
            tvConsoleTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            tvConsoleTitle.setLayoutParams(lp);
            tvConsoleTitle.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);

            boolean isLight = com.primx.utils.Setting.isLight(getContext());
            tvConsoleTitle.setTextColor(isLight ? android.graphics.Color.parseColor("#1E88E5") : android.graphics.Color.parseColor("#90CAF9"));
            tvConsoleTitle.setPadding(dpToPx(16), dpToPx(8), dpToPx(8), dpToPx(8));
        }
        int btnAiFixId = getResources().getIdentifier("btnAiFix", "id", getContext().getPackageName());
        btnAiFix = btnAiFixId != 0 ? (TextView) findViewById(btnAiFixId) : null;
        if (btnAiFix != null) {
            btnAiFix.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    aiHandler.triggerAutoFixByAI(context);
                }
            });
        }

        int statusHeaderId = getResources().getIdentifier("statusHeader", "id", getContext().getPackageName());
        statusHeader = statusHeaderId != 0 ? (LinearLayout) findViewById(statusHeaderId) : null;

        int statusIconContainerId = getResources().getIdentifier("statusIconContainer", "id", getContext().getPackageName());
        android.widget.FrameLayout iconContainer = statusIconContainerId != 0 ? (android.widget.FrameLayout) findViewById(statusIconContainerId) : null;
        if (iconContainer != null) {
            statusIcon = new StatusIconView(context);
            iconContainer.addView(statusIcon, new android.widget.FrameLayout.LayoutParams(dpToPx(20), dpToPx(20)));
        }

        int tvStatusTextId = getResources().getIdentifier("tvStatusText", "id", getContext().getPackageName());
        tvStatusText = tvStatusTextId != 0 ? (TextView) findViewById(tvStatusTextId) : null;

        int statusDividerId = getResources().getIdentifier("statusDivider", "id", getContext().getPackageName());
        statusDivider = statusDividerId != 0 ? findViewById(statusDividerId) : null;

        int scrollId = getResources().getIdentifier("consoleScroll", "id", getContext().getPackageName());
        scrollView = scrollId != 0 ? (ScrollView) findViewById(scrollId) : null;

        int tvOutputId = getResources().getIdentifier("tvOutput", "id", getContext().getPackageName());
        tvOutput = tvOutputId != 0 ? (TextView) findViewById(tvOutputId) : null;
        if (tvOutput != null) {
            tvOutput.setTextIsSelectable(true);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                tvOutput.setTextClassifier(android.view.textclassifier.TextClassifier.NO_OP);
            }
        }

        int successLayoutId = getResources().getIdentifier("successLayout", "id", getContext().getPackageName());
        successLayout = successLayoutId != 0 ? (LinearLayout) findViewById(successLayoutId) : null;

        tabError = findViewById(getResources().getIdentifier("tabError", "id", getContext().getPackageName()));
        tabWarning = findViewById(getResources().getIdentifier("tabWarning", "id", getContext().getPackageName()));
        tabLog = findViewById(getResources().getIdentifier("tabLog", "id", getContext().getPackageName()));

        View tabErrorWrapper = findViewById(getResources().getIdentifier("tabErrorWrapper", "id", getContext().getPackageName()));
        View tabWarningWrapper = findViewById(getResources().getIdentifier("tabWarningWrapper", "id", getContext().getPackageName()));
        View tabLogWrapper = findViewById(getResources().getIdentifier("tabLogWrapper", "id", getContext().getPackageName()));

        if (tabLogWrapper != null && tabLogWrapper.getParent() instanceof LinearLayout) {
            LinearLayout tabsContainer = (LinearLayout) tabLogWrapper.getParent();

            LinearLayout logcatWrapper = new LinearLayout(context);
            logcatWrapper.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dpToPx(4), 0, dpToPx(4), 0);
            logcatWrapper.setLayoutParams(lp);
            logcatWrapper.setGravity(android.view.Gravity.CENTER);

            tabLogcat = new TextView(context);
            tabLogcat.setText("Logcat");
            tabLogcat.setTextSize(14f);
            tabLogcat.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

            logcatWrapper.addView(tabLogcat);
            tabsContainer.addView(logcatWrapper);
            this.tabLogcatWrapper = logcatWrapper;

            logcatWrapper.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    currentTab = 3;
                    errorRenderer.refreshErrorUI();
                    updateTabUI();
                }
            });
        }

        if (tabErrorWrapper != null) {
            tabErrorWrapper.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    currentTab = 0;
                    errorRenderer.refreshErrorUI();
                    updateTabUI();
                }
            });
        }
        if (tabWarningWrapper != null) {
            tabWarningWrapper.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    currentTab = 1;
                    errorRenderer.refreshErrorUI();
                    updateTabUI();
                }
            });
        }
        if (tabLogWrapper != null) {
            tabLogWrapper.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    currentTab = 2;
                    errorRenderer.refreshErrorUI();
                    updateTabUI();
                }
            });
        }
        updateTabUI();
        startLogcatReader();

        OnTouchListener tapListener = new OnTouchListener() {
            long touchDownTime = 0;
            float startX = 0;
            float startY = 0;
            boolean isLongPress = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (currentTab == 2 || currentTab == 3) {
                    return false;
                }

                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    touchDownTime = System.currentTimeMillis();
                    startX = event.getX();
                    startY = event.getY();
                    isLongPress = false;
                } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    if (System.currentTimeMillis() - touchDownTime > 500) {
                        isLongPress = true;
                    }
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    long duration = System.currentTimeMillis() - touchDownTime;
                    float endX = event.getX();
                    float endY = event.getY();

                    if (isLongPress) {
                        return false;
                    }

                    if (duration < 350 && v instanceof TextView && Math.abs(endY - startY) < 50 && Math.abs(endX - startX) < 50) {
                        handleConsoleTap((TextView) v, event.getX(), event.getY());
                        return true;
                    } else if (endY - startY > 150) {
                        if (!(v instanceof ScrollView) || ((ScrollView) v).getScrollY() == 0) {
                            String activeProjPath = getActiveProjectPath();
                            File projDir = (activeProjPath != null && !activeProjPath.isEmpty()) ? new File(activeProjPath) : (liveAnalyzer != null ? liveAnalyzer.lastProjectDir : null);
                            if (liveAnalyzer != null && projDir != null) {
                                if (getContext() instanceof MainActivity) ((MainActivity) getContext()).showCustomToast("Refreshing Errors...");
                                else Toast.makeText(getContext(), "Refreshing Errors...", Toast.LENGTH_SHORT).show();
                                liveAnalyzer.startLiveProjectAnalysis(projDir, liveAnalyzer.lastToolsDirPath, liveAnalyzer.lastDynamicXmx, lastActiveFile);
                            } else {
                                if (getContext() instanceof MainActivity) ((MainActivity) getContext()).showCustomToast("No active project to refresh.");
                                else Toast.makeText(getContext(), "No active project to refresh.", Toast.LENGTH_SHORT).show();
                            }
                        }
                    }
                }
                return false;
            }
        };
        if (tvOutput != null) tvOutput.setOnTouchListener(tapListener);
        if (scrollView != null) scrollView.setOnTouchListener(tapListener);
        if (successLayout != null) successLayout.setOnTouchListener(tapListener);

        updateStatusHeader();
    }

    public void updateTabUI() {
        if (tabError == null || tabWarning == null || tabLog == null) return;
        boolean isLight = com.primx.utils.Setting.isLight(getContext());

        View tabErrorWrapper = findViewById(getResources().getIdentifier("tabErrorWrapper", "id", getContext().getPackageName()));
        View tabWarningWrapper = findViewById(getResources().getIdentifier("tabWarningWrapper", "id", getContext().getPackageName()));
        View tabLogWrapper = findViewById(getResources().getIdentifier("tabLogWrapper", "id", getContext().getPackageName()));

        int errorColor = 0xFFF44336;
        int warningColor = 0xFFFFC107;
        int logColor = 0xFF8BC34A;
        int logcatColor = 0xFF9C27B0;

        if (tabErrorWrapper != null) tabErrorWrapper.setBackground(getTabBackground(errorColor, currentTab == 0, isLight));
        if (tabWarningWrapper != null) tabWarningWrapper.setBackground(getTabBackground(warningColor, currentTab == 1, isLight));
        if (tabLogWrapper != null) tabLogWrapper.setBackground(getTabBackground(logColor, currentTab == 2, isLight));
        if (tabLogcatWrapper != null) tabLogcatWrapper.setBackground(getTabBackground(logcatColor, currentTab == 3, isLight));
    }

    private GradientDrawable getTabBackground(int baseColor, boolean isActive, boolean isLight) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dpToPx(18));
        d.setStroke(dpToPx(1.5f), baseColor);

        if (isActive) {
            int fillAlpha = isLight ? 25 : 40;
            int r = Color.red(baseColor);
            int g = Color.green(baseColor);
            int b = Color.blue(baseColor);
            int fillColor = Color.argb(fillAlpha, r, g, b);
            d.setColor(fillColor);
        } else {
            d.setColor(Color.TRANSPARENT);
        }
        return d;
    }
    private void showWarningFixDialog() {
        final Context context = getContext();
        final android.app.Dialog dialog = new android.app.Dialog(context);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }

        boolean isLight = com.primx.utils.Setting.isLight(context);
        int bgColor = getThemeColor(context, R.attr.ide_dialog_bg_color, isLight ? Color.WHITE : Color.parseColor("#2D2D2D"));
        int textColor = getThemeColor(context, R.attr.ide_text_primary, isLight ? Color.BLACK : Color.WHITE);
        int accentColor = Color.parseColor("#3574F0");

        android.widget.LinearLayout root = new android.widget.LinearLayout(context);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(16));

        android.graphics.drawable.GradientDrawable bgDrawable = new android.graphics.drawable.GradientDrawable();
        bgDrawable.setColor(bgColor);
        bgDrawable.setCornerRadius(dpToPx(18));
        root.setBackground(bgDrawable);

        TextView tvMsg = new TextView(context);
        tvMsg.setText("Fix All Warning Tap Fix Button");
        tvMsg.setTextColor(textColor);
        tvMsg.setTextSize(16f);
        tvMsg.setTypeface(null, android.graphics.Typeface.BOLD);
        tvMsg.setGravity(android.view.Gravity.CENTER);
        root.addView(tvMsg);

        android.widget.LinearLayout btnLayout = new android.widget.LinearLayout(context);
        btnLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        btnLayout.setGravity(android.view.Gravity.END);
        android.widget.LinearLayout.LayoutParams btnLp = new android.widget.LinearLayout.LayoutParams(
        android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.setMargins(0, dpToPx(24), 0, 0);
        btnLayout.setLayoutParams(btnLp);

        TextView btnCancel = new TextView(context);
        btnCancel.setText("CANCEL");
        btnCancel.setTextColor(Color.GRAY);
        btnCancel.setTextSize(14f);
        btnCancel.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        btnCancel.setTypeface(null, android.graphics.Typeface.BOLD);
        btnCancel.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        TextView btnFix = new TextView(context);
        btnFix.setText("FIX");
        btnFix.setTextColor(accentColor);
        btnFix.setTextSize(14f);
        btnFix.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        btnFix.setTypeface(null, android.graphics.Typeface.BOLD);
        btnFix.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                if (context instanceof MainActivity) {
                    ((MainActivity) context).showCustomToast("🛠️ Auto-Fixing Warnings...");
                }

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        int fixedCount = 0;
                        for (String filePath : errorMap.keySet()) {
                            List<ErrorItem> items = errorMap.get(filePath);
                            if (items == null) continue;
                            File f = new File(filePath);
                            if (!f.exists()) continue;

                            try {
                                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f));
                                StringBuilder sb = new StringBuilder();
                                String lineStr;
                                while((lineStr = br.readLine()) != null) sb.append(lineStr).append("\n");
                                br.close();

                                String[] lines = sb.toString().split("\n", -1);
                                boolean changed = false;

                                List<ErrorItem> warnings = new ArrayList<>();
                                for (ErrorItem e : items) if (e.isWarning) warnings.add(e);
                                java.util.Collections.sort(warnings, new java.util.Comparator<ErrorItem>() {
                                    public int compare(ErrorItem a, ErrorItem b) { return b.line - a.line; }
                                });

                                for (ErrorItem w : warnings) {
                                    int l = w.line - 1;
                                    if (l < 0 || l >= lines.length) continue;
                                    String msg = w.message.toLowerCase();

                                    if (msg.contains("import") && msg.contains("never used")) {
                                        lines[l] = "// Auto-removed unused import: " + lines[l].trim();
                                        changed = true;
                                        fixedCount++;
                                    } else if (msg.contains("never used")) {
                                        if (msg.contains("method")) {
                                            int braceCount = 0;
                                            boolean foundStart = false;
                                            for (int i = l; i < lines.length; i++) {
                                                String currLine = lines[i];
                                                lines[i] = "// Auto-removed unused method: " + currLine;
                                                for(int c = 0; c < currLine.length(); c++) {
                                                    if(currLine.charAt(c) == '{') { foundStart = true; braceCount++; }
                                                    if(currLine.charAt(c) == '}') braceCount--;
                                                }
                                                if (foundStart && braceCount <= 0) break;
                                                if (!foundStart && (i - l > 5)) break;
                                            }
                                            changed = true;
                                            fixedCount++;
                                        } else {
                                            lines[l] = "// Auto-removed unused variable: " + lines[l];
                                            changed = true;
                                            fixedCount++;
                                        }
                                    }
                                }

                                if (changed) {
                                    StringBuilder out = new StringBuilder();
                                    for (int i = 0; i < lines.length; i++) {
                                        out.append(lines[i]);
                                        if (i < lines.length - 1) out.append("\n");
                                    }
                                    java.io.FileWriter fw = new java.io.FileWriter(f);
                                    fw.write(out.toString());
                                    fw.close();
                                }
                            } catch (Exception e) {}
                        }

                        final int fc = fixedCount;
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                            public void run() {
                                if (context instanceof MainActivity) {
                                    if (fc > 0) {
                                        ((MainActivity) context).showCustomToast("✅ " + fc + " Warnings Fixed!");
                                    } else {
                                        ((MainActivity) context).showCustomToast("ℹ️ No fixable warnings found.");
                                    }
                                }
                            }
                        });
                    }
                }).start();
            }
        });

        btnLayout.addView(btnCancel);
        btnLayout.addView(btnFix);
        root.addView(btnLayout);

        dialog.setContentView(root);
        int width = (int)(getResources().getDisplayMetrics().widthPixels * 0.85);
        dialog.getWindow().setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.show();

        try { com.primx.utils.UIEffectsManager.applyPremiumBlur(dialog); } catch (Exception ignored){}
    }

        public void updateStatusHeader() {
        pruneForeignProjectErrors();

        boolean isLight = com.primx.utils.Setting.isLight(getContext());
        setBackgroundColor(getThemeColor(getContext(), R.attr.ide_bg_color,
        isLight ? Color.WHITE : Color.parseColor("#181818")));

        if (statusHeader != null) {
            statusHeader.setBackgroundResource(R.drawable.bg_whit_blac_theme);
        }
        
        int totalWarnings = 0;
        int totalErrors = 0;
        for (List<ErrorItem> lst : errorMap.values()) {
            for (ErrorItem e : lst) {
                if (e.isWarning) totalWarnings++;
                else totalErrors++;
            }
        }
        totalErrorCount = totalErrors;

        if (tvStatusText != null) tvStatusText.setVisibility(View.GONE);

        int tvConsoleTitleId = getResources().getIdentifier("tvConsoleTitle", "id", getContext().getPackageName());
        TextView tvConsoleTitle = tvConsoleTitleId != 0 ? (TextView) findViewById(tvConsoleTitleId) : null;

        int statusIconContainerId = getResources().getIdentifier("statusIconContainer", "id", getContext().getPackageName());
        android.widget.FrameLayout iconContainer = statusIconContainerId != 0 ? (android.widget.FrameLayout) findViewById(statusIconContainerId) : null;

        // 🔥 FIX 1: Container ko dynamic banaya taaki 2 icons aane par wo fail sake aur kate nahi
        if (iconContainer != null) {
            android.view.ViewGroup.LayoutParams icLp = iconContainer.getLayoutParams();
            if (icLp != null) {
                icLp.width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                iconContainer.setLayoutParams(icLp);
            }
        }

        if (tvConsoleTitle != null) {
             tvConsoleTitle.setText("PRIMX CONSOLE"); 
             tvConsoleTitle.setSingleLine(true);
             tvConsoleTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }

        if (totalErrors == 0 && totalWarnings == 0) {
            if (iconContainer != null) iconContainer.setVisibility(View.GONE);
            if (tvConsoleTitle != null) {
                tvConsoleTitle.setVisibility(View.VISIBLE);
                tvConsoleTitle.setTextSize(16f); // Perfect size
            }
            if (statusDivider != null) statusDivider.setBackgroundColor(isLight ? Color.parseColor("#E5E7EB") : Color.parseColor("#333333"));
            if (btnAiFix != null) btnAiFix.setText("❇️ AI");

        } else if (totalErrors > 0 && totalWarnings == 0) {
            if (iconContainer != null) {
                iconContainer.setVisibility(View.VISIBLE);
                iconContainer.removeAllViews();
                StatusIconView errIcon = new StatusIconView(getContext());
                errIcon.setStatus(false, false);
                iconContainer.addView(errIcon, new android.widget.FrameLayout.LayoutParams(dpToPx(18), dpToPx(18)));
            }
            if (tvConsoleTitle != null) {
                tvConsoleTitle.setVisibility(View.VISIBLE);
                tvConsoleTitle.setTextSize(14f);
            }
            if (statusDivider != null) statusDivider.setBackgroundColor(isLight ? Color.parseColor("#D32F2F") : Color.parseColor("#F44336"));
            if (btnAiFix != null) btnAiFix.setText("❇️ FIX");

        } else if (totalErrors == 0 && totalWarnings > 0) {
            if (iconContainer != null) {
                iconContainer.setVisibility(View.VISIBLE);
                iconContainer.removeAllViews();
                StatusIconView warnIcon = new StatusIconView(getContext());
                warnIcon.setStatus(false, true);
                
                warnIcon.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) { showWarningFixDialog(); }
                });
                
                iconContainer.addView(warnIcon, new android.widget.FrameLayout.LayoutParams(dpToPx(18), dpToPx(18)));
            }
            if (tvConsoleTitle != null) {
                tvConsoleTitle.setVisibility(View.VISIBLE);
                tvConsoleTitle.setTextSize(14f);
            }
            if (statusDivider != null) statusDivider.setBackgroundColor(isLight ? Color.parseColor("#F57F17") : Color.parseColor("#FFC107"));
            if (btnAiFix != null) btnAiFix.setText("❇️ FIX");

        } else {
            // 🔥 FIX 2: Jab Dono Error & Warning aayenge
            if (iconContainer != null) {
                iconContainer.setVisibility(View.VISIBLE);
                iconContainer.removeAllViews();

                LinearLayout dualIcon = new LinearLayout(getContext());
                dualIcon.setOrientation(LinearLayout.HORIZONTAL);
                dualIcon.setGravity(android.view.Gravity.CENTER_VERTICAL);

                StatusIconView errIcon = new StatusIconView(getContext());
                errIcon.setStatus(false, false);
                dualIcon.addView(errIcon, new LinearLayout.LayoutParams(dpToPx(18), dpToPx(18)));

                View spacer = new View(getContext());
                dualIcon.addView(spacer, new LinearLayout.LayoutParams(dpToPx(6), dpToPx(18)));

                StatusIconView warnIcon = new StatusIconView(getContext());
                warnIcon.setStatus(false, true);
                
                warnIcon.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) { showWarningFixDialog(); }
                });
                
                dualIcon.addView(warnIcon, new LinearLayout.LayoutParams(dpToPx(18), dpToPx(18)));

                iconContainer.addView(dualIcon, new android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            if (tvConsoleTitle != null) {
                tvConsoleTitle.setVisibility(View.GONE); 
            }
            if (statusDivider != null) statusDivider.setBackgroundColor(isLight ? Color.parseColor("#D32F2F") : Color.parseColor("#F44336"));
            if (btnAiFix != null) btnAiFix.setText("❇️ FIX");
        }

        EngineService.updateStatus(getContext(), totalErrors, totalWarnings);
    }
    public boolean hasGlobalErrors() {
        return totalErrorCount > 0;
    }

    public void forceUpdateStatusHeader() {
        updateStatusHeader();
    }

    private void handleConsoleTap(TextView tv, float tapX, float tapY) {
        if (tv == null || tv.getLayout() == null) return;

        Console.ErrorItem item = errorRenderer.getItemAtPosition(tv, tapX, tapY);
        if (item != null) {
            if (consoleListener != null) {
                consoleListener.onErrorClicked(
                item.filePath, item.line, item.colStart, item.colLen, item.message);
            }
            return;
        }

        try {
            int line = tv.getLayout().getLineForVertical((int) tapY);
            int startLine = Math.max(0, line - 3);
            int endLine = Math.min(tv.getLineCount() - 1, line + 3);
            int startOffset = tv.getLayout().getLineStart(startLine);
            int endOffset = tv.getLayout().getLineEnd(endLine);
            String contextText = tv.getText().subSequence(startOffset, endOffset).toString();

            for (String path : errorMap.keySet()) {
                File f = new File(path);
                String name = f.getName();
                if (name == null || name.isEmpty()) continue;
                if (!contextText.contains(name)) continue;

                int lNum = -1;
                Matcher m = Pattern.compile("Line\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(contextText);
                if (m.find()) {
                    try { lNum = Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
                }

                int cStart = -1, cLen = -1;
                String eMsg = "Error in " + name;

                if (lNum != -1) {
                    for (ErrorItem e : errorMap.get(path)) {
                        if (e.line == lNum) {
                            cStart = e.colStart;
                            cLen = e.colLen;
                            eMsg = e.message;
                            break;
                        }
                    }
                }
                if (lNum == -1 && !errorMap.get(path).isEmpty()) {
                    ErrorItem first = errorMap.get(path).get(0);
                    lNum = first.line > 0 ? first.line : 1;
                    cStart = first.colStart;
                    cLen = first.colLen;
                    eMsg = first.message;
                }

                if (consoleListener != null) consoleListener.onErrorClicked(path, lNum, cStart, cLen, eMsg);
                return;
            }
        } catch (Exception ignored) {}
    }

    public void startBuildLog() {
        currentTab = 2;
        rawLogs.setLength(0);
        if (tvOutput != null) tvOutput.setText("");
        updateTabUI();
        errorRenderer.refreshErrorUI();
        if (scrollView != null) scrollView.post(new Runnable() {
            @Override
            public void run() {
                scrollView.fullScroll(View.FOCUS_UP);
            }
        });
    }

    public void openBuildLogTab() {
        currentTab = 2;
        updateTabUI();
        errorRenderer.refreshErrorUI();
    }

    public void log(final String message) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (message == null || message.trim().isEmpty() || message.startsWith("#PROGRESS:")) return;
                String cleanMsg = message.replace("<font color='#4CAF50'>", "")
                .replace("<font color='#F44336'>", "")
                .replace("<b>", "").replace("</b>", "")
                .replace("</font>", "").replace("<br>", "\n").trim();

                if (cleanMsg.contains("SUCCESS_NO_ERRORS") || cleanMsg.contains("Build Complete") || cleanMsg.contains("Terminal Ready")) {
                    clearLogs();
                    return;
                }

                if (cleanMsg.startsWith("#ERROR:|") || cleanMsg.startsWith("#WARN:|") || cleanMsg.startsWith("#WARNING:|")) {
                    boolean isWarnMsg = cleanMsg.startsWith("#WARN:|") || cleanMsg.startsWith("#WARNING:|");
                    String[] parts = cleanMsg.split("\\|", 4);
                    if (parts.length >= 4) {
                        try {
                            String rawFile = parts[1].trim();
                            int ln = Integer.parseInt(parts[2].trim());
                            String rawBody = parts[3];

                            // If compiler sent raw multi-problem ECJ output, split & match files/lines/cols
                            if (rawBody.contains("(at line ") || rawBody.contains("----------") || "Compiler Error".equalsIgnoreCase(rawFile)) {
                                List<ErrorItem> parsed = new ArrayList<>();
                                parseRawEcjOutput(rawBody, null, parsed);
                                if (!parsed.isEmpty()) {
                                    for (ErrorItem item : parsed) {
                                        addErrorWithoutRefresh(item.filePath, item.line, item.colStart, item.colLen, item.message, item.isWarning);
                                    }
                                    if (totalErrorCount > 0 || hasAnyRealErrorsInMap()) {
                                        currentTab = 0;
                                    } else {
                                        currentTab = 1;
                                    }
                                    errorRenderer.refreshErrorUI();
                                    updateTabUI();
                                    pushErrorsToEditor();
                                    return;
                                }
                            }

                            // Skip redundant "Build System -> Java Compilation Failed!" if real errors already exist
                            if (("Build System".equalsIgnoreCase(rawFile) || "Compiler Error".equalsIgnoreCase(rawFile)) && hasAnyRealErrorsInMap()) {
                                currentTab = 0;
                                errorRenderer.refreshErrorUI();
                                updateTabUI();
                                return;
                            }

                            String formattedError = formatErrorMessage(rawBody);
                            boolean finalWarn = isWarnMsg || isWarningMessage(formattedError);
                            addError(rawFile, ln, -1, -1, formattedError, finalWarn);
                        } catch (Exception e) {}
                    }
                    return;
                }

                boolean isFirstLogAfterClear = (rawLogs.length() == 0);
                rawLogs.append(cleanMsg).append("\n");

                if (currentTab == 2) {
                    if (scrollView != null) scrollView.setVisibility(View.VISIBLE);
                    if (successLayout != null) successLayout.setVisibility(View.GONE);
                    if (tvOutput != null) {
                        tvOutput.setVisibility(View.VISIBLE);
                        if (isFirstLogAfterClear) {
                            CharSequence cur = tvOutput.getText();
                            if (cur != null && cur.toString().startsWith("Please run project")) {
                                tvOutput.setText("");
                            }
                        }
                        boolean isLight = com.primx.utils.Setting.isLight(getContext());
                        tvOutput.append(errorRenderer.colorizeLogLine(" " + cleanMsg, isLight));
                        tvOutput.append("\n");
                    }
                    if (scrollView != null) scrollView.post(new Runnable() {
                        @Override
                        public void run() {
                            scrollView.fullScroll(View.FOCUS_DOWN);
                        }
                    });
                }
            }
        });
    }

    private boolean hasAnyRealErrorsInMap() {
        for (List<ErrorItem> list : errorMap.values()) {
            for (ErrorItem e : list) {
                if (!e.isWarning) return true;
            }
        }
        return false;
    }

    public void parseRawEcjOutput(String rawOutput, Map<String, String> tempToRealPathMap, List<ErrorItem> outErrors) {
        if (rawOutput == null || rawOutput.trim().isEmpty()) return;

        Map<String, List<String>> projectJavaLinesCache = loadProjectJavaFilesCache();
        String[] fileGroups = rawOutput.replace("\r\n", "\n").split("----------\\s*\n\\s*----------");

        for (String group : fileGroups) {
            List<ErrorItem> groupItems = new ArrayList<>();
            List<Integer> groupLineNums = new ArrayList<>();
            List<String> groupCodeSnippets = new ArrayList<>();
            String explicitFilePath = "";

            String[] lines = group.split("\n");
            int i = 0;
            while (i < lines.length) {
                String trimmed = lines[i].trim();
                Matcher stdM = STD_ECJ_HEADER.matcher(trimmed);
                Matcher fbM = FALLBACK_ECJ_HEADER.matcher(trimmed);

                boolean isStd = stdM.matches();
                boolean isFb = !isStd && fbM.matches();

                if (isStd || isFb) {
                    boolean isWarn;
                    int lineNumber = 1;
                    if (isStd) {
                        isWarn = "WARNING".equalsIgnoreCase(stdM.group(1));
                        explicitFilePath = stdM.group(2).trim();
                        try { lineNumber = Integer.parseInt(stdM.group(3)); } catch (Exception ignored) {}
                    } else {
                        String kind = fbM.group(1);
                        isWarn = kind != null && "warning".equalsIgnoreCase(kind);
                        try { lineNumber = Integer.parseInt(fbM.group(3)); } catch (Exception ignored) {}
                    }

                    i++;
                    String codeLine = (i < lines.length) ? lines[i] : "";
                    i++;
                    String caretLine = (i < lines.length) ? lines[i] : "";

                    int colStart = -1;
                    int colLen = -1;
                    if (caretLine.contains("^")) {
                        int firstCaret = caretLine.indexOf('^');
                        int lastCaret = caretLine.lastIndexOf('^');
                        int tabOffset = codeLine.startsWith("\t") ? 1 : 0;
                        colStart = Math.max(0, firstCaret - tabOffset);
                        colLen = Math.max(1, (lastCaret - firstCaret) + 1);
                        i++;
                    }

                    StringBuilder msgBuilder = new StringBuilder();
                    while (i < lines.length) {
                        String mLine = lines[i].trim();
                        if (mLine.startsWith("----------") || mLine.startsWith("Missing message: compile.")) {
                            break;
                        }
                        if (STD_ECJ_HEADER.matcher(mLine).matches() || FALLBACK_ECJ_HEADER.matcher(mLine).matches()) {
                            i--;
                            break;
                        }
                        if (msgBuilder.length() > 0) msgBuilder.append(" ");
                        msgBuilder.append(mLine);
                        i++;
                    }

                    String cleanMsg = formatErrorMessage(msgBuilder.toString());
                    if (!cleanMsg.isEmpty()) {
                        if (isWarningMessage(cleanMsg)) isWarn = true;
                        groupItems.add(new ErrorItem("", lineNumber, colStart, colLen, cleanMsg, isWarn, false));
                        groupLineNums.add(lineNumber);
                        groupCodeSnippets.add(codeLine.trim());
                    }
                }
                i++;
            }

            if (groupItems.isEmpty()) continue;

            String resolvedPath = explicitFilePath;
            if (resolvedPath != null && !resolvedPath.isEmpty() && tempToRealPathMap != null) {
                if (tempToRealPathMap.containsKey(resolvedPath)) {
                    resolvedPath = tempToRealPathMap.get(resolvedPath);
                } else if (tempToRealPathMap.containsKey(canon(resolvedPath))) {
                    resolvedPath = tempToRealPathMap.get(canon(resolvedPath));
                }
            }

            if (resolvedPath == null || resolvedPath.isEmpty()) {
                resolvedPath = matchFileForGroup(projectJavaLinesCache, groupLineNums, groupCodeSnippets);
            }

            if (resolvedPath == null || resolvedPath.isEmpty()) {
                resolvedPath = (lastActiveFile != null) ? lastActiveFile.getAbsolutePath() : "Project File";
            }

            if (resolvedPath.contains("primx_live_ram/gen") && resolvedPath.endsWith("R.java")) {
                continue;
            }

            for (ErrorItem gi : groupItems) {
                boolean dup = false;
                for (ErrorItem ex : outErrors) {
                    if (ex.filePath.equals(resolvedPath) && ex.line == gi.line && ex.message.equals(gi.message)) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    outErrors.add(new ErrorItem(resolvedPath, gi.line, gi.colStart, gi.colLen, gi.message, gi.isWarning, false));
                }
            }
        }
    }

    private Map<String, List<String>> loadProjectJavaFilesCache() {
        Map<String, List<String>> cache = new HashMap<>();
        String projPath = getActiveProjectPath();
        if (projPath == null || projPath.isEmpty()) return cache;
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(new File(projPath), javaFiles);
        for (File jf : javaFiles) {
            List<String> fileLines = new ArrayList<>();
            BufferedReader br = null;
            try {
                br = new BufferedReader(new InputStreamReader(new FileInputStream(jf), "UTF-8"));
                String l;
                while ((l = br.readLine()) != null) {
                    fileLines.add(l.trim());
                }
                cache.put(jf.getAbsolutePath(), fileLines);
            } catch (Exception ignored) {
            } finally {
                try { if (br != null) br.close(); } catch (Exception ignored) {}
            }
        }
        return cache;
    }

    private void collectJavaFiles(File dir, List<File> out) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            if (f.isDirectory()) {
                if (n.equals(".git") || n.equals(".gradle") || n.equals("build")) continue;
                collectJavaFiles(f, out);
            } else if (n.endsWith(".java")) {
                out.add(f);
            }
        }
    }

    private String matchFileForGroup(Map<String, List<String>> cache, List<Integer> lineNums, List<String> snippets) {
        String bestFile = "";
        int bestScore = 0;

        for (Map.Entry<String, List<String>> entry : cache.entrySet()) {
            List<String> fLines = entry.getValue();
            int score = 0;
            for (int k = 0; k < lineNums.size(); k++) {
                int ln = lineNums.get(k) - 1;
                String snip = snippets.get(k);
                if (snip.isEmpty()) continue;
                if (ln >= 0 && ln < fLines.size()) {
                    String candidate = fLines.get(ln);
                    if (candidate.equals(snip)) {
                        score += 10;
                    } else if (candidate.contains(snip) || snip.contains(candidate)) {
                        score += 5;
                    }
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestFile = entry.getKey();
            }
        }
        return bestFile;
    }

    public void startLogcatReader() {
        if (logcatProcess != null) return;

        logcatLogs.append("[SYSTEM] App Logcat Engine Started!\n");
        logcatLogs.append("[SYSTEM] OS Noise Filtered. Info, Debug, Warnings & Crashes will appear here...\n");
        logcatLogs.append("───────────────────────────────────────────\n\n");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Runtime.getRuntime().exec("logcat -c").waitFor();
                    logcatProcess = Runtime.getRuntime().exec("logcat -v time");
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(logcatProcess.getInputStream()));
                    String line;
                    while ((line = br.readLine()) != null) {

                        // 🔥 PRO FILTER: आपके स्क्रीनशॉट में आए फालतू लॉग्‍स (libMEOW, MsyncFactory) को ब्लॉक कर दिया है
                        String lowerLine = line.toLowerCase();
                        if (lowerLine.contains("openglrenderer") || lowerLine.contains("eglmanager")
                        || lowerLine.contains("type=1400 audit") || lowerLine.contains("ziparchive")
                        || lowerLine.contains("apksig") || lowerLine.contains("apksigner")
                        || lowerLine.contains("mali") || lowerLine.contains("gralloc")
                        || lowerLine.contains("avc: denied") || lowerLine.contains("avc: granted")
                        || lowerLine.contains("dalvik.system.closeguard") || lowerLine.contains("ion")
                        || lowerLine.contains("wificond") || lowerLine.contains("battery")
                        || lowerLine.contains("activitymanager: start u0")
                        || lowerLine.contains("networkmanagement") || lowerLine.contains("netd")
                        || lowerLine.contains("system_server") || lowerLine.contains("surfaceflinger")
                        || lowerLine.contains("msyncfactory") || lowerLine.contains("libmeow")
                        || lowerLine.contains("powerhalwrapper") || lowerLine.contains("bufferqueueconsumer")
                        || lowerLine.contains("blastbufferqueue") || lowerLine.contains("compatibilitychangereporter")) {
                            continue;
                        }

                        final String l = line;
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                logcatLogs.append(l).append("\n");
                                if (logcatLogs.length() > 50000) {
                                    logcatLogs.delete(0, 10000);
                                }
                                if (currentTab == 3 && tvOutput != null) {
                                    boolean isLight = com.primx.utils.Setting.isLight(getContext());
                                    tvOutput.append(errorRenderer.colorizeLogcatLine(l, isLight));
                                    tvOutput.append("\n");
                                    if (scrollView != null) scrollView.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            scrollView.fullScroll(View.FOCUS_DOWN);
                                        }
                                    });
                                }
                            }
                        });
                    }
                } catch (Exception e) {}
            }
        }).start();
    }
    public void updateFileInstantErrors(final String filePath, final List<ErrorItem> fileErrors) {
        runOnMain(new Runnable() {
            @Override
            public void run() {
                if (filePath == null || filePath.isEmpty() || !isFileInActiveProject(filePath)) return;
                String key = resolveKey(filePath);
                if (fileErrors == null || fileErrors.isEmpty()) {
                    errorMap.remove(key);
                } else {
                    List<ErrorItem> cleanList = new ArrayList<>();
                    for (ErrorItem item : fileErrors) {
                        cleanList.add(new ErrorItem(key, item.line, item.colStart, item.colLen, formatErrorMessage(item.message), item.isWarning, item.isLiveAst));
                    }
                    errorMap.put(key, cleanList);
                }
                errorRenderer.refreshErrorUI();
                pushErrorsToEditor();
            }
        });
    }

    public void addLiveDiagnostic(String filePath, int line, int colStart, int errorLength, String errorMessage) {
        if (!isFileInActiveProject(filePath)) return;
        String key = resolveKey(filePath);
        String msg = formatErrorMessage(errorMessage);
        if (!errorMap.containsKey(key)) {
            errorMap.put(key, new ArrayList<ErrorItem>());
        }
        for (ErrorItem item : errorMap.get(key)) {
            if (item.line == line && item.message.equals(msg)) return;
        }
        errorMap.get(key).add(new ErrorItem(key, line, colStart, errorLength, msg, false, false));
        errorRenderer.refreshErrorUI();
        pushErrorsToEditor();
    }

    public void addAstDiagnostic(final String filePath, final int line, final int col, final int len, final String message, final boolean isWarning) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (!isFileInActiveProject(filePath)) return;
                String key = resolveKey(filePath);
                if (!errorMap.containsKey(key)) errorMap.put(key, new ArrayList<ErrorItem>());
                boolean duplicate = false;
                String clean = formatErrorMessage(message);
                for (ErrorItem item : errorMap.get(key)) {
                    if (item.line == line && item.message.equals(clean)) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    errorMap.get(key).add(new ErrorItem(key, line, col, len, clean, isWarning, true));
                    errorRenderer.refreshErrorUI();
                    pushErrorsToEditor();
                }
            }
        });
    }

    public void clearAstErrorsForFile(final String filePath) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (filePath != null) {
                    String key = resolveKey(filePath);
                    if (errorMap.containsKey(key)) {
                        List<ErrorItem> items = errorMap.get(key);
                        List<ErrorItem> toRemove = new ArrayList<>();
                        for (ErrorItem e : items) if (e.isLiveAst) toRemove.add(e);
                        items.removeAll(toRemove);
                        if (items.isEmpty()) errorMap.remove(key);
                        errorRenderer.refreshErrorUI();
                        pushErrorsToEditor();
                    }
                }
            }
        });
    }

    public void clearLogs() {
        if (tvOutput != null) {
            tvOutput.setText("");
            tvOutput.setVisibility(View.GONE);
        }
        rawLogs.setLength(0);
        errorMap.clear();
        totalErrorCount = 0;
        updateStatusHeader();
        if (successLayout != null) successLayout.setVisibility(View.VISIBLE);
        if (consoleListener != null) consoleListener.onLiveDiagnosticsClear();

        errorRenderer.refreshErrorUI();
    }

    public void clearLiveErrorsUI() {
        errorMap.clear();
        totalErrorCount = 0;
        errorRenderer.refreshErrorUI();
        if (consoleListener != null) consoleListener.onLiveDiagnosticsClear();
        pushErrorsToEditor();
    }

    public void clearCompilerErrors() {}

    public void clearErrorsForFile(String filePath) {
        if (filePath != null) {
            String key = resolveKey(filePath);
            if (errorMap.containsKey(key) && !new File(filePath).exists()) {
                errorMap.remove(key);
                errorRenderer.refreshErrorUI();
                pushErrorsToEditor();
            }
        }
    }

    public List<ErrorItem> getErrorsForFile(String filePath) {
        if (filePath != null) {
            String key = resolveKey(filePath);
            if (errorMap.containsKey(key)) return new ArrayList<>(errorMap.get(key));
            String targetName = new File(filePath).getName();
            for (String k : errorMap.keySet()) {
                if (new File(k).getName().equals(targetName)) {
                    return new ArrayList<>(errorMap.get(k));
                }
            }
        }
        return new ArrayList<>();
    }

    private void runOnMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else new Handler(Looper.getMainLooper()).post(r);
    }

    public static String canon(String p) {
        if (p == null) return "";
        try {
            return new File(p).getCanonicalPath();
        } catch (Exception e) {
            return p;
        }
    }

    public String resolveKey(String path) {
        if (path == null) return "";
        if (errorMap.containsKey(path)) return path;
        String c = canon(path);
        for (String k : errorMap.keySet()) {
            if (canon(k).equals(c)) return k;
        }
        return path;
    }

    public void applyLiveAnalysis(final File analysedFile, final List<ErrorItem> found) {
        runOnMain(new Runnable() {
            @Override
            public void run() {
                // 🔥 FIX: Agar live analyzer ko koi result nahi mila (jaise in-process ECJ skip hua),
                // toh pehle se maujood Compiler Errors ko bilkul delete mat karo!
                if ((found == null || found.isEmpty()) && !errorMap.isEmpty()) {
                    pushCompilerErrorsToEditor();
                    return;
                }

                errorMap.clear();

                if (found != null) {
                    for (ErrorItem it : found) {
                        if (it == null || it.filePath == null || !isFileInActiveProject(it.filePath)) continue;
                        String key = resolveKey(it.filePath);
                        List<ErrorItem> list = errorMap.get(key);
                        if (list == null) {
                            list = new ArrayList<>();
                            errorMap.put(key, list);
                        }
                        String cleanMsg = formatErrorMessage(it.message);
                        boolean dup = false;
                        for (ErrorItem x : list) {
                            if (x.line == it.line && x.message.equals(cleanMsg)) {
                                dup = true;
                                break;
                            }
                        }
                        if (!dup) {
                            list.add(new ErrorItem(key, it.line, it.colStart, it.colLen, cleanMsg, it.isWarning, false));
                        }
                    }
                }

                errorRenderer.refreshErrorUI();
                pushCompilerErrorsToEditor();
            }
        });
    }

    private void pushCompilerErrorsToEditor() {
        if (consoleListener == null) return;
        List<ErrorItem> snapshot = new ArrayList<>();
        for (List<ErrorItem> list : errorMap.values()) {
            for (ErrorItem e : list) {
                snapshot.add(e);
            }
        }
        consoleListener.onLiveDiagnosticsClear();
        for (ErrorItem e : snapshot) {
            consoleListener.onLiveDiagnosticError(e.filePath, e.line, e.colStart, e.colLen, e.message, e.isWarning);
        }
    }

    private void pushErrorsToEditor() {
        pushCompilerErrorsToEditor();
    }

    private void addErrorWithoutRefresh(String filePath, int line, int colStart, int colLen, String msg, boolean isWarn) {
        if (!isFileInActiveProject(filePath)) return;
        String key = resolveKey(filePath);
        if (!errorMap.containsKey(key)) errorMap.put(key, new ArrayList<ErrorItem>());
        for (ErrorItem item : errorMap.get(key)) {
            if (item.line == line && item.message.equals(msg)) return;
        }
        errorMap.get(key).add(new ErrorItem(key, line, colStart, colLen, msg, isWarn, false));
    }

    public void addError(String filePath, int line, int colStart, int colLen, String msg, boolean isWarn) {
        addErrorWithoutRefresh(filePath, line, colStart, colLen, msg, isWarn);
        errorRenderer.refreshErrorUI();
        pushErrorsToEditor();
    }

    public static void installApk(Context context, String apkPath) {
        File apkFile = new File(apkPath);
        if (!apkFile.exists()) {
            if (context instanceof MainActivity) {
                ((MainActivity) context).showCustomToast("APK not found!");
            }
            return;
        }
        Uri contentUri = Uri.parse("content://" + context.getPackageName() + ".consoleprovider" + apkFile.getAbsolutePath());
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setDataAndType(contentUri, "application/vnd.android.package-archive");
        context.startActivity(intent);
    }

    public static class MiniProvider extends android.content.ContentProvider {
        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public android.database.Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
            if (projection == null) projection = new String[]{android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE};
            android.database.MatrixCursor cursor = new android.database.MatrixCursor(projection);
            File file = new File(uri.getPath());
            Object[] row = new Object[projection.length];
            for (int i = 0; i < projection.length; i++) {
                if (android.provider.OpenableColumns.DISPLAY_NAME.equals(projection[i])) row[i] = file.getName();
                else if (android.provider.OpenableColumns.SIZE.equals(projection[i])) row[i] = file.length();
            }
            cursor.addRow(row);
            return cursor;
        }

        @Override
        public String getType(Uri uri) {
            return "application/vnd.android.package-archive";
        }

        @Override
        public Uri insert(Uri uri, android.content.ContentValues values) {
            return null;
        }

        @Override
        public int delete(Uri uri, String selection, String[] selectionArgs) {
            return 0;
        }

        @Override
        public int update(Uri uri, android.content.ContentValues values, String selection, String[] selectionArgs) {
            return 0;
        }

        @Override
        public android.os.ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
            return android.os.ParcelFileDescriptor.open(new File(uri.getPath()), android.os.ParcelFileDescriptor.MODE_READ_ONLY);
        }
    }

    public int getThemeColor(Context context, int attrResId, int defaultColor) {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        if (context.getTheme().resolveAttribute(attrResId, typedValue, true)) {
            return typedValue.data;
        }
        return defaultColor;
    }
}
