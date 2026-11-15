package com.termux;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.TrafficStats;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import com.primx.ui.mainactivity.MainActivity;

public class Terminal implements TerminalSession.TerminalUiListener {
    private final MainActivity activity;
    private final ViewGroup hostContainer;
    private final Handler mainHandler;

    private TerminalSession sessionManager;

    private FrameLayout rootOverlay;
    private LinearLayout mainLayout;
    private LinearLayout drawerLayout;
    private ScrollView outputScrollView;
    private EditText outputText;
    private LinearLayout sessionListContainer;
    private LinearLayout quickKeysContainer;
    private TextView headerTitleText;
    private View networkIndicatorDot;
    private HorizontalScrollView quickKeysScroll;
    private LinearLayout dragHandleBar;

    private TextView themeToggleDot;
    private View btnMinimize;
    private View btnExpand;

    private boolean isLightTheme = false;
    private int bgPrimary;
    private int bgSecondary;
    private int bgDrawer;
    private int textPrimary;
    private int textSecondary;
    private final int accentColor = 0xFF2196F3;
    private int borderColor;

    private int sheetHeightPx;
    private int minHandleHeightPx;
    private int dragStartY;
    private int dragStartHeight;
    private final Handler netCheckHandler = new Handler(Looper.getMainLooper());
    private Runnable netCheckRunnable;

    private long lastRxBytes = 0;
    private long lastTxBytes = 0;
    private long lastCheckTime = 0;

    private int effectiveLockedLen = 0;
    private String promptSuffix = "";
    private boolean suppressTextWatcher = false;
    private boolean isKeyboardVisible = false; // 🔥 नया वेरिएबल

    // Dynamic Projects
    private boolean showProjects = true;
    private List<File> cachedProjects = null;
    private boolean isScanningProjects = false;

    // 🔥 CUSTOM USER BUTTONS
    private List<String[]> customButtons = null;

    public Terminal(MainActivity activity, ViewGroup hostContainer, String initialProjectPath) {
        this.activity = activity;
        this.hostContainer = hostContainer;
        this.mainHandler = new Handler(Looper.getMainLooper());

        detectThemeColors();
        initViewsProgrammatically();
        setupQuickKeys();

        this.sessionManager = new TerminalSession(activity, initialProjectPath, isLightTheme, textPrimary, this);
        this.sessionManager.initOrResumeSession(); // 🔥 अब यह चेक करेगा कि पुराना सेशन है या नहीं

        setupInputLockLogic();
        startNetworkSpeedMonitor();
        startProjectScan();
    }

        private void detectThemeColors() {
        try {
            // 🔥 NAYA: Pehle check karega ki user ne manually kya save kiya tha
            SharedPreferences prefs = activity.getSharedPreferences("primx_terminal_prefs", Context.MODE_PRIVATE);
            if (prefs.contains("is_light_theme")) {
                isLightTheme = prefs.getBoolean("is_light_theme", false);
            } else {
                int nightModeFlags = activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
                isLightTheme = (nightModeFlags == Configuration.UI_MODE_NIGHT_NO);
            }
        } catch (Throwable ignored) {
            isLightTheme = false;
        }
        applyThemeColorValues();
    }

    private void applyThemeColorValues() {
        if (isLightTheme) {
            bgPrimary = 0xFFF8F9FA;
            bgSecondary = 0xFFECEFF1;
            bgDrawer = 0xFFFFFFFF;
            textPrimary = 0xFF212121;
            textSecondary = 0xFF616161;
            borderColor = 0xFFDADCE0;
        } else {
            bgPrimary = 0xFF0D1117;
            bgSecondary = 0xFF161B22;
            bgDrawer = 0xFF12161F;
            textPrimary = 0xFFE6EDF3;
            textSecondary = 0xFF8B949E;
            borderColor = 0xFF30363D;
        }
    }

