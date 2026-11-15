package com.primx.ui.explorer;

import com.primx.utils.Setting;
import com.primx.R;
import com.primx.ui.editor.EditorView;
import com.primx.ui.console.Console;
import com.primx.ui.mainactivity.MainActivity;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Environment;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Filesview extends LinearLayout {

    // 🔥 FIX: headerContainer ko yahan add kiya gaya hai
    LinearLayout fileTreeContainer, btnCreateProject, searchContainer, fabFolderContainer, fabFileContainer, headerContainer;
    ImageView btnToggleSearch, refreshIcon, fabMain;
    EditText inputSearch;
    ScrollView mainScrollView;
    View refreshCircle, dimOverlay;

    IDEInteractionListener ideListener;
    int themeTextColor = Color.parseColor("#FFE0E0E0");
    int themeSubTextColor = Color.parseColor("#FF888888");

    String currentOpenProjectPath = "", activeFilePath = "";
    private String lastKnownProjectPath = "";
    public boolean isProjectMode = true;
    SharedPreferences prefs;
    Set<String> expandedFolders;

    File rootDir;
    ExecutorService executorService = Executors.newFixedThreadPool(4);

    float startY;
    boolean isPulling = false, isFabOpen = false;

    FilesTreeStyle treeStyle;
    FilesSearchManager searchManager;
    FilesProjectCreator projectCreator;
    FilesOperations fileOperations;

    private Dialog loadingDialog;

    public interface IDEInteractionListener {
        void onFileOpened(File file);
        void onProjectOpened(File projectDir);
        void onFileDeleted(File file);
        void onEditorClearRequested();
    }

    public void setIDEInteractionListener(IDEInteractionListener listener) {
        this.ideListener = listener;
    }

    public Filesview(Context context) {
        super(context);
        init();
    }

    public Filesview(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public void applyTheme(String theme) {
        if (treeStyle != null) {
            treeStyle.applyTheme(theme);
        }
    }

    void showCustomToast(String message) {
        if (getContext() instanceof MainActivity) {
            ((MainActivity) getContext()).showCustomToast(message);
        } else {
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        }
    }

    void showLoadingDialog(final String message) {
        post(new Runnable() {
                @Override
                public void run() {
                    if (loadingDialog == null) {
                        boolean isLight = Setting.isLight(getContext());
                        int themeStyleId = getResources().getIdentifier(isLight ? "Theme.PrimX.Light" : "Theme.PrimX.Dark", "style", getContext().getPackageName());
                        if (themeStyleId == 0) themeStyleId = android.R.style.Theme_NoTitleBar_Fullscreen;

                        loadingDialog = new Dialog(getContext(), themeStyleId);
                        loadingDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
                        loadingDialog.setCancelable(false);

                        if (loadingDialog.getWindow() != null) {
                            loadingDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                            android.view.WindowManager.LayoutParams lp = loadingDialog.getWindow().getAttributes();
                            lp.dimAmount = 0.75f;
                            loadingDialog.getWindow().setAttributes(lp);
                            loadingDialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                        }

                        LinearLayout layout = new LinearLayout(getContext());
                        layout.setOrientation(LinearLayout.HORIZONTAL);
                        layout.setPadding((int) dpToPx(24), (int) dpToPx(24), (int) dpToPx(24), (int) dpToPx(24));
                        layout.setGravity(Gravity.CENTER_VERTICAL);

                        int dialogBgResId = getResources().getIdentifier("bg_dialog_common", "drawable", getContext().getPackageName());
                        if (dialogBgResId != 0) {
                            layout.setBackgroundResource(dialogBgResId);
                        } else {
                            GradientDrawable gd = new GradientDrawable();
                            gd.setColor(isLight ? Color.WHITE : Color.parseColor("#252526"));
                            gd.setCornerRadius(dpToPx(12));
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                                layout.setBackground(gd);
                            } else {
                                layout.setBackgroundDrawable(gd);
                            }
                        }

                        ProgressBar pb = new ProgressBar(getContext());
                        pb.setIndeterminate(true);
                        layout.addView(pb);

                        TextView tv = new TextView(getContext());
                        tv.setText(message);
                        tv.setTextColor(isLight ? Color.parseColor("#1F2328") : Color.parseColor("#E0E0E0"));
                        tv.setTextSize(16f);
                        tv.setPadding((int) dpToPx(20), 0, 0, 0);
                        tv.setId(android.R.id.text1);
                        layout.addView(tv);

                        loadingDialog.setContentView(layout);
                    } else {
                        TextView tv = loadingDialog.findViewById(android.R.id.text1);
                        if (tv != null) tv.setText(message);
                    }

                    if (!loadingDialog.isShowing()) {
                        loadingDialog.show(); com.primx.utils.UIEffectsManager.applyPremiumBlur(loadingDialog);
                    }
                }
            });
    }

    void hideLoadingDialog() {
        post(new Runnable() {
                @Override
                public void run() {
                    if (loadingDialog != null && loadingDialog.isShowing()) {
                        loadingDialog.dismiss();
                    }
                }
            });
    }

    private void init() {
        treeStyle = new FilesTreeStyle(this);
        searchManager = new FilesSearchManager(this);
        projectCreator = new FilesProjectCreator(this);
        fileOperations = new FilesOperations(this);

        prefs = getContext().getSharedPreferences("EditorPrefs", Context.MODE_PRIVATE);
        currentOpenProjectPath = prefs.getString("projectPath", "");
        lastKnownProjectPath = currentOpenProjectPath;
        activeFilePath = prefs.getString("currentFile", "");
        expandedFolders = new HashSet<>(prefs.getStringSet("expanded_folders", new HashSet<String>()));
        isProjectMode = (currentOpenProjectPath != null && !currentOpenProjectPath.isEmpty());

        boolean isLight = Setting.isLight(getContext());
        themeTextColor = isLight ? Color.parseColor("#1F2328") : Color.parseColor("#FFE0E0E0");
        themeSubTextColor = isLight ? Color.parseColor("#656D76") : Color.parseColor("#FF888888");

        int layoutId = getResources().getIdentifier("filesview", "layout", getContext().getPackageName());
        if (layoutId != 0) {
            LayoutInflater.from(getContext()).inflate(layoutId, this, true);
        }

        int ftcId = getResources().getIdentifier("fileTreeContainer", "id", getContext().getPackageName());
        if (ftcId != 0) fileTreeContainer = (LinearLayout) findViewById(ftcId);

        // 🔥 FIX: headerContainer ko dhoondh liya
        int hcId = getResources().getIdentifier("headerContainer", "id", getContext().getPackageName());
        if (hcId != 0) headerContainer = (LinearLayout) findViewById(hcId);

        int bcpId = getResources().getIdentifier("btnCreateProject", "id", getContext().getPackageName());
        if (bcpId != 0) btnCreateProject = (LinearLayout) findViewById(bcpId);

        int btsId = getResources().getIdentifier("btnToggleSearch", "id", getContext().getPackageName());
        if (btsId != 0) btnToggleSearch = (ImageView) findViewById(btsId);

        int scId = getResources().getIdentifier("searchContainer", "id", getContext().getPackageName());
        if (scId != 0) searchContainer = (LinearLayout) findViewById(scId);

        int isId = getResources().getIdentifier("inputSearch", "id", getContext().getPackageName());
        if (isId != 0) inputSearch = (EditText) findViewById(isId);

        int msvId = getResources().getIdentifier("mainScrollView", "id", getContext().getPackageName());
        if (msvId != 0) mainScrollView = (ScrollView) findViewById(msvId);

        int rcId = getResources().getIdentifier("refreshCircle", "id", getContext().getPackageName());
        if (rcId != 0) refreshCircle = findViewById(rcId);

        int riId = getResources().getIdentifier("refreshIcon", "id", getContext().getPackageName());
        if (riId != 0) refreshIcon = (ImageView) findViewById(riId);

        int fmId = getResources().getIdentifier("fabMain", "id", getContext().getPackageName());
        if (fmId != 0) fabMain = (ImageView) findViewById(fmId);

        int doId = getResources().getIdentifier("dimOverlay", "id", getContext().getPackageName());
        if (doId != 0) dimOverlay = findViewById(doId);

        int ffcId = getResources().getIdentifier("fabFolderContainer", "id", getContext().getPackageName());
        if (ffcId != 0) fabFolderContainer = (LinearLayout) findViewById(ffcId);

        int ffc2Id = getResources().getIdentifier("fabFileContainer", "id", getContext().getPackageName());
        if (ffc2Id != 0) fabFileContainer = (LinearLayout) findViewById(ffc2Id);

        fileOperations.setupFabLogic();

        if (btnCreateProject != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                btnCreateProject.setBackground(treeStyle.getGreyTouchEffect());
            } else {
                btnCreateProject.setBackgroundDrawable(treeStyle.getGreyTouchEffect());
            }

            btnCreateProject.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        FilesTypeMenu.show(getContext(), new FilesTypeMenu.Callback() {
                                @Override
                                public void onSelect(String type) {
                                    projectCreator.showCustomCreateProjectDialog(type);
                                }
                            });
                    }
                });
        }

        searchManager.setupSearch(isLight);
    }

    float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    void saveExpandedState() {
        prefs.edit().putStringSet("expanded_folders", expandedFolders).apply();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (mainScrollView != null && mainScrollView.getScrollY() == 0 && !isFabOpen) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startY = event.getY();
                    isPulling = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    float diffY = event.getY() - startY;
                    float threshold = dpToPx(80);
                    if (diffY > threshold) {
                        isPulling = true;
                        float translation = (diffY - threshold) * 0.4f;
                        if (translation > dpToPx(80)) translation = dpToPx(80);
                        if (refreshCircle != null) refreshCircle.setTranslationY(translation - dpToPx(30));
                        if (refreshIcon != null) refreshIcon.setRotation(translation * 2);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (isPulling) {
                        if ((event.getY() - startY) > dpToPx(130)) performCustomRefresh();
                        else if (refreshCircle != null) refreshCircle.animate().translationY(-dpToPx(60)).setDuration(250).start();
                    }
                    isPulling = false;
                    break;
            }
        }
        return super.dispatchTouchEvent(event);
    }

    private void performCustomRefresh() {
        if (searchContainer != null && searchContainer.getVisibility() == View.VISIBLE) {
            searchContainer.setVisibility(View.GONE);

            // 🔥 FIX: Refresh karne par Header wapas dikhega!
            if (headerContainer != null) {
                headerContainer.setVisibility(View.VISIBLE);
            }

            if (inputSearch != null) inputSearch.setText("");
            searchManager.hideKeyboard();
        }
        if (refreshIcon != null) refreshIcon.animate().rotationBy(720).setDuration(800).start();
        initFileManager();
        if (refreshCircle != null) refreshCircle.animate().translationY(-dpToPx(60)).setStartDelay(800).setDuration(300).start();
        showCustomToast("Refreshed!");
    }

    public void initFileManager() {
        File storageRoot = Environment.getExternalStorageDirectory();
        if (storageRoot == null || !storageRoot.exists()) return;

        activeFilePath = prefs.getString("currentFile", "");
        currentOpenProjectPath = prefs.getString("projectPath", "");

        if (currentOpenProjectPath != null && !currentOpenProjectPath.isEmpty() && !currentOpenProjectPath.equals(lastKnownProjectPath)) {
            isProjectMode = true;
            lastKnownProjectPath = currentOpenProjectPath;
        }

        File targetDir = storageRoot;
        if (isProjectMode && currentOpenProjectPath != null && !currentOpenProjectPath.isEmpty()) {
            File projDir = new File(currentOpenProjectPath);
            if (projDir.exists() && projDir.isDirectory()) {
                targetDir = projDir;
            } else {
                isProjectMode = false;
            }
        }

        rootDir = targetDir;

        if (fabMain != null) {
            if (isProjectMode) {
                if (isFabOpen && fileOperations != null) fileOperations.closeFabMenu();
                fabMain.setVisibility(View.GONE);
            } else {
                fabMain.setVisibility(View.VISIBLE);
            }
        }

        if (fileTreeContainer != null && treeStyle != null) {
            treeStyle.loadFilesIntoContainer(rootDir, fileTreeContainer, 0);
        }
    }

    public static String detectProjectType(File dir) {
        return FilesProjectCreator.detectProjectType(dir);
    }

    public static boolean isProjectFolder(File dir) {
        return FilesProjectCreator.isProjectFolder(dir);
    }
}

