package com.primx.ui.terminal;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

public class MainActivity extends Activity {
    private TermuxEngine engine;
    private TermuxView termuxView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // UI Load hone do pehle
        termuxView = new TermuxView(this);
        setContentView(termuxView);

        // Native engine ab apne aap JNI block mein load hota hai (TerminalPty.java mein)
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            
            engine = new TermuxEngine(text -> termuxView.appendOutput(text));
            termuxView.attachEngine(engine);
            
            // Start the shell from current internal directory
            engine.startNativeShell(getFilesDir().getAbsolutePath());
        }, 500); // Half second delay for smooth UI load
    }
}
