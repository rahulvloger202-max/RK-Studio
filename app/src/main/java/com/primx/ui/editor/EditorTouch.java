package com.primx.ui.editor;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;
import java.util.HashMap;

public class EditorTouch {
    public OverScroller scroller;
    public GestureDetector gestureDetector;
    public ScaleGestureDetector scaleGestureDetector;

    public float scrollY = 0, scrollX = 0;
    
    public HashMap<View, Float> sloppyTouchX = new HashMap<>();
    public HashMap<View, Float> sloppyTouchY = new HashMap<>();

    public EditorTouch(Context context, GestureDetector.SimpleOnGestureListener gestureListener, ScaleGestureDetector.SimpleOnScaleGestureListener scaleListener) {
        scroller = new OverScroller(context);
        gestureDetector = new GestureDetector(context, gestureListener);
        scaleGestureDetector = new ScaleGestureDetector(context, scaleListener);
    }

    public void clampScroll(float maxScrollY) {
        scrollY = Math.max(0, Math.min(scrollY, maxScrollY)); 
        scrollX = Math.max(0, scrollX);
    }

    public void computeScroll(View view, float maxScrollY) {
        if (scroller.computeScrollOffset()) { 
            scrollX = scroller.getCurrX(); 
            scrollY = scroller.getCurrY(); 
            clampScroll(maxScrollY); 
            view.invalidate(); 
        }
    }

    public void centerSearchOnScreen(float targetY, float lineHeight, float halfScreenHeight, float matchX, float viewWidth) {
        if (scroller != null && !scroller.isFinished()) {
            scroller.forceFinished(true);
        }
        scrollY = Math.max(0, targetY + (lineHeight / 2f) - halfScreenHeight);
        if (matchX >= 0) {
            scrollX = Math.max(0, matchX - (viewWidth / 3f)); 
        }
    }
}