        private void toggleAppTheme() {
        isLightTheme = !isLightTheme;
        // 🔥 NAYA: Theme change hote hi save kar do
        activity.getSharedPreferences("primx_terminal_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("is_light_theme", isLightTheme).apply();
        
        applyThemeColorValues();
        // ... baaki ka poora code waisa hi rahega

        if (sessionManager != null) sessionManager.updateThemeColors(isLightTheme, textPrimary);

        if (mainLayout != null) mainLayout.setBackground(createBoxDrawable(bgPrimary, borderColor, 18, 0));
        if (outputScrollView != null) outputScrollView.setBackgroundColor(bgPrimary);
        if (outputText != null) outputText.setTextColor(textPrimary);
        if (dragHandleBar != null) dragHandleBar.setBackground(createBoxDrawable(bgSecondary, borderColor, 18, 0));
        if (headerTitleText != null) headerTitleText.setTextColor(textSecondary);
        if (quickKeysScroll != null) quickKeysScroll.setBackgroundColor(bgSecondary);
        if (drawerLayout != null) drawerLayout.setBackground(createBoxDrawable(bgDrawer, borderColor, 18, 0));

        int dynamicStrokeColor = isLightTheme ? 0xFF000000 : 0xFFFFFFFF;
        if (themeToggleDot != null) themeToggleDot.setBackground(createBoxDrawable(accentColor, dynamicStrokeColor, 15, 15));
        if (btnMinimize != null) btnMinimize.invalidate();
        if (btnExpand != null) btnExpand.invalidate();

        setupQuickKeys();
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }

    private GradientDrawable createBoxDrawable(int fillColor, int strokeColor, int topRadiusDp, int bottomRadiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(fillColor);
        float topR = dp(topRadiusDp);
        float botR = dp(bottomRadiusDp);
        gd.setCornerRadii(new float[]{topR, topR, topR, topR, botR, botR, botR, botR});
        if (strokeColor != 0) gd.setStroke(dp(1), strokeColor);
        return gd;
    }

    private View createCircularFabButton(final String symbol, int sizeDp, int bgColor, final int fgColor, View.OnClickListener listener) {
        View fab = new View(activity) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                float cx = getWidth() / 2f, cy = getHeight() / 2f;
                float r = Math.min(cx, cy) - dp(2);
                paint.setStyle(Paint.Style.FILL); paint.setColor(bgColor);
                canvas.drawCircle(cx, cy, r, paint);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1));
                paint.setColor(isLightTheme ? 0xFF000000 : 0xFFFFFFFF);
                canvas.drawCircle(cx, cy, r, paint);
                paint.setStrokeWidth(dp(2)); paint.setColor(fgColor);
                if ("+".equals(symbol)) {
                    float len = r * 0.45f;
                    canvas.drawLine(cx - len, cy, cx + len, cy, paint);
                    canvas.drawLine(cx, cy - len, cx, cy + len, paint);
                } else if ("_".equals(symbol)) {
                    float len = r * 0.45f;
                    canvas.drawLine(cx - len, cy + (len * 0.3f), cx + len, cy + (len * 0.3f), paint);
                } else if ("^".equals(symbol)) {
                    float len = r * 0.4f;
                    canvas.drawLine(cx - len, cy + (len * 0.3f), cx, cy - (len * 0.4f), paint);
                    canvas.drawLine(cx, cy - (len * 0.4f), cx + len, cy + (len * 0.3f), paint);
                } else if ("X".equals(symbol)) {
                    float len = r * 0.38f;
                    canvas.drawLine(cx - len, cy - len, cx + len, cy + len, paint);
                    canvas.drawLine(cx + len, cy - len, cx - len, cy + len, paint);
                }
            }
        };
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.setMargins(dp(4), 0, dp(4), 0);
        fab.setLayoutParams(lp);
        fab.setOnClickListener(listener);
        return fab;
    }

    private void initViewsProgrammatically() {
        int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
        minHandleHeightPx = dp(46);
        sheetHeightPx = (int) (screenHeight * 0.75f);

        rootOverlay = new FrameLayout(activity);
        rootOverlay.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View dimOverlay = new View(activity);
        dimOverlay.setBackgroundColor(0x99000000);
        rootOverlay.addView(dimOverlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dimOverlay.setOnClickListener(v -> closeTerminal());

        mainLayout = new LinearLayout(activity);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, sheetHeightPx, Gravity.BOTTOM));
        mainLayout.setBackground(createBoxDrawable(bgPrimary, borderColor, 18, 0));

        dragHandleBar = buildDraggableHeaderBar();
        mainLayout.addView(dragHandleBar);

        FrameLayout contentContainer = new FrameLayout(activity);
        mainLayout.addView(contentContainer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        outputScrollView = new ScrollView(activity);
        outputScrollView.setFillViewport(true);
        outputScrollView.setBackgroundColor(bgPrimary);

        outputText = new EditText(activity) {
            @Override protected void onSelectionChanged(int selStart, int selEnd) {
                if (getText() != null && selStart == selEnd && selStart < effectiveLockedLen) {
                    setSelection(getText().length());
                    return;
                }
                super.onSelectionChanged(selStart, selEnd);
            }
        };

        outputText.addTextChangedListener(new TextWatcher() {
            private String beforeSnapshot;
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                if (suppressTextWatcher) return;
                beforeSnapshot = s.toString();
            }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (suppressTextWatcher || beforeSnapshot == null) { beforeSnapshot = null; return; }
                if (sessionManager != null && sessionManager.isCommandInProgress()) {
                    suppressTextWatcher = true;
                    outputText.setText(beforeSnapshot);
                    outputText.setSelection(beforeSnapshot.length());
                    suppressTextWatcher = false;
                    beforeSnapshot = null;
                    return;
                }
                int checkLen = Math.min(effectiveLockedLen, beforeSnapshot.length());
                if (checkLen > 0) {
                    String currentText = s.toString();
                    if (currentText.length() < checkLen ||
                    !currentText.substring(0, checkLen).equals(beforeSnapshot.substring(0, checkLen))) {
                        suppressTextWatcher = true;
                        outputText.setText(beforeSnapshot);
                        outputText.setSelection(beforeSnapshot.length());
                        suppressTextWatcher = false;
                    }
                }
                beforeSnapshot = null;
            }
        });

        outputText.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        outputText.setBackgroundColor(0x00000000);
        outputText.setTextColor(textPrimary);
        outputText.setTextSize(13.5f);
        outputText.setTypeface(Typeface.MONOSPACE);
        outputText.setGravity(Gravity.TOP | Gravity.START);
        outputText.setPadding(dp(12), dp(10), dp(12), dp(10));
        outputText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        outputText.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NONE);
        outputText.setCursorVisible(true);
        outputText.setHorizontallyScrolling(false);
        outputText.setTextIsSelectable(true);

        outputScrollView.addView(outputText);
        contentContainer.addView(outputScrollView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        quickKeysScroll = new HorizontalScrollView(activity);
        quickKeysScroll.setHorizontalScrollBarEnabled(false);
        quickKeysScroll.setBackgroundColor(bgSecondary);
        quickKeysScroll.setPadding(dp(6), dp(6), dp(6), dp(6));
        quickKeysScroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        quickKeysContainer = new LinearLayout(activity);
        quickKeysContainer.setOrientation(LinearLayout.HORIZONTAL);
        quickKeysContainer.setGravity(Gravity.CENTER_VERTICAL);
        quickKeysContainer.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        quickKeysScroll.addView(quickKeysContainer);
        mainLayout.addView(quickKeysScroll);

        buildLeftSessionDrawer();
        contentContainer.addView(drawerLayout);
        rootOverlay.addView(mainLayout);
        hostContainer.addView(rootOverlay);

        mainLayout.setTranslationY(screenHeight);
        mainLayout.animate().translationY(0).setDuration(300).start();

        // 🔥 FIX 1: Keyboard detection and resizing
        rootOverlay.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            android.graphics.Rect r = new android.graphics.Rect();
            rootOverlay.getWindowVisibleDisplayFrame(r);
            int currentScreenHeight = rootOverlay.getRootView().getHeight();
            int keypadHeight = currentScreenHeight - r.bottom;

            if (keypadHeight > currentScreenHeight * 0.15) {
                if (!isKeyboardVisible) {
                    isKeyboardVisible = true;
                    setSheetHeight(currentScreenHeight - keypadHeight);
                }
            } else {
                if (isKeyboardVisible) {
                    isKeyboardVisible = false;
                    setSheetHeight((int) (currentScreenHeight * 0.75f));
                }
            }
        });
    }

    private LinearLayout buildDraggableHeaderBar() {
        LinearLayout handleBar = new LinearLayout(activity);
        handleBar.setOrientation(LinearLayout.HORIZONTAL);
        handleBar.setGravity(Gravity.CENTER_VERTICAL);
        handleBar.setPadding(dp(12), dp(6), dp(10), dp(6));
        handleBar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, minHandleHeightPx));
        handleBar.setBackground(createBoxDrawable(bgSecondary, borderColor, 18, 0));

        LinearLayout centerGripZone = new LinearLayout(activity);
        centerGripZone.setOrientation(LinearLayout.VERTICAL);
        centerGripZone.setGravity(Gravity.CENTER_HORIZONTAL);
        centerGripZone.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View pillBar = new View(activity);
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(dp(44), dp(5));
        pillParams.setMargins(0, 0, 0, dp(4));
        pillBar.setLayoutParams(pillParams);
        pillBar.setBackground(createBoxDrawable(accentColor, 0, 4, 4));

        headerTitleText = new TextView(activity);
        headerTitleText.setText("PRIMX HYBRID TERMINAL");
        headerTitleText.setTextSize(11.5f);
        headerTitleText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        headerTitleText.setTextColor(textSecondary);

        centerGripZone.addView(pillBar);
        centerGripZone.addView(headerTitleText);
        handleBar.addView(centerGripZone);

        networkIndicatorDot = new View(activity);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(14), dp(14));
        dotLp.setMargins(dp(6), 0, dp(4), 0);
        networkIndicatorDot.setLayoutParams(dotLp);
        networkIndicatorDot.setBackground(createBoxDrawable(0xFF9E9E9E, 0, 7, 7));
        handleBar.addView(networkIndicatorDot);

        int dynamicStrokeColor = isLightTheme ? 0xFF000000 : 0xFFFFFFFF;

        themeToggleDot = new TextView(activity);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        tLp.setMargins(dp(4), 0, dp(4), 0);
        themeToggleDot.setLayoutParams(tLp);
        themeToggleDot.setText("T");
        themeToggleDot.setTextSize(12f);
        themeToggleDot.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        themeToggleDot.setTextColor(0xFFFFFFFF);
        themeToggleDot.setGravity(Gravity.CENTER);
        themeToggleDot.setBackground(createBoxDrawable(accentColor, dynamicStrokeColor, 15, 15));
        themeToggleDot.setOnClickListener(v -> toggleAppTheme());
        handleBar.addView(themeToggleDot);

        btnMinimize = createCircularFabButton("_", 30, bgPrimary, textPrimary, v -> setSheetHeight(minHandleHeightPx));
        btnExpand = createCircularFabButton("^", 30, bgPrimary, accentColor, v -> {
            int maxH = hostContainer.getHeight() > 0 ? hostContainer.getHeight() : activity.getResources().getDisplayMetrics().heightPixels;
            if (sheetHeightPx < (maxH * 0.85f)) setSheetHeight(maxH);
            else setSheetHeight((int) (maxH * 0.75f));
        });

        handleBar.addView(btnMinimize);
        handleBar.addView(btnExpand);

        View.OnTouchListener dragListener = (v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                dragStartY = (int) event.getRawY();
                dragStartHeight = mainLayout.getLayoutParams().height;
                return true;
                case MotionEvent.ACTION_MOVE:
                int deltaY = dragStartY - (int) event.getRawY();
                setSheetHeight(dragStartHeight + deltaY);
                return true;
            }
            return false;
        };

        handleBar.setOnTouchListener(dragListener);
        centerGripZone.setOnTouchListener(dragListener);
        return handleBar;
    }

    private void startNetworkSpeedMonitor() {
        lastRxBytes = TrafficStats.getTotalRxBytes();
        lastTxBytes = TrafficStats.getTotalTxBytes();
        lastCheckTime = System.currentTimeMillis();

        netCheckRunnable = new Runnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                long rx = TrafficStats.getTotalRxBytes();
                long tx = TrafficStats.getTotalTxBytes();
                long timeDiff = now - lastCheckTime;
                if (timeDiff > 0) {
                    long bytesPerSec = (((rx - lastRxBytes) + (tx - lastTxBytes)) * 1000) / timeDiff;
                    int colorHex;
                    if (bytesPerSec < 512) colorHex = 0xFF9E9E9E;
                    else if (bytesPerSec < 10240) colorHex = 0xFF4CAF50;
                    else if (bytesPerSec < 51200) colorHex = 0xFF00BCD4;
                    else if (bytesPerSec < 204800) colorHex = 0xFF2196F3;
                    else if (bytesPerSec < 1048576) colorHex = 0xFF9C27B0;
                    else colorHex = 0xFFFF5252;
                    setDotColor(colorHex);
                }
                lastRxBytes = rx; lastTxBytes = tx; lastCheckTime = now;
                netCheckHandler.postDelayed(this, 1000);
            }
        };
        netCheckHandler.post(netCheckRunnable);
    }

    private void setDotColor(int colorHex) {
        if (networkIndicatorDot != null) {
            networkIndicatorDot.setBackground(createBoxDrawable(colorHex, 0, 7, 7));
        }
    }

    private void setSheetHeight(int targetHeightPx) {
        int maxHostHeight = hostContainer.getHeight() > 0 ? hostContainer.getHeight() : activity.getResources().getDisplayMetrics().heightPixels;
        int clamped = Math.max(minHandleHeightPx, Math.min(targetHeightPx, maxHostHeight));
        sheetHeightPx = clamped;
        ViewGroup.LayoutParams lp = mainLayout.getLayoutParams();
        lp.height = clamped;
        mainLayout.setLayoutParams(lp);
    }

    private void buildLeftSessionDrawer() {
        drawerLayout = new LinearLayout(activity);
        drawerLayout.setOrientation(LinearLayout.VERTICAL);

        // 🔥 FIX 2: चौड़ाई 260dp से 200dp कर दी, और फोकस रोक दिया
        FrameLayout.LayoutParams drawerParams = new FrameLayout.LayoutParams(dp(200), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
        drawerLayout.setLayoutParams(drawerParams);
        drawerLayout.setBackground(createBoxDrawable(bgDrawer, borderColor, 18, 0));
        drawerLayout.setPadding(dp(14), dp(14), dp(14), dp(14));

        drawerLayout.setClickable(true);
        drawerLayout.setFocusable(true);
        drawerLayout.setFocusableInTouchMode(true);
        drawerLayout.setVisibility(View.GONE);

        LinearLayout drawerHeader = new LinearLayout(activity);
        drawerHeader.setOrientation(LinearLayout.HORIZONTAL);
        drawerHeader.setGravity(Gravity.CENTER_VERTICAL);
        drawerHeader.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView drawerTitle = new TextView(activity);
        drawerTitle.setText("SESSIONS & CLOUD");
        drawerTitle.setTextSize(15f);
        drawerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        drawerTitle.setTextColor(textPrimary);
        drawerTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        drawerHeader.addView(drawerTitle);

        View btnCloseDrawer = createCircularFabButton("X", 32, bgSecondary, accentColor, v -> {
            drawerLayout.setVisibility(View.GONE);
            outputText.requestFocus();
        });
        drawerHeader.addView(btnCloseDrawer);
        drawerLayout.addView(drawerHeader);

        ScrollView sessionScroll = new ScrollView(activity);
        LinearLayout.LayoutParams sessionScrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sessionScrollParams.setMargins(0, dp(12), 0, dp(12));
        sessionScroll.setLayoutParams(sessionScrollParams);

        sessionListContainer = new LinearLayout(activity);
        sessionListContainer.setOrientation(LinearLayout.VERTICAL);
        sessionListContainer.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sessionScroll.addView(sessionListContainer);
        drawerLayout.addView(sessionScroll);

        LinearLayout newSessionRow = new LinearLayout(activity);
        newSessionRow.setOrientation(LinearLayout.HORIZONTAL);
        newSessionRow.setGravity(Gravity.CENTER);
        newSessionRow.setPadding(dp(12), dp(8), dp(12), dp(8));
        newSessionRow.setBackground(createBoxDrawable(accentColor, 0, 16, 16));
        newSessionRow.setOnClickListener(v -> {
            try { if (sessionManager != null) sessionManager.createNewSession(); }
            catch (Exception e) { Toast.makeText(activity, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show(); }
        });

        View plusFab = createCircularFabButton("+", 26, 0x22FFFFFF, 0xFFFFFFFF, v -> {
            try { if (sessionManager != null) sessionManager.createNewSession(); }
            catch (Exception e) { Toast.makeText(activity, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show(); }
        });
        TextView btnNewText = new TextView(activity);
        btnNewText.setText("NEW SESSION");
        btnNewText.setTextSize(13f);
        btnNewText.setTypeface(Typeface.DEFAULT_BOLD);
        btnNewText.setTextColor(0xFFFFFFFF);

        newSessionRow.addView(plusFab);
        newSessionRow.addView(btnNewText);
        drawerLayout.addView(newSessionRow);
    }

    private void closeTerminal() {
        try { if (netCheckHandler != null && netCheckRunnable != null) netCheckHandler.removeCallbacks(netCheckRunnable); }
        catch (Throwable ignored) {}
        activity.closeTerminal();
        int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
        mainLayout.animate().translationY(screenHeight).setDuration(250).withEndAction(() -> {
            if (hostContainer != null) hostContainer.removeView(rootOverlay);
        }).start();
    }

    private void setupInputLockLogic() {
        outputText.setOnKeyListener((v, keyCode, event) -> {
            int lockedLen = effectiveLockedLen;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DEL) {
                    if (outputText.getSelectionStart() <= lockedLen) return true;
                } else if (keyCode == KeyEvent.KEYCODE_ENTER) {
                    String processText = outputText.getText().toString();
                    if (!promptSuffix.isEmpty() && processText.endsWith(promptSuffix)) {
                        processText = processText.substring(0, processText.length() - promptSuffix.length());
                    }
                    String cmd = (processText.length() >= lockedLen) ? processText.substring(lockedLen) : "";
                    try { if (sessionManager != null) sessionManager.executeCommandInActiveSession(cmd); }
                    catch (Exception e) { Toast.makeText(activity, "Command Error: " + e.getMessage(), Toast.LENGTH_SHORT).show(); }
                    return true;
                }
            }
            return false;
        });
    }

    private TextView createTerminalButton(String text, int textColor, int dynamicStrokeColor) {
        TextView btn = new TextView(activity);
        btn.setText(text);
        btn.setTextSize(12.5f);
        btn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        btn.setTextColor(textColor);
        btn.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        params.setMargins(dp(3), 0, dp(3), 0);
        btn.setLayoutParams(params);
        btn.setPadding(dp(14), 0, dp(14), 0);
        btn.setBackground(createBoxDrawable(bgPrimary, dynamicStrokeColor, 14, 14));
        return btn;
    }

    // 🔥 CUSTOM BUTTONS STORAGE
    private SharedPreferences getPrefs() {
        return activity.getSharedPreferences("primx_terminal_prefs", Context.MODE_PRIVATE);
    }

    private void loadCustomButtons() {
        if (customButtons != null) return;
        customButtons = new ArrayList<>();
        try {
            String json = getPrefs().getString("custom_buttons", "[]");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                customButtons.add(new String[]{o.optString("label", ""), o.optString("cmd", "")});
            }
        } catch (Exception ignored) {}
    }

    private void saveCustomButtons() {
        try {
            JSONArray arr = new JSONArray();
            for (String[] b : customButtons) {
                JSONObject o = new JSONObject();
                o.put("label", b[0]);
                o.put("cmd", b[1]);
                arr.put(o);
            }
            getPrefs().edit().putString("custom_buttons", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void showAddCustomButtonDialog() {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

        LinearLayout rootLayout = new LinearLayout(activity);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackground(createBoxDrawable(bgPrimary, borderColor, 18, 18));
        rootLayout.setPadding(dp(22), dp(22), dp(22), dp(18));
        int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.85);

        TextView titleView = new TextView(activity);
        titleView.setText("Add Custom Button");
        titleView.setTextSize(17f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(textPrimary);
        titleView.setPadding(0, 0, 0, dp(6));
        rootLayout.addView(titleView);

        TextView hintView = new TextView(activity);
        hintView.setText("Create your own command shortcut button");
        hintView.setTextSize(12f);
        hintView.setTextColor(textSecondary);
        hintView.setPadding(0, 0, 0, dp(16));
        rootLayout.addView(hintView);

        final EditText labelInput = new EditText(activity);
        labelInput.setHint("Button Label (e.g., PYTHON)");
        labelInput.setTextColor(textPrimary);
        labelInput.setHintTextColor(textSecondary);
        labelInput.setTextSize(14f);
        labelInput.setBackground(createBoxDrawable(bgSecondary, borderColor, 8, 8));
        labelInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p1.setMargins(0, 0, 0, dp(12));
        labelInput.setLayoutParams(p1);
        rootLayout.addView(labelInput);

        final EditText cmdInput = new EditText(activity);
        cmdInput.setHint("Command (e.g., ls -la)");
        cmdInput.setTextColor(textPrimary);
        cmdInput.setHintTextColor(textSecondary);
        cmdInput.setTextSize(14f);
        cmdInput.setBackground(createBoxDrawable(bgSecondary, borderColor, 8, 8));
        cmdInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p2.setMargins(0, 0, 0, dp(16));
        cmdInput.setLayoutParams(p2);
        rootLayout.addView(cmdInput);

        LinearLayout btnLayout = new LinearLayout(activity);
        btnLayout.setOrientation(LinearLayout.HORIZONTAL);
        btnLayout.setGravity(Gravity.END);

        TextView btnCancel = new TextView(activity);
        btnCancel.setText("CANCEL");
        btnCancel.setTextColor(textSecondary);
        btnCancel.setPadding(dp(12), dp(10), dp(12), dp(10));
        btnCancel.setTypeface(Typeface.DEFAULT_BOLD);
        btnCancel.setOnClickListener(v -> dialog.dismiss());

        TextView btnSave = new TextView(activity);
        btnSave.setText("ADD");
        btnSave.setTextColor(accentColor);
        btnSave.setPadding(dp(12), dp(10), dp(8), dp(10));
        btnSave.setTypeface(Typeface.DEFAULT_BOLD);
        btnSave.setOnClickListener(v -> {
            String label = labelInput.getText().toString().trim();
            String cmd = cmdInput.getText().toString().trim();
            if (label.isEmpty() || cmd.isEmpty()) {
                Toast.makeText(activity, "Both fields required", Toast.LENGTH_SHORT).show();
                return;
            }
            loadCustomButtons();
            customButtons.add(new String[]{label, cmd});
            saveCustomButtons();
            setupQuickKeys();
            dialog.dismiss();
            Toast.makeText(activity, "Added: " + label, Toast.LENGTH_SHORT).show();
        });

        btnLayout.addView(btnCancel);
        btnLayout.addView(btnSave);
        rootLayout.addView(btnLayout);

        dialog.setContentView(rootLayout);
        dialog.show();
        try { com.primx.utils.UIEffectsManager.applyPremiumBlur(dialog); } catch (Exception ignored) {}
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void startProjectScan() {
        if (isScanningProjects) return;
        isScanningProjects = true;
        new Thread(() -> {
            List<File> projects = new ArrayList<>();
            File root = Environment.getExternalStorageDirectory();
            File[] dirs = root.listFiles();
            if (dirs != null) {
                for (File dir : dirs) {
                    if (dir.isDirectory() && !dir.getName().startsWith(".")) {
                        if (dir.getName().equals("Android") || dir.getName().equals("DCIM") ||
                        dir.getName().equals("Pictures") || dir.getName().equals("Music") ||
                        dir.getName().equals("Movies") || dir.getName().equals("Download") ||
                        dir.getName().equals("Documents") || dir.getName().equals("build_tools")) continue;

                        boolean isProj = new File(dir, "build.gradle").exists() ||
                        new File(dir, "build.gradle.kts").exists() ||
                        new File(dir, "app").exists() ||
                        new File(dir, "AndroidManifest.xml").exists() ||
                        new File(dir, "main.py").exists() ||
                        new File(dir, "index.html").exists() ||
                        dir.getName().endsWith(".px");
                        if (isProj) projects.add(dir);
                    }
                }
            }
            final List<File> finalProjects = projects;
            mainHandler.post(() -> {
                cachedProjects = finalProjects;
                isScanningProjects = false;
                setupQuickKeys();
            });
        }).start();
    }

    private void setupQuickKeys() {
        if (quickKeysContainer == null) return;
        quickKeysContainer.removeAllViews();
        int dynamicStrokeColor = isLightTheme ? 0xFF000000 : 0xFFFFFFFF;

        // 1. MENU Button (Sessions aur Drawer kholne ke liye wapas aa gaya!)
        TextView menuBtn = createTerminalButton("MENU", accentColor, dynamicStrokeColor);
        menuBtn.setOnClickListener(v -> {
            try {
                outputText.clearFocus();
                InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(outputText.getWindowToken(), 0);
                drawerLayout.setVisibility(View.VISIBLE);
            } catch (Exception e) { Toast.makeText(activity, "Error opening menu", Toast.LENGTH_SHORT).show(); }
        });
        quickKeysContainer.addView(menuBtn);

        // 2. COPY Button
        TextView copyBtn = createTerminalButton("COPY", 0xFFFF9800, dynamicStrokeColor);
        copyBtn.setOnClickListener(v -> {
            try {
                android.content.ClipboardManager cb = (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cb != null) {
                    cb.setPrimaryClip(android.content.ClipData.newPlainText("Terminal Output", outputText.getText().toString()));
                    Toast.makeText(activity, "Copied!", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) { Toast.makeText(activity, "Copy failed", Toast.LENGTH_SHORT).show(); }
        });
        quickKeysContainer.addView(copyBtn);

        // 3. SETUP Button (Token aur Repo ke liye)
        TextView setupBtn = createTerminalButton("SETUP", textPrimary, dynamicStrokeColor);
        setupBtn.setOnClickListener(v -> {
            try { showCloudSetupDialog(); }
            catch (Exception e) { Toast.makeText(activity, "Setup error", Toast.LENGTH_SHORT).show(); }
        });
        quickKeysContainer.addView(setupBtn);

        // 4. TERMUX / CLOUD TOGGLE
        boolean isCloud = sessionManager != null && sessionManager.isOnlineMode();
        String toggleText = isCloud ? "SWITCH TO TERMUX" : "SWITCH TO CLOUD";
        int toggleColor = isCloud ? 0xFFFF9800 : 0xFF00E676; // Orange = Termux, Green = Cloud

        TextView cloudToggleBtn = createTerminalButton(toggleText, toggleColor, dynamicStrokeColor);
        cloudToggleBtn.setOnClickListener(v -> {
            try {
                if (sessionManager != null) {
                    boolean newMode = !sessionManager.isOnlineMode();
                    sessionManager.setOnlineMode(newMode);
                    setupQuickKeys();
                }
            } catch (Exception e) { Toast.makeText(activity, "Mode switch failed", Toast.LENGTH_SHORT).show(); }
        });
        quickKeysContainer.addView(cloudToggleBtn);

        // 5. CUSTOM USER BUTTONS (CMD wale buttons)
        loadCustomButtons();
        for (int i = 0; i < customButtons.size(); i++) {
            final int idx = i;
            final String[] item = customButtons.get(i);
            TextView customBtn = createTerminalButton(item[0], 0xFFFF6F00, dynamicStrokeColor);

            customBtn.setOnClickListener(v -> {
                try { if (sessionManager != null) sessionManager.executeCommandInActiveSession(item[1]); }
                catch (Exception e) { Toast.makeText(activity, "Command failed", Toast.LENGTH_SHORT).show(); }
            });

            customBtn.setOnLongClickListener(v -> {
                final Dialog dialog = new Dialog(activity);
                dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
                if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

                LinearLayout rootLayout = new LinearLayout(activity);
                rootLayout.setOrientation(LinearLayout.VERTICAL);
                rootLayout.setBackground(createBoxDrawable(bgPrimary, borderColor, 18, 18));
                rootLayout.setPadding(dp(22), dp(22), dp(22), dp(18));
                int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.85);

                TextView titleView = new TextView(activity);
                titleView.setText("Delete Button");
                titleView.setTextSize(17f);
                titleView.setTypeface(Typeface.DEFAULT_BOLD);
                titleView.setTextColor(textPrimary);
                rootLayout.addView(titleView);

                TextView msg = new TextView(activity);
                msg.setText("Kya aap sach mein \"" + item[0] + "\" button hatana chahte hain?");
                msg.setTextColor(textSecondary);
                msg.setPadding(0, dp(6), 0, dp(20));
                rootLayout.addView(msg);

                LinearLayout btnLayout = new LinearLayout(activity);
                btnLayout.setOrientation(LinearLayout.HORIZONTAL);
                btnLayout.setGravity(Gravity.END);

                TextView btnCancel = new TextView(activity);
                btnCancel.setText("CANCEL");
                btnCancel.setTextColor(textSecondary);
                btnCancel.setPadding(dp(12), dp(10), dp(12), dp(10));
                btnCancel.setTypeface(Typeface.DEFAULT_BOLD);
                btnCancel.setOnClickListener(d -> dialog.dismiss());

                TextView btnDelete = new TextView(activity);
                btnDelete.setText("DELETE");
                btnDelete.setTextColor(0xFFF44336);
                btnDelete.setPadding(dp(12), dp(10), dp(8), dp(10));
                btnDelete.setTypeface(Typeface.DEFAULT_BOLD);
                btnDelete.setOnClickListener(d -> {
                    customButtons.remove(idx);
                    saveCustomButtons();
                    setupQuickKeys();
                    dialog.dismiss();
                });

                btnLayout.addView(btnCancel);
                btnLayout.addView(btnDelete);
                rootLayout.addView(btnLayout);

                dialog.setContentView(rootLayout);
                dialog.show();
                try { com.primx.utils.UIEffectsManager.applyPremiumBlur(dialog); } catch (Exception ignored) {}
                if (dialog.getWindow() != null) dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
                return true;
            });
            quickKeysContainer.addView(customBtn);
        }

        // 6. + ADD CMD Button
        TextView addBtn = createTerminalButton("+ ADD CMD", 0xFF00E676, dynamicStrokeColor);
        addBtn.setOnClickListener(v -> showAddCustomButtonDialog());
        quickKeysContainer.addView(addBtn);
    }
    private void handleQuickKey(String key) {
        if (sessionManager == null) return;
        int lockedLen = effectiveLockedLen;

        if ("TAB".equals(key)) {
            outputText.getText().insert(Math.max(outputText.getSelectionStart(), lockedLen), "\t");
        } else if ("CLEAR".equals(key) || "CLR".equals(key)) {
            sessionManager.clearActiveSessionBuffer();
        } else if ("CTRL".equals(key)) {
            sessionManager.sendControlByteToActive(3);
        } else if ("ESC".equals(key)) {
            sessionManager.sendControlByteToActive(27);
        } else if ("SETUP".equals(key)) {
            showCloudSetupDialog();
        } else if ("UP".equals(key)) {
            sessionManager.sendRawSequence("\u001B[A");
        } else if ("DOWN".equals(key)) {
            sessionManager.sendRawSequence("\u001B[B");
        } else if ("RIGHT".equals(key)) {
            sessionManager.sendRawSequence("\u001B[C");
        } else if ("LEFT".equals(key)) {
            sessionManager.sendRawSequence("\u001B[D");
        } else {
            int pos = Math.max(outputText.getSelectionStart(), lockedLen);
            if (pos <= outputText.getText().length()) outputText.getText().insert(pos, key);
        }
    }

    @Override
    public void onSessionBufferUpdated(final SpannableStringBuilder buffer, final int lockedLength, final String sessionTitle) {
        mainHandler.post(() -> {
            try { // 🔥 ANTI-CRASH: App kabhi band nahi hoga
                if (headerTitleText != null) headerTitleText.setText("PRIMX TERMINAL  [ " + sessionTitle + " ]");

                // Asli shell ka output seedha print karo (Bina kisi extra kachre ke)
                SpannableStringBuilder display = new SpannableStringBuilder(buffer);

                // 🔥 NAKLI '$' KA KACHRA HATA DIYA GAYA HAI
                effectiveLockedLen = lockedLength;

                suppressTextWatcher = true;
                outputText.setText(display);
                suppressTextWatcher = false;

                // Cursor ko sahi jagah par lock karna
                int safeSelection = Math.min(effectiveLockedLen, outputText.getText().length());
                outputText.setSelection(Math.max(0, safeSelection));

                // Screen ko hamesha neeche (latest command par) scroll karna
                outputScrollView.post(() -> {
                    try {
                        outputScrollView.fullScroll(View.FOCUS_DOWN);
                    } catch (Exception ignored) {}
                });

            } catch (Exception e) {
                // Agar UI mein koi dikkat aayi, toh app crash hone ke bajaye error print karega
                android.util.Log.e("TerminalError", "Buffer UI Error: " + e.getMessage());
            }
        });
    }
    @Override
    public void onSessionListUpdated(final List<TerminalSession.SessionInfo> sessionInfos, final int activeIndex) {
        mainHandler.post(() -> {
            if (sessionListContainer == null) return;
            sessionListContainer.removeAllViews();
            for (int i = 0; i < sessionInfos.size(); i++) {
                final int index = i;
                TerminalSession.SessionInfo info = sessionInfos.get(i);
                TextView tv = new TextView(activity);
                tv.setText("[ " + (i + 1) + " ]  " + info.title + " (ID:" + info.id + ")");
                tv.setTextSize(13.5f);
                tv.setPadding(dp(12), dp(12), dp(12), dp(12));

                LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                itemParams.setMargins(0, 0, 0, dp(6));
                tv.setLayoutParams(itemParams);

                if (index == activeIndex) {
                    tv.setTextColor(accentColor);
                    tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                    tv.setBackground(createBoxDrawable(bgSecondary, accentColor, 12, 12));
                } else {
                    tv.setTextColor(textSecondary);
                    tv.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
                    tv.setBackground(createBoxDrawable(0x00000000, borderColor, 12, 12));
                }

                tv.setOnClickListener(v -> {
                    try {
                        sessionManager.switchSession(index);
                        drawerLayout.setVisibility(View.GONE);
                        outputText.requestFocus();
                    } catch (Exception e) { Toast.makeText(activity, "Switch error", Toast.LENGTH_SHORT).show(); }
                });
                sessionListContainer.addView(tv);
            }
        });
    }

    @Override public void onRequestNetworkDialog() { mainHandler.post(this::showInternetPromptDialog); }
    @Override public void onRequestCloudSetupDialog() { mainHandler.post(this::showCloudSetupDialog); }

    public void showInternetPromptDialog() {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

        LinearLayout rootLayout = new LinearLayout(activity);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackground(createBoxDrawable(bgPrimary, borderColor, 18, 18));
        rootLayout.setPadding(dp(22), dp(22), dp(22), dp(18));
        int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.85);

        TextView titleView = new TextView(activity);
        titleView.setText("Internet Required");
        titleView.setTextSize(17f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(textPrimary);
        titleView.setPadding(0, 0, 0, dp(12));
        rootLayout.addView(titleView);

        TextView msg = new TextView(activity);
        msg.setText("Cloud GitHub Codespaces & Actions requires an active internet connection.\n\nWould you like to turn on internet / Wi-Fi or continue in Offline Android Shell mode?");
        msg.setTextColor(textSecondary);
        msg.setTextSize(14f);
        msg.setLineSpacing(0, 1.2f);
        rootLayout.addView(msg);

        LinearLayout btnLayout = new LinearLayout(activity);
        btnLayout.setOrientation(LinearLayout.HORIZONTAL);
        btnLayout.setGravity(Gravity.END);
        btnLayout.setPadding(0, dp(20), 0, 0);

        TextView btnOffline = new TextView(activity);
        btnOffline.setText("OFFLINE MODE");
        btnOffline.setTextColor(textSecondary);
        btnOffline.setPadding(dp(12), dp(10), dp(12), dp(10));
        btnOffline.setTypeface(Typeface.DEFAULT_BOLD);
        btnOffline.setOnClickListener(v -> { sessionManager.setOnlineMode(false); setupQuickKeys(); dialog.dismiss(); });

        TextView btnSettings = new TextView(activity);
        btnSettings.setText("SETTINGS");
        btnSettings.setTextColor(accentColor);
        btnSettings.setPadding(dp(12), dp(10), dp(8), dp(10));
        btnSettings.setTypeface(Typeface.DEFAULT_BOLD);
        btnSettings.setOnClickListener(v -> {
            try { activity.startActivity(new android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)); }
            catch (Throwable ignored) {}
            dialog.dismiss();
        });

        btnLayout.addView(btnOffline);
        btnLayout.addView(btnSettings);
        rootLayout.addView(btnLayout);
        dialog.setContentView(rootLayout);
        dialog.show();
        try { com.primx.utils.UIEffectsManager.applyPremiumBlur(dialog); } catch (Exception ignored) {}
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public void showCloudSetupDialog() {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

        LinearLayout rootLayout = new LinearLayout(activity);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackground(createBoxDrawable(bgPrimary, borderColor, 18, 18));
        rootLayout.setPadding(dp(22), dp(22), dp(22), dp(18));
        int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.85);

        TextView titleView = new TextView(activity);
        titleView.setText("Configure GitHub Cloud");
        titleView.setTextSize(17f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(textPrimary);
        titleView.setPadding(0, 0, 0, dp(16));
        rootLayout.addView(titleView);

        final EditText tokenInput = new EditText(activity);
        tokenInput.setHint("GitHub PAT Token");
        tokenInput.setTextColor(textPrimary);
        tokenInput.setHintTextColor(textSecondary);
        tokenInput.setTextSize(14f);
        tokenInput.setText(BootstrapInstaller.getGithubToken(activity));
        tokenInput.setBackground(createBoxDrawable(bgSecondary, borderColor, 8, 8));
        tokenInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p1.setMargins(0, 0, 0, dp(12));
        tokenInput.setLayoutParams(p1);
        rootLayout.addView(tokenInput);

        final EditText repoInput = new EditText(activity);
        repoInput.setHint("username/repo");
        repoInput.setTextColor(textPrimary);
        repoInput.setHintTextColor(textSecondary);
        repoInput.setTextSize(14f);
        repoInput.setText(BootstrapInstaller.getGithubRepo(activity));
        repoInput.setBackground(createBoxDrawable(bgSecondary, borderColor, 8, 8));
        repoInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p2.setMargins(0, 0, 0, dp(16));
        repoInput.setLayoutParams(p2);
        rootLayout.addView(repoInput);

        LinearLayout btnLayout = new LinearLayout(activity);
        btnLayout.setOrientation(LinearLayout.HORIZONTAL);
        btnLayout.setGravity(Gravity.END);

        TextView btnCancel = new TextView(activity);
        btnCancel.setText("CANCEL");
        btnCancel.setTextColor(textSecondary);
        btnCancel.setPadding(dp(12), dp(10), dp(12), dp(10));
        btnCancel.setTypeface(Typeface.DEFAULT_BOLD);
        btnCancel.setOnClickListener(v -> dialog.dismiss());

        TextView btnSave = new TextView(activity);
        btnSave.setText("SAVE & CONNECT");
        btnSave.setTextColor(accentColor);
        btnSave.setPadding(dp(12), dp(10), dp(8), dp(10));
        btnSave.setTypeface(Typeface.DEFAULT_BOLD);
        btnSave.setOnClickListener(v -> {
            String token = tokenInput.getText().toString();
            String repo = repoInput.getText().toString();
            BootstrapInstaller.saveCloudConfig(activity, token, repo, "main");
            sessionManager.setOnlineMode(true);
            setupQuickKeys();
            dialog.dismiss();
        });

        btnLayout.addView(btnCancel);
        btnLayout.addView(btnSave);
        rootLayout.addView(btnLayout);
        dialog.setContentView(rootLayout);
        dialog.show();
        try { com.primx.utils.UIEffectsManager.applyPremiumBlur(dialog); } catch (Exception ignored) {}
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}