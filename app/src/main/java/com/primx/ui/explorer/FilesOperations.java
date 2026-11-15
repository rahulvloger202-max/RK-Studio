package com.primx.ui.explorer;

import com.primx.utils.Setting;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Environment;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class FilesOperations {

    private final Filesview view;

    public FilesOperations(Filesview view) {
        this.view = view;
    }

    public File getTargetDirectory() {
        if (view.activeFilePath != null && !view.activeFilePath.isEmpty()) {
            File activeFile = new File(view.activeFilePath);
            if (activeFile.exists()) {
                return activeFile.getParentFile();
            }
        }
        if (view.currentOpenProjectPath != null && !view.currentOpenProjectPath.isEmpty()) {
            File projectDir = new File(view.currentOpenProjectPath);
            if (projectDir.exists()) {
                return projectDir;
            }
        }
        return view.rootDir != null ? view.rootDir : Environment.getExternalStorageDirectory();
    }

    public void setupFabLogic() {
        if (view.fabMain != null) {
            view.fabMain.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!view.isFabOpen) openFabMenu();
                    else closeFabMenu();
                }
            });
        }
        if (view.dimOverlay != null) {
            view.dimOverlay.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeFabMenu();
                }
            });
        }
        if (view.fabFolderContainer != null) {
            view.fabFolderContainer.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeFabMenu();
                    showCreationDialog("Folder", getTargetDirectory());
                }
            });
        }
        if (view.fabFileContainer != null) {
            view.fabFileContainer.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeFabMenu();
                    showCreationDialog("File", getTargetDirectory());
                }
            });
        }
    }

    public void openFabMenu() {
        view.isFabOpen = true;
        if (view.fabMain != null) view.fabMain.animate().rotation(45).setDuration(200).start();
        if (view.dimOverlay != null) {
            view.dimOverlay.setVisibility(View.VISIBLE);
            view.dimOverlay.setAlpha(0f);
            view.dimOverlay.animate().alpha(1f).setDuration(200).start();
        }
        if (view.fabFolderContainer != null) {
            view.fabFolderContainer.setVisibility(View.VISIBLE);
            view.fabFolderContainer.setTranslationY(0f);
            view.fabFolderContainer.animate().translationY(-view.dpToPx(60)).alpha(1f).setDuration(200).start();
        }
        if (view.fabFileContainer != null) {
            view.fabFileContainer.setVisibility(View.VISIBLE);
            view.fabFileContainer.setTranslationY(0f);
            view.fabFileContainer.animate().translationY(-view.dpToPx(120)).alpha(1f).setDuration(200).start();
        }
    }

    public void closeFabMenu() {
        view.isFabOpen = false;
        if (view.fabMain != null) view.fabMain.animate().rotation(0).setDuration(200).start();
        if (view.dimOverlay != null) {
            view.dimOverlay.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
                @Override
                public void run() {
                    view.dimOverlay.setVisibility(View.GONE);
                }
            }).start();
        }
        if (view.fabFolderContainer != null) {
            view.fabFolderContainer.animate().translationY(0f).alpha(0f).setDuration(200).withEndAction(new Runnable() {
                @Override
                public void run() {
                    view.fabFolderContainer.setVisibility(View.GONE);
                }
            }).start();
        }
        if (view.fabFileContainer != null) {
            view.fabFileContainer.animate().translationY(0f).alpha(0f).setDuration(200).withEndAction(new Runnable() {
                @Override
                public void run() {
                    view.fabFileContainer.setVisibility(View.GONE);
                }
            }).start();
        }
    }

    public void showCreationDialog(final String type, final File targetDirectory) {
        boolean isLight = Setting.isLight(view.getContext());
        int themeStyleId = view.getResources().getIdentifier(isLight ? "Theme.PrimX.Light" : "Theme.PrimX.Dark", "style", view.getContext().getPackageName());
        if (themeStyleId == 0) themeStyleId = android.R.style.Theme_NoTitleBar_Fullscreen;

        final Dialog dialog = new Dialog(view.getContext(), themeStyleId);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        int layoutId = view.getResources().getIdentifier("dialog_create_file_folder", "layout", view.getContext().getPackageName());
        if (layoutId == 0) return;
        dialog.setContentView(layoutId);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            android.view.WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
            lp.dimAmount = 0.75f;
            dialog.getWindow().setAttributes(lp);
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.setCancelable(true);

        int inputId = view.getResources().getIdentifier("inputItemName", "id", view.getContext().getPackageName());
        final EditText inputName = inputId != 0 ? (EditText) dialog.findViewById(inputId) : null;
        if (inputName != null) {
            inputName.setHint(type.equals("File") ? "e.g. main.py or index.html" : "e.g. assets_folder");
        }

        int btnCancelId = view.getResources().getIdentifier("btnCancelItem", "id", view.getContext().getPackageName());
        TextView btnCancel = btnCancelId != 0 ? (TextView) dialog.findViewById(btnCancelId) : null;
        if (btnCancel != null) {
            btnCancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                }
            });
        }

        int btnCreateId = view.getResources().getIdentifier("btnCreateItem", "id", view.getContext().getPackageName());
        TextView btnCreate = btnCreateId != 0 ? (TextView) dialog.findViewById(btnCreateId) : null;
        if (btnCreate != null) {
            btnCreate.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (inputName == null) return;
                    String name = inputName.getText().toString().trim();
                    if (!name.isEmpty()) {
                        File parentDir = targetDirectory != null ? targetDirectory : Environment.getExternalStorageDirectory();
                        if (!parentDir.exists()) parentDir.mkdirs();
                        File newItem = new File(parentDir, name);

                        try {
                            if (type.equals("Folder")) {
                                boolean created = newItem.mkdirs();
                                if (created || newItem.exists()) {
                                    view.expandedFolders.add(parentDir.getAbsolutePath());
                                    view.saveExpandedState();
                                    view.showCustomToast("Folder Created!");
                                } else {
                                    view.showCustomToast("Failed to create folder!");
                                }
                            } else {
                                boolean created = newItem.createNewFile();
                                if (created || newItem.exists()) {
                                    view.showCustomToast("File Created!");
                                    view.activeFilePath = newItem.getAbsolutePath();
                                    view.prefs.edit().putString("currentFile", view.activeFilePath).apply();
                                    if (view.ideListener != null) view.ideListener.onFileOpened(newItem);
                                } else {
                                    view.showCustomToast("Failed to create file!");
                                }
                            }

                            // 🔥 FIX: Scroll jump issue yahan se hat gaya
                            view.initFileManager();

                        } catch (IOException e) {
                            view.showCustomToast("Error creating " + type + ": " + e.getMessage());
                        }
                        dialog.dismiss();
                    }
                }
            });
        }
        dialog.show(); com.primx.utils.UIEffectsManager.applyPremiumBlur(dialog);
    }

    public boolean isImageOrBinaryFile(String name) {
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".gif") ||
                name.endsWith(".bmp") || name.endsWith(".webp") || name.endsWith(".mp3") || name.endsWith(".mp4") ||
                name.endsWith(".avi") || name.endsWith(".mkv") || name.endsWith(".wav") || name.endsWith(".pdf") ||
                name.endsWith(".doc") || name.endsWith(".docx") || name.endsWith(".xls") || name.endsWith(".xlsx") ||
                name.endsWith(".ppt") || name.endsWith(".pptx") || name.endsWith(".so") || name.endsWith(".dex") ||
                name.endsWith(".class") || name.endsWith(".jar") || name.endsWith(".aar") || name.endsWith(".ttf") ||
                name.endsWith(".otf") || name.endsWith(".jks") || name.endsWith(".keystore");
    }

    public void extractZipInBackground(final File zipFile) {
        view.showCustomToast("Extracting " + zipFile.getName() + "...");
        view.executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String baseName = zipFile.getName();
                    if (baseName.toLowerCase().endsWith(".zip")) {
                        baseName = baseName.substring(0, baseName.length() - 4);
                    }
                    final File targetDir = new File(zipFile.getParentFile(), baseName);
                    if (!targetDir.exists()) targetDir.mkdirs();

                    ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)));
                    ZipEntry zipEntry;
                    byte[] buffer = new byte[8192];

                    while ((zipEntry = zis.getNextEntry()) != null) {
                        File newFile = new File(targetDir, zipEntry.getName());
                        if (zipEntry.isDirectory()) {
                            newFile.mkdirs();
                        } else {
                            File parent = newFile.getParentFile();
                            if (parent != null && !parent.exists()) {
                                parent.mkdirs();
                            }
                            FileOutputStream fos = new FileOutputStream(newFile);
                            int len;
                            while ((len = zis.read(buffer)) > 0) {
                                fos.write(buffer, 0, len);
                            }
                            fos.close();
                        }
                        zis.closeEntry();
                    }
                    zis.close();

                    view.post(new Runnable() {
                        @Override
                        public void run() {
                            view.expandedFolders.add(targetDir.getAbsolutePath());
                            if (targetDir.getParentFile() != null) {
                                view.expandedFolders.add(targetDir.getParentFile().getAbsolutePath());
                            }
                            view.saveExpandedState();
                            view.initFileManager();
                            view.showCustomToast("Extracted: " + targetDir.getName());
                        }
                    });
                } catch (final Throwable e) {
                    view.post(new Runnable() {
                        @Override
                        public void run() {
                            view.showCustomToast("Extraction failed: Corrupted Zip.");
                        }
                    });
                }
            }
        });
    }

    public void showPremiumContextMenu(final File targetFile, final LinearLayout parentContainer, final LinearLayout childContainer, final int depth) {
        boolean isLight = Setting.isLight(view.getContext());
        int themeStyleId = view.getResources().getIdentifier(isLight ? "Theme.PrimX.Light" : "Theme.PrimX.Dark", "style", view.getContext().getPackageName());
        if (themeStyleId == 0) themeStyleId = android.R.style.Theme_NoTitleBar_Fullscreen;

        final Dialog dialog = new Dialog(view.getContext(), themeStyleId);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        int resId = view.getResources().getIdentifier("dialog_options_menu", "layout", view.getContext().getPackageName());
        if (resId != 0) {
            dialog.setContentView(resId);

            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                android.view.WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
                lp.dimAmount = 0.75f;
                dialog.getWindow().setAttributes(lp);
                dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            }
            dialog.setCancelable(true);

            int titleId = view.getResources().getIdentifier("tvMenuTitle", "id", view.getContext().getPackageName());
            TextView tvTitle = titleId != 0 ? (TextView) dialog.findViewById(titleId) : null;

            int folderId = view.getResources().getIdentifier("optNewFolder", "id", view.getContext().getPackageName());
            LinearLayout optNewFolder = folderId != 0 ? (LinearLayout) dialog.findViewById(folderId) : null;

            int fileId = view.getResources().getIdentifier("optNewFile", "id", view.getContext().getPackageName());
            LinearLayout optNewFile = fileId != 0 ? (LinearLayout) dialog.findViewById(fileId) : null;

            int renameId = view.getResources().getIdentifier("optRename", "id", view.getContext().getPackageName());
            LinearLayout optRename = renameId != 0 ? (LinearLayout) dialog.findViewById(renameId) : null;

            int deleteId = view.getResources().getIdentifier("optDelete", "id", view.getContext().getPackageName());
            LinearLayout optDelete = deleteId != 0 ? (LinearLayout) dialog.findViewById(deleteId) : null;

            if (tvTitle != null) tvTitle.setText(targetFile.getName());
            if (targetFile.isDirectory()) {
                if (optNewFolder != null) {
                    optNewFolder.setVisibility(View.VISIBLE);
                    optNewFolder.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            dialog.dismiss();
                            showCreationDialog("Folder", targetFile);
                        }
                    });
                }
                if (optNewFile != null) {
                    optNewFile.setVisibility(View.VISIBLE);
                    optNewFile.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            dialog.dismiss();
                            showCreationDialog("File", targetFile);
                        }
                    });
                }
            } else {
                if (optNewFolder != null) optNewFolder.setVisibility(View.GONE);
                if (optNewFile != null) optNewFile.setVisibility(View.GONE);
            }
            if (optRename != null) {
                optRename.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog.dismiss();
                        showRenameDialog(targetFile, parentContainer, depth);
                    }
                });
            }

            if (optDelete != null) {
                optDelete.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog.dismiss();
                        confirmAndDeleteWithProgress(targetFile);
                    }
                });
            }
            dialog.show(); com.primx.utils.UIEffectsManager.applyPremiumBlur(dialog);
        }
    }

    public void confirmAndDeleteWithProgress(final File targetFile) {
        final Dialog progDialog = new Dialog(view.getContext());
        progDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        progDialog.setCancelable(false);

        int layoutId = view.getResources().getIdentifier("dialog_delete_progress", "layout", view.getContext().getPackageName());
        if (layoutId == 0) return;

        progDialog.setContentView(layoutId);

        if (progDialog.getWindow() != null) {
            progDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            android.view.WindowManager.LayoutParams lp = progDialog.getWindow().getAttributes();
            lp.dimAmount = 0.75f;
            progDialog.getWindow().setAttributes(lp);
            progDialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        int progressTextId = view.getResources().getIdentifier("tvDeleteProgressText", "id", view.getContext().getPackageName());
        final TextView tvProgress = progressTextId != 0 ? (TextView) progDialog.findViewById(progressTextId) : null;

        progDialog.show(); com.primx.utils.UIEffectsManager.applyPremiumBlur(progDialog);

        view.executorService.execute(new Runnable() {
            @Override
            public void run() {
                final int totalFiles = countFiles(targetFile);
                final int[] deletedCount = {0};
                final boolean isActiveFileDeleted = !view.activeFilePath.isEmpty() && (view.activeFilePath.equals(targetFile.getAbsolutePath()) || view.activeFilePath.startsWith(targetFile.getAbsolutePath() + File.separator));

                deleteRecursivelyWithProgress(targetFile, totalFiles, deletedCount, tvProgress);

                view.post(new Runnable() {
                    @Override
                    public void run() {
                        progDialog.dismiss();
                        if (view.ideListener != null) {
                            view.ideListener.onFileDeleted(targetFile);
                            if (isActiveFileDeleted) {
                                view.ideListener.onEditorClearRequested();
                                view.activeFilePath = "";
                                view.prefs.edit().putString("currentFile", "").apply();
                            }
                        }
                        view.showCustomToast("Deleted Successfully!");

                        // 🔥 FIX: Scroll jump issue yahan se hat gaya
                        view.initFileManager();
                    }
                });
            }
        });
    }

    public int countFiles(File file) {
        int count = 1;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) count += countFiles(child);
            }
        }
        return count;
    }

    public void deleteRecursivelyWithProgress(File file, final int totalFiles, final int[] deletedCount, final TextView tvProgress) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursivelyWithProgress(child, totalFiles, deletedCount, tvProgress);
                }
            }
        }
        if (file.delete()) {
            deletedCount[0]++;
            final int percent = (int) (((float) deletedCount[0] / totalFiles) * 100);
            view.post(new Runnable() {
                @Override
                public void run() {
                    if (tvProgress != null) {
                        tvProgress.setText(percent + "% (" + deletedCount[0] + "/" + totalFiles + ")");
                    }
                }
            });
        }
    }

    public void showRenameDialog(final File targetFile, final LinearLayout parentContainer, final int depth) {
        boolean isLight = Setting.isLight(view.getContext());
        int themeStyleId = view.getResources().getIdentifier(isLight ? "Theme.PrimX.Light" : "Theme.PrimX.Dark", "style", view.getContext().getPackageName());
        if (themeStyleId == 0) themeStyleId = android.R.style.Theme_NoTitleBar_Fullscreen;

        final Dialog dialog = new Dialog(view.getContext(), themeStyleId);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        int resId = view.getResources().getIdentifier("dialog_rename", "layout", view.getContext().getPackageName());
        if (resId != 0) {
            dialog.setContentView(resId);

            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                android.view.WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
                lp.dimAmount = 0.75f;
                dialog.getWindow().setAttributes(lp);
                dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            }
            dialog.setCancelable(true);

            int inputRenameId = view.getResources().getIdentifier("inputRename", "id", view.getContext().getPackageName());
            final EditText inputRename = inputRenameId != 0 ? (EditText) dialog.findViewById(inputRenameId) : null;
            if (inputRename != null) {
                inputRename.setText(targetFile.getName());
            }

            int cancelId = view.getResources().getIdentifier("btnCancelRename", "id", view.getContext().getPackageName());
            TextView btnCancel = cancelId != 0 ? (TextView) dialog.findViewById(cancelId) : null;
            int confirmId = view.getResources().getIdentifier("btnConfirmRename", "id", view.getContext().getPackageName());
            TextView btnConfirm = confirmId != 0 ? (TextView) dialog.findViewById(confirmId) : null;

            if (btnCancel != null) {
                btnCancel.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog.dismiss();
                    }
                });
            }

            if (btnConfirm != null) {
                btnConfirm.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String newName = inputRename != null ? inputRename.getText().toString().trim() : "";
                        if (!newName.isEmpty() && !newName.equals(targetFile.getName())) {
                            File newFile = new File(targetFile.getParentFile(), newName);
                            if (targetFile.renameTo(newFile)) {
                                view.showCustomToast("Renamed Successfully!");
                                if (targetFile.getAbsolutePath().equals(view.activeFilePath)) {
                                    view.activeFilePath = newFile.getAbsolutePath();
                                    view.prefs.edit().putString("currentFile", view.activeFilePath).apply();
                                    if (view.ideListener != null) view.ideListener.onFileOpened(newFile);
                                }
                                view.initFileManager();
                            } else {
                                view.showCustomToast("Rename Failed!");
                            }
                            dialog.dismiss();
                        }
                    }
                });
            }
            dialog.show(); com.primx.utils.UIEffectsManager.applyPremiumBlur(dialog);
        }
    }
}

