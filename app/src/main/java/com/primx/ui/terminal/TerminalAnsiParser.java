package com.primx.ui.terminal;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TerminalAnsiParser {
    public static final int MAX_BUFFER_CHARS = 45000;
    private static final Pattern ANSI_COLOR_PATTERN = Pattern.compile("\u001B\\[[;\\d]*m");
    private static final Pattern NON_COLOR_CSI_PATTERN = Pattern.compile("\u001B\\[[\\?0-9;]*[A-Za-ln-z]");
    private static final Pattern OSC_PATTERN = Pattern.compile("\u001B\\][^\\u0007\\u001B]*(\\u0007|\\u001B\\\\)");

    public static void parseAndAppend(SpannableStringBuilder buffer, String rawText, int defaultColor) {
        if (rawText == null || rawText.isEmpty()) return;

        if (rawText.contains("\u001B[2J") || rawText.contains("\u001Bc")) buffer.clear();

        String text = rawText.replace("\r\n", "\n").replace("\r", "");
        text = OSC_PATTERN.matcher(text).replaceAll("");
        text = NON_COLOR_CSI_PATTERN.matcher(text).replaceAll("");

        int currentColor = defaultColor;
        Matcher matcher = ANSI_COLOR_PATTERN.matcher(text);
        int lastEnd = 0;

        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                appendChunk(buffer, text.substring(lastEnd, matcher.start()), currentColor);
            }
            currentColor = extractColor(matcher.group(), currentColor, defaultColor);
            lastEnd = matcher.end();
        }

        if (lastEnd < text.length()) {
            appendChunk(buffer, text.substring(lastEnd), currentColor);
        }
        if (buffer.length() > MAX_BUFFER_CHARS) {
            buffer.delete(0, buffer.length() - (MAX_BUFFER_CHARS / 2));
        }
    }

    private static void appendChunk(SpannableStringBuilder buffer, String text, int color) {
        if (text == null || text.isEmpty()) return;
        int start = buffer.length();
        buffer.append(text);
        buffer.setSpan(new ForegroundColorSpan(color), start, buffer.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static int extractColor(String ansiCode, int currentColor, int defaultColor) {
        if (ansiCode.equals("\u001B[0m") || ansiCode.equals("\u001B[m")) return defaultColor;
        if (ansiCode.contains("31") || ansiCode.contains("91")) return 0xFFF44336;
        if (ansiCode.contains("32") || ansiCode.contains("92")) return 0xFF4CAF50;
        if (ansiCode.contains("33") || ansiCode.contains("93")) return 0xFFFFEB3B;
        if (ansiCode.contains("34") || ansiCode.contains("94")) return 0xFF2196F3;
        if (ansiCode.contains("35") || ansiCode.contains("95")) return 0xFF9C27B0;
        if (ansiCode.contains("36") || ansiCode.contains("96")) return 0xFF00BCD4;
        return currentColor;
    }
}
