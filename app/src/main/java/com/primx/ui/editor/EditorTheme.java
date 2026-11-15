package com.primx.ui.editor;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;

public class EditorTheme {
    public Paint plainTextPaint;
    public Paint textPaint, keywordPaint, controlPaint, typePaint;
    public Paint stringPaint, numberPaint, symbolPaint, xmlTagPaint;
    public Paint xmlAttrPaint, methodPaint, annotationPaint, commentPaint;

    public Paint gutterPaint, lineNumberPaint, cursorPaint, indentGuidePaint;
    public Paint activeLinePaint, selectionPaint, handlePaint, undoHighlightPaint;

    public Paint searchLineBackgroundPaint, searchTextHighlightPaint;
    public Paint errorZigZagPaint, errorHighlightBackgroundPaint, warningZigZagPaint, fadedPaint;
    public Paint colorPillPaint, colorStrokePaint, colorTextDarkPaint, colorTextLightPaint;
    public Paint statusPaint, statusTextPaint;
    public Path statusPath;
    public Paint headerTitlePaint, headerPathPaint, dividerPaint;

    public int editorBackgroundColor = Color.parseColor("#121212");
    public String currentAppTheme = "Dark";
    public float currentTextSize = 28f;
    public final float MIN_TEXT_SIZE = 15f, MAX_TEXT_SIZE = 90f;
    public float lineHeight;
    public int gutterWidth;
    private final Context context;

    public EditorTheme(Context context) {
        this.context = context;
        initPaints();
        try {
            SharedPreferences _tp = context.getSharedPreferences("EditorPrefs", Context.MODE_PRIVATE);
            String _savedTheme = _tp.getString("appTheme", "Dark");
            setTheme(_savedTheme);
        } catch (Exception ignored) {}
    }

    private void initPaints() {
        statusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        statusPaint.setStyle(Paint.Style.STROKE);
        statusPaint.setStrokeWidth(3.5f);
        statusPaint.setStrokeCap(Paint.Cap.ROUND);
        statusPaint.setStrokeJoin(Paint.Join.ROUND);

        statusTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        statusTextPaint.setTextSize(16f);
        statusTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        statusTextPaint.setTextAlign(Paint.Align.RIGHT);

        statusPath = new Path();
        Typeface patlaFont = Typeface.create("sans-serif", Typeface.NORMAL);
        Typeface commentFont = Typeface.create("sans-serif", Typeface.ITALIC);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTypeface(patlaFont);

        plainTextPaint = new Paint(textPaint);
        keywordPaint = new Paint(textPaint);
        controlPaint = new Paint(textPaint);
        typePaint = new Paint(textPaint);
        stringPaint = new Paint(textPaint);
        numberPaint = new Paint(textPaint);
        symbolPaint = new Paint(textPaint);
        xmlTagPaint = new Paint(textPaint);
        xmlAttrPaint = new Paint(textPaint);
        methodPaint = new Paint(textPaint);
        annotationPaint = new Paint(textPaint);
        commentPaint = new Paint(textPaint);
        commentPaint.setTypeface(commentFont);

        gutterPaint = new Paint();
        gutterPaint.setColor(Color.TRANSPARENT);
        indentGuidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        indentGuidePaint.setStyle(Paint.Style.STROKE);
        indentGuidePaint.setStrokeWidth(1.5f);

        activeLinePaint = new Paint();
        lineNumberPaint = new Paint(textPaint);
        lineNumberPaint.setTextAlign(Paint.Align.RIGHT);
        cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cursorPaint.setStrokeWidth(4f);
        selectionPaint = new Paint();
        selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectionPaint.setStyle(Paint.Style.FILL);

        handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        searchLineBackgroundPaint = new Paint();
        searchTextHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        searchTextHighlightPaint.setStyle(Paint.Style.FILL);

        headerTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        headerTitlePaint.setTextSize(30f);
        headerPathPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        headerPathPaint.setTextSize(18f);
        dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dividerPaint.setStrokeWidth(2f);

        errorZigZagPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        errorZigZagPaint.setStyle(Paint.Style.STROKE);
        errorZigZagPaint.setStrokeJoin(Paint.Join.ROUND);
        errorHighlightBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        errorHighlightBackgroundPaint.setStyle(Paint.Style.FILL);
        warningZigZagPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        warningZigZagPaint.setStyle(Paint.Style.STROKE);
        warningZigZagPaint.setStrokeJoin(Paint.Join.ROUND);
        fadedPaint = new Paint(textPaint);

        colorPillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        colorPillPaint.setStyle(Paint.Style.FILL);
        colorStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        colorStrokePaint.setStyle(Paint.Style.STROKE);
        colorTextDarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        colorTextDarkPaint.setColor(Color.parseColor("#1F2328"));
        colorTextDarkPaint.setTypeface(Typeface.DEFAULT_BOLD);
        colorTextLightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        colorTextLightPaint.setColor(Color.parseColor("#F8F8F2"));
        colorTextLightPaint.setTypeface(Typeface.DEFAULT_BOLD);
        undoHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        undoHighlightPaint.setColor(0x30FFC107); // हल्का Amber/Yellow कलर 
        undoHighlightPaint.setStyle(Paint.Style.FILL);

    }

