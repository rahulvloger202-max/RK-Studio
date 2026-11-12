package com.termux;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

public class MainActivity extends Activity {
    private TermuxEngine engine;
    private TermuxView termuxView;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        termuxView = new TermuxView(this);
        setContentView(termuxView);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            engine = new TermuxEngine(text -> termuxView.appendOutput(text));
            termuxView.attachEngine(engine);
            engine.startNativeShell();
        }, 500);
    }
}
