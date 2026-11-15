package com.primx.ui.editor;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

public class EditorSelection {
    public int cursorLine = 0;
    public int cursorCol = 0;
    public boolean isSelecting = false;
    public boolean isActionModeActive = false;
    public int selStartLine = 0, selStartCol = 0, selEndLine = 0, selEndCol = 0;
    public int draggingHandle = 0; // 0=none, 1=start, 2=end
    private Path handlePath = new Path();

    public void selectAll(EditorTextManager textManager) {
        if (textManager.lines.isEmpty()) return;
        selStartLine = 0; 
        selStartCol = 0; 
        selEndLine = textManager.lines.size() - 1; 
        selEndCol = textManager.lines.get(textManager.lines.size() - 1).length();
        isSelecting = true; 
        isActionModeActive = true; 
    }

    public String getSelectedText(EditorTextManager textManager) {
        if (!isSelecting) return "";
        StringBuilder sb = new StringBuilder();
        if (selStartLine == selEndLine) { 
            sb.append(textManager.lines.get(selStartLine).substring(selStartCol, selEndCol)); 
        } else {
            sb.append(textManager.lines.get(selStartLine).substring(selStartCol)).append("\n");
            for (int i = selStartLine + 1; i < selEndLine; i++) sb.append(textManager.lines.get(i)).append("\n");
            sb.append(textManager.lines.get(selEndLine).substring(0, selEndCol));
        }
        return sb.toString();
    }

    public void deleteSelectedText(EditorTextManager textManager) {
        if (!isSelecting) return;
        if (selStartLine == selEndLine) { 
            textManager.lines.get(selStartLine).delete(selStartCol, selEndCol); 
        } else {
            String remain = textManager.lines.get(selEndLine).substring(selEndCol); 
            textManager.lines.get(selStartLine).delete(selStartCol, textManager.lines.get(selStartLine).length()); 
            textManager.lines.get(selStartLine).append(remain);
            for (int i = selEndLine; i > selStartLine; i--) textManager.lines.remove(i);
        }
        cursorLine = Math.max(0, Math.min(selStartLine, textManager.lines.size() - 1)); 
        cursorCol = Math.max(0, Math.min(selStartCol, textManager.lines.get(cursorLine).length()));
        isSelecting = false; 
        isActionModeActive = false; 
    }

    public void copyToClipboard(Context context, EditorTextManager textManager) {
        String selected = getSelectedText(textManager);
        if (!selected.isEmpty()) {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("PrimX Code", selected));
            isSelecting = false; isActionModeActive = false;
        }
    }

    public void cutToClipboard(Context context, EditorTextManager textManager) {
        String selected = getSelectedText(textManager);
        if (!selected.isEmpty()) {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("PrimX Code", selected));
            deleteSelectedText(textManager); 
        }
    }

    public void pasteFromClipboard(Context context, EditorTextManager textManager) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
            CharSequence textToPaste = clipboard.getPrimaryClip().getItemAt(0).getText();
            if (textToPaste != null) {
                if (isSelecting) deleteSelectedText(textManager);
                String input = textToPaste.toString();
                for (int i = 0; i < input.length(); i++) {
                    char c = input.charAt(i);
                    if (c == '\n') {
                        String remain = textManager.lines.get(cursorLine).substring(cursorCol);
                        textManager.lines.get(cursorLine).delete(cursorCol, textManager.lines.get(cursorLine).length());
                        textManager.lines.add(cursorLine + 1, new StringBuilder(remain));
                        cursorLine++; cursorCol = 0;
                    } else { 
                        textManager.lines.get(cursorLine).insert(cursorCol, c); 
                        cursorCol++; 
                    }
                }
                isSelecting = false; isActionModeActive = false;
            }
        }
    }

    public void drawSelectionHandle(Canvas canvas, float tipX, float tipY, Paint paint, float currentTextSize) {
        handlePath.reset(); 
        float radius = Math.max(22f, currentTextSize * 0.6f); 
        float cy = tipY + (radius * 1.3f);
        handlePath.moveTo(tipX, tipY); 
        handlePath.cubicTo(tipX - (radius * 0.4f), tipY + (radius * 0.4f), tipX - radius, cy - (radius * 0.6f), tipX - radius, cy);
        handlePath.arcTo(new RectF(tipX - radius, cy - radius, tipX + radius, cy + radius), 180, -180, false);
        handlePath.cubicTo(tipX + radius, cy - (radius * 0.6f), tipX + (radius * 0.4f), tipY + (radius * 0.4f), tipX, tipY);
        handlePath.close(); 
        canvas.drawPath(handlePath, paint);
    }
}