    private int resolveAttrColor(int styleResId, String attrName, int fallbackColor) {
        if (context == null || styleResId == 0) return fallbackColor;
        try {
            int attrId = context.getResources().getIdentifier(attrName, "attr", context.getPackageName());
            if (attrId == 0) return fallbackColor;
            TypedArray ta = context.obtainStyledAttributes(styleResId, new int[]{attrId});
            int color = ta.getColor(0, fallbackColor);
            ta.recycle();
            return color;
        } catch (Exception e) {
            return fallbackColor;
        }
    }

    public void setTheme(String themeName) {
        if (themeName == null || themeName.trim().isEmpty()) themeName = "Dark";
        this.currentAppTheme = themeName;
        boolean isLight = themeName.equalsIgnoreCase("Light") || themeName.equalsIgnoreCase("White");
        int styleId = 0;
        if (context != null) {
            styleId = context.getResources().getIdentifier(isLight ? "Theme.PrimX.Light" : "Theme.PrimX.Dark", "style", context.getPackageName());
        }

        textPaint.setColor(Color.parseColor(isLight ? "#00796B" : "#4DD0E1"));
        keywordPaint.setColor(Color.parseColor(isLight ? "#1565C0" : "#58A6FF"));
        controlPaint.setColor(Color.parseColor(isLight ? "#AD1457" : "#F778BA"));
        typePaint.setColor(Color.parseColor(isLight ? "#D84315" : "#FFA657"));
        stringPaint.setColor(Color.parseColor(isLight ? "#2E7D32" : "#56D364"));
        numberPaint.setColor(Color.parseColor(isLight ? "#E65100" : "#FF9E64"));
        symbolPaint.setColor(Color.parseColor(isLight ? "#00838F" : "#79C0FF"));
        xmlTagPaint.setColor(Color.parseColor(isLight ? "#1565C0" : "#58A6FF"));
        xmlAttrPaint.setColor(Color.parseColor(isLight ? "#7B1FA2" : "#D2A8FF"));
        methodPaint.setColor(Color.parseColor(isLight ? "#4527A0" : "#B392F0"));
        annotationPaint.setColor(Color.parseColor(isLight ? "#9E7B00" : "#E3B341"));
        commentPaint.setColor(Color.parseColor(isLight ? "#6E7781" : "#8B949E"));

        if (isLight) {
            editorBackgroundColor = resolveAttrColor(styleId, "editor_bg_color", Color.parseColor("#FFFFFF"));
            plainTextPaint.setColor(resolveAttrColor(styleId, "editor_plain_text_color", Color.parseColor("#1F2328")));
            activeLinePaint.setColor(resolveAttrColor(styleId, "editor_active_line_color", Color.parseColor("#FFFDE7")));
            indentGuidePaint.setColor(resolveAttrColor(styleId, "editor_indent_line_color", Color.parseColor("#D0D7DE")));
            lineNumberPaint.setColor(resolveAttrColor(styleId, "editor_line_number_color", Color.parseColor("#8C959F")));
            dividerPaint.setColor(resolveAttrColor(styleId, "ide_stroke_color", Color.parseColor("#D0D7DE")));
            headerTitlePaint.setColor(resolveAttrColor(styleId, "ide_text_primary", Color.parseColor("#212121")));
            headerPathPaint.setColor(resolveAttrColor(styleId, "ide_text_secondary", Color.parseColor("#757575")));
            cursorPaint.setColor(Color.parseColor("#0969DA"));
            fadedPaint.setColor(Color.parseColor("#6E7781"));
            handlePaint.setColor(Color.parseColor("#0969DA"));
            errorZigZagPaint.setColor(Color.parseColor("#CF222E"));
            errorHighlightBackgroundPaint.setColor(Color.parseColor("#1ACF222E"));
            warningZigZagPaint.setColor(Color.parseColor("#BF8700"));

            // 🔥 FIX: Light Theme Selection & Background Colors
            selectionPaint.setColor(Color.parseColor("#4DA8C7")); // Light blue visible selection
            searchLineBackgroundPaint.setColor(Color.parseColor("#1A000000")); // Grey background
            searchTextHighlightPaint.setColor(Color.parseColor("#40000000")); // Dark grey highlight behind text
        } else {
            int fallbackBg = Color.parseColor("#121212");
            if (themeName.equalsIgnoreCase("Dracula")) fallbackBg = Color.parseColor("#282A36");
            else if (themeName.equalsIgnoreCase("Monokai")) fallbackBg = Color.parseColor("#272822");
            else if (themeName.equalsIgnoreCase("One Dark") || themeName.equalsIgnoreCase("OneDark")) fallbackBg = Color.parseColor("#21252B");

            editorBackgroundColor = themeName.equalsIgnoreCase("Dark") ? resolveAttrColor(styleId, "editor_bg_color", fallbackBg) : fallbackBg;
            plainTextPaint.setColor(resolveAttrColor(styleId, "editor_plain_text_color", Color.parseColor("#E6EDF3")));
            activeLinePaint.setColor(resolveAttrColor(styleId, "editor_active_line_color", Color.parseColor("#1E2430")));
            indentGuidePaint.setColor(resolveAttrColor(styleId, "editor_indent_line_color", Color.parseColor("#30363D")));
            lineNumberPaint.setColor(resolveAttrColor(styleId, "editor_line_number_color", Color.parseColor("#7D8590")));
            dividerPaint.setColor(resolveAttrColor(styleId, "ide_stroke_color", Color.parseColor("#30363D")));
            headerTitlePaint.setColor(resolveAttrColor(styleId, "ide_text_primary", Color.parseColor("#E0E0E0")));
            headerPathPaint.setColor(resolveAttrColor(styleId, "ide_text_secondary", Color.parseColor("#9E9E9E")));
            cursorPaint.setColor(Color.parseColor("#2F81F7"));
            fadedPaint.setColor(Color.parseColor("#7D8590"));
            handlePaint.setColor(Color.parseColor("#2F81F7"));
            errorZigZagPaint.setColor(Color.parseColor("#F85149"));
            errorHighlightBackgroundPaint.setColor(Color.parseColor("#1AF85149"));
            warningZigZagPaint.setColor(Color.parseColor("#D29922"));

            // 🔥 FIX: Dark Theme Selection & Background Colors
            selectionPaint.setColor(Color.parseColor("#801E88E5")); // Deep blue visible selection
            searchLineBackgroundPaint.setColor(Color.parseColor("#33FFFFFF")); // White background
            searchTextHighlightPaint.setColor(Color.parseColor("#80FFFFFF")); // White highlight behind text
        }
    }

