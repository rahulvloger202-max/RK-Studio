package com.primx.ui.editor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class EditorTextManager {
    public List<StringBuilder> lines = new ArrayList<>();

    public boolean isSearchHighlight = false;
    public int searchLine = -1, searchStartCol = -1, searchEndCol = -1;
    public String lastQuery = "";

    private HashMap<String, List<EditorState>> fileUndoStacks = new HashMap<>();
    private HashMap<String, List<EditorState>> fileRedoStacks = new HashMap<>();
    private long lastSaveTime = 0;
    public boolean isHistoryAction = false;
    public String currentFilePath = "untitled";

    public interface OnHistoryChangeListener { void onHistoryChanged(boolean canUndo, boolean canRedo); }
    public OnHistoryChangeListener historyChangeListener;

    public static class EditorState {
        String text; int line, col;
        EditorState(String t, int cl, int cc) { text = t; line = cl; col = cc; }
    }

    public EditorTextManager() {
        lines.add(new StringBuilder());
    }

    public String getText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(lines.get(i));
            if (i < lines.size() - 1) sb.append('\n');
        }
        return sb.toString();
    }

    public void setText(String text) {
        lines.clear();
        if (text == null) text = "";
        String[] parts = text.split("\n", -1);
        for (String p : parts) lines.add(new StringBuilder(p));
        if (lines.isEmpty()) lines.add(new StringBuilder());
    }

    public void clearSearch() {
        isSearchHighlight = false; searchLine = -1; searchStartCol = -1; searchEndCol = -1;
    }

    public void findText(String query, EditorSelection selection) {
        if (query == null || query.trim().isEmpty()) {
            clearSearch();
            lastQuery = "";
            return;
        }

        query = query.trim();
        lastQuery = query;
        boolean found = false;
        String qLower = query.toLowerCase();

        for (int i = 0; i < lines.size(); i++) {
            String lineStr = lines.get(i).toString().toLowerCase();
            int cIndex = lineStr.indexOf(qLower);
            if (cIndex != -1) {
                searchLine = i; searchStartCol = cIndex; searchEndCol = cIndex + query.length();
                found = true; break;
            }
        }

        if (found) {
            isSearchHighlight = true;
            selection.selStartLine = searchLine;
            selection.selStartCol = searchStartCol;
            selection.selEndLine = searchLine;
            selection.selEndCol = searchEndCol;
            selection.isSelecting = true;
            selection.isActionModeActive = true;
            selection.cursorLine = searchLine;
            selection.cursorCol = searchEndCol;
        } else {
            isSearchHighlight = false;
            searchLine = -1; searchStartCol = -1; searchEndCol = -1;
            selection.isSelecting = false;
            selection.isActionModeActive = false;
        }
    }

    public void findNext(EditorSelection selection) {
        if (lastQuery == null || lastQuery.trim().isEmpty()) return;
        boolean found = false;
        String qLower = lastQuery.toLowerCase();
        int startL = searchLine >= 0 ? searchLine : 0;
        int startC = searchEndCol >= 0 ? searchEndCol : 0;

        for (int i = startL; i < lines.size(); i++) {
            String lineStr = lines.get(i).toString().toLowerCase();
            int safeStartC = (i == startL) ? Math.min(startC, lineStr.length()) : 0;
            int cIndex = lineStr.indexOf(qLower, safeStartC);
            if (cIndex != -1) {
                searchLine = i; searchStartCol = cIndex; searchEndCol = cIndex + lastQuery.length();
                found = true; break;
            }
        }

        if (!found && (startL > 0 || startC > 0)) {
            for (int i = 0; i <= startL; i++) {
                String lineStr = lines.get(i).toString().toLowerCase();
                int safeEndC = (i == startL) ? Math.min(startC, lineStr.length()) : lineStr.length();
                int cIndex = lineStr.substring(0, safeEndC).indexOf(qLower);
                if (cIndex != -1) {
                    searchLine = i; searchStartCol = cIndex; searchEndCol = cIndex + lastQuery.length();
                    found = true; break;
                }
            }
        }

        if (found) {
            isSearchHighlight = true;
            selection.selStartLine = searchLine;
            selection.selStartCol = searchStartCol;
            selection.selEndLine = searchLine;
            selection.selEndCol = searchEndCol;
            selection.isSelecting = true;
            selection.isActionModeActive = true;
            selection.cursorLine = searchLine;
            selection.cursorCol = searchEndCol;
        }
    }

    private List<EditorState> getUndoStack() {
        if (!fileUndoStacks.containsKey(currentFilePath)) fileUndoStacks.put(currentFilePath, new ArrayList<EditorState>());
        return fileUndoStacks.get(currentFilePath);
    }

    private List<EditorState> getRedoStack() {
        if (!fileRedoStacks.containsKey(currentFilePath)) fileRedoStacks.put(currentFilePath, new ArrayList<EditorState>());
        return fileRedoStacks.get(currentFilePath);
    }

    public void updateHistoryButtons() {
        if (historyChangeListener != null) {
            List<EditorState> uStack = getUndoStack(); List<EditorState> rStack = getRedoStack();
            boolean canUndo = uStack.size() > 1 || (uStack.size() == 1 && !uStack.get(0).text.equals(getText()));
            historyChangeListener.onHistoryChanged(canUndo, rStack.size() > 0);
        }
    }

    public void undo(EditorSelection selection) {
        List<EditorState> undoStack = getUndoStack(); List<EditorState> redoStack = getRedoStack();
        if (undoStack.size() > 1 || (undoStack.size() == 1 && !undoStack.get(0).text.equals(getText()))) {
            isHistoryAction = true; String currentText = getText();
            EditorState top = undoStack.get(undoStack.size() - 1);
            if (top.text.equals(currentText) && undoStack.size() > 1) undoStack.remove(undoStack.size() - 1);
            EditorState targetState = undoStack.get(undoStack.size() - 1);
            redoStack.add(new EditorState(currentText, selection.cursorLine, selection.cursorCol));
            restoreState(targetState, selection);
            isHistoryAction = false; updateHistoryButtons();
        }
    }

    public void redo(EditorSelection selection) {
        List<EditorState> undoStack = getUndoStack(); List<EditorState> redoStack = getRedoStack();
        if (!redoStack.isEmpty()) {
            isHistoryAction = true; String currentText = getText();
            EditorState top = undoStack.isEmpty() ? null : undoStack.get(undoStack.size() - 1);
            if (top == null || !top.text.equals(currentText)) undoStack.add(new EditorState(currentText, selection.cursorLine, selection.cursorCol));
            EditorState state = redoStack.remove(redoStack.size() - 1);
            undoStack.add(state);
            restoreState(state, selection);
            isHistoryAction = false; updateHistoryButtons();
        }
    }

    private void restoreState(EditorState state, EditorSelection selection) {
        setText(state.text);
        selection.cursorLine = Math.max(0, Math.min(state.line, lines.size() - 1));
        selection.cursorCol = Math.max(0, Math.min(state.col, lines.get(selection.cursorLine).length()));
        selection.isSelecting = false; selection.isActionModeActive = false;
    }

    // 🔥 FIX: Ye raha dummy method jiski wajah se EditorView error de raha tha!
    public void saveHistory(boolean force, int cursorLine, int cursorCol) {
        // Original undo/redo has been migrated to FileHistoryManager
        // This keeps backward compatibility for existing method calls in EditorView.
    }
}
