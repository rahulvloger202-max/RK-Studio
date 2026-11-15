package com.primx.ui.explorer;



import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

public class FilesTypeMenu implements View.OnClickListener {

    public interface Callback {
        void onSelect(String type);
    }

    private Dialog dialog;
    private Callback callback;
    private Context context;
    private int androidId, javaId, webId, androidxId, pythonId, pxProgramId;

    public static void show(Context context, Callback callback) {
        new FilesTypeMenu(context, callback).open();
    }

    private FilesTypeMenu(Context context, Callback callback) {
        this.context = context;
        this.callback = callback;
    }

    private void open() {
        dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        int layoutId = context.getResources().getIdentifier("dialog_select_project_type", "layout", context.getPackageName());
        if (layoutId == 0) return;
        dialog.setContentView(layoutId);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
            lp.dimAmount = 0.75f;
            dialog.getWindow().setAttributes(lp);
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.setCancelable(true);

        androidId = context.getResources().getIdentifier("optAndroidApp", "id", context.getPackageName());
        javaId = context.getResources().getIdentifier("optJavaConsole", "id", context.getPackageName());
        webId = context.getResources().getIdentifier("optWeb", "id", context.getPackageName());
        androidxId = context.getResources().getIdentifier("optAndroidXApp", "id", context.getPackageName());
        pythonId = context.getResources().getIdentifier("optPython", "id", context.getPackageName());
        pxProgramId = context.getResources().getIdentifier("optPxProgram", "id", context.getPackageName());

        bind(androidId);
        bind(javaId);
        bind(webId);
        bind(pythonId);
        bind(androidxId);
        bind(pxProgramId);

        dialog.show(); com.primx.utils.UIEffectsManager.applyPremiumBlur(dialog);
    }

    private void bind(int resId) {
        if (resId != 0 && dialog != null) {
            View v = dialog.findViewById(resId);
            if (v != null) v.setOnClickListener(this);
        }
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();

        if (dialog != null) dialog.dismiss();

        if (id == androidId) {
            if (callback != null) callback.onSelect("Android");
        } else if (id == javaId) {
            if (callback != null) callback.onSelect("Java");
        } else if (id == pythonId) {
            if (callback != null) callback.onSelect("Python");
        } else if (id == androidxId) {
            if (callback != null) callback.onSelect("AndroidX");
        } else if (id == webId) {
            if (callback != null) callback.onSelect("Web");
        } else if (id == pxProgramId) {
            // 🔥 FIX: Ab PX Program par click karne se naya project banega
            if (callback != null) callback.onSelect("PX");
        }
    }
}

class Filesto {
    public interface Callback extends FilesTypeMenu.Callback {}

    public static void show(Context context, final Callback callback) {
        FilesTypeMenu.show(context, callback);
    }

    public static void show(Context context, final FilesTypeMenu.Callback callback) {
        FilesTypeMenu.show(context, callback);
    }
}