    public void updateFontMetrics(int linesSize) {
        plainTextPaint.setTextSize(currentTextSize);
        textPaint.setTextSize(currentTextSize);
        keywordPaint.setTextSize(currentTextSize);
        controlPaint.setTextSize(currentTextSize);
        typePaint.setTextSize(currentTextSize);
        stringPaint.setTextSize(currentTextSize);
        numberPaint.setTextSize(currentTextSize);
        symbolPaint.setTextSize(currentTextSize);
        xmlTagPaint.setTextSize(currentTextSize);
        xmlAttrPaint.setTextSize(currentTextSize);
        methodPaint.setTextSize(currentTextSize);
        annotationPaint.setTextSize(currentTextSize);
        commentPaint.setTextSize(currentTextSize);
        lineNumberPaint.setTextSize(currentTextSize * 0.88f);
        fadedPaint.setTextSize(currentTextSize);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        lineHeight = fm.descent - fm.ascent + fm.leading;

        int digits = Math.max(2, String.valueOf(linesSize + 1).length());
        StringBuilder dummy = new StringBuilder();
        for (int i = 0; i < digits; i++) dummy.append("0");
        gutterWidth = (int) (lineNumberPaint.measureText(dummy.toString()) + (currentTextSize * 0.65f));

        indentGuidePaint.setStrokeWidth(Math.max(1.2f, currentTextSize * 0.04f));

        float strokeW = Math.max(1.8f, currentTextSize * 0.045f);
        errorZigZagPaint.setStrokeWidth(strokeW);
        errorZigZagPaint.setStrokeCap(Paint.Cap.ROUND);
        errorZigZagPaint.setStrokeJoin(Paint.Join.ROUND);
        warningZigZagPaint.setStrokeWidth(strokeW);
        warningZigZagPaint.setStrokeCap(Paint.Cap.ROUND);
        warningZigZagPaint.setStrokeJoin(Paint.Join.ROUND);

        colorTextDarkPaint.setTextSize(currentTextSize);
        colorTextLightPaint.setTextSize(currentTextSize);
        colorStrokePaint.setStrokeWidth(2f);
    }
}
