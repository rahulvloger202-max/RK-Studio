package com.primx.ui;

import com.primx.utils.Setting;

import com.primx.R;
import com.primx.ui.mainactivity.MainActivity;



import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupWindow;

public class Menu {

    public interface MenuListener {
        void onSearchClicked();
        void onSettingsClicked();
    }

    public static void show(Context context, View anchor, final MenuListener listener) {
        int layoutId = context.getResources().getIdentifier("menu", "layout", context.getPackageName());
        if (layoutId == 0) return;

        View view = LayoutInflater.from(context).inflate(layoutId, null);

        // Naya Popup Window setup bina kisi hardcoded background gradient ke
        // Kyunki humne menu.xml me pehle se hi "@drawable/bg_dialog_common" laga diya hai
        final PopupWindow popupWindow = new PopupWindow(view,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT, true);

        popupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popupWindow.setOutsideTouchable(true);

        if (android.os.Build.VERSION.SDK_INT >= 21) {
            popupWindow.setElevation(15f);
        }

        // Direct XML waale IDs ko point kar rahe hain
        int searchId = context.getResources().getIdentifier("menuSearch", "id", context.getPackageName());
        int settingsId = context.getResources().getIdentifier("ic_settings", "id", context.getPackageName());
        // Note: XML me doosre ImageView ka ID nahi tha, to main index 1 se utha lunga as fallback

        View menuSearch = searchId != 0 ? view.findViewById(searchId) : null;
        View menuSettings = settingsId != 0 ? view.findViewById(settingsId) : null;

        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            if (menuSearch == null && vg.getChildCount() > 0) menuSearch = vg.getChildAt(0);
            if (menuSettings == null && vg.getChildCount() > 1) menuSettings = vg.getChildAt(1);
        }

        // Ab koi hardcoded icon tint ya background nahi lagana,
        // kyunki XML (menu.xml) me '?attr/ide_icon_tint' aur ripple pehle se set hain.
        if (menuSearch != null) {
            menuSearch.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    popupWindow.dismiss();
                    if (listener != null) listener.onSearchClicked();
                }
            });
        }

        if (menuSettings != null) {
            menuSettings.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    popupWindow.dismiss();
                    if (listener != null) listener.onSettingsClicked();
                }
            });
        }

        // Popup ko thoda left shift kar rahe hain taki screen ke bahar na jaye
        popupWindow.showAsDropDown(anchor, -120, 10);
    }
}

