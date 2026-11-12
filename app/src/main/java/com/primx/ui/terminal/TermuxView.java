package com.primx.ui.terminal;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class TermuxView extends FrameLayout {
    private ScrollView scrollView;
    private TextView terminalOutput;
    private EditText hiddenInput;
    private TermuxEngine engine;
    private SpannableStringBuilder buffer;

    public TermuxView(Context context) {
        super(context);
        buffer = new SpannableStringBuilder();
        initUI(context);
    }

    private void initUI(Context context) {
        setBackgroundColor(Color.parseColor("#000000"));

        scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);

        terminalOutput = new TextView(context);
        terminalOutput.setTextColor(Color.parseColor("#E0E0E0"));
        terminalOutput.setTextSize(14f);
        terminalOutput.setTypeface(Typeface.MONOSPACE);
        terminalOutput.setPadding(20, 20, 20, 20);
        terminalOutput.setTextIsSelectable(true);
        
        scrollView.addView(terminalOutput, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        hiddenInput = new EditText(context);
        hiddenInput.setBackgroundColor(Color.TRANSPARENT);
        hiddenInput.setTextColor(Color.TRANSPARENT);
        hiddenInput.setCursorVisible(false);
        hiddenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hiddenInput.setImeOptions(EditorInfo.IME_ACTION_DONE);

        addView(scrollView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(hiddenInput, new LayoutParams(1, 1));

        hiddenInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                String cmd = hiddenInput.getText().toString();
                if (engine != null) engine.writeCommand(cmd);
                hiddenInput.setText(""); 
                return true;
            }
            return false;
        });

        setOnClickListener(v -> showKeyboard());
        terminalOutput.setOnClickListener(v -> showKeyboard());
    }

    public void attachEngine(TermuxEngine engine) {
        this.engine = engine;
    }

    public void appendOutput(String text) {
        TerminalAnsiParser.parseAndAppend(buffer, text, Color.parseColor("#E0E0E0"));
        terminalOutput.setText(buffer);
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void showKeyboard() {
        hiddenInput.requestFocus();
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(hiddenInput, InputMethodManager.SHOW_IMPLICIT);
    }
}
