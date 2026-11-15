package com.primx.ui.mainactivity;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.primx.ui.Menu;
import com.primx.ui.console.Console;
import com.primx.ui.editor.EditorView;
import com.primx.ui.explorer.Filesview;
import com.primx.utils.Setting;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainUIHandler {

    private final MainActivity activity;
    private View terminalHandlePill;

    public MainUIHandler(MainActivity activity) {
        this.activity = activity;
    }

    /** Convenience — avoids repeating getResources().getIdentifier(...) everywhere. */
    private int id(String name) {
        return activity.getResources().getIdentifier(name, "id", activity.getPackageName());
    }

    private int drawableId(String name) {
        return activity.getResources().getIdentifier(name, "drawable", activity.getPackageName());
    }

    public void setupViews() {
        activity.leftPanel      = activity.findViewById(id("leftPanel"));
        activity.filesViewPanel = activity.findViewById(id("filesViewPanel"));
        activity.editorBlocker  = activity.findViewById(id("editorBlocker"));
        activity.codeCanvas     = activity.findViewById(id("codeCanvas"));
        activity.emptyStateLayout = activity.findViewById(id("emptyStateLayout"));

        // ---------- Console re-parenting ----------
        activity.consoleView = activity.findViewById(id("consoleView"));
        if (activity.consoleView != null) {
            ViewGroup parent = (ViewGroup) activity.consoleView.getParent();
            if (parent != null) parent.removeView(activity.consoleView);

            ViewGroup rootView = activity.findViewById(android.R.id.content);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT);
            activity.consoleView.setLayoutParams(params);

            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            activity.consoleView.setTranslationX(screenWidth);
            activity.consoleView.setVisibility(View.GONE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                activity.consoleView.setElevation(activity.dpToPx(20));
            }
            rootView.addView(activity.consoleView);
        }

        activity.btnOpenMenu        = activity.findViewById(id("btnOpenMenu"));
        activity.layoutTitle        = activity.findViewById(id("layoutTitle"));
        activity.tvHeaderTitle      = activity.findViewById(id("tvHeaderTitle"));
        activity.tvProjectName      = activity.findViewById(id("tvProjectName"));
        activity.tabContainer       = activity.findViewById(id("tabContainer"));
        activity.layoutDefaultActions = activity.findViewById(id("layoutDefaultActions"));
        activity.layoutTypingActions  = activity.findViewById(id("layoutTypingActions"));
        activity.floatingSelectionBar = activity.findViewById(id("floatingSelectionBar"));
        activity.layoutSearchBar    = activity.findViewById(id("layoutSearchBar"));
        activity.inputEditorSearch  = activity.findViewById(id("inputEditorSearch"));

        // ---------- Search ----------
        ImageView btnClearSearch = activity.findViewById(id("btnClearSearch"));
        if (btnClearSearch != null) {
            btnClearSearch.setOnClickListener(v -> {
                if (activity.inputEditorSearch != null) activity.inputEditorSearch.setText("");
                if (activity.codeCanvas != null)        activity.codeCanvas.clearSearch();
            });
        }

        if (activity.inputEditorSearch != null && activity.codeCanvas != null) {
            activity.inputEditorSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    String query = s.toString();
                    if (query.isEmpty()) activity.codeCanvas.clearSearch();
                    else                 activity.codeCanvas.findText(query);
                }
            });

            activity.inputEditorSearch.setOnEditorActionListener((v, actionId, event) -> {
                boolean isSearchAction = actionId == EditorInfo.IME_ACTION_SEARCH
                || actionId == EditorInfo.IME_ACTION_DONE
                || actionId == EditorInfo.IME_ACTION_NEXT
                || (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() == KeyEvent.KEYCODE_ENTER);
                if (isSearchAction) {
                    String query = activity.inputEditorSearch.getText().toString();
                    if (!query.isEmpty()) activity.codeCanvas.findNext();
                    hideKeyboard();
                    return true;
                }
                return false;
            });
        }

        activity.btnRun  = activity.findViewById(id("btnRun"));
        activity.btnEdit = activity.findViewById(id("btnEdit"));
        ImageView btnEditorMenu = activity.findViewById(id("btnEditorMenu"));

        // ---------- Left drawer ----------
        if (activity.leftPanel != null) {
            ViewGroup.LayoutParams lp = activity.leftPanel.getLayoutParams();
            lp.width = (int) activity.drawerWidthPx;
            activity.leftPanel.setLayoutParams(lp);
            activity.leftPanel.setTranslationX(-activity.drawerWidthPx);
            activity.leftPanel.setVisibility(View.GONE);
        }

        // ---------- Undo / Redo ----------
        ImageView btnUndo = activity.findViewById(id("btnUndo"));
        ImageView btnRedo = activity.findViewById(id("btnRedo"));
        if (btnUndo != null) {
            btnUndo.setAlpha(0.3f);
            btnUndo.setEnabled(false);
            if (activity.codeCanvas != null) btnUndo.setOnClickListener(v -> activity.codeCanvas.undo());
        }
        if (btnRedo != null) {
            btnRedo.setAlpha(0.3f);
            btnRedo.setEnabled(false);
            if (activity.codeCanvas != null) btnRedo.setOnClickListener(v -> activity.codeCanvas.redo());
        }

        // ---------- Console listener ----------
        if (activity.consoleView != null) {
            activity.consoleView.setConsoleListener(new Console.ConsoleListener() {
                @Override
                public void onErrorClicked(String filePath, int lineNumber, int columnStart, int errorLength, String errorMessage) {
                    File targetFile = resolveErrorFile(filePath);
                    if (targetFile != null && targetFile.exists()) {
                        boolean isNewFile = activity.currentEditingFile == null
                        || !activity.currentEditingFile.getAbsolutePath().equals(targetFile.getAbsolutePath());

                        if (isNewFile) {
                            if (activity.fileManager != null) {
                                activity.fileManager.openFileInEditor(targetFile);
                            }
                        }

                        // 🔥 FIX: Delay lagaya hai taaki EditorView file load kar le uske baad exact line par focus kare
                        if (activity.codeCanvas != null) {
                            activity.codeCanvas.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    activity.codeCanvas.focusError(lineNumber, columnStart, errorLength, errorMessage);
                                }
                            }, isNewFile ? 350 : 50);
                        }
                    } else {
                        if (activity != null) {
                            activity.showCustomToast("File not found: " + filePath);
                        }
                    }
                    activity.closeConsole();
                }

                @Override
                public void onLiveDiagnosticsClear() {
                    if (activity.codeCanvas != null) activity.codeCanvas.clearLiveErrors();
                }

                @Override
                public void onLiveDiagnosticError(String filePath, int lineNumber, int columnStart, int errorLength, String errorMessage, boolean isWarning) {
                    if (activity.codeCanvas != null && activity.currentEditingFile != null
                    && Console.canon(activity.currentEditingFile.getAbsolutePath()).equals(Console.canon(filePath))) {
                        activity.codeCanvas.setDiagnostic(lineNumber, columnStart, errorLength, errorMessage, isWarning);
                    }
                }
            });
        }
        // ---------- Editor wiring ----------
        if (activity.codeCanvas != null) {

            activity.codeCanvas.setOnLiveCheckListener(() -> {
                if (activity.consoleView != null
                && activity.consoleView.liveAnalyzer != null
                && activity.currentEditingFile != null) {
                    String unsaved = activity.codeCanvas.getText();
                    activity.consoleView.liveAnalyzer.triggerQuickAnalysis(
                    activity.currentEditingFile, unsaved);
                }
            });

            activity.codeCanvas.setOnAutoSaveListener(() -> {
                if (activity.currentEditingFile != null && activity.isFileModified
                && activity.fileManager != null) {
                    activity.fileManager.saveCurrentFile(true);
                }
            });

            activity.codeCanvas.setOnContentChangeListener(() -> {
                if (!activity.isFileModified) updateSaveButtonState(true);
            });

            activity.codeCanvas.setOnSelectionListener(isSelected -> {
                activity.isSelectionModeActive = isSelected;
                if (isSelected) {
                    if (activity.layoutDefaultActions != null) {
                        activity.layoutDefaultActions.setVisibility(View.GONE);
                        activity.layoutDefaultActions.setAlpha(0f);
                    }
                    if (activity.layoutTypingActions != null) {
                        activity.layoutTypingActions.setVisibility(View.GONE);
                        activity.layoutTypingActions.setAlpha(0f);
                    }
                    if (activity.floatingSelectionBar != null) {
                        activity.floatingSelectionBar.setVisibility(View.VISIBLE);
                        activity.floatingSelectionBar.setTranslationY(-200f);
                        activity.floatingSelectionBar.animate().translationY(0).setDuration(250).start();
                    }
                } else if (activity.floatingSelectionBar != null) {
                    activity.floatingSelectionBar.animate()
                    .translationY(-200f)
                    .setDuration(250)
                    .withEndAction(() -> {
                        activity.floatingSelectionBar.setVisibility(View.GONE);
                        View root = activity.findViewById(android.R.id.content);
                        int heightDiff = root.getRootView().getHeight() - root.getHeight();
                        boolean keyboardOpen = heightDiff > activity.dpToPx(200);
                        if (activity.isSearchMode) return;
                        if (keyboardOpen) {
                            if (activity.layoutTypingActions != null) {
                                activity.layoutTypingActions.setVisibility(View.VISIBLE);
                                activity.layoutTypingActions.setAlpha(1f);
                            }
                            if (activity.layoutDefaultActions != null) {
                                activity.layoutDefaultActions.setVisibility(View.GONE);
                                activity.layoutDefaultActions.setAlpha(0f);
                            }
                        } else {
                            if (activity.layoutDefaultActions != null) {
                                activity.layoutDefaultActions.setVisibility(View.VISIBLE);
                                activity.layoutDefaultActions.setAlpha(1f);
                            }
                            if (activity.layoutTypingActions != null) {
                                activity.layoutTypingActions.setVisibility(View.GONE);
                                activity.layoutTypingActions.setAlpha(0f);
                            }
                        }
                    })
                    .start();
                }
            });

            activity.codeCanvas.setOnHistoryChangeListener((canUndo, canRedo) -> {
                ImageView bUndo = activity.findViewById(id("btnUndo"));
                ImageView bRedo = activity.findViewById(id("btnRedo"));
                if (bUndo != null) { bUndo.setAlpha(canUndo ? 1f : 0.3f); bUndo.setEnabled(canUndo); }
                if (bRedo != null) { bRedo.setAlpha(canRedo ? 1f : 0.3f); bRedo.setEnabled(canRedo); }
            });

            activity.codeCanvas.setOnFileCloseListener(() -> {
                if (activity.currentEditingFile != null) {
                    activity.openedFiles.remove(activity.currentEditingFile);
                    if (activity.fileManager != null) activity.fileManager.refreshTabs();
                }
                activity.currentEditingFile = null;
                activity.codeCanvas.setVisibility(View.GONE);
                if (activity.emptyStateLayout != null) activity.emptyStateLayout.setVisibility(View.VISIBLE);
                if (activity.fileManager != null) activity.fileManager.updateHeaderLabels();
                updateEditButtonState();
            });
        }

        // ---------- File explorer ----------
        if (activity.filesViewPanel != null) {
            activity.filesViewPanel.setIDEInteractionListener(new Filesview.IDEInteractionListener() {
                @Override public void onProjectOpened(File projectDir) {
                    if (activity.fileManager != null) activity.fileManager.loadProjectDirectly(projectDir);
                    if (activity.tvProjectName != null && projectDir != null) {
                        activity.tvProjectName.setText(projectDir.getName());
                    }
                    activity.closeExplorer();
                }

                @Override public void onFileOpened(File file) {
                    if (activity.fileManager != null) activity.fileManager.openFileInEditor(file);
                    if (activity.codeCanvas != null) activity.codeCanvas.setVisibility(View.VISIBLE);
                    if (activity.emptyStateLayout != null) activity.emptyStateLayout.setVisibility(View.GONE);
                    if (activity.tvProjectName != null && MainActivity.currentProjectName != null
                    && !MainActivity.currentProjectName.isEmpty()) {
                        activity.tvProjectName.setText(MainActivity.currentProjectName);
                    }
                    if (activity.consoleView != null && activity.consoleView.liveAnalyzer != null) {
                        activity.consoleView.lastActiveFile = file;
                        activity.consoleView.liveAnalyzer.triggerQuickAnalysis(
                        file, activity.codeCanvas != null ? activity.codeCanvas.getText() : null);
                    }
                    activity.closeExplorer();
                }

                @Override public void onFileDeleted(File file) {
                    if (activity.fileManager != null) activity.fileManager.handleFileDeleted(file);
                }

                @Override public void onEditorClearRequested() {
                    activity.currentEditingFile = null;
                    if (activity.codeCanvas != null) {
                        activity.codeCanvas.setText("");
                        activity.codeCanvas.setFileInfo("", "");
                        activity.codeCanvas.setVisibility(View.GONE);
                    }
                    if (activity.emptyStateLayout != null) activity.emptyStateLayout.setVisibility(View.VISIBLE);
                    if (activity.tvProjectName != null) activity.tvProjectName.setText("");
                    updateEditButtonState();
                }
            });
        }

        // ---------- Header / menu ----------
        if (activity.btnOpenMenu != null) {
            activity.btnOpenMenu.setOnClickListener(v -> {
                if (activity.isSearchMode) {
                    exitSearchMode();
                } else {
                    activity.openExplorer();
                }
            });
        }

        if (btnEditorMenu != null) {
            btnEditorMenu.setOnClickListener(v ->
            Menu.show(activity, v, new Menu.MenuListener() {
                @Override public void onSearchClicked() {
                    activity.isSearchMode = true;
                    if (activity.layoutTitle != null) activity.layoutTitle.setVisibility(View.GONE);
                    if (activity.layoutDefaultActions != null) activity.layoutDefaultActions.setVisibility(View.GONE);
                    if (activity.layoutSearchBar != null) activity.layoutSearchBar.setVisibility(View.VISIBLE);
                    if (activity.inputEditorSearch != null) {
                        activity.inputEditorSearch.setText("");
                        activity.inputEditorSearch.requestFocus();
                        showKeyboard(activity.inputEditorSearch);
                    }
                }
                @Override public void onSettingsClicked() {
                    Setting.show(activity, activity.codeCanvas);
                }
            }));
        }

        // ---------- Edit / Preview button ----------
        if (activity.btnEdit != null) {
            activity.btnEdit.setOnClickListener(v -> onEditButtonClicked());
            updateEditButtonState();
        }

        if (activity.floatingSelectionBar != null) {
            activity.floatingSelectionBar.setClickable(true);
            activity.floatingSelectionBar.setFocusable(true);
        }

        // ---------- Default action bar ----------
        View btnSaveApp = activity.findViewById(id("btnSaveApp"));
        if (btnSaveApp != null) {
            btnSaveApp.setClickable(true);
            btnSaveApp.setOnClickListener(v -> {
                if (activity.fileManager != null) activity.fileManager.saveCurrentFile(true);
                hideKeyboard();
            });
            updateSaveButtonState(false);
        }

        View btnSelectAll = activity.findViewById(id("btnSelectAll"));
        if (btnSelectAll != null) {
            btnSelectAll.setClickable(true);
            btnSelectAll.setOnClickListener(v -> { if (activity.codeCanvas != null) activity.codeCanvas.selectAll(); });
        }

        View btnCut = activity.findViewById(id("btnCut"));
        if (btnCut != null) {
            btnCut.setClickable(true);
            btnCut.setOnClickListener(v -> { if (activity.codeCanvas != null) activity.codeCanvas.cutToClipboard(); });
        }

        View btnCopy = activity.findViewById(id("btnCopy"));
        if (btnCopy != null) {
            btnCopy.setClickable(true);
            btnCopy.setOnClickListener(v -> { if (activity.codeCanvas != null) activity.codeCanvas.copyToClipboard(); });
        }

        View btnPaste = activity.findViewById(id("btnPaste"));
        if (btnPaste != null) {
            btnPaste.setClickable(true);
            btnPaste.setOnClickListener(v -> { if (activity.codeCanvas != null) activity.codeCanvas.pasteFromClipboard(); });
        }

        View btnComment = activity.findViewById(id("btnComment"));
        if (btnComment != null) {
            btnComment.setClickable(true);
            btnComment.setOnClickListener(v -> {
                if (activity.codeCanvas == null || activity.codeCanvas.isReadOnly()) return;
                activity.codeCanvas.toggleComment();
                if (activity.floatingSelectionBar != null)
                activity.floatingSelectionBar.setVisibility(View.GONE);
                activity.isSelectionModeActive = false;
            });
        }

        // ---------- Terminal handle ----------
        View terminalHandle = activity.findViewById(id("terminalHandleBtn"));
        if (terminalHandle == null) terminalHandle = this.terminalHandlePill;   // reuse existing
        if (terminalHandle == null) {
            ViewGroup rootView = activity.findViewById(android.R.id.content);
            LinearLayout container = new LinearLayout(activity);
            container.setGravity(Gravity.CENTER);
            container.setPadding(0, (int) activity.dpToPx(15), 0, (int) activity.dpToPx(15));

            View pill = new View(activity);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(Color.parseColor("#66888888"));
            bg.setCornerRadius(activity.dpToPx(4));
            pill.setBackground(bg);
            container.addView(pill, new LinearLayout.LayoutParams(
            (int) activity.dpToPx(50), (int) activity.dpToPx(5)));

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.BOTTOM;
            rootView.addView(container, lp);
            terminalHandle = container;
        }

        terminalHandle.setOnTouchListener(new View.OnTouchListener() {
            float startY;
            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                    startY = event.getRawY();
                    return true;
                    case MotionEvent.ACTION_MOVE:
                    if (startY - event.getRawY() > 50) {
                        activity.openTerminal();
                        return true;
                    }
                    return false;
                    case MotionEvent.ACTION_UP:
                    float endY = event.getRawY();
                    // tap (small delta) or upward swipe → open terminal
                    if (startY - endY > 30 || Math.abs(startY - endY) < 20) {
                        activity.openTerminal();
                    }
                    return true;
                }
                return false;
            }
        });

        this.terminalHandlePill = terminalHandle;
        setupSymbolBar();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void exitSearchMode() {
        activity.isSearchMode = false;
        if (activity.layoutSearchBar != null) activity.layoutSearchBar.setVisibility(View.GONE);
        if (activity.layoutTitle != null)     activity.layoutTitle.setVisibility(View.VISIBLE);
        if (activity.layoutDefaultActions != null) {
            activity.layoutDefaultActions.setVisibility(View.VISIBLE);
            activity.layoutDefaultActions.setAlpha(1f);
        }
        int splitIconId = drawableId("ic_split_screen");
        if (splitIconId != 0 && activity.btnOpenMenu != null)
        activity.btnOpenMenu.setImageResource(splitIconId);
        if (activity.inputEditorSearch != null) activity.inputEditorSearch.setText("");
        if (activity.codeCanvas != null) activity.codeCanvas.clearSearch();
        hideKeyboard();
    }

        private void onEditButtonClicked() {
        if (activity.currentEditingFile == null || activity.codeCanvas == null) return;

        String path = activity.currentEditingFile.getAbsolutePath();
        String lowerPath = path.toLowerCase();
        boolean isLayoutXml = lowerPath.endsWith(".xml") && lowerPath.contains("/layout/");
        boolean isManifest = path.endsWith("AndroidManifest.xml");

        if (isLayoutXml || isManifest) {
            hideKeyboard();
            if (activity.fileManager != null) activity.fileManager.saveCurrentFile(false);
            try {
                String xmlContent = activity.codeCanvas.getText();
                if (xmlContent == null || xmlContent.trim().isEmpty()) {
                    activity.showCustomToast("XML file is empty");
                    return;
                }
                
                // 🔥 FIX: Manifest है तो Permission Manager, वरना लेआउट प्रीव्यू
                if (activity.previewHelper != null) {
                    if (isManifest) {
                        activity.previewHelper.showPermissionManager(activity.currentEditingFile);
                    } else {
                        activity.previewHelper.showXmlPreview(activity.currentEditingFile);
                    }
                }
            } catch (Exception e) {
                activity.showCustomToast("Error opening preview: " + e.getMessage());
                e.printStackTrace();
            }
            return;
        }

        boolean wasReadOnly = activity.codeCanvas.isReadOnly();
        activity.codeCanvas.setReadOnly(!wasReadOnly);
        updateEditButtonState();
        activity.showCustomToast(!wasReadOnly ? "Read-Only Mode Enabled" : "Edit Mode Enabled");
    }

    public void updateEditButtonState() {
        if (activity.btnEdit == null) return;

        boolean isLayoutXml = false;
        boolean isManifest = false;
        if (activity.currentEditingFile != null) {
            String path = activity.currentEditingFile.getAbsolutePath();
            String lowerPath = path.toLowerCase();
            isLayoutXml = lowerPath.endsWith(".xml") && lowerPath.contains("/layout/");
            isManifest = path.endsWith("AndroidManifest.xml");
        }

        if (isLayoutXml || isManifest) {
            int iconId = activity.getResources().getIdentifier("ic_image_preview", "drawable", activity.getPackageName());
            if (iconId != 0) activity.btnEdit.setImageResource(iconId);
            activity.btnEdit.setColorFilter(android.graphics.Color.parseColor("#00E676"));
            return;
        }

        boolean isReadOnly = activity.codeCanvas != null && activity.codeCanvas.isReadOnly();

        int eyeIconId = activity.getResources().getIdentifier("ic_eye", "drawable", activity.getPackageName());
        if (eyeIconId == 0) eyeIconId = activity.getResources().getIdentifier("ic_visibility", "drawable", activity.getPackageName());
        if (eyeIconId == 0) eyeIconId = android.R.drawable.ic_menu_view;

        int editIconId = activity.getResources().getIdentifier("ic_edit", "drawable", activity.getPackageName());
        if (editIconId == 0) editIconId = activity.getResources().getIdentifier("ic_create", "drawable", activity.getPackageName());
        if (editIconId == 0) editIconId = android.R.drawable.ic_menu_edit;

        activity.btnEdit.setImageResource(isReadOnly ? eyeIconId : editIconId);

        int tintColor = activity.resolveThemeColor("ide_text_primary");
        if (tintColor == 0) tintColor = android.graphics.Color.WHITE;
        activity.btnEdit.setColorFilter(tintColor);
    }

    // 👇 ये रहे वो दो मेथड्स जो गलती से डिलीट हो गए थे (इनसे एरर फिक्स हो जाएगा)
    private File resolveErrorFile(String filePath) {
        File direct = new File(filePath);
        if (direct.exists()) return direct;
        if (MainActivity.currentProjectPath == null || MainActivity.currentProjectPath.isEmpty())
            return null;

        List<String> found = new ArrayList<>();
        findFilesByName(new File(MainActivity.currentProjectPath), new File(filePath).getName(), found);
        return found.isEmpty() ? null : new File(found.get(0));
    }

    public void updateSaveButtonState(boolean modified) {
        activity.isFileModified = modified;
        int btnId = activity.getResources().getIdentifier("btnSaveApp", "id", activity.getPackageName());
        View btnSaveApp = activity.findViewById(btnId);
        if (btnSaveApp == null) return;

        if (btnSaveApp instanceof ImageView) {
            if (modified) ((ImageView) btnSaveApp).setColorFilter(android.graphics.Color.parseColor("#FF7B1FA2"));
            else          ((ImageView) btnSaveApp).clearColorFilter();
        }
        btnSaveApp.setAlpha(modified ? 1.0f : 0.4f);
        btnSaveApp.setEnabled(modified);
    }

    
    public void setupSymbolBar() {
        final View symbolBox = activity.findViewById(id("symbolBox"));
        final LinearLayout symbolContainer = activity.findViewById(id("symbolContainer"));
        final TextView btnToggleSymbolBar = activity.findViewById(id("btnToggleSymbolBar"));

        if (symbolContainer == null) return;

        // 🔥 एक्सट्रा पैडिंग हटा रहे हैं ताकि डब्बा सच में स्लिम दिखे
        symbolContainer.setPadding(0, 0, 0, 0);

        if (symbolContainer.getChildCount() == 0) {
            String[] symbols = {"Tab", "{", "}", "(", ")", "[", "]", ";", "=", "\"", "'", "/", "\\", "<", ">"};
            String[] inserts = {"    ", "{", "}", "(", ")", "[", "]", ";", "=", "\"", "'", "/", "\\", "<", ">"};

            int padH = (int) activity.dpToPx(16); // बायीं-दायीं पैडिंग
            int padV = (int) activity.dpToPx(6);  // ऊपर-नीचे की पैडिंग (बिल्कुल स्लिम)

            int symbolColor = activity.resolveThemeColor("ide_text_primary");
            if (symbolColor == 0) symbolColor = android.graphics.Color.WHITE;

            for (int i = 0; i < symbols.length; i++) {
                final String insertText = inserts[i];
                TextView btn = new TextView(activity);
                btn.setText(symbols[i]);
                btn.setTextColor(symbolColor);
                btn.setTextSize(16f);
                btn.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                btn.setPadding(padH, padV, padH, padV);

                // बटन फोकस ना ले, ताकि कीबोर्ड बंद ना हो
                btn.setFocusable(false);
                btn.setFocusableInTouchMode(false);

                try { btn.setBackgroundResource(android.R.attr.selectableItemBackground); }
                catch (Exception ignored) {}

                // 🔥 FIX: Android का डिफ़ॉल्ट OnClickListener (यह स्क्रॉल करते समय टाइप नहीं होने देगा)
                btn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (activity.codeCanvas != null) {
                            activity.codeCanvas.insertSymbol(insertText);
                            // फोकस वापस एडिटर पर ताकि कीबोर्ड खुला रहे
                            activity.codeCanvas.requestFocus();
                        }
                    }
                });

                symbolContainer.addView(btn);
            }
        }

        if (btnToggleSymbolBar != null && symbolBox != null) {
            btnToggleSymbolBar.setOnClickListener(v -> {
                boolean showing = symbolBox.getVisibility() == View.VISIBLE;
                symbolBox.setVisibility(showing ? View.GONE : View.VISIBLE);
                btnToggleSymbolBar.setText(showing ? "^" : "v");
            });
        }
    }
    public void setupKeyboardDetector() {
        final View activityRootView = activity.findViewById(android.R.id.content);
        activityRootView.getViewTreeObserver().addOnGlobalLayoutListener(
        new ViewTreeObserver.OnGlobalLayoutListener() {
            private int lastHeightDiff = -1;
            @Override public void onGlobalLayout() {
                int heightDiff = activityRootView.getRootView().getHeight() - activityRootView.getHeight();
                if (heightDiff == lastHeightDiff) return;
                lastHeightDiff = heightDiff;

                View bottomAccessoryContainer = activity.findViewById(id("bottomAccessoryContainer"));
                View floatingSymbolBar        = activity.findViewById(id("floatingSymbolBar"));
                TextView btnToggleSymbolBar   = activity.findViewById(id("btnToggleSymbolBar"));

                boolean keyboardOpen = heightDiff > activity.dpToPx(200);

                if (keyboardOpen) {
                    if (activity.isTerminalOpen()) {
                        if (floatingSymbolBar != null) floatingSymbolBar.setVisibility(View.GONE);
                        if (bottomAccessoryContainer != null) bottomAccessoryContainer.setVisibility(View.GONE);
                    } else {
                        if (bottomAccessoryContainer != null) bottomAccessoryContainer.setVisibility(View.VISIBLE);
                        if (floatingSymbolBar != null)        floatingSymbolBar.setVisibility(View.VISIBLE);
                        if (btnToggleSymbolBar != null)       btnToggleSymbolBar.setText("v");

                        if (!activity.isSelectionModeActive && !activity.isSearchMode) {
                            if (activity.layoutDefaultActions != null) {
                                activity.layoutDefaultActions.setAlpha(0f);
                                activity.layoutDefaultActions.setVisibility(View.GONE);
                            }
                            if (activity.layoutTypingActions != null) {
                                activity.layoutTypingActions.setVisibility(View.VISIBLE);
                                activity.layoutTypingActions.setAlpha(1f);
                            }
                        }
                        if (terminalHandlePill != null) terminalHandlePill.setVisibility(View.GONE);
                    }
                } else {
                    if (bottomAccessoryContainer != null) bottomAccessoryContainer.setVisibility(View.GONE);
                    if (activity.layoutTypingActions != null) {
                        activity.layoutTypingActions.setAlpha(0f);
                        activity.layoutTypingActions.setVisibility(View.GONE);
                    }
                    if (!activity.isSelectionModeActive && !activity.isSearchMode
                    && activity.layoutDefaultActions != null) {
                        activity.layoutDefaultActions.setVisibility(View.VISIBLE);
                        activity.layoutDefaultActions.setAlpha(1f);
                    }
                    if (terminalHandlePill != null) terminalHandlePill.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    public void hideKeyboard() {
        View view = activity.getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    public void showKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            v.requestFocus();
            imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    /**
    * Recursively searches {@code dir} for files whose name equals {@code fileName}
    * (or ends with it — used to locate a file by partial name when the full path
    * is unknown). NOTE: parameter was originally mis-named "extension".
    */
    private void findFilesByName(File dir, String fileName, List<String> list) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                findFilesByName(f, fileName, list);
            } else if (f.getName().equals(fileName) || f.getName().endsWith(fileName)) {
                list.add(f.getAbsolutePath());
            }
        }
    }
}






