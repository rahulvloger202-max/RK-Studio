package com.primx.ui.editor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

public class FileHistoryManager {

    public static class HistoryState {
        public final String text;
        public final int cursorLine;
        public final int cursorCol;
        public final long timestamp;

        public HistoryState(String text, int line, int col) {
            this.text = text;
            this.cursorLine = line;
            this.cursorCol = col;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private static final int MAX_HISTORY_PER_FILE = 500;

    private final Map<String, Deque<HistoryState>> undoMap = new HashMap<>();
    private final Map<String, Deque<HistoryState>> redoMap = new HashMap<>();

    private String normalizeKey(String filePath) {
        return (filePath == null || filePath.trim().isEmpty()) ? "__untitled__" : filePath.trim();
    }

    public void saveState(String filePath, String text, int line, int col) {
        if (text == null) return;
        String key = normalizeKey(filePath);
        Deque<HistoryState> stack = undoMap.get(key);
        if (stack == null) {
            stack = new ArrayDeque<>();
            undoMap.put(key, stack);
        }
        HistoryState last = stack.peekLast();
        if (last != null && last.text.equals(text)) {
            return;
        }
        stack.addLast(new HistoryState(text, line, col));
        if (stack.size() > MAX_HISTORY_PER_FILE) {
            stack.removeFirst();
        }
        Deque<HistoryState> redo = redoMap.get(key);
        if (redo != null) {
            redo.clear();
        }
    }

    public HistoryState undo(String filePath, String currentText, int curLine, int curCol) {
        String key = normalizeKey(filePath);
        Deque<HistoryState> stack = undoMap.get(key);
        if (stack == null || stack.isEmpty()) return null;

        // 🔥 FIX: Agar stack ke top par currentText hi pada hai toh usey hata do (1-Tap Undo Fix)
        while (!stack.isEmpty() && stack.peekLast().text.equals(currentText)) {
            stack.removeLast();
        }
        if (stack.isEmpty()) return null;

        Deque<HistoryState> redo = redoMap.get(key);
        if (redo == null) {
            redo = new ArrayDeque<>();
            redoMap.put(key, redo);
        }
        if (redo.isEmpty() || !redo.peekLast().text.equals(currentText)) {
            redo.addLast(new HistoryState(currentText, curLine, curCol));
        }

        return stack.removeLast();
    }

    public HistoryState redo(String filePath, String currentText, int curLine, int curCol) {
        String key = normalizeKey(filePath);
        Deque<HistoryState> redo = redoMap.get(key);
        if (redo == null || redo.isEmpty()) return null;

        // 🔥 FIX: Agar redo ke top par currentText hi pada hai toh usey hata do (1-Tap Redo Fix)
        while (!redo.isEmpty() && redo.peekLast().text.equals(currentText)) {
            redo.removeLast();
        }
        if (redo.isEmpty()) return null;

        Deque<HistoryState> stack = undoMap.get(key);
        if (stack == null) {
            stack = new ArrayDeque<>();
            undoMap.put(key, stack);
        }
        if (stack.isEmpty() || !stack.peekLast().text.equals(currentText)) {
            stack.addLast(new HistoryState(currentText, curLine, curCol));
        }

        return redo.removeLast();
    }

    public boolean canUndo(String filePath) {
        String key = normalizeKey(filePath);
        Deque<HistoryState> s = undoMap.get(key);
        return s != null && !s.isEmpty();
    }

    public boolean canRedo(String filePath) {
        String key = normalizeKey(filePath);
        Deque<HistoryState> s = redoMap.get(key);
        return s != null && !s.isEmpty();
    }

    public void clear(String filePath) {
        String key = normalizeKey(filePath);
        undoMap.remove(key);
        redoMap.remove(key);
    }
}
