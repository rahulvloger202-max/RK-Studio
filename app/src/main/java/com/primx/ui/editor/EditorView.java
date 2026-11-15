package com.primx.ui.editor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import com.primx.ui.mainactivity.MainActivity;
import java.util.HashMap;

public class EditorView extends View {

    public EditorTheme theme;
    public EditorSelection selection;
    public EditorTextManager textManager;
    public EditorTouch touch;

    private boolean isReadOnly = false;
    private boolean isCursorVisible = true;
    private Handler cursorHandler = new Handler(), debounceHandler = new Handler();
    private Runnable cursorRunnable, debounceRunnable;
    private FileHistoryManager fileHistoryManager = new FileHistoryManager();
    private java.util.Set<Integer> temporaryHighlightLines = new java.util.HashSet<>();
    private Handler highlightHandler = new Handler();
    private Runnable highlightClearer;

    private String currentFileName = "No File", currentFilePath = "";
    private Drawable fileIcon = null;
    private final float TOP_PADDING = 100f;
    private final float CODE_START_PAD = 8f;

    private String[] keywords = {"public", "private", "protected", "class", "interface", "enum", "void", "new", "static", "final", "abstract", "extends", "implements", "super", "this", "synchronized", "strictfp", "transient", "volatile", "def", "lambda", "global", "nonlocal", "class", "fun", "val", "var", "const", "let", "function"};
    private String[] controls = {"return", "if", "else", "elif", "for", "while", "do", "switch", "case", "break", "continue", "default", "try", "catch", "finally", "throw", "throws", "import", "package", "from", "as", "pass", "yield", "with", "in", "is", "not", "and", "or", "when"};
    private String[] types = {"int", "boolean", "float", "double", "char", "long", "byte", "short", "str", "list", "dict", "set", "tuple", "Object", "String", "View", "Activity", "Bundle", "Context", "Intent", "File"};
    private String[] literals = {"true", "false", "null", "True", "False", "None", "undefined", "NaN"};
    private String[] packages = {"android", "androidx", "java", "javax", "com", "org", "widget", "content", "view", "os", "app", "util", "text", "graphics", "database", "net", "provider", "media", "hardware", "lang", "io", "nio", "math", "security", "reflect", "primx"};

    private class ErrorInfo {
        int colStart, length; String msg;
        ErrorInfo(int c, int l, String m) { colStart = c; length = l; msg = m; }
    }

    private HashMap<Integer, ErrorInfo> ecjErrors = new HashMap<>();
    private HashMap<Integer, ErrorInfo> ecjWarnings = new HashMap<>();

    private int focusedErrorLine = -1, focusedErrorCol = -1, focusedErrorLen = -1;

    public interface OnSelectionListener { void onSelectionChanged(boolean isSelected); }
    public interface OnContentChangeListener { void onContentChanged(); }
    public interface OnLiveCheckListener { void onCheckRequested(); }
    public interface OnAutoSaveListener { void onAutoSaveRequested(); }
    public interface OnFileCloseListener { void onFileClosed(); }
    public interface OnHistoryChangeListener { void onHistoryChanged(boolean canUndo, boolean canRedo); }

    private OnSelectionListener selectionListener;
    private OnContentChangeListener contentChangeListener;
    private OnLiveCheckListener liveCheckListener;
    private OnAutoSaveListener autoSaveListener;
    private OnFileCloseListener fileCloseListener;
    private OnHistoryChangeListener historyChangeListener;

    public void setOnSelectionListener(OnSelectionListener l) { this.selectionListener = l; }
    public void setOnContentChangeListener(OnContentChangeListener l) { this.contentChangeListener = l; }
    public void setOnLiveCheckListener(OnLiveCheckListener l) { this.liveCheckListener = l; }
    public void setOnAutoSaveListener(OnAutoSaveListener l) { this.autoSaveListener = l; }
    public void setOnFileCloseListener(OnFileCloseListener l) { this.fileCloseListener = l; }

    public void setOnHistoryChangeListener(final OnHistoryChangeListener l) {
        this.historyChangeListener = l;
        // 🔥 FIX: Disconnected broken "untitled" listener of EditorTextManager
    }

    public EditorView(Context context) { super(context); init(context); }
    public EditorView(Context context, AttributeSet attrs) { super(context, attrs); init(context); }

    private void init(Context context) {
        setFocusable(true);
        setFocusableInTouchMode(true);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        theme = new EditorTheme(context);
        selection = new EditorSelection();
        textManager = new EditorTextManager();
        touch = new EditorTouch(context, new GestureListener(), new ScaleListener());

        theme.updateFontMetrics(textManager.lines.size());
        cursorRunnable = new Runnable() {
            @Override public void run() {
                isCursorVisible = !isReadOnly && !isCursorVisible;
                invalidate();
                cursorHandler.postDelayed(this, 500);
            }
        };
        cursorHandler.postDelayed(cursorRunnable, 500);
        setBackgroundColor(theme.editorBackgroundColor);
    }

    public void setTheme(String themeName) {
        theme.setTheme(themeName);
        setBackgroundColor(theme.editorBackgroundColor);
        invalidate();
    }

    public String getCurrentTheme() { return theme.currentAppTheme; }

    public void setTextSize(float size) {
        theme.currentTextSize = Math.max(theme.MIN_TEXT_SIZE, Math.min(size, theme.MAX_TEXT_SIZE));
        theme.updateFontMetrics(textManager.lines.size());
        invalidate();
    }

    public void setReadOnly(boolean readOnly) {
        this.isReadOnly = readOnly;
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (readOnly) {
            isCursorVisible = false;
            if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
        } else {
            isCursorVisible = true;
            requestFocus();
            if (imm != null) {
                imm.restartInput(this);
                imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
            }
        }
        invalidate();
    }
    public boolean isReadOnly() {
        return this.isReadOnly;
    }
    public String getCurrentFilePath() {
        return this.currentFilePath != null ? this.currentFilePath : "";
    }

    public void setFileInfo(String name, String path) {
        this.currentFileName = name != null ? name : "No File";
        this.currentFilePath = path != null ? path : "";
        this.fileIcon = loadFileIconDrawable(getContext(), this.currentFileName);
        clearLiveErrors();
        runInstantSyntaxCheck();
        invalidate();

        if (historyChangeListener != null) {
            historyChangeListener.onHistoryChanged(
                    fileHistoryManager.canUndo(currentFilePath),
                    fileHistoryManager.canRedo(currentFilePath)
            );
        }
    }

    private void showCustomToast(String message) {
        if (getContext() instanceof MainActivity) ((MainActivity) getContext()).showCustomToast(message);
        else Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
    }

    @Override public void scrollTo(int x, int y) {}
    @Override public void scrollBy(int x, int y) {}
    @Override public boolean requestRectangleOnScreen(Rect rectangle, boolean immediate) { return false; }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (h < oldh) {
            postDelayed(new Runnable() {
                @Override public void run() {
                    ensureCursorVisible();
                }
            }, 100);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cursorHandler.removeCallbacks(cursorRunnable);
        if (debounceRunnable != null) debounceHandler.removeCallbacks(debounceRunnable);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        cursorHandler.removeCallbacks(cursorRunnable);
        cursorHandler.postDelayed(cursorRunnable, 500);
    }

    private String formatCodeIndentation(String rawText) {
        if (rawText == null || rawText.isEmpty()) return "";
        String fName = currentFileName != null ? currentFileName.toLowerCase() : "";
        boolean isXml = fName.endsWith(".xml") || fName.endsWith(".html") || fName.endsWith(".htm");
        boolean isBraceLang = fName.endsWith(".java") || fName.endsWith(".kt") || fName.endsWith(".kts")
        || fName.endsWith(".js") || fName.endsWith(".json") || fName.endsWith(".gradle")
        || fName.endsWith(".c") || fName.endsWith(".cpp") || fName.endsWith(".h");

        if (!isXml && !isBraceLang) return rawText.replace("\t", "    ");

        String[] rawLines = rawText.replace("\r\n", "\n").split("\n", -1);
        StringBuilder out = new StringBuilder();

        if (isXml) {
            int depth = 0;
            boolean insideOpenTag = false;
            for (int i = 0; i < rawLines.length; i++) {
                String trimmed = rawLines[i].trim();
                if (trimmed.isEmpty()) { if (i < rawLines.length - 1) out.append("\n"); continue; }

                if (trimmed.startsWith("</")) depth = Math.max(0, depth - 1);
                int lineIndent = depth + (insideOpenTag ? 1 : 0);

                if (depth == 0 && !insideOpenTag && !trimmed.startsWith("<?") && !trimmed.startsWith("<!")
                && !trimmed.startsWith("<manifest") && !trimmed.startsWith("</manifest")
                && !trimmed.startsWith("<resources") && !trimmed.startsWith("</resources")) {
                    if (fName.equals("androidmanifest.xml")) lineIndent = 1;
                }
                for (int s = 0; s < lineIndent * 4; s++) out.append(' ');
                out.append(trimmed);
                if (i < rawLines.length - 1) out.append("\n");

                if (insideOpenTag) {
                    if (trimmed.endsWith("/>")) insideOpenTag = false;
                    else if (trimmed.endsWith(">")) { insideOpenTag = false; depth++; }
                } else {
                    if (trimmed.startsWith("<?") || trimmed.startsWith("<!") || trimmed.startsWith("</")) {
                    } else if (trimmed.startsWith("<")) {
                        if (trimmed.endsWith("/>") || (trimmed.contains("</") && trimmed.endsWith(">"))) {
                        } else if (trimmed.endsWith(">")) {
                            if (!trimmed.startsWith("<manifest")) depth++;
                        } else {
                            insideOpenTag = true;
                        }
                    }
                }
            }
            return out.toString();
        } else {
            int depth = 0;
            for (int i = 0; i < rawLines.length; i++) {
                String trimmed = rawLines[i].trim();
                if (trimmed.isEmpty()) { if (i < rawLines.length - 1) out.append("\n"); continue; }

                if (trimmed.startsWith("}") || trimmed.startsWith("]")) depth = Math.max(0, depth - 1);
                for (int s = 0; s < depth * 4; s++) out.append(' ');
                out.append(trimmed);
                if (i < rawLines.length - 1) out.append("\n");

                int netBraces = 0;
                boolean inStr = false; char quote = 0;
                for (int c = 0; c < trimmed.length(); c++) {
                    char ch = trimmed.charAt(c);
                    if (inStr) {
                        if (ch == '\\') { c++; continue; }
                        if (ch == quote) inStr = false;
                    } else {
                        if (ch == '"' || ch == '\'') { inStr = true; quote = ch; }
                        else if (ch == '/' && c + 1 < trimmed.length() && trimmed.charAt(c + 1) == '/') break;
                        else if (ch == '{' || ch == '[') netBraces++;
                        else if (ch == '}' || ch == ']') netBraces--;
                    }
                }
                if (trimmed.startsWith("}") || trimmed.startsWith("]")) netBraces++;
                depth = Math.max(0, depth + netBraces);
            }
            return out.toString();
        }
    }

