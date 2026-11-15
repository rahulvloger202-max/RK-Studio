package com.primx.utils;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

public class UIEffectsManager {

    public static void applyPremiumBlur(Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return;

        Context context = dialog.getContext();
        Window window = dialog.getWindow();

        // Background ko transparent karna zaroori hai taaki peeche ka blur dikhe
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        // Native Hardware Blur for Android 12+ (API 31)
        if (Build.VERSION.SDK_INT >= 31) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            window.getAttributes().setBlurBehindRadius(35); // Blur intensity
        } else {
            // Purane devices ke liye halka transparent dark/light dim overlay
            boolean isLight = Setting.isLight(context);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.dimAmount = isLight ? 0.2f : 0.4f;
            window.setAttributes(lp);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        // Yeh dono lines ensure karengi ki har dialog bahar click karne par band ho
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
    }

    public static void applyViewBlur(View target, boolean blur) {
        if (target == null) return;
        if (Build.VERSION.SDK_INT >= 31) {
            if (blur) {
                target.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(25f, 25f, android.graphics.Shader.TileMode.CLAMP));
            } else {
                target.setRenderEffect(null);
            }
        }
    }
}
