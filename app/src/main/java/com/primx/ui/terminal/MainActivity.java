package com.primx.ui.terminal;

import android.app.Activity;
import android.os.Bundle;

public class MainActivity extends Activity {
    private TermuxEngine engine;
    private TermuxView termuxView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Native library load karo sabse pehle
        TerminalPty.initNative(this);

        // Screen lagao
        termuxView = new TermuxView(this);
        setContentView(termuxView);

        // Engine chalu karo
        engine = new TermuxEngine(new TermuxEngine.EngineListener() {
            @Override
            public void onOutput(String text) {
                termuxView.appendOutput(text);
            }
        });

        termuxView.attachEngine(engine);
        engine.startNativeShell(getFilesDir().getAbsolutePath());
    }
}