    public void updateCodeFromAI(String newText) {
        saveCurrentStateForUndo();
        int oldScrollY = (int) touch.scrollY;
        int oldScrollX = (int) touch.scrollX;
        textManager.setText(formatCodeIndentation(newText));
        selection.cursorLine = Math.min(selection.cursorLine, Math.max(0, textManager.lines.size() - 1));
        selection.cursorCol = Math.min(selection.cursorCol, textManager.lines.get(selection.cursorLine).length());
        selection.isSelecting = false;
        selection.isActionModeActive = false;
        if (selectionListener != null) selectionListener.onSelectionChanged(false);
        touch.scrollY = oldScrollY;
        touch.scrollX = oldScrollX;
        clearLiveErrors();
        if (debounceRunnable != null) debounceHandler.removeCallbacks(debounceRunnable);
        theme.updateFontMetrics(textManager.lines.size());
        if (historyChangeListener != null) {
            historyChangeListener.onHistoryChanged(
                    fileHistoryManager.canUndo(currentFilePath),
                    fileHistoryManager.canRedo(currentFilePath)
            );
        }
        runInstantSyntaxCheck();
        if (liveCheckListener != null) liveCheckListener.onCheckRequested();
        invalidate();
    }

    public void setText(String text) {
        textManager.setText(formatCodeIndentation(text));
        selection.cursorLine = 0;
        selection.cursorCol = 0;
        touch.scrollY = 0;
        touch.scrollX = 0;
        selection.isSelecting = false;
        selection.isActionModeActive = false;
        if (debounceRunnable != null) debounceHandler.removeCallbacks(debounceRunnable);
        textManager.clearSearch();
        if (selectionListener != null) selectionListener.onSelectionChanged(false);

        // 🔥 FIX: Do NOT call saveState or liveCheckListener here!
        // Isse dusri file ka Undo/Redo stack mix nahi hoga aur Console ke errors gayab nahi honge.
        if (historyChangeListener != null) {
            historyChangeListener.onHistoryChanged(
                    fileHistoryManager.canUndo(currentFilePath),
                    fileHistoryManager.canRedo(currentFilePath)
            );
        }

        theme.updateFontMetrics(textManager.lines.size());
        runInstantSyntaxCheck();
        invalidate();
    }

    public String getText() { return textManager.getText(); }

    public void saveCurrentStateForUndo() {
        if (currentFilePath == null || currentFilePath.isEmpty()) return;
        fileHistoryManager.saveState(currentFilePath, getText(), selection.cursorLine, selection.cursorCol);
    }

    private int calculateAutoIndentSpaces(int lineIdx) {
        if (lineIdx < 0 || lineIdx >= textManager.lines.size()) return 0;
        String currentLine = textManager.lines.get(lineIdx).toString();
        int baseSpaces = getLineIndentSpaces(currentLine);
        String trimmed = currentLine.trim();
        if (trimmed.endsWith("{") || trimmed.endsWith("[") || trimmed.endsWith(":")
        || (trimmed.startsWith("<") && !trimmed.startsWith("</") && !trimmed.startsWith("<?")
        && !trimmed.startsWith("<!") && !trimmed.endsWith("/>") && !trimmed.contains("</"))) {
            return baseSpaces + 4;
        }
        return baseSpaces;
    }

    public void insertSymbol(String symbol) {
        if (isReadOnly) return;
        saveCurrentStateForUndo();
        if (selection.isSelecting) selection.deleteSelectedText(textManager);

        for (int i = 0; i < symbol.length(); i++) {
            char c = symbol.charAt(i);
            if (c == '\n') {
                int indent = calculateAutoIndentSpaces(selection.cursorLine);
                String remain = textManager.lines.get(selection.cursorLine).substring(selection.cursorCol);
                textManager.lines.get(selection.cursorLine)
                .delete(selection.cursorCol, textManager.lines.get(selection.cursorLine).length());
                StringBuilder nextLine = new StringBuilder();
                for (int s = 0; s < indent; s++) nextLine.append(' ');
                nextLine.append(remain);
                textManager.lines.add(selection.cursorLine + 1, nextLine);
                selection.cursorLine++;
                selection.cursorCol = indent;
            } else {
                textManager.lines.get(selection.cursorLine).insert(selection.cursorCol, c);
                selection.cursorCol++;
            }
        }
        theme.updateFontMetrics(textManager.lines.size());
        selection.isSelecting = false;
        selection.isActionModeActive = false;
        if (selectionListener != null) selectionListener.onSelectionChanged(false);
        ensureCursorVisible();
        triggerTextModified();
        invalidate();
    }

