
package com.primx.utils;


import com.primx.ui.editor.EditorView;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;

public class Setting {

    public static boolean isLight(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = context.getSharedPreferences("EditorPrefs", Context.MODE_PRIVATE);
        String theme = prefs.getString("appTheme", "Dark");
        return "Light".equalsIgnoreCase(theme) || "White".equalsIgnoreCase(theme);
    }

    public static void show(final Context context, final EditorView codeCanvas) {
        boolean isLight = isLight(context);
        int themeStyleId = context.getResources().getIdentifier(isLight ? "Theme.PrimX.Light" : "Theme.PrimX.Dark", "style", context.getPackageName());
        if (themeStyleId == 0) themeStyleId = android.R.style.Theme_NoTitleBar_Fullscreen;

        final Dialog dialog = new Dialog(context, themeStyleId);
        int layoutId = context.getResources().getIdentifier("dialog_settings", "layout", context.getPackageName());
        if (layoutId == 0) return;
        dialog.setContentView(layoutId);

        final SharedPreferences prefs = context.getSharedPreferences("EditorPrefs", Context.MODE_PRIVATE);

        int backId = context.getResources().getIdentifier("btnSettingsBack", "id", context.getPackageName());
        View btnBack = backId != 0 ? dialog.findViewById(backId) : null;
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { dialog.dismiss(); }
            });
        }

        // 🔥 NEW: Gradle Switch handler
        int switchGradleId = context.getResources().getIdentifier("switchUseGradle", "id", context.getPackageName());
        Switch switchUseGradle = switchGradleId != 0 ? (Switch) dialog.findViewById(switchGradleId) : null;
        if (switchUseGradle != null) {
            switchUseGradle.setChecked(prefs.getBoolean("useGradle", false));
            switchUseGradle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    prefs.edit().putBoolean("useGradle", isChecked).apply();
                }
            });
        }

        int[] themeBtnIds = new int[] {
            context.getResources().getIdentifier("btnThemeDark", "id", context.getPackageName()),
            context.getResources().getIdentifier("btnThemeLight", "id", context.getPackageName())
        };
        final String[] themeNames = new String[] { "Dark", "Light" };

        for (int i = 0; i < themeBtnIds.length; i++) {
            if (themeBtnIds[i] != 0) {
                View tv = dialog.findViewById(themeBtnIds[i]);
                final String tName = themeNames[i];
                if (tv != null) {
                    tv.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            String currentTheme = prefs.getString("appTheme", "Dark");
                            if (!currentTheme.equalsIgnoreCase(tName)) {
                                prefs.edit().putString("appTheme", tName).apply();
                                if (codeCanvas != null) codeCanvas.setTheme(tName);
                                dialog.dismiss();
                                if (context instanceof Activity) {
                                    ((Activity) context).recreate();
                                }
                            }
                        }
                    });
                }
            }
        }

        dialog.show(); com.primx.utils.UIEffectsManager.applyPremiumBlur(dialog);


    }
}
