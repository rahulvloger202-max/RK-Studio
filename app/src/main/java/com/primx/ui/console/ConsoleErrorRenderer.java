package com.primx.ui.console;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.primx.R;
import com.primx.ui.editor.EditorView;

public class ConsoleErrorRenderer {

    private Console console;

    private static final int TAB_ERROR_COLOR   = 0xFFF44336;
    private static final int TAB_WARNING_COLOR = 0xFFFFC107;
    private static final int TAB_LOG_COLOR     = 0xFF8BC34A;

    public static class ErrorRange {
        public int startOffset;
        public int endOffset;
        public Console.ErrorItem item;
    }

    public final List<ErrorRange> renderedRanges = new ArrayList<>();

    public ConsoleErrorRenderer(Console console) {
        this.console = console;
    }

    public Console.ErrorItem getItemAtPosition(TextView tv, float x, float y) {
        if (tv == null || tv.getLayout() == null || renderedRanges.isEmpty()) return null;
        try {
            Layout layout = tv.getLayout();
            int adjX = (int) x - tv.getTotalPaddingLeft() + tv.getScrollX();
            int adjY = (int) y - tv.getTotalPaddingTop() + tv.getScrollY();
            int line = layout.getLineForVertical(Math.max(0, adjY));
            int offset = layout.getOffsetForHorizontal(line, Math.max(0, adjX));
            for (ErrorRange r : renderedRanges) {
                if (offset >= r.startOffset && offset <= r.endOffset) {
                    return r.item;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public void openBuildLogTabOnRun() {
        try {
            console.currentTab = 2;
            refreshErrorUI();
        } catch (Exception e) {}
    }
    private SpannableStringBuilder getEmptyStateSpannable(boolean isLight, boolean isWarningTab) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int iconPos = sb.length();
        sb.append("  ");
        try {
            Drawable icon = getGeneratedIcon(console.getContext(), false, isWarningTab);
            if (icon != null) {
                icon.setBounds(0, 0, console.dpToPx(24), console.dpToPx(24));
                sb.setSpan(new ImageSpan(icon, ImageSpan.ALIGN_BOTTOM),
                iconPos, iconPos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        } catch (Exception ignored) {}
        
        sb.append(isWarningTab ? "  No Warnings Found" : "  No Errors Found");
        return sb;
    }
    public void refreshErrorUI() {
        renderedRanges.clear();
        console.pruneForeignProjectErrors();

        boolean isLight = com.primx.utils.Setting.isLight(console.getContext());
        int fileHeaderColor  = isLight ? Color.parseColor("#1B5E20") : Color.parseColor("#69F0AE");
        int errTitleColor    = isLight ? Color.parseColor("#B71C1C") : Color.parseColor("#FF5252");
        int warnTitleColor   = isLight ? Color.parseColor("#E65100") : Color.parseColor("#FFC107");
        int locLinkColor     = isLight ? Color.parseColor("#0D47A1") : Color.parseColor("#82B1FF");

        int successColor = console.getThemeColor(console.getContext(), R.attr.ide_log_success_color,
        isLight ? Color.parseColor("#2E7D32") : Color.parseColor("#4CAF50"));
        int warningColor = console.getThemeColor(console.getContext(), R.attr.ide_log_warning_color,
        isLight ? Color.parseColor("#F57F17") : Color.parseColor("#FFC107"));

        if (console.tvOutput != null) {
            console.tvOutput.setGravity(android.view.Gravity.START | android.view.Gravity.TOP);
        }

        console.totalErrorCount = 0;
        int totalWarningCount = 0;
        for (List<Console.ErrorItem> lst : console.errorMap.values()) {
            for (Console.ErrorItem e : lst) {
                if (e.isWarning) totalWarningCount++;
                else console.totalErrorCount++;
            }
        }
        console.updateStatusHeader();

        if (console.tabError != null) {
            if (console.totalErrorCount > 0) {
                console.tabError.setText("Errors (" + console.totalErrorCount + ")");
                console.tabError.setTextSize(12f);
            } else {
                console.tabError.setText("Errors");
                console.tabError.setTextSize(14f);
            }
            console.tabError.setSingleLine(true);
            console.tabError.setTextColor(TAB_ERROR_COLOR);
        }

        if (console.tabWarning != null) {
            if (totalWarningCount > 0) {
                console.tabWarning.setText("Warnings (" + totalWarningCount + ")");
                console.tabWarning.setTextSize(12f);
            } else {
                console.tabWarning.setText("Warnings");
                console.tabWarning.setTextSize(14f);
            }
            console.tabWarning.setSingleLine(true);
            console.tabWarning.setTextColor(TAB_WARNING_COLOR);
        }
        if (console.tabLog != null) {
            console.tabLog.setText("Build Log");
            console.tabLog.setTextColor(TAB_LOG_COLOR);
        }
        if (console.tabLogcat != null) {
            console.tabLogcat.setText("Logcat");
            console.tabLogcat.setTextColor(0xFF9C27B0);
        }

        if (console.currentTab == 3) {
            if (console.scrollView != null) console.scrollView.setVisibility(View.VISIBLE);
            if (console.successLayout != null) console.successLayout.setVisibility(View.GONE);
            if (console.tvOutput != null) {
                console.tvOutput.setVisibility(View.VISIBLE);
                int bgColor = console.getThemeColor(console.getContext(), R.attr.ide_bg_color,
                isLight ? Color.WHITE : Color.parseColor("#181818"));
                console.tvOutput.setBackgroundColor(bgColor);
                if (console.logcatLogs == null || console.logcatLogs.length() == 0) {
                    console.tvOutput.setGravity(android.view.Gravity.CENTER);
                    console.tvOutput.setText("Waiting for device logs...\n(App crashes & Runtime errors will appear here)");
                    int textColor = console.getThemeColor(console.getContext(), R.attr.ide_text_secondary,
                    isLight ? Color.GRAY : Color.LTGRAY);
                    console.tvOutput.setTextColor(textColor);
                } else {
                    String displayLogs = console.logcatLogs.toString();
                    if (displayLogs.length() > 5000) displayLogs = displayLogs.substring(displayLogs.length() - 5000);
                    SpannableStringBuilder sb = new SpannableStringBuilder();
                    for (String l : displayLogs.split("\n")) sb.append(colorizeLogcatLine(l, isLight)).append("\n");
                    console.tvOutput.setText(sb);
                }
            }
            return;
        }

        if (console.currentTab == 2) {
            if (console.scrollView != null) console.scrollView.setVisibility(View.VISIBLE);
            if (console.successLayout != null) console.successLayout.setVisibility(View.GONE);
            if (console.tvOutput != null) {
                console.tvOutput.setVisibility(View.VISIBLE);
                int bgColor = console.getThemeColor(console.getContext(), R.attr.ide_bg_color,
                isLight ? Color.WHITE : Color.parseColor("#181818"));
                console.tvOutput.setBackgroundColor(bgColor);
                if (console.rawLogs.length() == 0) {
                    console.tvOutput.setGravity(android.view.Gravity.CENTER);
                    console.tvOutput.setText("🚀 Engine is Ready!\n\nPress the Run button to compile your project.\n(All APK generation steps will appear here)");
                    int textColor = console.getThemeColor(console.getContext(), R.attr.ide_text_secondary,
                    isLight ? Color.GRAY : Color.LTGRAY);
                    console.tvOutput.setTextColor(textColor);
                } else {
                    console.tvOutput.setText(buildColorizedLogs(console.rawLogs.toString(), isLight));
                }
            }
            return;
        }

        if (console.totalErrorCount == 0 && totalWarningCount == 0) {
            if (console.successLayout != null) console.successLayout.setVisibility(View.GONE);
            if (console.scrollView != null) console.scrollView.setVisibility(View.VISIBLE);
            if (console.tvOutput != null) {
                console.tvOutput.setVisibility(View.VISIBLE);
                console.tvOutput.setGravity(android.view.Gravity.CENTER); 
                int bgColor = console.getThemeColor(console.getContext(), R.attr.ide_bg_color,
                isLight ? Color.WHITE : Color.parseColor("#181818"));
                console.tvOutput.setBackgroundColor(bgColor);
                
                console.tvOutput.setText(getEmptyStateSpannable(isLight, console.currentTab == 1));
                console.tvOutput.setTextColor(successColor); 
            }
            saveErrorsToStorage();
            return;
        }

        if (console.successLayout != null) console.successLayout.setVisibility(View.GONE);
        if (console.scrollView != null) console.scrollView.setVisibility(View.VISIBLE);
        if (console.tvOutput != null) {
            console.tvOutput.setVisibility(View.VISIBLE);
            int bgColor = console.getThemeColor(console.getContext(), R.attr.ide_bg_color,
            isLight ? Color.WHITE : Color.parseColor("#181818"));
            console.tvOutput.setBackgroundColor(bgColor);
            int textColor = console.getThemeColor(console.getContext(), R.attr.ide_text_primary,
            isLight ? Color.BLACK : Color.WHITE);
            console.tvOutput.setTextColor(textColor);
        }

        SpannableStringBuilder builder = new SpannableStringBuilder();
        boolean hasDataInCurrentTab = false;

        for (String filePath : console.errorMap.keySet()) {
            List<Console.ErrorItem> items = console.errorMap.get(filePath);
            if (items == null || items.isEmpty()) continue;

            File f = new File(filePath);
            String fileName = f.getName();
            if (fileName == null || fileName.isEmpty()) fileName = "Unsaved File";

            List<Console.ErrorItem> realErrors = new ArrayList<>();
            List<Console.ErrorItem> warnings = new ArrayList<>();
            for (Console.ErrorItem e : items) {
                if (e.isWarning) {
                    if (console.currentTab == 1) warnings.add(e);
                } else {
                    if (console.currentTab == 0) realErrors.add(e);
                }
            }
            if (realErrors.isEmpty() && warnings.isEmpty()) continue;
            hasDataInCurrentTab = true;

            appendFileHeader(builder, filePath, fileName, fileHeaderColor, isLight);

            for (Console.ErrorItem err : realErrors) {
                appendItem(builder, err, fileName, true, isLight, errTitleColor, locLinkColor);
            }
            for (Console.ErrorItem warn : warnings) {
                appendItem(builder, warn, fileName, false, isLight, warnTitleColor, locLinkColor);
            }
        }

        if (!hasDataInCurrentTab) {
            if (console.tvOutput != null) {
                console.tvOutput.setGravity(android.view.Gravity.CENTER);
                console.tvOutput.setText(getEmptyStateSpannable(isLight, console.currentTab == 1));
                console.tvOutput.setTextColor(successColor);
            }
        } else {
            if (console.tvOutput != null) console.tvOutput.setText(builder);
        }
        saveErrorsToStorage();
    }
    private void appendFileHeader(SpannableStringBuilder builder, String filePath, String fileName,
    int fileHeaderColor, boolean isLight) {
        int iconPos = builder.length();
        builder.append("  ");
        try {
            Drawable d = EditorView.loadFileIconDrawable(console.getContext(), fileName);
            if (d != null) {
                d.setBounds(0, 0, console.dpToPx(24), console.dpToPx(24));
                builder.setSpan(new ImageSpan(d, ImageSpan.ALIGN_BOTTOM),
                iconPos, iconPos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        } catch (Exception ignored) {}

        int nameStart = builder.length();
        builder.append(" ").append(fileName.isEmpty() ? "Unknown File" : fileName).append("\n");
        builder.setSpan(new ForegroundColorSpan(fileHeaderColor), nameStart, builder.length(),
        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), nameStart, builder.length(),
        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new AbsoluteSizeSpan(18, true), nameStart, builder.length(),
        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        int pathStart = builder.length();
        builder.append("   ").append(filePath).append("\n\n");
        int pathColor = console.getThemeColor(console.getContext(), R.attr.ide_text_secondary,
        isLight ? Color.parseColor("#757575") : Color.parseColor("#9E9E9E"));
        builder.setSpan(new ForegroundColorSpan(pathColor), pathStart, builder.length(),
        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new AbsoluteSizeSpan(11, true), pathStart, builder.length(),
        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), pathStart, builder.length(),
        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void appendItem(SpannableStringBuilder builder, Console.ErrorItem item, String fileName,
    boolean isError, boolean isLight, int titleColor, int locLinkColor) {
        int itemStart = builder.length();

        int iconPos = builder.length();
        builder.append("  ");
        try {
            Drawable icon = getGeneratedIcon(console.getContext(), isError, !isError);
            if (icon != null) {
                icon.setBounds(0, 0, console.dpToPx(18), console.dpToPx(18));
                builder.setSpan(new ImageSpan(icon, ImageSpan.ALIGN_BOTTOM),
                iconPos, iconPos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        } catch (Exception ignored) {}

        int msgStart = builder.length();
        String fullMsg = item.message != null ? item.message : "Unknown Error";
        builder.append(" ").append(fullMsg).append("\n");
        builder.setSpan(new ForegroundColorSpan(titleColor), msgStart, builder.length(),
        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), msgStart, builder.length(),
        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new AbsoluteSizeSpan(15, true), msgStart, builder.length(),
        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        int locStart = builder.length();
        builder.append("      ↳ ").append(fileName);
        if (item.line > 0) {
            builder.append("  •  Line ").append(String.valueOf(item.line));
            if (item.colStart >= 0) {
                builder.append(", Col ").append(String.valueOf(item.colStart + 1));
            }
        } else {
            builder.append("  •  (General)");
        }
        builder.append("\n");
        builder.setSpan(new ForegroundColorSpan(locLinkColor), locStart, builder.length(),
        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), locStart, builder.length(),
        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new AbsoluteSizeSpan(13, true), locStart, builder.length(),
        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        int divStart = builder.length();
        builder.append("───────────────────────────────────────────\n\n");
        int divColor = console.getThemeColor(console.getContext(), R.attr.ide_border_color,
        isLight ? Color.parseColor("#E0E0E0") : Color.parseColor("#333333"));
        builder.setSpan(new ForegroundColorSpan(divColor), divStart, builder.length(),
        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        ErrorRange range = new ErrorRange();
        range.startOffset = itemStart;
        range.endOffset = builder.length();
        range.item = item;
        renderedRanges.add(range);
    }

    public CharSequence colorizeLogLine(String line, boolean isLight) {
        if (line == null) return "";
        if (line.isEmpty()) return line;

        int infoColor = console.getThemeColor(console.getContext(), R.attr.ide_log_info_color, isLight ? Color.parseColor("#0288D1") : Color.parseColor("#29B6F6"));
        int successColor = console.getThemeColor(console.getContext(), R.attr.ide_log_success_color, isLight ? Color.parseColor("#388E3C") : Color.parseColor("#66BB6A"));
        int warningColor = console.getThemeColor(console.getContext(), R.attr.ide_log_warning_color, isLight ? Color.parseColor("#F57F17") : Color.parseColor("#FFCA28"));
        int errorColor = console.getThemeColor(console.getContext(), R.attr.ide_log_error_color, isLight ? Color.parseColor("#D32F2F") : Color.parseColor("#EF5350"));
        
        int stepColor = isLight ? Color.parseColor("#7B1FA2") : Color.parseColor("#CE93D8");
        int defaultTextColor = console.getThemeColor(console.getContext(), R.attr.ide_text_primary, isLight ? Color.BLACK : Color.WHITE);

        String cleanLine = line.replace("✅", "").replace("❌", "").replace("⚠", "").replace("⚠️", "").replace("✓", "").replace("$", "").trim();
        String upper = cleanLine.toUpperCase();

        SpannableStringBuilder sb = new SpannableStringBuilder();

        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\[[^\\]]+\\])(.*)$").matcher(cleanLine);

        if (m.matches()) {
            String tag = m.group(1); 
            String msg = m.group(2); 

            int tagColor = infoColor;
            if (tag.toUpperCase().contains("SUCCESS") || tag.toUpperCase().contains("DONE")) tagColor = successColor;
            else if (tag.toUpperCase().contains("ERROR") || tag.toUpperCase().contains("FAIL")) tagColor = errorColor;
            else if (tag.toUpperCase().contains("WARN")) tagColor = warningColor;
            else if (tag.matches(".*\\[\\s*\\d+.*")) tagColor = stepColor;

            int tagStart = sb.length();
            sb.append(tag);
            sb.setSpan(new ForegroundColorSpan(tagColor), tagStart, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), tagStart, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

            int msgStart = sb.length();
            sb.append(msg);
            sb.setSpan(new ForegroundColorSpan(defaultTextColor), msgStart, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            
            if (upper.contains("FAIL") || upper.contains("ERROR")) {
                sb.setSpan(new ForegroundColorSpan(errorColor), msgStart, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        } else {
            int color = defaultTextColor;
            boolean bold = false;
            if (upper.contains("BUILD COMPLETE!") || upper.contains("BUILD FINISHED")) { color = successColor; bold = true; }
            else if (upper.contains("BUILD FAILED") || upper.contains("EXCEPTION")) { color = errorColor; bold = true; }
            else if (upper.contains("WARN")) color = warningColor;

            sb.append(cleanLine);
            sb.setSpan(new ForegroundColorSpan(color), 0, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (bold) {
                sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        boolean isProcessComplete = upper.contains("BUILD COMPLETE!") 
                                 || upper.contains("CACHE ALREADY EMPTY") 
                                 || upper.contains("ITEMS, FREED") 
                                 || upper.endsWith("DONE!");

        if (isProcessComplete) {
            int dollarStart = sb.length();
            sb.append("  $");
            int dollarColor = isLight ? Color.parseColor("#E65100") : Color.parseColor("#FF9800");
            sb.setSpan(new ForegroundColorSpan(dollarColor), dollarStart, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), dollarStart, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new AbsoluteSizeSpan(16, true), dollarStart, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        return sb;
    }

    private CharSequence buildColorizedLogs(String logs, boolean isLight) {
        if (logs == null || logs.isEmpty()) return "";
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String[] lines = logs.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            sb.append(colorizeLogLine(lines[i], isLight));
            if (i < lines.length - 1) sb.append("\n");
        }
        return sb;
    }

    public CharSequence colorizeLogcatLine(String line, boolean isLight) {
        if (line == null || line.isEmpty()) return "";

        int colorInfo = isLight ? Color.parseColor("#388E3C") : Color.parseColor("#4CAF50");
        int colorDebug = isLight ? Color.parseColor("#1976D2") : Color.parseColor("#42A5F5");
        int colorWarn = isLight ? Color.parseColor("#F57F17") : Color.parseColor("#FFCA28");
        int colorError = isLight ? Color.parseColor("#D32F2F") : Color.parseColor("#EF5350");
        int colorTime = isLight ? Color.parseColor("#757575") : Color.parseColor("#9E9E9E");
        int defaultColor = isLight ? Color.BLACK : Color.WHITE;

        int divColor = console.getThemeColor(console.getContext(), R.attr.ide_border_color,
        isLight ? Color.parseColor("#E0E0E0") : Color.parseColor("#333333"));

        // Regex: स्टैंडर्ड एंड्रॉइड Logcat फॉर्मेट को तोड़ेगा
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+([VDIWEF])/(.*?)\\(\\s*\\d+\\):\\s*(.*)$").matcher(line);

        if (m.matches()) {
            String time = m.group(1);
            String level = m.group(2);
            String tag = m.group(3).trim();
            String msg = m.group(4);

            int levelColor = defaultColor;
            String levelName = "LOG";
            switch (level) {
                case "I": levelColor = colorInfo; levelName = "INFO"; break;
                case "D": levelColor = colorDebug; levelName = "DEBUG"; break;
                case "W": levelColor = colorWarn; levelName = "WARN"; break;
                case "E":
                case "F": levelColor = colorError; levelName = "ERROR"; break;
                case "V": levelColor = colorTime; levelName = "VERB"; break;
            }

            SpannableStringBuilder sb = new SpannableStringBuilder();

            // 1. Header (Level, Tag, Time) - वार्निंग बॉक्स जैसी हेडिंग
            int headerStart = sb.length();
            sb.append("  [").append(levelName).append("]  ").append(tag).append("  •  ").append(time).append("\n");
            sb.setSpan(new ForegroundColorSpan(levelColor), headerStart, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), headerStart, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new AbsoluteSizeSpan(14, true), headerStart, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

            // 2. Message Body (तीर के साथ थोड़ा अंदर की तरफ)
            int msgStart = sb.length();
            sb.append("      ↳ ").append(msg).append("\n");
            sb.setSpan(new ForegroundColorSpan((level.equals("E") || level.equals("F")) ? colorError : defaultColor), msgStart, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new AbsoluteSizeSpan(13, true), msgStart, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

            // 3. Divider (नीचे की लाइन)
            int divStart = sb.length();
            sb.append("───────────────────────────────────────────");
            sb.setSpan(new ForegroundColorSpan(divColor), divStart, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

            return sb;
        }

        // ⚠️ अगर लाइन Regex मैच नहीं करती है (जैसे Exception Stacktrace) तो उसे भी सही जगह पर एलाइन करें
        int fallbackColor = defaultColor;
        boolean isBold = false;
        if (line.contains(" E ") || line.contains(" F ") || line.toLowerCase().contains("exception") || line.toLowerCase().contains("fatal") || line.toLowerCase().contains("crash")) {
            fallbackColor = colorError; isBold = true;
        } else if (line.contains(" W ")) {
            fallbackColor = colorWarn;
        } else if (line.contains(" I ")) {
            fallbackColor = colorInfo;
        } else if (line.contains(" D ")) {
            fallbackColor = colorDebug;
        }

        SpannableStringBuilder fallback = new SpannableStringBuilder();
        // इसे भी अंदर की तरफ खिसकाया है ताकि ऊपर वाले "↳" के ठीक नीचे दिखे
        fallback.append("      ").append(line);
        fallback.setSpan(new ForegroundColorSpan(fallbackColor), 0, fallback.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (isBold) {
            fallback.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, fallback.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return fallback;
    }
    public Drawable getGeneratedIcon(Context context, boolean isError, boolean isWarning) {
        int size = console.dpToPx(16);
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size, size,
        android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(android.graphics.Paint.Style.STROKE);
        paint.setStrokeWidth(console.dpToPx(2f));
        paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        android.graphics.Path path = new android.graphics.Path();
        if (isWarning) {
            paint.setColor(Color.parseColor("#FFC107"));
            path.moveTo(size * 0.5f, size * 0.15f);
            path.lineTo(size * 0.9f, size * 0.85f);
            path.lineTo(size * 0.1f, size * 0.85f);
            path.close();
            canvas.drawPath(path, paint);
            paint.setStrokeWidth(console.dpToPx(1.5f));
            canvas.drawLine(size * 0.5f, size * 0.42f, size * 0.5f, size * 0.62f, paint);
            canvas.drawPoint(size * 0.5f, size * 0.74f, paint);
        } else if (isError) {
            paint.setColor(Color.parseColor("#F44336"));
            path.moveTo(size * 0.2f, size * 0.2f);
            path.lineTo(size * 0.8f, size * 0.8f);
            path.moveTo(size * 0.8f, size * 0.2f);
            path.lineTo(size * 0.2f, size * 0.8f);
            canvas.drawPath(path, paint);
        } else {
            paint.setColor(Color.parseColor("#4CAF50"));
            path.moveTo(size * 0.2f, size * 0.5f);
            path.lineTo(size * 0.45f, size * 0.75f);
            path.lineTo(size * 0.8f, size * 0.25f);
            canvas.drawPath(path, paint);
        }
        return new android.graphics.drawable.BitmapDrawable(context.getResources(), bitmap);
    }

    public void saveErrorsToStorage() {
        try {
            SharedPreferences prefs = console.getContext().getSharedPreferences("EditorPrefs", Context.MODE_PRIVATE);
            org.json.JSONObject root = new org.json.JSONObject();
            for (String path : console.errorMap.keySet()) {
                if (!console.isFileInActiveProject(path)) continue;
                org.json.JSONArray array = new org.json.JSONArray();
                for (Console.ErrorItem item : console.errorMap.get(path)) {
                    org.json.JSONObject obj = new org.json.JSONObject();
                    obj.put("filePath", item.filePath);
                    obj.put("line", item.line);
                    obj.put("colStart", item.colStart);
                    obj.put("colLen", item.colLen);
                    obj.put("message", item.message);
                    obj.put("isWarning", item.isWarning);
                    obj.put("isLiveAst", item.isLiveAst);
                    array.put(obj);
                }
                root.put(path, array);
            }
            prefs.edit().putString("saved_errors", root.toString()).apply();
        } catch (Exception ignored) {}
    }

    public void loadErrorsFromStorage() {
        try {
            SharedPreferences prefs = console.getContext().getSharedPreferences("EditorPrefs", Context.MODE_PRIVATE);
            String jsonStr = prefs.getString("saved_errors", "");
            console.errorMap.clear();
            if (jsonStr.isEmpty()) {
                refreshErrorUI();
                return;
            }
            org.json.JSONObject root = new org.json.JSONObject(jsonStr);
            java.util.Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String path = keys.next();
                if (!console.isFileInActiveProject(path)) continue;
                org.json.JSONArray array = root.getJSONArray(path);
                List<Console.ErrorItem> list = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    org.json.JSONObject obj = array.getJSONObject(i);
                    list.add(new Console.ErrorItem(
                    obj.getString("filePath"), obj.getInt("line"),
                    obj.getInt("colStart"), obj.getInt("colLen"),
                    obj.getString("message"), obj.getBoolean("isWarning"),
                    obj.getBoolean("isLiveAst")));
                }
                if (!list.isEmpty()) console.errorMap.put(path, list);
            }
            refreshErrorUI();
        } catch (Exception ignored) {}
    }
}
