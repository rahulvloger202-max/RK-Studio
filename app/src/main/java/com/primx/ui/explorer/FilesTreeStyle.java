package com.primx.ui.explorer;

import com.primx.utils.Setting;
import com.primx.ui.console.Console;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class FilesTreeStyle {

    private final Filesview view;
    private static final SimpleDateFormat SDF = new SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault());
    private final HashMap<String, Integer> iconCache = new HashMap<>();

    public static class FileItem {
        File file;
        int subCount;

        FileItem(File f, int count) {
            file = f;
            subCount = count;
        }
    }

    public FilesTreeStyle(Filesview view) {
        this.view = view;
    }

    public void applyTheme(String theme) {
        boolean isLight = Setting.isLight(view.getContext());
        view.themeTextColor = isLight ? Color.parseColor("#1F2328") : Color.parseColor("#FFE0E0E0");
        view.themeSubTextColor = isLight ? Color.parseColor("#656D76") : Color.parseColor("#FF888888");
        view.initFileManager();
    }

    public StateListDrawable getGreyTouchEffect() {
        StateListDrawable states = new StateListDrawable();
        boolean isLight = Setting.isLight(view.getContext());
        int touchColor = isLight ? Color.parseColor("#E0E0E0") : Color.parseColor("#FF2C2C2C");
        states.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(touchColor));
        states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return states;
    }

    public View createSpecialNavigationRow(final String title, final String subtitle, int iconResId, final boolean switchToProjectMode) {
        boolean isLight = Setting.isLight(view.getContext());
        int accentColor = isLight ? Color.parseColor("#0969DA") : Color.parseColor("#2962FF");
        int iconColor = isLight ? Color.parseColor("#1F2328") : view.themeTextColor;
        int dividerColor = isLight ? Color.parseColor("#E5E7EB") : Color.parseColor("#2C2C2C");

        LinearLayout wrapper = new LinearLayout(view.getContext());
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(view.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(20, 18, 10, 18);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            row.setBackground(getGreyTouchEffect());
        } else {
            row.setBackgroundDrawable(getGreyTouchEffect());
        }

        ImageView icon = new ImageView(view.getContext());
        icon.setLayoutParams(new LinearLayout.LayoutParams((int) view.dpToPx(28), (int) view.dpToPx(28)));
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        if (iconResId != 0) {
            icon.setImageResource(iconResId);
            icon.setColorFilter(switchToProjectMode ? accentColor : iconColor);
        }

        LinearLayout textContainer = new LinearLayout(view.getContext());
        textContainer.setOrientation(LinearLayout.VERTICAL);
        textContainer.setPadding(20, 0, 0, 0);
        textContainer.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView tvTitle = new TextView(view.getContext());
        tvTitle.setText(title);
        tvTitle.setTextSize(15f);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setTextColor(switchToProjectMode ? accentColor : view.themeTextColor);
        textContainer.addView(tvTitle);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView tvSub = new TextView(view.getContext());
            tvSub.setText(subtitle);
            tvSub.setTextSize(12f);
            tvSub.setTextColor(view.themeSubTextColor);
            textContainer.addView(tvSub);
        }

        row.addView(icon);
        row.addView(textContainer);

        View divider = new View(view.getContext());
        divider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) view.dpToPx(1)));
        divider.setBackgroundColor(dividerColor);

        wrapper.addView(row);
        wrapper.addView(divider);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                view.isProjectMode = switchToProjectMode;
                if (view.mainScrollView != null) {
                    view.mainScrollView.scrollTo(0, 0);
                }
                view.initFileManager();
            }
        });

        return wrapper;
    }

    public void loadFilesIntoContainer(final File dir, final LinearLayout container, final int depth) {
        if (depth == 0) {
            view.showLoadingDialog("Loading Folders...");
        }

        // 🔥 FIX: Scroll ki position Background Thread chalu hone se ekdum pehle save ki ja rahi hai!
        final int savedScrollY = (view.mainScrollView != null) ? view.mainScrollView.getScrollY() : 0;

        view.executorService.execute(new Runnable() {
            @Override
            public void run() {
                final File[] filesArr = dir.listFiles();
                if (filesArr == null) {
                    if (depth == 0) view.hideLoadingDialog();
                    return;
                }

                final List<FileItem> filesList = new ArrayList<>();
                for (File f : filesArr) {
                    if (!f.isHidden()) {
                        int count = 0;
                        if (f.isDirectory()) {
                            String[] subs = f.list();
                            count = subs != null ? subs.length : 0;
                        }
                        filesList.add(new FileItem(f, count));
                    }
                }

                Collections.sort(filesList, new Comparator<FileItem>() {
                    @Override
                    public int compare(FileItem f1, FileItem f2) {
                        if (f1.file.isDirectory() && !f2.file.isDirectory()) return -1;
                        if (!f1.file.isDirectory() && f2.file.isDirectory()) return 1;
                        return f1.file.getName().compareToIgnoreCase(f2.file.getName());
                    }
                });

                view.post(new Runnable() {
                    @Override
                    public void run() {
                        if (depth == 0 && view.mainScrollView != null) {
                            // Height lock karni padti hai taki clear hone pe jump na mare
                            container.setMinimumHeight(container.getHeight());
                        }

                        container.removeAllViews();

                        if (depth == 0 && view.currentOpenProjectPath != null && !view.currentOpenProjectPath.isEmpty()) {
                            File activeProj = new File(view.currentOpenProjectPath);
                            if (activeProj.exists() && activeProj.isDirectory()) {
                                if (view.isProjectMode) {
                                    int backIconId = view.getResources().getIdentifier("ic_back_storage", "drawable", view.getContext().getPackageName());
                                    if (backIconId == 0) backIconId = android.R.drawable.ic_menu_revert;
                                    container.addView(createSpecialNavigationRow("Back to Main Storage", "Active: " + activeProj.getName(), backIconId, false));
                                } else {
                                    int projIconId = view.getResources().getIdentifier("ic_active_project", "drawable", view.getContext().getPackageName());
                                    if (projIconId == 0) projIconId = view.getResources().getIdentifier("ic_tree_folder", "drawable", view.getContext().getPackageName());
                                    if (projIconId == 0) projIconId = android.R.drawable.ic_menu_agenda;
                                    container.addView(createSpecialNavigationRow("Go to Active Project", activeProj.getName(), projIconId, true));
                                }
                            }
                        }

                        if (filesList.isEmpty()) {
                            TextView emptyMsg = new TextView(view.getContext());
                            emptyMsg.setText("Folder is empty");
                            emptyMsg.setTextColor(view.themeSubTextColor);
                            emptyMsg.setTextSize(12f);
                            emptyMsg.setPadding(20 + (depth * 45) + 72, 10, 20, 10);
                            container.addView(emptyMsg);
                        } else {
                            for (final FileItem item : filesList) {
                                container.addView(createFileRow(item.file, item.subCount, depth, container));
                            }
                        }

                        if (view.mainScrollView != null) {
                            container.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (depth == 0) container.setMinimumHeight(0);
                                    // 🔥 FIX: Exact usi jagah scroll return hoga bina kisi delay ke
                                    view.mainScrollView.scrollTo(0, savedScrollY);
                                }
                            });
                        }

                        if (depth == 0) {
                            view.hideLoadingDialog();
                        }
                    }
                });
            }
        });
    }

    public int getFileIconId(String fileName) {
    String name = fileName.toLowerCase();
    String iconName = "ic_tree_file";

    if (name.endsWith(".java")) iconName = "ic_file_java";
    else if (name.endsWith(".kt") || name.endsWith(".kts")) iconName = "ic_file_kt";
    else if (name.endsWith(".c") || name.endsWith(".cpp") || name.endsWith(".cc") || name.endsWith(".h") || name.endsWith(".hpp")) iconName = "ic_file_c";
    else if (name.endsWith(".py") || name.endsWith(".pyw")) iconName = "ic_file_py";
    else if (name.endsWith(".html") || name.endsWith(".htm")) iconName = "ic_file_html";
    else if (name.endsWith(".js")) iconName = "ic_file_js";
    else if (name.endsWith(".json")) iconName = "ic_file_json";
    else if (name.endsWith(".xml")) iconName = "ic_file_xml";
    else if (name.endsWith(".gradle")) iconName = "ic_file_gradle";
    else if (name.endsWith(".pro") || name.endsWith(".properties")) iconName = "ic_file_pro";
    else if (name.endsWith(".pdf")) iconName = "ic_file_pdf";
    else if (name.endsWith(".zip") || name.endsWith(".rar") || name.endsWith(".7z") || name.endsWith(".tar") || name.endsWith(".gz")) iconName = "ic_file_zip";
    else if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi") || name.endsWith(".3gp") || name.endsWith(".mov")) iconName = "ic_file_video";
    else if (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".ogg") || name.endsWith(".m4a")) iconName = "ic_file_audio";
    else if (name.endsWith(".apk")) iconName = "ic_file_apk";
    else if (name.endsWith(".jar") || name.endsWith(".class") || name.endsWith(".aar") || name.endsWith(".dex")) iconName = "ic_file_jar";
    else if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp") || name.endsWith(".gif")) iconName = "ic_file_image";
    // 🔥 यहाँ .px के लिए कंडीशन जोड़ें
    else if (name.endsWith(".px")) iconName = "ic_file_px";
    else if (name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".sh") || name.endsWith(".css")) iconName = "ic_file_txt";

    if (iconCache.containsKey(iconName)) return iconCache.get(iconName);

    int resId = view.getResources().getIdentifier(iconName, "drawable", view.getContext().getPackageName());
    if (resId == 0 && iconName.equals("ic_file_zip")) {
        resId = view.getResources().getIdentifier("ic_zip", "drawable", view.getContext().getPackageName());
    }
    if (resId == 0 && iconName.equals("ic_file_jar")) {
        resId = view.getResources().getIdentifier("ic_file_zip", "drawable", view.getContext().getPackageName());
    }
    if (resId == 0) {
        resId = view.getResources().getIdentifier("ic_tree_file", "drawable", view.getContext().getPackageName());
    }
    int finalResId = resId != 0 ? resId : android.R.drawable.ic_menu_sort_by_size;
    iconCache.put(iconName, finalResId);
    return finalResId;
}
    public View createFileRow(final File file, final int subCount, final int depth, final LinearLayout parentContainer) {
        LinearLayout itemWrapper = new LinearLayout(view.getContext());
        itemWrapper.setOrientation(LinearLayout.VERTICAL);
        itemWrapper.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(view.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(20 + (depth * 45), 18, 10, 18);

        boolean isLight = Setting.isLight(view.getContext());
        int activeColor = isLight ? Color.parseColor("#150969DA") : Color.parseColor("#332962FF");

        row.setBackgroundColor(!file.isDirectory() && file.getAbsolutePath().equals(view.activeFilePath) ? activeColor : Color.TRANSPARENT);
        if (row.getBackground() instanceof ColorDrawable && ((ColorDrawable) row.getBackground()).getColor() == Color.TRANSPARENT) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                row.setBackground(getGreyTouchEffect());
            } else {
                row.setBackgroundDrawable(getGreyTouchEffect());
            }
        }

        final ImageView icon = new ImageView(view.getContext());
        icon.setLayoutParams(new LinearLayout.LayoutParams((int) view.dpToPx(36), (int) view.dpToPx(36)));
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);

        if (file.isDirectory()) {
            int resId = view.getResources().getIdentifier("ic_tree_folder", "drawable", view.getContext().getPackageName());
            icon.setImageResource(resId != 0 ? resId : android.R.drawable.ic_menu_today);
        } else {
            icon.setImageResource(getFileIconId(file.getName()));

            String lowerName = file.getName().toLowerCase();
            if (lowerName.endsWith(".png") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".webp")) {
                loadRealImageThumbnail(file, icon);
            } else if (lowerName.endsWith(".apk")) {
                loadRealApkIcon(file, icon);
            }
        }

        LinearLayout textContainer = new LinearLayout(view.getContext());
        textContainer.setOrientation(LinearLayout.VERTICAL);
        textContainer.setPadding(20, 0, 0, 0);
        textContainer.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView tvTitle = new TextView(view.getContext());
        tvTitle.setText(file.getName());
        tvTitle.setTextSize(16f);
        tvTitle.setTextColor(view.themeTextColor);

        TextView tvSubtitle = new TextView(view.getContext());
        tvSubtitle.setTextSize(12f);
        tvSubtitle.setTextColor(view.themeSubTextColor);

        if (file.isDirectory()) {
            tvSubtitle.setText(subCount + " items  -  " + SDF.format(new Date(file.lastModified())));
        } else {
            tvSubtitle.setText(formatSize(file.length()) + "  -  " + SDF.format(new Date(file.lastModified())));
        }

        textContainer.addView(tvTitle);
        textContainer.addView(tvSubtitle);
        row.addView(icon);
        row.addView(textContainer);

        final LinearLayout childContainer = new LinearLayout(view.getContext());
        childContainer.setOrientation(LinearLayout.VERTICAL);
        childContainer.setVisibility(View.GONE);

        itemWrapper.addView(row);
        itemWrapper.addView(childContainer);

        if (file.isDirectory() && view.expandedFolders.contains(file.getAbsolutePath())) {
            childContainer.setVisibility(View.VISIBLE);
            loadFilesIntoContainer(file, childContainer, depth + 1);
        }

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (file.isDirectory()) {
                    boolean isInsideCurrent = (view.currentOpenProjectPath != null && !view.currentOpenProjectPath.isEmpty()
                    && (file.getAbsolutePath().startsWith(view.currentOpenProjectPath + File.separator)
                    || file.getAbsolutePath().equals(view.currentOpenProjectPath)));

                    if (!isInsideCurrent && FilesProjectCreator.isProjectFolder(file)) {
                        view.projectCreator.showOpenProjectDialog(file, childContainer, depth);
                    } else {
                        toggleFolder(childContainer, file, depth);
                    }
                } else {
                    String ext = file.getName().toLowerCase();

                    if (ext.endsWith(".apk")) {
                        Console.installApk(view.getContext(), file.getAbsolutePath());
                    } else if (ext.endsWith(".zip")) {
                        view.fileOperations.extractZipInBackground(file);
                    } else if (view.fileOperations.isImageOrBinaryFile(ext)) {
                        view.showCustomToast("Cannot open media/binary file in editor");
                    } else {
                        view.showLoadingDialog("Opening File...");
                        view.activeFilePath = file.getAbsolutePath();
                        view.prefs.edit().putString("currentFile", view.activeFilePath).apply();

                        view.executorService.execute(new Runnable() {
                            @Override
                            public void run() {
                                view.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        view.initFileManager();
                                        if (view.ideListener != null) view.ideListener.onFileOpened(file);
                                        view.hideLoadingDialog();
                                    }
                                });
                            }
                        });
                    }
                }
            }
        });

        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                view.fileOperations.showPremiumContextMenu(file, parentContainer, childContainer, depth);
                return true;
            }
        });

        return itemWrapper;
    }

    public void loadRealImageThumbnail(final File file, final ImageView imageView) {
        view.executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                    options.inSampleSize = calculateInSampleSize(options, 100, 100);
                    options.inJustDecodeBounds = false;
                    final Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                    if (bitmap != null) {
                        view.post(new Runnable() {
                            @Override
                            public void run() {
                                imageView.setImageBitmap(bitmap);
                            }
                        });
                    }
                } catch (Exception e) {}
            }
        });
    }

    public int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight, width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2, halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    public void loadRealApkIcon(final File file, final ImageView imageView) {
        view.executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    PackageManager pm = view.getContext().getPackageManager();
                    PackageInfo pi = pm.getPackageArchiveInfo(file.getAbsolutePath(), 0);
                    if (pi != null) {
                        pi.applicationInfo.sourceDir = file.getAbsolutePath();
                        pi.applicationInfo.publicSourceDir = file.getAbsolutePath();
                        final Drawable apkIcon = pi.applicationInfo.loadIcon(pm);
                        if (apkIcon != null) {
                            view.post(new Runnable() {
                                @Override
                                public void run() {
                                    imageView.setImageDrawable(apkIcon);
                                }
                            });
                        }
                    }
                } catch (Exception e) {}
            }
        });
    }

    public void toggleFolder(LinearLayout childContainer, File file, int depth) {
        if (childContainer.getVisibility() == View.GONE) {
            view.showLoadingDialog("Loading Folder...");
            view.expandedFolders.add(file.getAbsolutePath());
            view.saveExpandedState();
            if (childContainer.getChildCount() == 0) {
                loadFilesIntoContainer(file, childContainer, depth + 1);
            }
            childContainer.setVisibility(View.VISIBLE);
            view.hideLoadingDialog();
        } else {
            view.expandedFolders.remove(file.getAbsolutePath());
            view.saveExpandedState();
            childContainer.setVisibility(View.GONE);
        }
    }

    public String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        return String.format(Locale.getDefault(), "%.1f %cB", bytes / Math.pow(1024, exp), "KMGTPE".charAt(exp - 1));
    }
}