    private void triggerTextModified() {
        focusedErrorLine = -1;
        if (ecjErrors.containsKey(selection.cursorLine)) ecjErrors.remove(selection.cursorLine);
        if (ecjWarnings.containsKey(selection.cursorLine)) ecjWarnings.remove(selection.cursorLine);

        // 🔥 FIX: Yahan saveState NAHI karna hai kyunki edit se pehle saveCurrentStateForUndo() ho chuka hai!
        // Ab Undo button 1st tap par hi turant kaam karega!
        if (historyChangeListener != null) {
            historyChangeListener.onHistoryChanged(
                    fileHistoryManager.canUndo(currentFilePath),
                    fileHistoryManager.canRedo(currentFilePath)
            );
        }

        if (contentChangeListener != null) contentChangeListener.onContentChanged();

        runInstantSyntaxCheck();

        if (debounceRunnable != null) debounceHandler.removeCallbacks(debounceRunnable);
        debounceRunnable = new Runnable() {
            @Override
            public void run() {
                if (autoSaveListener != null) autoSaveListener.onAutoSaveRequested();
                runInstantSyntaxCheck();
                if (liveCheckListener != null) liveCheckListener.onCheckRequested();
            }
        };
        debounceHandler.postDelayed(debounceRunnable, 700);
    }
    private void runInstantSyntaxCheck() {
        String currentText = getText();
        if (currentFileName != null && (currentFileName.toLowerCase().endsWith(".xml") || currentFileName.toLowerCase().endsWith(".html"))) {
            ecjErrors.clear();
            ecjWarnings.clear();

            if (currentText.trim().isEmpty() && !currentFilePath.isEmpty()) {
                ecjErrors.put(0, new ErrorInfo(0, 1, "XML file is empty"));
                invalidate();
                return;
            }
            try {
                javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
                factory.setValidating(false);
                factory.setNamespaceAware(true);
                javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
                builder.setErrorHandler(new org.xml.sax.ErrorHandler() {
                    @Override
                    public void warning(org.xml.sax.SAXParseException e) {
                        ecjWarnings.put(Math.max(0, e.getLineNumber() - 1), new ErrorInfo(Math.max(0, e.getColumnNumber() - 1), 1, e.getMessage()));
                    }

                    @Override
                    public void error(org.xml.sax.SAXParseException e) {
                        ecjErrors.put(Math.max(0, e.getLineNumber() - 1), new ErrorInfo(Math.max(0, e.getColumnNumber() - 1), 1, e.getMessage()));
                    }

                    @Override
                    public void fatalError(org.xml.sax.SAXParseException e) {
                        ecjErrors.put(Math.max(0, e.getLineNumber() - 1), new ErrorInfo(Math.max(0, e.getColumnNumber() - 1), 1, e.getMessage()));
                    }
                });
                builder.parse(new org.xml.sax.InputSource(new java.io.StringReader(currentText)));
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("lineNumber: ")) {
                    try {
                        int line = Integer.parseInt(msg.substring(msg.indexOf("lineNumber: ") + 12, msg.indexOf(";", msg.indexOf("lineNumber: "))));
                        ecjErrors.put(Math.max(0, line - 1), new ErrorInfo(0, 1, msg));
                    } catch (Exception ex) {
                        ecjErrors.put(0, new ErrorInfo(0, 1, msg));
                    }
                } else if (ecjErrors.isEmpty()) {
                    ecjErrors.put(0, new ErrorInfo(0, 1, msg != null ? msg : "XML Syntax Error"));
                }
            }
            invalidate();
        }
    }

    public void clearLiveErrors() {
        ecjErrors.clear();
        ecjWarnings.clear();
        invalidate();
    }

    public void requestLiveCheck() {
        runInstantSyntaxCheck();
        if (liveCheckListener != null) liveCheckListener.onCheckRequested();
    }

    public void focusError(int ecjLineNumber, int colStart, int errorLen, String msg) {
        if (textManager.lines.isEmpty()) return;
        int computedLine = Math.max(0, ecjLineNumber - 1);
        final int targetLine = Math.min(computedLine, textManager.lines.size() - 1);

        focusedErrorLine = targetLine;
        focusedErrorCol = Math.max(0, colStart);
        focusedErrorLen = errorLen > 0 ? errorLen : 1;

        // 🔥 FIX: Hum baaki errors ko overwrite ya clear nahi karenge!
        // Agar us line par pehle se koi error nahi hai, toh ek temporary info daal do focus ke liye.
        if (!ecjErrors.containsKey(targetLine) && !ecjWarnings.containsKey(targetLine)) {
            ecjErrors.put(targetLine, new ErrorInfo(focusedErrorCol, focusedErrorLen, msg != null ? msg : "Error"));
        }

        selection.cursorLine = targetLine;
        selection.cursorCol = Math.max(0, Math.min(focusedErrorCol, textManager.lines.get(targetLine).length()));

        ensureCursorVisible();
        float targetY = (targetLine * theme.lineHeight) + TOP_PADDING;
        touch.centerSearchOnScreen(targetY, theme.lineHeight, getHeight() / 2f, touch.scrollX, getWidth());

        // 🔥 Highlight the targeted line temporarily so user spots it, while preserving ALL red zig-zags
        temporaryHighlightLines.add(targetLine);
        highlightHandler.removeCallbacks(highlightClearer);
        highlightClearer = new Runnable() {
            @Override
            public void run() {
                temporaryHighlightLines.remove(targetLine);
                focusedErrorLine = -1; // Remove the single focus state after animation
                invalidate();
            }
        };
        highlightHandler.postDelayed(highlightClearer, 2000);

        invalidate();
    }
    public void setDiagnostic(int ecjLineNumber, int colStart, int errorLen, String msg, boolean isWarning) {
        if (ecjLineNumber <= 0) return;
        int actualLine = ecjLineNumber - 1;
        if (isWarning) ecjWarnings.put(actualLine, new ErrorInfo(colStart, errorLen, msg));
        else ecjErrors.put(actualLine, new ErrorInfo(colStart, errorLen, msg));
        invalidate();
    }

    public void navigateToError(int ecjLineNumber) {
        int targetLine = ecjLineNumber - 1;
        if (targetLine < 0 || targetLine >= textManager.lines.size()) return;
        selection.cursorLine = targetLine;
        selection.cursorCol = textManager.lines.get(selection.cursorLine).length();
        ensureCursorVisible();
        invalidate();
    }

    public void findText(String query) {
        textManager.findText(query, selection);
        if (textManager.isSearchHighlight && textManager.searchLine >= 0
        && textManager.searchLine < textManager.lines.size()) {
            String lineStr = textManager.lines.get(textManager.searchLine).toString();
            int safeCol = Math.min(Math.max(0, textManager.searchStartCol), lineStr.length());
            float targetY = (textManager.searchLine * theme.lineHeight) + TOP_PADDING;
            float matchX = theme.gutterWidth + CODE_START_PAD
            + theme.textPaint.measureText(lineStr.substring(0, safeCol));
            touch.centerSearchOnScreen(targetY, theme.lineHeight, getHeight() / 2f, matchX, getWidth());
            touch.clampScroll(getMaxScrollY());
        }
        invalidate();
    }

    public void findNext() {
        textManager.findNext(selection);
        if (textManager.isSearchHighlight && textManager.searchLine >= 0
        && textManager.searchLine < textManager.lines.size()) {
            String lineStr = textManager.lines.get(textManager.searchLine).toString();
            int safeCol = Math.min(Math.max(0, textManager.searchStartCol), lineStr.length());
            float targetY = (textManager.searchLine * theme.lineHeight) + TOP_PADDING;
            float matchX = theme.gutterWidth + CODE_START_PAD
            + theme.textPaint.measureText(lineStr.substring(0, safeCol));
            touch.centerSearchOnScreen(targetY, theme.lineHeight, getHeight() / 2f, matchX, getWidth());
            touch.clampScroll(getMaxScrollY());
        } else {
            showCustomToast("No more matches found");
        }
        invalidate();
    }

    public void clearSearch() { textManager.clearSearch(); invalidate(); }

    public void selectAll() {
        selection.selectAll(textManager);
        if (selectionListener != null) selectionListener.onSelectionChanged(true);
        invalidate();
    }

        private void keepKeyboardAlive() {
        if (isReadOnly) return;
        requestFocus();
        final InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
        }
        postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isReadOnly) {
                    requestFocus();
                    if (imm != null) imm.showSoftInput(EditorView.this, InputMethodManager.SHOW_IMPLICIT);
                }
            }
        }, 60);
    }

    private void highlightChangedLines(String beforeText, String afterText, int fallbackLine) {
        temporaryHighlightLines.clear();
        if (beforeText != null && afterText != null && !beforeText.equals(afterText)) {
            String[] oldLines = beforeText.split("\n", -1);
            String[] newLines = afterText.split("\n", -1);

            int startDiff = 0;
            int minLen = Math.min(oldLines.length, newLines.length);
            while (startDiff < minLen && oldLines[startDiff].equals(newLines[startDiff])) {
                startDiff++;
            }

            int endOld = oldLines.length - 1;
            int endNew = newLines.length - 1;
            while (endOld >= startDiff && endNew >= startDiff && oldLines[endOld].equals(newLines[endNew])) {
                endOld--;
                endNew--;
            }

            int fromLine = Math.max(0, Math.min(startDiff, newLines.length - 1));
            int toLine = Math.max(fromLine, Math.min(endNew, newLines.length - 1));
            for (int i = fromLine; i <= toLine; i++) {
                temporaryHighlightLines.add(i);
            }
        }
        if (temporaryHighlightLines.isEmpty() && fallbackLine >= 0 && fallbackLine < textManager.lines.size()) {
            temporaryHighlightLines.add(fallbackLine);
        }

        highlightHandler.removeCallbacks(highlightClearer);
        highlightClearer = new Runnable() {
            @Override
            public void run() {
                temporaryHighlightLines.clear();
                invalidate();
            }
        };
        highlightHandler.postDelayed(highlightClearer, 1800);
    }

    public void copyToClipboard() {
        selection.copyToClipboard(getContext(), textManager);
        if (selectionListener != null) selectionListener.onSelectionChanged(false);
        keepKeyboardAlive();
        showCustomToast("Copied!");
        invalidate();
    }

    public void cutToClipboard() {
        if (isReadOnly) { copyToClipboard(); return; }
        saveCurrentStateForUndo();
        selection.cutToClipboard(getContext(), textManager);
        theme.updateFontMetrics(textManager.lines.size());
        triggerTextModified();
        textManager.clearSearch();
        if (selectionListener != null) selectionListener.onSelectionChanged(false);

        ensureCursorVisible();

        // 🔥 Highlight the line where cut finished
        final int targetLine = Math.max(0, Math.min(selection.cursorLine, textManager.lines.size() - 1));
        temporaryHighlightLines.clear();
        temporaryHighlightLines.add(targetLine);
        highlightHandler.removeCallbacks(highlightClearer);
        highlightClearer = new Runnable() {
            @Override public void run() {
                temporaryHighlightLines.clear();
                invalidate();
            }
        };
        highlightHandler.postDelayed(highlightClearer, 1800);

        keepKeyboardAlive();
        showCustomToast("Cut!");
        invalidate();
    }

    public void pasteFromClipboard() {
        if (isReadOnly) return;
        saveCurrentStateForUndo();
        selection.pasteFromClipboard(getContext(), textManager);
        theme.updateFontMetrics(textManager.lines.size());

        ensureCursorVisible();
        triggerTextModified();
        textManager.clearSearch();
        if (selectionListener != null) selectionListener.onSelectionChanged(false);

        // 🔥 Highlight the last line of pasted code
        final int targetLine = Math.max(0, Math.min(selection.cursorLine, textManager.lines.size() - 1));
        temporaryHighlightLines.clear();
        temporaryHighlightLines.add(targetLine);
        highlightHandler.removeCallbacks(highlightClearer);
        highlightClearer = new Runnable() {
            @Override public void run() {
                temporaryHighlightLines.clear();
                invalidate();
            }
        };
        highlightHandler.postDelayed(highlightClearer, 1800);

        keepKeyboardAlive();
        showCustomToast("Pasted!");
        invalidate();
    }

    private void applyHistoryState(String previousScreenText, String targetText, int line, int col) {
        textManager.setText(targetText);
        selection.cursorLine = Math.max(0, Math.min(line, textManager.lines.size() - 1));
        String lineStr = textManager.lines.get(selection.cursorLine).toString();
        selection.cursorCol = Math.max(0, Math.min(col, lineStr.length()));
        selection.isSelecting = false;
        selection.isActionModeActive = false;
        theme.updateFontMetrics(textManager.lines.size());

        if (contentChangeListener != null) contentChangeListener.onContentChanged();
        runInstantSyntaxCheck();
        if (liveCheckListener != null) liveCheckListener.onCheckRequested();

        // 🔥 AIDE-style changed line highlight on Undo / Redo
        highlightChangedLines(previousScreenText, targetText, selection.cursorLine);
        ensureCursorVisible();
        keepKeyboardAlive();
        invalidate();
    }

    public void undo() {
        if (isReadOnly) return;
        String beforeText = getText();
        FileHistoryManager.HistoryState s = fileHistoryManager.undo(
                currentFilePath, beforeText, selection.cursorLine, selection.cursorCol);
        if (s == null) {
            showCustomToast("Nothing to undo");
            if (historyChangeListener != null) {
                historyChangeListener.onHistoryChanged(false, fileHistoryManager.canRedo(currentFilePath));
            }
            return;
        }
        applyHistoryState(beforeText, s.text, s.cursorLine, s.cursorCol);
        if (historyChangeListener != null) {
            historyChangeListener.onHistoryChanged(
                    fileHistoryManager.canUndo(currentFilePath),
                    fileHistoryManager.canRedo(currentFilePath)
            );
        }
    }

    public void redo() {
        if (isReadOnly) return;
        String beforeText = getText();
        FileHistoryManager.HistoryState s = fileHistoryManager.redo(
                currentFilePath, beforeText, selection.cursorLine, selection.cursorCol);
        if (s == null) {
            showCustomToast("Nothing to redo");
            if (historyChangeListener != null) {
                historyChangeListener.onHistoryChanged(fileHistoryManager.canUndo(currentFilePath), false);
            }
            return;
        }
        applyHistoryState(beforeText, s.text, s.cursorLine, s.cursorCol);
        if (historyChangeListener != null) {
            historyChangeListener.onHistoryChanged(
                    fileHistoryManager.canUndo(currentFilePath),
                    fileHistoryManager.canRedo(currentFilePath)
            );
        }
    }
    public void closeCurrentFile() {
        setFileInfo("No File", "");
        setText("");
        if (fileCloseListener != null) fileCloseListener.onFileClosed();
        invalidate();
    }

    private float getMaxScrollY() {
        return Math.max(0, (textManager.lines.size() * theme.lineHeight) - getHeight() + TOP_PADDING + 300);
    }

    @Override public void computeScroll() { touch.computeScroll(this, getMaxScrollY()); }

    private int getLineIndentSpaces(String line) {
        int count = 0; int len = line.length();
        for (int i = 0; i < len; i++) {
            char c = line.charAt(i);
            if (c == ' ') count++;
            else if (c == '\t') count += 4;
            else break;
        }
        return count;
    }

    private void drawIndentGuides(Canvas canvas, int lineIndex, String line, float codeStartX, float lineTop) {
        int indentSpaces = 0;
        String trimmed = line.trim();
        if (!trimmed.isEmpty()) {
            indentSpaces = getLineIndentSpaces(line);
        } else {
            int prevIndent = 0;
            for (int p = lineIndex - 1; p >= Math.max(0, lineIndex - 50); p--) {
                String pl = textManager.lines.get(p).toString();
                if (!pl.trim().isEmpty()) { prevIndent = getLineIndentSpaces(pl); break; }
            }
            int nextIndent = 0; String nextTrimmed = "";
            for (int n = lineIndex + 1; n < Math.min(textManager.lines.size(), lineIndex + 50); n++) {
                String nl = textManager.lines.get(n).toString(); String nt = nl.trim();
                if (!nt.isEmpty()) { nextIndent = getLineIndentSpaces(nl); nextTrimmed = nt; break; }
            }
            if (nextTrimmed.startsWith("</") || nextTrimmed.startsWith("}"))
            indentSpaces = Math.min(prevIndent, nextIndent + 4);
            else
            indentSpaces = Math.min(prevIndent, nextIndent);
        }
        if (indentSpaces < 4) return;
        float spaceW = theme.textPaint.measureText(" ");
        int maxGuideCol = trimmed.isEmpty() ? indentSpaces : (indentSpaces - 1);
        for (int col = 4; col <= maxGuideCol; col += 4) {
            float gx = codeStartX + (col * spaceW);
            canvas.drawLine(gx, lineTop, gx, lineTop + theme.lineHeight, theme.indentGuidePaint);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int saveCount = -1;
        try {
            super.onDraw(canvas);
            boolean hasErrors = !ecjErrors.isEmpty();
            float statusSize = 18f;
            float statusX = getWidth() - 140f;
            float statusY = 20f;
            theme.statusPath.reset();
            if (hasErrors) {
                theme.statusPaint.setColor(theme.errorZigZagPaint.getColor());
                theme.statusTextPaint.setColor(theme.errorZigZagPaint.getColor());
                theme.statusPath.moveTo(statusX, statusY);
                theme.statusPath.lineTo(statusX + statusSize, statusY + statusSize);
                theme.statusPath.moveTo(statusX + statusSize, statusY);
                theme.statusPath.lineTo(statusX, statusY + statusSize);
                canvas.drawText("Errors", statusX - 12, statusY + statusSize - 2, theme.statusTextPaint);
            } else {
                theme.statusPaint.setColor(theme.stringPaint.getColor());
                theme.statusTextPaint.setColor(theme.stringPaint.getColor());
                theme.statusPath.moveTo(statusX, statusY + (statusSize * 0.5f));
                theme.statusPath.lineTo(statusX + (statusSize * 0.35f), statusY + statusSize);
                theme.statusPath.lineTo(statusX + statusSize, statusY + (statusSize * 0.1f));
                canvas.drawText("OK", statusX - 12, statusY + statusSize - 2, theme.statusTextPaint);
            }
            canvas.drawPath(theme.statusPath, theme.statusPaint);

            saveCount = canvas.save();
            int drawScrollX = (int) touch.scrollX;
            int drawScrollY = (int) touch.scrollY;
            canvas.translate(-drawScrollX, 0);

            float hX = 15f;
            float headerStartY = -drawScrollY + 28f;
            float dividerY = -drawScrollY + TOP_PADDING;
            float codeStartX = theme.gutterWidth + CODE_START_PAD;

            theme.headerTitlePaint.setFakeBoldText(true);
            theme.headerTitlePaint.setStrokeWidth(0.5f);
            theme.headerTitlePaint.setStyle(Paint.Style.FILL_AND_STROKE);

            if (fileIcon != null) {
                int ix = (int) hX, iy = (int) headerStartY + 2, size = 66;
                fileIcon.setBounds(ix, iy, ix + size, iy + size);
                fileIcon.draw(canvas);
            }
            canvas.drawText(currentFileName, hX + 85, headerStartY + 32, theme.headerTitlePaint);
            canvas.drawText(currentFilePath, hX + 85, headerStartY + 62, theme.headerPathPaint);
            canvas.drawLine(0, dividerY,
            Math.max(getWidth() + drawScrollX,
            theme.textPaint.measureText(currentFilePath) + hX + 200),
            dividerY, theme.dividerPaint);

            float gutterBottom = Math.max(getHeight() + 2000,
            (textManager.lines.size() * theme.lineHeight) + Math.abs(drawScrollY) + 1000);
            canvas.drawRect(0, dividerY, theme.gutterWidth, gutterBottom, theme.gutterPaint);
            canvas.drawLine(theme.gutterWidth, dividerY, theme.gutterWidth, gutterBottom, theme.indentGuidePaint);

            int startIdx = Math.max(0, (int) ((drawScrollY - TOP_PADDING) / theme.lineHeight));
            int endIdx = Math.min(textManager.lines.size(),
            (int) ((drawScrollY + getHeight() - TOP_PADDING) / theme.lineHeight) + 2);

            if (!isReadOnly && hasFocus()
            && selection.cursorLine >= 0 && selection.cursorLine < textManager.lines.size()) {
                float lineTop = (selection.cursorLine * theme.lineHeight) - drawScrollY + TOP_PADDING;
                float highlightWidth = Math.max(getWidth() + drawScrollX,
                codeStartX + theme.textPaint.measureText(textManager.lines.get(selection.cursorLine).toString()) + 200);
                canvas.drawRect(theme.gutterWidth + 1f, lineTop, highlightWidth, lineTop + theme.lineHeight,
                theme.activeLinePaint);
            }

            if (textManager.isSearchHighlight && textManager.searchLine >= 0
            && textManager.searchLine < textManager.lines.size()) {
                float lineTop = (textManager.searchLine * theme.lineHeight) - drawScrollY + TOP_PADDING;
                float lineBottom = lineTop + theme.lineHeight;
                float highlightWidth = Math.max(getWidth() + drawScrollX,
                codeStartX + theme.textPaint.measureText(textManager.lines.get(textManager.searchLine).toString()) + 200);
                canvas.drawRect(theme.gutterWidth + 1f, lineTop, highlightWidth, lineBottom,
                theme.searchLineBackgroundPaint);
            }

            if (selection.isSelecting) {
                for (int i = startIdx; i < endIdx; i++) {
                    if (i >= selection.selStartLine && i <= selection.selEndLine) {
                        float lineTop = (i * theme.lineHeight) - drawScrollY + TOP_PADDING;
                        float xStart = codeStartX;
                        float xEnd = codeStartX + theme.textPaint.measureText(textManager.lines.get(i).toString());
                        if (i == selection.selStartLine)
                        xStart = codeStartX + theme.textPaint.measureText(
                        textManager.lines.get(i).substring(0,
                        Math.min(selection.selStartCol, textManager.lines.get(i).length())));
                        if (i == selection.selEndLine)
                        xEnd = codeStartX + theme.textPaint.measureText(
                        textManager.lines.get(i).substring(0,
                        Math.min(selection.selEndCol, textManager.lines.get(i).length())));
                        canvas.drawRect(xStart, lineTop, xEnd, lineTop + theme.lineHeight, theme.selectionPaint);
                    }
                }
            }

            Paint.FontMetrics fm = theme.textPaint.getFontMetrics();
            for (int i = startIdx; i < endIdx; i++) {
                float lineTop = (i * theme.lineHeight) - drawScrollY + TOP_PADDING;
                float baselineY = lineTop - fm.ascent;
                String line = textManager.lines.get(i).toString();
                if (temporaryHighlightLines.contains(i)) {
                    canvas.drawRect(theme.gutterWidth + 1f, lineTop,
                    getWidth() + drawScrollX, lineTop + theme.lineHeight,
                    theme.undoHighlightPaint);
                }

                canvas.drawText(String.valueOf(i + 1), theme.gutterWidth - 6f, baselineY, theme.lineNumberPaint);
                drawIndentGuides(canvas, i, line, codeStartX, lineTop);
                fastDrawJavaLine(canvas, line, codeStartX, baselineY, lineTop);

                ErrorInfo err = ecjErrors.get(i);
                if (err == null && i == focusedErrorLine)
                err = new ErrorInfo(focusedErrorCol, focusedErrorLen, "Focused Error");
                ErrorInfo warn = ecjWarnings.get(i);

                if (warn != null)
                drawZigZag(canvas, line, warn, baselineY, drawScrollX, theme.warningZigZagPaint, null);
                if (err != null) {
                    boolean isFocused = (i == focusedErrorLine);
                    Paint bgPaint = isFocused ? theme.errorHighlightBackgroundPaint : null;
                    drawZigZag(canvas, line, err, baselineY, drawScrollX, theme.errorZigZagPaint, bgPaint);
                }
            }

            if (selection.isSelecting
            && selection.selStartLine < textManager.lines.size()
            && selection.selEndLine < textManager.lines.size()) {
                float startX = codeStartX + theme.textPaint.measureText(
                textManager.lines.get(selection.selStartLine).substring(0,
                Math.min(selection.selStartCol, textManager.lines.get(selection.selStartLine).length())));
                float startY = (selection.selStartLine * theme.lineHeight) - drawScrollY + TOP_PADDING + theme.lineHeight;
                float endX = codeStartX + theme.textPaint.measureText(
                textManager.lines.get(selection.selEndLine).substring(0,
                Math.min(selection.selEndCol, textManager.lines.get(selection.selEndLine).length())));
                float endY = (selection.selEndLine * theme.lineHeight) - drawScrollY + TOP_PADDING + theme.lineHeight;
                selection.drawSelectionHandle(canvas, startX, startY, theme.handlePaint, theme.currentTextSize);
                selection.drawSelectionHandle(canvas, endX, endY, theme.handlePaint, theme.currentTextSize);
            }

            if (!isReadOnly && !selection.isSelecting && isCursorVisible && hasFocus()
            && selection.cursorLine >= 0 && selection.cursorLine < textManager.lines.size()) {
                String cLine = textManager.lines.get(selection.cursorLine).toString();
                if (selection.cursorCol > cLine.length()) selection.cursorCol = cLine.length();
                float cursorDrawX = codeStartX + theme.textPaint.measureText(cLine.substring(0, selection.cursorCol));
                float lineTop = (selection.cursorLine * theme.lineHeight) - drawScrollY + TOP_PADDING;
                if (lineTop + theme.lineHeight > 0 && lineTop < getHeight()) {
                    canvas.drawLine(cursorDrawX, lineTop, cursorDrawX, lineTop + theme.lineHeight, theme.cursorPaint);
                    selection.drawSelectionHandle(canvas, cursorDrawX, lineTop + theme.lineHeight,
                    theme.cursorPaint, theme.currentTextSize);
                }
            }

            canvas.restore();
            saveCount = -1;
        } catch (Exception e) {
            if (saveCount != -1) {
                try { canvas.restoreToCount(saveCount); } catch (Exception ignored) {}
            }
        }
    }

    private void drawZigZag(Canvas canvas, String line, ErrorInfo info, float baselineY,
    float drawScrollX, Paint zigZagPaint, Paint bgPaint) {
        float xStart, xEnd;
        int lineLen = line.length();
        float codeStartX = theme.gutterWidth + CODE_START_PAD;

        if (info.colStart >= 0 && info.length > 0 && info.colStart < lineLen) {
            int safeStart = Math.max(0, Math.min(info.colStart, lineLen));
            int safeEnd = Math.max(safeStart + 1, Math.min(info.colStart + info.length, lineLen));
            xStart = codeStartX + theme.textPaint.measureText(line.substring(0, safeStart));
            xEnd = codeStartX + theme.textPaint.measureText(line.substring(0, safeEnd));
            if (xEnd <= xStart + 25f) xEnd = xStart + 45f;
        } else {
            int firstChar = line.indexOf(line.trim()); if (firstChar == -1) firstChar = 0;
            xStart = codeStartX + theme.textPaint.measureText(line.substring(0, firstChar));
            xEnd = codeStartX + theme.textPaint.measureText(line);
            if (line.trim().isEmpty()) { xStart = codeStartX; xEnd = xStart + 80; }
        }

        if (bgPaint != null) {
            float lineTop = baselineY + theme.textPaint.getFontMetrics().ascent;
            canvas.drawRect(xStart, lineTop, xEnd, lineTop + theme.lineHeight, bgPaint);
        }

        Path zigZagPath = new Path();
        float vStartX = Math.max(xStart, drawScrollX);
        float vEndX = Math.min(xEnd, drawScrollX + getWidth());
        if (vEndX > vStartX) {
            float waveStep = Math.max(7f, theme.currentTextSize * 0.22f);
            float amplitude = Math.max(2.5f, theme.currentTextSize * 0.10f);
            float yBase = baselineY + Math.max(4f, theme.currentTextSize * 0.14f);
            zigZagPath.moveTo(vStartX, yBase);
            float currX = vStartX;
            boolean curveUp = true;
            while (currX < vEndX) {
                float nextX = Math.min(currX + waveStep, vEndX);
                float midX = (currX + nextX) / 2f;
                float controlY = curveUp ? (yBase - amplitude) : (yBase + amplitude);
                zigZagPath.quadTo(midX, controlY, nextX, yBase);
                currX = nextX;
                curveUp = !curveUp;
            }
            canvas.drawPath(zigZagPath, zigZagPaint);
        }
    }

    private boolean isPlainTextFile() {
        if (currentFileName == null) return false;
        String lower = currentFileName.toLowerCase().trim();
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".log") || lower.equals("no file");
    }

    private void fastDrawJavaLine(Canvas canvas, String line, float x, float y, float lineTop) {
        if (isPlainTextFile()) { canvas.drawText(line, x, y, theme.plainTextPaint); return; }

        String lowerFile = currentFileName != null ? currentFileName.toLowerCase() : "";
        boolean isXmlFile = lowerFile.endsWith(".xml") || lowerFile.endsWith(".html") || lowerFile.endsWith(".htm");
        boolean isPyFile = lowerFile.endsWith(".py") || lowerFile.endsWith(".pyw") || lowerFile.endsWith(".sh")
        || lowerFile.endsWith(".properties") || lowerFile.endsWith(".pro");
        boolean isJsonFile = lowerFile.endsWith(".json");

        String trimmed = line.trim();
        if (trimmed.startsWith("//") || trimmed.startsWith("<!--") || (isPyFile && trimmed.startsWith("#"))) {
            canvas.drawText(line, x, y, theme.commentPaint);
            return;
        }
        if (line.length() > 500) { canvas.drawText(line, x, y, theme.textPaint); return; }

        float currentX = x;
        int len = line.length();
        int i = 0;
        while (i < len) {
            char c = line.charAt(i);

            if ((c == '/' && i + 1 < len && line.charAt(i + 1) == '/')
            || (c == '<' && line.startsWith("<!--", i))
            || (isPyFile && c == '#')) {
                canvas.drawText(line.substring(i), currentX, y, theme.commentPaint);
                return;
            }

            if (c == '<' && isXmlFile) {
                int tStart = i;
                i++;
                if (i < len && (line.charAt(i) == '/' || line.charAt(i) == '?')) i++;
                while (i < len && (Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == '-'
                || line.charAt(i) == ':' || line.charAt(i) == '_' || line.charAt(i) == '.')) i++;
                if (i < len && line.charAt(i) == '>') i++;
                String tagToken = line.substring(tStart, i);
                canvas.drawText(tagToken, currentX, y, theme.xmlTagPaint);
                currentX += theme.textPaint.measureText(tagToken);
                continue;
            }

            if (isXmlFile && (c == '/' || c == '?') && i + 1 < len && line.charAt(i + 1) == '>') {
                String closeTag = line.substring(i, i + 2);
                canvas.drawText(closeTag, currentX, y, theme.xmlTagPaint);
                currentX += theme.textPaint.measureText(closeTag);
                i += 2;
                continue;
            }

            if (c == '>' && isXmlFile) {
                canvas.drawText(">", currentX, y, theme.xmlTagPaint);
                currentX += theme.textPaint.measureText(">");
                i++;
                continue;
            }

            if (c == '"' || c == '\'') {
                char quoteChar = c;
                int qStart = i;
                int j = i + 1;
                while (j < len) {
                    char ch = line.charAt(j);
                    if (ch == '\\') { j += 2; continue; }
                    if (ch == quoteChar) { j++; break; }
                    j++;
                }
                String strToken = line.substring(qStart, Math.min(j, len));
                Integer colorVal = parseColorValue(strToken);
                if (colorVal != null) {
                    drawColorPill(canvas, strToken, currentX, y, lineTop, colorVal);
                } else {
                    int afterStr = Math.min(j, len);
                    while (afterStr < len && line.charAt(afterStr) == ' ') afterStr++;
                    boolean isJsonKey = isJsonFile && afterStr < len && line.charAt(afterStr) == ':';
                    canvas.drawText(strToken, currentX, y, isJsonKey ? theme.xmlAttrPaint : theme.stringPaint);
                }
                currentX += theme.textPaint.measureText(strToken);
                i = j;
                continue;
            }

            if (c == '@' && !isXmlFile) {
                int aStart = i;
                i++;
                while (i < len && (Character.isLetterOrDigit(line.charAt(i))
                || line.charAt(i) == '_' || line.charAt(i) == '.')) i++;
                String annot = line.substring(aStart, i);
                canvas.drawText(annot, currentX, y, theme.annotationPaint);
                currentX += theme.textPaint.measureText(annot);
                continue;
            }

            boolean isWordChar = Character.isLetterOrDigit(c) || c == ':' || c == '_' || c == '$' || c == '@';
            if (isWordChar) {
                int wStart = i;
                while (i < len && (Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == ':'
                || line.charAt(i) == '_' || line.charAt(i) == '$' || line.charAt(i) == '@'
                || (isXmlFile && line.charAt(i) == '-'))) i++;
                String word = line.substring(wStart, i);

                Integer colorVal = null;
                if (word.startsWith("0x") || word.startsWith("0X")
                || (word.length() == 6 && isAllHex(word))
                || (word.length() == 8 && isAllHex(word))) {
                    colorVal = parseColorValue(word);
                }

                if (colorVal != null) {
                    drawColorPill(canvas, word, currentX, y, lineTop, colorVal);
                } else {
                    int nextIdx = i;
                    while (nextIdx < len && line.charAt(nextIdx) == ' ') nextIdx++;
                    char nextChar = nextIdx < len ? line.charAt(nextIdx) : 0;

                    Paint p = theme.textPaint;
                    boolean matched = false;

                    if (isXmlFile && (nextChar == '=' || word.contains(":"))) { p = theme.xmlAttrPaint; matched = true; }
                    if (!matched) { for (String ctrl : controls) { if (word.equals(ctrl)) { p = theme.controlPaint; matched = true; break; } } }
                    if (!matched) { for (String kw : keywords) { if (word.equals(kw)) { p = theme.keywordPaint; matched = true; break; } } }
                    if (!matched) { for (String lit : literals) { if (word.equals(lit)) { p = theme.numberPaint; matched = true; break; } } }
                    if (!matched) { for (String ty : types) { if (word.equals(ty)) { p = theme.typePaint; matched = true; break; } } }
                    if (!matched) { for (String pk : packages) { if (word.equals(pk)) { p = theme.annotationPaint; matched = true; break; } } }
                    if (!matched && nextChar == '(') { p = theme.methodPaint; matched = true; }
                    if (!matched && word.length() > 0 && Character.isUpperCase(word.charAt(0))) p = theme.typePaint;
                    else if (!matched && word.matches("\\d+(\\.\\d+)?[fFdDlL]?")) p = theme.numberPaint;
                    else if (!matched && (word.startsWith("android:") || word.startsWith("app:")
                    || word.startsWith("xmlns:") || word.startsWith("tools:"))) p = theme.xmlAttrPaint;

                    canvas.drawText(word, currentX, y, p);
                }
                currentX += theme.textPaint.measureText(word);
                continue;
            }

            if (c == '#') {
                int hStart = i;
                i++;
                while (i < len && isHexChar(line.charAt(i))) i++;
                String hexToken = line.substring(hStart, i);
                Integer colorVal = parseColorValue(hexToken);
                if (colorVal != null)
                drawColorPill(canvas, hexToken, currentX, y, lineTop, colorVal);
                else
                canvas.drawText(hexToken, currentX, y, theme.symbolPaint);
                currentX += theme.textPaint.measureText(hexToken);
                continue;
            }

            String sym = String.valueOf(c);
            Paint sp = (isXmlFile && c == '=') ? theme.xmlAttrPaint : theme.symbolPaint;
            canvas.drawText(sym, currentX, y, sp);
            currentX += theme.textPaint.measureText(sym);
            i++;
        }
    }

    public static Integer parseColorValue(String s) {
        if (s == null) return null;
        String t = s.trim();
        if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))) {
            if (t.length() >= 2) t = t.substring(1, t.length() - 1).trim();
        }
        if (t.startsWith("#")) {
            String hex = t.substring(1);
            if ((hex.length() == 3 || hex.length() == 4 || hex.length() == 6 || hex.length() == 8)
            && isAllHex(hex)) {
                try {
                    if (hex.length() == 3) {
                        char r = hex.charAt(0), g = hex.charAt(1), b = hex.charAt(2);
                        hex = "" + r + r + g + g + b + b;
                    } else if (hex.length() == 4) {
                        char a = hex.charAt(0), r = hex.charAt(1), g = hex.charAt(2), b = hex.charAt(3);
                        hex = "" + a + a + r + r + g + g + b + b;
                    }
                    long val = Long.parseLong(hex, 16);
                    if (hex.length() == 6) val |= 0xFF000000L;
                    return (int) val;
                } catch (Exception ignored) {}
            }
            return null;
        }
        if (t.startsWith("0x") || t.startsWith("0X")) {
            String hex = t.substring(2);
            if ((hex.length() == 6 || hex.length() == 8) && isAllHex(hex)) {
                try {
                    long val = Long.parseLong(hex, 16);
                    if (hex.length() == 6) val |= 0xFF000000L;
                    return (int) val;
                } catch (Exception ignored) {}
            }
            return null;
        }
        if ((t.length() == 6 || t.length() == 8) && isAllHex(t)) {
            try {
                long val = Long.parseLong(t, 16);
                if (t.length() == 6) val |= 0xFF000000L;
                return (int) val;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static boolean isHexChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isAllHex(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (!isHexChar(s.charAt(i))) return false;
        return true;
    }

    private void drawColorPill(Canvas canvas, String token, float currentX, float y, float lineTop, Integer colorVal) {
        int c = colorVal.intValue();
        float tokenW = theme.textPaint.measureText(token);
        float pad = Math.max(3f, theme.currentTextSize * 0.10f);
        float top = lineTop + Math.max(2f, theme.currentTextSize * 0.08f);
        float bottom = lineTop + theme.lineHeight - Math.max(2f, theme.currentTextSize * 0.08f);
        RectF pill = new RectF(currentX - pad, top, currentX + tokenW + pad, bottom);
        float rad = Math.max(4f, theme.currentTextSize * 0.15f);

        theme.colorPillPaint.setColor(c);
        canvas.drawRoundRect(pill, rad, rad, theme.colorPillPaint);

        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        if (lum < 0.18) {
            theme.colorStrokePaint.setColor(theme.indentGuidePaint.getColor());
            canvas.drawRoundRect(pill, rad, rad, theme.colorStrokePaint);
        }
        Paint textP = (lum > 0.5) ? theme.colorTextDarkPaint : theme.colorTextLightPaint;
        canvas.drawText(token, currentX, y, textP);
    }

    private static String iconKeyForFile(String fileName) {
        String n = fileName == null ? "" : fileName.toLowerCase().trim();
        if (n.endsWith(".java")) return "java";
        if (n.endsWith(".kt") || n.endsWith(".kts")) return "kt";
        if (n.endsWith(".c") || n.endsWith(".cpp") || n.endsWith(".cc") || n.endsWith(".h") || n.endsWith(".hpp")) return "c";
        if (n.endsWith(".py") || n.endsWith(".pyw")) return "py";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "html";
        if (n.endsWith(".js")) return "js";
        if (n.endsWith(".json")) return "json";
        if (n.endsWith(".xml")) return "xml";
        if (n.endsWith(".gradle")) return "gradle";
        if (n.endsWith(".pro") || n.endsWith(".properties")) return "pro";
        if (n.endsWith(".pdf")) return "pdf";
        if (n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z")
        || n.endsWith(".tar") || n.endsWith(".gz")) return "zip";
        if (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi")
        || n.endsWith(".3gp") || n.endsWith(".mov")) return "video";
        if (n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".ogg") || n.endsWith(".m4a")) return "audio";
        if (n.endsWith(".apk")) return "apk";
        if (n.endsWith(".jar") || n.endsWith(".class") || n.endsWith(".aar") || n.endsWith(".dex")) return "jar";
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
        || n.endsWith(".webp") || n.endsWith(".gif")) return "image";
        // 🔥 यहाँ .px जोड़ा गया है
        if (n.endsWith(".px")) return "px";
        return "txt";
    }
    public static int colorForFile(String fileName) {
        switch (iconKeyForFile(fileName)) {
            case "java":   return Color.parseColor("#FFE76F00");
            case "kt":     return Color.parseColor("#FF7F52FF");
            case "c":      return Color.parseColor("#FF00599C");
            case "py":     return Color.parseColor("#FF3776AB");
            case "html":   return Color.parseColor("#FFE34F26");
            case "js":     return Color.parseColor("#FFF7DF1E");
            case "json":   return Color.parseColor("#FF546E7A");
            case "xml":    return Color.parseColor("#FF2962FF");
            case "gradle": return Color.parseColor("#FF00897B");
            case "pro":    return Color.parseColor("#FF90A4AE");
            case "pdf":    return Color.parseColor("#FFF40F02");
            case "zip":    return Color.parseColor("#FF795548");
            case "video":  return Color.parseColor("#FFF44336");
            case "audio":  return Color.parseColor("#FF9C27B0");
            case "apk":    return Color.parseColor("#FF1976D2");
            case "jar":    return Color.parseColor("#FFD32F2F");
            case "image":  return Color.parseColor("#FFBDBDBD");
            // 🔥 यहाँ PX फाइल का कलर जोड़ा गया है
            case "px":     return Color.parseColor("#FF2962FF");
            default:       return Color.parseColor("#FF888888");
        }
    }
    public static int resolveFileIconId(android.content.Context ctx, String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase().trim();
        String iconName = "ic_tree_file";
        if (name.endsWith(".java")) iconName = "ic_file_java";
        else if (name.endsWith(".kt") || name.endsWith(".kts")) iconName = "ic_file_kt";
        else if (name.endsWith(".c") || name.endsWith(".cpp") || name.endsWith(".cc")
        || name.endsWith(".h") || name.endsWith(".hpp")) iconName = "ic_file_c";
        else if (name.endsWith(".py") || name.endsWith(".pyw")) iconName = "ic_file_py";
        else if (name.endsWith(".html") || name.endsWith(".htm")) iconName = "ic_file_html";
        else if (name.endsWith(".js")) iconName = "ic_file_js";
        else if (name.endsWith(".json")) iconName = "ic_file_json";
        else if (name.endsWith(".xml")) iconName = "ic_file_xml";
        else if (name.endsWith(".gradle")) iconName = "ic_file_gradle";
        else if (name.endsWith(".pro") || name.endsWith(".properties")) iconName = "ic_file_pro";
        else if (name.endsWith(".pdf")) iconName = "ic_file_pdf";
        else if (name.endsWith(".zip") || name.endsWith(".rar") || name.endsWith(".7z")
        || name.endsWith(".tar") || name.endsWith(".gz")) iconName = "ic_file_zip";
        else if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi")
        || name.endsWith(".3gp") || name.endsWith(".mov")) iconName = "ic_file_video";
        else if (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".ogg")
        || name.endsWith(".m4a")) iconName = "ic_file_audio";
        else if (name.endsWith(".apk")) iconName = "ic_file_apk";
        else if (name.endsWith(".jar") || name.endsWith(".class") || name.endsWith(".aar")
        || name.endsWith(".dex")) iconName = "ic_file_jar";
        else if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
        || name.endsWith(".webp") || name.endsWith(".gif")) iconName = "ic_file_image";
        // 🔥 यहाँ .px के लिए आइकॉन जोड़ा गया है
        else if (name.endsWith(".px")) iconName = "ic_file_px";
        else if (name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".sh")
        || name.endsWith(".css")) iconName = "ic_file_txt";

        android.content.res.Resources res = ctx.getResources();
        String pkg = ctx.getPackageName();
        int resId = res.getIdentifier(iconName, "drawable", pkg);
        if (resId == 0 && iconName.equals("ic_file_zip")) resId = res.getIdentifier("ic_zip", "drawable", pkg);
        if (resId == 0 && iconName.equals("ic_file_jar")) resId = res.getIdentifier("ic_file_zip", "drawable", pkg);
        if (resId == 0) resId = res.getIdentifier("ic_tree_file", "drawable", pkg);
        return resId != 0 ? resId : android.R.drawable.ic_menu_sort_by_size;
    }
    public static Drawable loadFileIconDrawable(android.content.Context ctx, String fileName) {
        int id = resolveFileIconId(ctx, fileName);
        Drawable d = null;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 21) d = ctx.getResources().getDrawable(id, ctx.getTheme());
            else d = ctx.getResources().getDrawable(id);
        } catch (Exception e) {
            try { d = ctx.getResources().getDrawable(id); } catch (Exception e2) { d = null; }
        }
        if (d != null) { try { d = d.mutate(); } catch (Exception ignored) {} }
        return d;
    }

    private boolean isWordCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        getParent().requestDisallowInterceptTouchEvent(true);
        if (event.getAction() == MotionEvent.ACTION_DOWN && !touch.scroller.isFinished())
        touch.scroller.forceFinished(true);

        boolean handledScale = touch.scaleGestureDetector.onTouchEvent(event);
        float codeStartX = theme.gutterWidth + CODE_START_PAD;

        if (event.getAction() == MotionEvent.ACTION_DOWN && selection.isSelecting
        && selection.selStartLine < textManager.lines.size()
        && selection.selEndLine < textManager.lines.size()) {
            float startX = codeStartX + theme.textPaint.measureText(
            textManager.lines.get(selection.selStartLine).substring(0,
            Math.min(selection.selStartCol, textManager.lines.get(selection.selStartLine).length())))
            - touch.scrollX;
            float startY = (selection.selStartLine * theme.lineHeight) - touch.scrollY + TOP_PADDING + theme.lineHeight;
            float endX = codeStartX + theme.textPaint.measureText(
            textManager.lines.get(selection.selEndLine).substring(0,
            Math.min(selection.selEndCol, textManager.lines.get(selection.selEndLine).length())))
            - touch.scrollX;
            float endY = (selection.selEndLine * theme.lineHeight) - touch.scrollY + TOP_PADDING + theme.lineHeight;
            float touchRadius = theme.currentTextSize * 2.5f;
            float distStart = (float) Math.hypot(event.getX() - startX, event.getY() - startY);
            float distEnd = (float) Math.hypot(event.getX() - endX, event.getY() - endY);

            if (distStart < touchRadius && distEnd < touchRadius) {
                selection.draggingHandle = event.getX() > startX ? 2 : 1;
                return true;
            } else if (distStart < touchRadius) { selection.draggingHandle = 1; return true; }
            else if (distEnd < touchRadius) { selection.draggingHandle = 2; return true; }
            else selection.draggingHandle = 0;
        }

        if (event.getAction() == MotionEvent.ACTION_MOVE && selection.draggingHandle != 0) {
            updateHandlePosition(event.getX(), event.getY(), selection.draggingHandle);
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            if (selection.draggingHandle != 0) { selection.draggingHandle = 0; return true; }
        }

        boolean handledGesture = touch.gestureDetector.onTouchEvent(event);
        return handledScale || handledGesture || super.onTouchEvent(event);
    }

    private void updateHandlePosition(float tapX, float tapY, int handleType) {
        float adjustedY = tapY - theme.lineHeight;
        int targetLine = Math.max(0, Math.min(textManager.lines.size() - 1,
        (int) ((adjustedY + touch.scrollY - TOP_PADDING) / theme.lineHeight)));
        String lineStr = textManager.lines.get(targetLine).toString();
        float targetX = tapX + touch.scrollX - theme.gutterWidth - CODE_START_PAD;

        int col = 0;
        float currentWidth = 0;
        while (col < lineStr.length()) {
            float cw = theme.textPaint.measureText(String.valueOf(lineStr.charAt(col)));
            if (currentWidth + (cw / 2) > targetX) break;
            currentWidth += cw;
            col++;
        }

        if (handleType == 1) {
            if (targetLine < selection.selEndLine
            || (targetLine == selection.selEndLine && col <= selection.selEndCol)) {
                selection.selStartLine = targetLine;
                selection.selStartCol = col;
            } else {
                selection.selStartLine = selection.selEndLine;
                selection.selStartCol = selection.selEndCol;
                selection.selEndLine = targetLine;
                selection.selEndCol = col;
                selection.draggingHandle = 2;
            }
        } else if (handleType == 2) {
            if (targetLine > selection.selStartLine
            || (targetLine == selection.selStartLine && col >= selection.selStartCol)) {
                selection.selEndLine = targetLine;
                selection.selEndCol = col;
            } else {
                selection.selEndLine = selection.selStartLine;
                selection.selEndCol = selection.selStartCol;
                selection.selStartLine = targetLine;
                selection.selStartCol = col;
                selection.draggingHandle = 1;
            }
        }
        ensureCursorVisibleForSelection(targetLine, col);
        invalidate();
    }

    private void ensureCursorVisibleForSelection(int targetLine, int col) {
        float codeStartX = theme.gutterWidth + CODE_START_PAD;
        float cursorYTop = (targetLine * theme.lineHeight) + TOP_PADDING;
        float cursorYBottom = cursorYTop + theme.lineHeight;
        String lineStr = textManager.lines.get(targetLine).toString();
        int safeCol = Math.min(col, lineStr.length());
        float cursorX = codeStartX + theme.textPaint.measureText(lineStr.substring(0, safeCol));
        float visibleTop = touch.scrollY + TOP_PADDING;
        float visibleBottom = touch.scrollY + getHeight() - (theme.lineHeight * 2f);
        float visibleLeft = touch.scrollX + codeStartX;
        float visibleRight = touch.scrollX + getWidth() - 50;
        boolean needsScroll = false;
        float targetScrollY = touch.scrollY, targetScrollX = touch.scrollX;

        if (cursorYBottom > visibleBottom) { targetScrollY = touch.scrollY + theme.lineHeight; needsScroll = true; }
        else if (cursorYTop < visibleTop) { targetScrollY = touch.scrollY - theme.lineHeight; needsScroll = true; }
        if (cursorX > visibleRight) { targetScrollX = touch.scrollX + 100; needsScroll = true; }
        else if (cursorX < visibleLeft) { targetScrollX = Math.max(0, touch.scrollX - 100); needsScroll = true; }

        if (needsScroll) {
            float maxScrollY = getMaxScrollY();
            if (!touch.scroller.isFinished()) touch.scroller.forceFinished(true);
            touch.scroller.startScroll(
            (int) touch.scrollX, (int) touch.scrollY,
            (int) (Math.max(0, targetScrollX) - touch.scrollX),
            (int) (Math.max(0, Math.min(targetScrollY, maxScrollY)) - touch.scrollY),
            350);
            invalidate();
        }
    }

    private void ensureCursorVisible() {
        if (textManager.lines.isEmpty()
        || selection.cursorLine < 0
        || selection.cursorLine >= textManager.lines.size()
        || getHeight() <= 0 || getWidth() <= 0) return;

        float codeStartX = theme.gutterWidth + CODE_START_PAD;
        float cursorYTop = (selection.cursorLine * theme.lineHeight) + TOP_PADDING;
        float cursorYBottom = cursorYTop + theme.lineHeight;
        String lineStr = textManager.lines.get(selection.cursorLine).toString();
        int safeCol = Math.min(selection.cursorCol, lineStr.length());
        float cursorX = codeStartX + theme.textPaint.measureText(lineStr.substring(0, safeCol));
        float visibleTop = touch.scrollY + TOP_PADDING;
        float visibleBottom = touch.scrollY + getHeight() - (theme.lineHeight * 1.5f);
        float visibleLeft = touch.scrollX + codeStartX;
        float visibleRight = touch.scrollX + getWidth() - 50;
        boolean needsScroll = false;
        float targetScrollY = touch.scrollY, targetScrollX = touch.scrollX;

        if (cursorYBottom > visibleBottom) { targetScrollY = cursorYBottom - getHeight() + (theme.lineHeight * 2); needsScroll = true; }
        else if (cursorYTop < visibleTop) { targetScrollY = cursorYTop - TOP_PADDING; needsScroll = true; }
        if (cursorX > visibleRight) { targetScrollX = cursorX - getWidth() + 100; needsScroll = true; }
        else if (cursorX < visibleLeft) { targetScrollX = Math.max(0, cursorX - codeStartX); needsScroll = true; }

        if (needsScroll) {
            float maxScrollY = getMaxScrollY();
            if (!touch.scroller.isFinished()) touch.scroller.forceFinished(true);
            touch.scroller.startScroll(
            (int) touch.scrollX, (int) touch.scrollY,
            (int) (Math.max(0, targetScrollX) - touch.scrollX),
            (int) (Math.max(0, Math.min(targetScrollY, maxScrollY)) - touch.scrollY),
            350);
            invalidate();
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override public boolean onDown(MotionEvent e) { return true; }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            if (selection.draggingHandle != 0) return false;
            touch.scrollX += distanceX;
            touch.scrollY += distanceY;
            touch.clampScroll(getMaxScrollY());
            invalidate();
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            if (selection.draggingHandle != 0) return false;
            touch.scroller.fling((int) touch.scrollX, (int) touch.scrollY,
            (int) -velocityX, (int) -velocityY,
            0, 5000, 0, (int) Math.max(0, getMaxScrollY()));
            invalidate();
            return true;
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            if (selection.isSelecting || selection.isActionModeActive) {
                selection.isSelecting = false;
                selection.isActionModeActive = false;
                selection.draggingHandle = 0;
                if (selectionListener != null) selectionListener.onSelectionChanged(false);
            }
            requestFocus();
            calculateCursorPosition(e.getX(), e.getY());
            if (!isReadOnly) {
                InputMethodManager imm = (InputMethodManager) getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(EditorView.this, InputMethodManager.SHOW_IMPLICIT);
            }
            invalidate();
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            if (e.getY() <= -touch.scrollY + TOP_PADDING) return;

            calculateCursorPosition(e.getX(), e.getY());

            if (selection.isSelecting) {
                int line = selection.cursorLine;
                int col = selection.cursorCol;

                int sL = Math.min(selection.selStartLine, selection.selEndLine);
                int eL = Math.max(selection.selStartLine, selection.selEndLine);
                int sC, eC;
                if (selection.selStartLine < selection.selEndLine) {
                    sC = selection.selStartCol;
                    eC = selection.selEndCol;
                } else if (selection.selStartLine > selection.selEndLine) {
                    sC = selection.selEndCol;
                    eC = selection.selStartCol;
                } else {
                    sC = Math.min(selection.selStartCol, selection.selEndCol);
                    eC = Math.max(selection.selStartCol, selection.selEndCol);
                }

                boolean inside;
                if (line > sL && line < eL) inside = true;
                else if (line == sL && line == eL) inside = (col >= sC && col <= eC);
                else if (line == sL) inside = (col >= sC);
                else if (line == eL) inside = (col <= eC);
                else inside = false;

                if (inside) {
                    selection.isActionModeActive = true;
                    if (selectionListener != null) selectionListener.onSelectionChanged(true);
                    invalidate();
                    return;
                }
            }

            ErrorInfo err = null;
            String icon = "❌ ";
            if (ecjErrors.containsKey(selection.cursorLine)) err = ecjErrors.get(selection.cursorLine);
            else if (ecjWarnings.containsKey(selection.cursorLine)) { err = ecjWarnings.get(selection.cursorLine); icon = "⚠️ "; }
            if (err != null) showCustomToast(icon + err.msg);

            String line = textManager.lines.get(selection.cursorLine).toString();
            if (line.trim().isEmpty()) {
                selection.selStartLine = selection.cursorLine;
                selection.selStartCol = selection.cursorCol;
                selection.selEndLine = selection.cursorLine;
                selection.selEndCol = selection.cursorCol;
                selection.isSelecting = false;
                selection.isActionModeActive = true;
                selection.draggingHandle = 0;
                if (selectionListener != null) selectionListener.onSelectionChanged(true);
            } else {
                int start = Math.min(selection.cursorCol, line.length());
                int end = Math.min(selection.cursorCol, line.length());
                while (start > 0 && isWordCharacter(line.charAt(start - 1))) start--;
                while (end < line.length() && isWordCharacter(line.charAt(end))) end++;

                if (start < end && start < line.length() && isWordCharacter(line.charAt(start))) {
                    selection.selStartLine = selection.cursorLine;
                    selection.selStartCol = start;
                    selection.selEndLine = selection.cursorLine;
                    selection.selEndCol = end;
                    selection.isSelecting = true;
                    selection.isActionModeActive = true;
                    selection.draggingHandle = 2;
                    if (selectionListener != null) selectionListener.onSelectionChanged(true);
                } else {
                    selection.selStartLine = selection.cursorLine;
                    selection.selStartCol = selection.cursorCol;
                    selection.selEndLine = selection.cursorLine;
                    selection.selEndCol = selection.cursorCol;
                    selection.isSelecting = false;
                    selection.isActionModeActive = true;
                    selection.draggingHandle = 0;
                    if (selectionListener != null) selectionListener.onSelectionChanged(true);
                }
            }
            invalidate();
        }
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float focusX = detector.getFocusX(), focusY = detector.getFocusY();
            float oldSize = theme.currentTextSize;
            float oldGutterWidth = theme.gutterWidth;
            theme.currentTextSize = Math.max(theme.MIN_TEXT_SIZE,
            Math.min(theme.currentTextSize * detector.getScaleFactor(), theme.MAX_TEXT_SIZE));
            float scale = theme.currentTextSize / oldSize;
            if (Math.abs(scale - 1.0f) > 0.01f) {
                theme.updateFontMetrics(textManager.lines.size());
                touch.scrollX = (focusX + touch.scrollX - oldGutterWidth - CODE_START_PAD) * scale
                - focusX + theme.gutterWidth + CODE_START_PAD;
                touch.scrollY = (focusY + touch.scrollY - TOP_PADDING) * scale - focusY + TOP_PADDING;
                touch.clampScroll(getMaxScrollY());
                invalidate();
            }
            return true;
        }
    }

    private void calculateCursorPosition(float tapX, float tapY) {
        selection.cursorLine = Math.max(0, Math.min(textManager.lines.size() - 1,
        (int) ((tapY + touch.scrollY - TOP_PADDING) / theme.lineHeight)));
        String lineStr = textManager.lines.get(selection.cursorLine).toString();
        float targetX = tapX + touch.scrollX - theme.gutterWidth - CODE_START_PAD;
        int col = 0;
        float currentWidth = 0;
        while (col < lineStr.length()) {
            float cw = theme.textPaint.measureText(String.valueOf(lineStr.charAt(col)));
            if (currentWidth + (cw / 2) > targetX) break;
            currentWidth += cw;
            col++;
        }
        selection.cursorCol = col;
        invalidate();
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        if (isReadOnly) return null;
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION | EditorInfo.IME_FLAG_NO_FULLSCREEN;

        return new BaseInputConnection(this, true) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                saveCurrentStateForUndo(); // 🔥 टाइपिंग से पहले सेव
                textManager.saveHistory(false, selection.cursorLine, selection.cursorCol);
                if (selection.isSelecting) selection.deleteSelectedText(textManager);
                String input = text.toString();
                for (int i = 0; i < input.length(); i++) {
                    char c = input.charAt(i);
                    if (c == '\n') {
                        int indent = calculateAutoIndentSpaces(selection.cursorLine);
                        String remain = textManager.lines.get(selection.cursorLine).substring(selection.cursorCol);
                        textManager.lines.get(selection.cursorLine)
                        .delete(selection.cursorCol, textManager.lines.get(selection.cursorLine).length());
                        StringBuilder nextLine = new StringBuilder();
                        for (int s = 0; s < indent; s++) nextLine.append(' ');
                        nextLine.append(remain);
                        textManager.lines.add(selection.cursorLine + 1, nextLine);
                        selection.cursorLine++;
                        selection.cursorCol = indent;
                    } else {
                        textManager.lines.get(selection.cursorLine).insert(selection.cursorCol, c);
                        selection.cursorCol++;
                    }
                }
                theme.updateFontMetrics(textManager.lines.size());
                ensureCursorVisible();
                triggerTextModified();
                invalidate();
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    saveCurrentStateForUndo(); // 🔥 डिलीट/एंटर से पहले सेव
                    textManager.saveHistory(false, selection.cursorLine, selection.cursorCol);
                    if (event.getKeyCode() == KeyEvent.KEYCODE_DEL) {
                        if (selection.isSelecting) {
                            selection.deleteSelectedText(textManager);
                            triggerTextModified();
                            return true;
                        }
                        if (selection.cursorCol > 0) {
                            textManager.lines.get(selection.cursorLine).deleteCharAt(selection.cursorCol - 1);
                            selection.cursorCol--;
                        } else if (selection.cursorLine > 0) {
                            StringBuilder prev = textManager.lines.get(selection.cursorLine - 1);
                            selection.cursorCol = prev.length();
                            prev.append(textManager.lines.get(selection.cursorLine));
                            textManager.lines.remove(selection.cursorLine);
                            selection.cursorLine--;
                        }
                        ensureCursorVisible();
                        triggerTextModified();
                        invalidate();
                        return true;
                    } else if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                        if (selection.isSelecting) selection.deleteSelectedText(textManager);
                        int indent = calculateAutoIndentSpaces(selection.cursorLine);
                        String remain = textManager.lines.get(selection.cursorLine).substring(selection.cursorCol);
                        textManager.lines.get(selection.cursorLine)
                        .delete(selection.cursorCol, textManager.lines.get(selection.cursorLine).length());
                        StringBuilder nextLine = new StringBuilder();
                        for (int s = 0; s < indent; s++) nextLine.append(' ');
                        nextLine.append(remain);
                        textManager.lines.add(selection.cursorLine + 1, nextLine);
                        selection.cursorLine++;
                        selection.cursorCol = indent;
                        ensureCursorVisible();
                        triggerTextModified();
                        invalidate();
                        return true;
                    }
                }
                return super.sendKeyEvent(event);
            }
        };
    }
    public void toggleComment() {
        if (isReadOnly) return;

        String fName = currentFileName != null ? currentFileName.toLowerCase() : "";

        boolean isBlock = false;
        String linePrefix = "// ";
        String blockStart = "";
        String blockEnd = "";

        // XML / HTML → <!-- -->
        if (fName.endsWith(".xml") || fName.endsWith(".html") || fName.endsWith(".htm")) {
            isBlock = true;
            blockStart = "<!--";
            blockEnd   = "-->";
        }
        // CSS → /* */
        else if (fName.endsWith(".css")) {
            isBlock = true;
            blockStart = "/*";
            blockEnd   = "*/";
        }
        // Python / Shell / properties → #
        else if (fName.endsWith(".py") || fName.endsWith(".sh")
        || fName.endsWith(".properties") || fName.endsWith(".pro")) {
            linePrefix = "# ";
        }
        // Baaki sab (java, kt, js, json, gradle, c, cpp, etc.) → //
        else {
            linePrefix = "// ";
        }

        if (isBlock) {
            toggleBlockComment(blockStart, blockEnd);
        } else {
            toggleLineComment(linePrefix);
        }

        triggerTextModified();
        invalidate();
    }

    private void toggleLineComment(String prefix) {
        int startL, endL;
        if (selection.isSelecting) {
            startL = Math.min(selection.selStartLine, selection.selEndLine);
            endL   = Math.max(selection.selStartLine, selection.selEndLine);
        } else {
            startL = endL = selection.cursorLine;
        }
        if (startL < 0 || endL >= textManager.lines.size()) return;

        saveCurrentStateForUndo(); // 🔥 कमेंट होने से पहले सेव
        textManager.saveHistory(true, selection.cursorLine, selection.cursorCol);

        String marker = prefix.trim();
        boolean allCommented = true;
        for (int i = startL; i <= endL; i++) {
            String t = textManager.lines.get(i).toString().trim();
            if (t.isEmpty()) continue;
            if (!t.startsWith(marker)) { allCommented = false; break; }
        }

        for (int i = startL; i <= endL; i++) {
            StringBuilder sb = textManager.lines.get(i);
            if (sb.toString().trim().isEmpty()) continue;

            int indent = 0;
            while (indent < sb.length()
            && (sb.charAt(indent) == ' ' || sb.charAt(indent) == '\t')) {
                indent++;
            }

            if (allCommented) {
                if (indent + marker.length() <= sb.length()
                && sb.substring(indent, indent + marker.length()).equals(marker)) {
                    int end = indent + marker.length();
                    if (end < sb.length() && sb.charAt(end) == ' ') end++;
                    sb.delete(indent, end);
                }
            } else {
                sb.insert(indent, prefix);
            }
        }
    }

    private void toggleBlockComment(String startMarker, String endMarker) {
        int startL, endL;
        if (selection.isSelecting) {
            startL = Math.min(selection.selStartLine, selection.selEndLine);
            endL   = Math.max(selection.selStartLine, selection.selEndLine);
        } else {
            startL = endL = selection.cursorLine;
        }
        if (startL < 0 || endL >= textManager.lines.size()) return;

        saveCurrentStateForUndo(); // 🔥 ब्लॉक कमेंट से पहले सेव
        textManager.saveHistory(true, selection.cursorLine, selection.cursorCol);

        // Already commented?
        boolean isCommented;
        if (startL == endL) {
            String line = textManager.lines.get(startL).toString().trim();
            isCommented = line.startsWith(startMarker) && line.endsWith(endMarker);
        } else {
            String first = textManager.lines.get(startL).toString().trim();
            String last  = textManager.lines.get(endL).toString().trim();
            isCommented = first.startsWith(startMarker) && last.endsWith(endMarker);
        }

        if (isCommented) {
            // ─── UNCOMMENT ───
            if (startL == endL) {
                StringBuilder sb = textManager.lines.get(startL);
                String content = sb.toString();
                int sIdx = content.indexOf(startMarker);
                int eIdx = content.lastIndexOf(endMarker);
                if (sIdx < 0 || eIdx < 0 || eIdx <= sIdx) return;

                StringBuilder work = new StringBuilder(content);
                // Remove end marker
                work.delete(eIdx, eIdx + endMarker.length());
                // Remove space before end marker
                if (eIdx > 0 && eIdx - 1 < work.length() && work.charAt(eIdx - 1) == ' ') {
                    work.deleteCharAt(eIdx - 1);
                }
                // Remove start marker
                work.delete(sIdx, sIdx + startMarker.length());
                // Remove space after start marker
                if (sIdx < work.length() && work.charAt(sIdx) == ' ') {
                    work.deleteCharAt(sIdx);
                }
                sb.setLength(0);
                sb.append(work);
            } else {
                // First line — remove start marker
                StringBuilder firstSb = textManager.lines.get(startL);
                String firstStr = firstSb.toString();
                int sIdx = firstStr.indexOf(startMarker);
                if (sIdx >= 0) {
                    firstSb.delete(sIdx, sIdx + startMarker.length());
                    if (sIdx < firstSb.length() && firstSb.charAt(sIdx) == ' ') {
                        firstSb.deleteCharAt(sIdx);
                    }
                }
                // Last line — remove end marker
                StringBuilder lastSb = textManager.lines.get(endL);
                String lastStr = lastSb.toString();
                int eIdx = lastStr.lastIndexOf(endMarker);
                if (eIdx >= 0) {
                    lastSb.delete(eIdx, eIdx + endMarker.length());
                    if (eIdx > 0 && eIdx - 1 < lastSb.length() && lastSb.charAt(eIdx - 1) == ' ') {
                        lastSb.deleteCharAt(eIdx - 1);
                    }
                }
            }
        } else {
            // ─── COMMENT ───
            if (startL == endL) {
                // Single line — wrap inline
                StringBuilder sb = textManager.lines.get(startL);
                String content = sb.toString();
                int indent = 0;
                while (indent < content.length()
                && (content.charAt(indent) == ' ' || content.charAt(indent) == '\t')) {
                    indent++;
                }
                String beforeIndent = content.substring(0, indent);
                String body = content.substring(indent);
                sb.setLength(0);
                sb.append(beforeIndent)
                .append(startMarker).append(' ')
                .append(body)
                .append(' ').append(endMarker);
            } else {
                // Multi-line — wrap first & last
                StringBuilder firstSb = textManager.lines.get(startL);
                int indent = 0;
                while (indent < firstSb.length()
                && (firstSb.charAt(indent) == ' ' || firstSb.charAt(indent) == '\t')) {
                    indent++;
                }
                firstSb.insert(indent, startMarker + " ");
                textManager.lines.get(endL).append(" ").append(endMarker);
            }
        }
    }
    public void saveHistory(boolean force, int cursorLine, int cursorCol) {
        // 🔥 Dummy method to prevent compilation errors.
        // Asli undo/redo ab naye isolated FileHistoryManager ke paas hai!
    }

}