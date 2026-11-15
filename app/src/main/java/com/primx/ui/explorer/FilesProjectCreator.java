package com.primx.ui.explorer;

import com.primx.utils.Setting;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Environment;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class FilesProjectCreator {

    private final Filesview view;

    public FilesProjectCreator(Filesview view) {
        this.view = view;
    }

    public void showCustomCreateProjectDialog(final String projectType) {
        boolean isLight = Setting.isLight(view.getContext());
        int themeStyleId = view.getResources().getIdentifier(isLight ? "Theme.PrimX.Light" : "Theme.PrimX.Dark", "style", view.getContext().getPackageName());
        if (themeStyleId == 0) themeStyleId = android.R.style.Theme_NoTitleBar_Fullscreen;

        final Dialog dialog = new Dialog(view.getContext(), themeStyleId);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        int layoutId = view.getResources().getIdentifier("dialog_create_project", "layout", view.getContext().getPackageName());
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

        int inputNameId = view.getResources().getIdentifier("inputProjectName", "id", view.getContext().getPackageName());
        final EditText inputName = inputNameId != 0 ? (EditText) dialog.findViewById(inputNameId) : null;

        int inputPackageId = view.getResources().getIdentifier("inputPackageName", "id", view.getContext().getPackageName());
        final EditText inputPackage = inputPackageId != 0 ? (EditText) dialog.findViewById(inputPackageId) : null;

        if (inputPackage != null) {
            inputPackage.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    String pkg = s.toString().trim();
                    inputPackage.setTextColor(pkg.isEmpty() ? view.themeTextColor : (isValidPackageName(pkg) ? Color.parseColor("#FF4CAF50") : Color.parseColor("#FFF44336")));
                }
            });
        }

        int btnCancelId = view.getResources().getIdentifier("btnCancelDialog", "id", view.getContext().getPackageName());
        TextView btnCancel = btnCancelId != 0 ? (TextView) dialog.findViewById(btnCancelId) : null;
        if (btnCancel != null) {
            btnCancel.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { dialog.dismiss(); }
            });
        }

        final boolean isConsole = "Java".equals(projectType);
        final boolean isWeb = "Web".equals(projectType);
        final boolean isPython = "Python".equals(projectType);
        final boolean noPackage = isConsole || isWeb || isPython;
        if (noPackage && inputPackage != null) {
            inputPackage.setVisibility(View.GONE);
        }

        int btnCreateId = view.getResources().getIdentifier("btnCreateDialog", "id", view.getContext().getPackageName());
        TextView btnCreate = btnCreateId != 0 ? (TextView) dialog.findViewById(btnCreateId) : null;
        if (btnCreate != null) {
            btnCreate.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (inputName == null) return;
                    String name = inputName.getText().toString().trim();
                    String pkg = (inputPackage != null) ? inputPackage.getText().toString().trim() : "";
                    if (noPackage) pkg = "";
                    if (name.isEmpty() || (!noPackage && pkg.isEmpty())) {
                        view.showCustomToast("Please fill all fields!");
                        return;
                    }
                    if (!noPackage && !isValidPackageName(pkg)) {
                        view.showCustomToast("Invalid Package! Use format: com.example.app");
                        return;
                    }
                    generateProject(name, pkg, projectType);
                    dialog.dismiss();
                }
            });
        }
        dialog.show(); com.primx.utils.UIEffectsManager.applyPremiumBlur(dialog);
    }

    public boolean isValidPackageName(String pkg) {
        return pkg.matches("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$");
    }

    public void generateProject(final String projectName, final String packageName, final String projectType) {
        view.showLoadingDialog("Scaffolding " + projectType + " Project...");
        view.executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File storageRoot = Environment.getExternalStorageDirectory();
                    final File projectDir = new File(storageRoot, projectName);

                    if (projectDir.exists()) {
                        view.post(new Runnable() {
                            @Override public void run() {
                                view.hideLoadingDialog();
                                view.showCustomToast("Project already exists here!");
                            }
                        });
                        return;
                    }

                    if (!projectDir.exists() && !projectDir.mkdirs()) {
                        view.post(new Runnable() {
                            @Override public void run() {
                                view.hideLoadingDialog();
                                view.showCustomToast("Failed to create project root folder! Check Storage Permissions.");
                            }
                        });
                        return;
                    }

                    if ("Python".equals(projectType)) {
                        String pyContent = "#!/usr/bin/env python3\n" +
                        "# PrimX-IDE Python Console\n\n" +
                        "def main():\n" +
                        "    print(\"==============================\")\n" +
                        "    print(\"  Hello from PrimX Python 3!  \")\n" +
                        "    print(\"==============================\")\n\n" +
                        "    name = input(\"Enter your name: \")\n" +
                        "    print(f\"Hello, {name}!\")\n\n" +
                        "if __name__ == '__main__':\n" +
                        "    main()\n";
                        writeToFile(new File(projectDir, "main.py"), pyContent);

                    } else if ("Web".equals(projectType)) {
                        String htmlContent = "<!DOCTYPE html>\n" +
                        "<html lang=\"en\">\n" +
                        "<head>\n" +
                        "    <meta charset=\"UTF-8\">\n" +
                        "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                        "    <title>" + projectName + "</title>\n" +
                        "    <link rel=\"stylesheet\" href=\"style.css\">\n" +
                        "</head>\n" +
                        "<body>\n" +
                        "    <div class=\"card\">\n" +
                        "        <h1>Hello PrimX Web!</h1>\n" +
                        "        <p>Your HTML, CSS, and JS website is ready.</p>\n" +
                        "        <button id=\"btnAction\">Click Me</button>\n" +
                        "        <p id=\"status\"></p>\n" +
                        "    </div>\n" +
                        "    <script src=\"script.js\"></script>\n" +
                        "</body>\n" +
                        "</html>";

                        String cssContent = "* {\n    box-sizing: border-box;\n    margin: 0;\n    padding: 0;\n}\n\n" +
                        "body {\n    font-family: sans-serif;\n    background-color: #121212;\n    color: #ffffff;\n" +
                        "    display: flex;\n    justify-content: center;\n    align-items: center;\n" +
                        "    height: 100vh;\n    text-align: center;\n}\n\n" +
                        ".card {\n    background: #1e1e1e;\n    padding: 30px;\n    border-radius: 12px;\n" +
                        "    box-shadow: 0 4px 20px rgba(0,0,0,0.6);\n}\n\n" +
                        "h1 {\n    color: #2962ff;\n    margin-bottom: 12px;\n}\n\n" +
                        "p {\n    color: #aaaaaa;\n    font-size: 15px;\n    margin-bottom: 15px;\n}\n\n" +
                        "button {\n    padding: 12px 24px;\n    background: #2962ff;\n    color: #ffffff;\n" +
                        "    border: none;\n    border-radius: 8px;\n    font-size: 16px;\n    cursor: pointer;\n}\n\n" +
                        "#status {\n    margin-top: 15px;\n    color: #00e676;\n    font-weight: bold;\n}";

                        String jsContent = "document.getElementById('btnAction').addEventListener('click', function() {\n" +
                        "    document.getElementById('status').innerText = 'JavaScript is working live in PrimX-IDE!';\n" +
                        "});";

                        writeToFile(new File(projectDir, "index.html"), htmlContent);
                        writeToFile(new File(projectDir, "style.css"), cssContent);
                        writeToFile(new File(projectDir, "script.js"), jsContent);

                    } else if ("PX".equals(projectType)) {
                        // 🔥 FIX: PX Program ke liye naya logic
                        File pxDir = new File(projectDir, packageName.replace(".", "/"));
                        if (!pxDir.exists() && !pxDir.mkdirs()) {
                            view.post(new Runnable() {
                                @Override public void run() {
                                    view.hideLoadingDialog();
                                    view.showCustomToast("Failed to create package folders!");
                                }
                            });
                            return;
                        }

                        String pxContent = "// PX Program Entry Point\n" +
                        "package " + packageName + ";\n\n" +
                        "fun main() {\n" +
                        "    print(\"Hello from PrimX PX Program!\");\n" +
                        "}\n";
                        writeToFile(new File(pxDir, "main.px"), pxContent);

                    } else {
                        File appDir = new File(projectDir, "app");
                        File srcMainDir = new File(appDir, "src/main");
                        File javaDir = (packageName == null || packageName.isEmpty()) ? new File(srcMainDir, "java") : new File(srcMainDir, "java/" + packageName.replace(".", "/"));

                        if (!javaDir.exists() && !javaDir.mkdirs()) {
                            view.post(new Runnable() {
                                @Override public void run() {
                                    view.hideLoadingDialog();
                                    view.showCustomToast("Failed to create source folders!");
                                }
                            });
                            return;
                        }

                        writeToFile(new File(projectDir, "build.gradle"), "applicationId \"" + packageName + "\"");

                        if ("Android".equals(projectType) || "AndroidX".equals(projectType)) {
                            File resDir = new File(srcMainDir, "res");
                            File layoutDir = new File(resDir, "layout");
                            layoutDir.mkdirs();
                            File valuesDir = new File(resDir, "values");
                            valuesDir.mkdirs();
                            File mipmapDir = new File(resDir, "mipmap");
                            mipmapDir.mkdirs();

                            writeToFile(new File(mipmapDir, "ic_launcher.xml"), "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" android:width=\"108dp\" android:height=\"108dp\" android:viewportWidth=\"108\" android:viewportHeight=\"108\">\n<path android:fillColor=\"#2962FF\" android:pathData=\"M0,0h108v108h-108z\"/>\n<path android:fillColor=\"#FFFFFF\" android:pathData=\"M54,20A34,34 0,1 0,88 54,34 34,0 0,0 54,20ZM54,80A26,26 0,1 1,80 54,26 26,0 0,1 54,80Z\"/>\n</vector>");
                            writeToFile(new File(projectDir, "settings.gradle"), "include ':app'\nrootProject.name = \"" + projectName + "\"");

                            writeToFile(new File(appDir, "build.gradle"), "apply plugin: 'com.android.application'\n\nandroid {\n    compileSdkVersion 33\n    defaultConfig {\n        applicationId \"" + packageName + "\"\n        minSdkVersion 21\n        targetSdkVersion 33\n        versionCode 1\n        versionName \"1.0\"\n    }\n}\n\ndependencies {\n    // Dependencies yahan add kar sakte hain\n}\n");

                            writeToFile(new File(srcMainDir, "AndroidManifest.xml"), "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n    package=\"" + packageName + "\">\n    <application\n        android:icon=\"@mipmap/ic_launcher\"\n        android:label=\"@string/app_name\"\n        android:theme=\"@style/AppTheme\">\n        <activity\n            android:name=\".MainActivity\"\n            android:exported=\"true\">\n            <intent-filter>\n                <action android:name=\"android.intent.action.MAIN\" />\n                <category android:name=\"android.intent.category.LAUNCHER\" />\n            </intent-filter>\n        </activity>\n    </application>\n</manifest>");

                            String mainActivityContent = "package " + packageName + ";\n\n" +
                            "import android.app.Activity;\n" +
                            "import android.os.Bundle;\n" +
                            "import android.view.View;\n" +
                            "import android.view.Window;\n" +
                            "import android.widget.Button;\n" +
                            "import android.widget.Toast;\n\n" +
                            "public class MainActivity extends Activity {\n" +
                            "    @Override\n" +
                            "    protected void onCreate(Bundle savedInstanceState) {\n" +
                            "        super.onCreate(savedInstanceState);\n" +
                            "        requestWindowFeature(Window.FEATURE_NO_TITLE);\n" +
                            "        setContentView(R.layout.activity_main);\n\n" +
                            "        Button btn = (Button) findViewById(R.id.btnAction);\n" +
                            "        if (btn != null) {\n" +
                            "            btn.setOnClickListener(new View.OnClickListener() {\n" +
                            "                @Override\n" +
                            "                public void onClick(View v) {\n" +
                            "                    Toast.makeText(MainActivity.this, \"Hello from PrimX App!\", Toast.LENGTH_SHORT).show();\n" +
                            "                }\n" +
                            "            });\n" +
                            "        }\n" +
                            "    }\n" +
                            "}";
                            writeToFile(new File(javaDir, "MainActivity.java"), mainActivityContent);

                            String activityMainXml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                            "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                            "    android:layout_width=\"match_parent\"\n" +
                            "    android:layout_height=\"match_parent\"\n" +
                            "    android:orientation=\"vertical\"\n" +
                            "    android:background=\"#1E1E1E\">\n\n" +
                            "    <LinearLayout\n" +
                            "        android:id=\"@+id/toolbar\"\n" +
                            "        android:layout_width=\"match_parent\"\n" +
                            "        android:layout_height=\"56dp\"\n" +
                            "        android:background=\"#141414\"\n" +
                            "        android:gravity=\"center_vertical\"\n" +
                            "        android:paddingStart=\"16dp\"\n" +
                            "        android:paddingEnd=\"16dp\"\n" +
                            "        android:orientation=\"horizontal\">\n\n" +
                            "        <ImageView\n" +
                            "            android:layout_width=\"28dp\"\n" +
                            "            android:layout_height=\"28dp\"\n" +
                            "            android:src=\"@mipmap/ic_launcher\" />\n\n" +
                            "        <TextView\n" +
                            "            android:id=\"@+id/tvToolbarTitle\"\n" +
                            "            android:layout_width=\"0dp\"\n" +
                            "            android:layout_height=\"wrap_content\"\n" +
                            "            android:layout_weight=\"1\"\n" +
                            "            android:layout_marginStart=\"12dp\"\n" +
                            "            android:text=\"@string/app_name\"\n" +
                            "            android:textColor=\"#FFFFFF\"\n" +
                            "            android:textSize=\"18sp\"\n" +
                            "            android:textStyle=\"bold\" />\n\n" +
                            "    </LinearLayout>\n\n" +
                            "    <View\n" +
                            "        android:layout_width=\"match_parent\"\n" +
                            "        android:layout_height=\"1dp\"\n" +
                            "        android:background=\"#2C2C2C\" />\n\n" +
                            "    <FrameLayout\n" +
                            "        android:id=\"@+id/contentContainer\"\n" +
                            "        android:layout_width=\"match_parent\"\n" +
                            "        android:layout_height=\"0dp\"\n" +
                            "        android:layout_weight=\"1\"\n" +
                            "        android:padding=\"20dp\">\n\n" +
                            "        <LinearLayout\n" +
                            "            android:layout_width=\"match_parent\"\n" +
                            "            android:layout_height=\"wrap_content\"\n" +
                            "            android:layout_gravity=\"center\"\n" +
                            "            android:orientation=\"vertical\"\n" +
                            "            android:gravity=\"center\"\n" +
                            "            android:background=\"#252526\"\n" +
                            "            android:padding=\"24dp\">\n\n" +
                            "            <TextView\n" +
                            "                android:id=\"@+id/tvWelcomeTitle\"\n" +
                            "                android:layout_width=\"wrap_content\"\n" +
                            "                android:layout_height=\"wrap_content\"\n" +
                            "                android:text=\"Welcome to PrimX\"\n" +
                            "                android:textColor=\"#2962FF\"\n" +
                            "                android:textSize=\"22sp\"\n" +
                            "                android:textStyle=\"bold\" />\n\n" +
                            "            <TextView\n" +
                            "                android:id=\"@+id/tvWelcomeDesc\"\n" +
                            "                android:layout_width=\"wrap_content\"\n" +
                            "                android:layout_height=\"wrap_content\"\n" +
                            "                android:layout_marginTop=\"8dp\"\n" +
                            "                android:text=\"Your Android application is running clean and full-screen with no title bar.\"\n" +
                            "                android:textColor=\"#AAAAAA\"\n" +
                            "                android:textSize=\"14sp\"\n" +
                            "                android:gravity=\"center\" />\n\n" +
                            "            <Button\n" +
                            "                android:id=\"@+id/btnAction\"\n" +
                            "                android:layout_width=\"wrap_content\"\n" +
                            "                android:layout_height=\"wrap_content\"\n" +
                            "                android:layout_marginTop=\"20dp\"\n" +
                            "                android:text=\"Get Started\"\n" +
                            "                android:background=\"#2962FF\"\n" +
                            "                android:textColor=\"#FFFFFF\"\n" +
                            "                android:paddingStart=\"24dp\"\n" +
                            "                android:paddingEnd=\"24dp\" />\n\n" +
                            "        </LinearLayout>\n\n" +
                            "    </FrameLayout>\n\n" +
                            "    <View\n" +
                            "        android:layout_width=\"match_parent\"\n" +
                            "        android:layout_height=\"1dp\"\n" +
                            "        android:background=\"#2C2C2C\" />\n\n" +
                            "    <LinearLayout\n" +
                            "        android:id=\"@+id/bottomNavBar\"\n" +
                            "        android:layout_width=\"match_parent\"\n" +
                            "        android:layout_height=\"54dp\"\n" +
                            "        android:background=\"#141414\"\n" +
                            "        android:gravity=\"center_vertical\"\n" +
                            "        android:orientation=\"horizontal\">\n\n" +
                            "        <TextView\n" +
                            "            android:id=\"@+id/navHome\"\n" +
                            "            android:layout_width=\"0dp\"\n" +
                            "            android:layout_height=\"match_parent\"\n" +
                            "            android:layout_weight=\"1\"\n" +
                            "            android:gravity=\"center\"\n" +
                            "            android:text=\"Home\"\n" +
                            "            android:textColor=\"#2962FF\"\n" +
                            "            android:textSize=\"13sp\"\n" +
                            "            android:textStyle=\"bold\" />\n\n" +
                            "        <TextView\n" +
                            "            android:id=\"@+id/navExplore\"\n" +
                            "            android:layout_width=\"0dp\"\n" +
                            "            android:layout_height=\"match_parent\"\n" +
                            "            android:layout_weight=\"1\"\n" +
                            "            android:gravity=\"center\"\n" +
                            "            android:text=\"Explore\"\n" +
                            "            android:textColor=\"#888888\"\n" +
                            "            android:textSize=\"13sp\" />\n\n" +
                            "        <TextView\n" +
                            "            android:id=\"@+id/navSettings\"\n" +
                            "            android:layout_width=\"0dp\"\n" +
                            "            android:layout_height=\"match_parent\"\n" +
                            "            android:layout_weight=\"1\"\n" +
                            "            android:gravity=\"center\"\n" +
                            "            android:text=\"Settings\"\n" +
                            "            android:textColor=\"#888888\"\n" +
                            "            android:textSize=\"13sp\" />\n\n" +
                            "    </LinearLayout>\n\n" +
                            "</LinearLayout>";
                            writeToFile(new File(layoutDir, "activity_main.xml"), activityMainXml);

                            writeToFile(new File(valuesDir, "strings.xml"), "<resources>\n    <string name=\"app_name\">" + projectName + "</string>\n</resources>");

                            String stylesXml = "<resources>\n" +
                            "    <style name=\"AppTheme\" parent=\"@android:style/Theme.Material.NoActionBar\">\n" +
                            "        <item name=\"android:windowNoTitle\">true</item>\n" +
                            "        <item name=\"android:windowActionBar\">false</item>\n" +
                            "        <item name=\"android:colorPrimary\">#1E1E1E</item>\n" +
                            "        <item name=\"android:colorPrimaryDark\">#141414</item>\n" +
                            "        <item name=\"android:colorAccent\">#2962FF</item>\n" +
                            "        <item name=\"android:statusBarColor\">#141414</item>\n" +
                            "        <item name=\"android:navigationBarColor\">#141414</item>\n" +
                            "        <item name=\"android:windowBackground\">#1E1E1E</item>\n" +
                            "    </style>\n" +
                            "</resources>";
                            writeToFile(new File(valuesDir, "styles.xml"), stylesXml);

                            File genDir = new File(projectDir, "build/gen/" + packageName.replace(".", "/"));
                            genDir.mkdirs();
                            String dummyR = "package " + packageName + ";\n" +
                            "public final class R {\n" +
                            "    public static final class layout { public static int activity_main = 1; }\n" +
                            "    public static final class string { public static int app_name = 1; }\n" +
                            "    public static final class mipmap { public static int ic_launcher = 1; }\n" +
                            "    public static final class style { public static int AppTheme = 1; }\n" +
                            "    public static final class id { public static int toolbar = 1; public static int tvToolbarTitle = 2; public static int contentContainer = 3; public static int tvWelcomeTitle = 4; public static int tvWelcomeDesc = 5; public static int btnAction = 6; public static int bottomNavBar = 7; public static int navHome = 8; public static int navExplore = 9; public static int navSettings = 10; }\n" +
                            "    public static final class drawable {}\n" +
                            "}";
                            writeToFile(new File(genDir, "R.java"), dummyR);
                        } else {
                            String pkgLine = (packageName == null || packageName.trim().isEmpty()) ? "" : "package " + packageName + ";\n\n";
                            String mainClassContent = pkgLine +
                            "import java.util.Scanner;\n\n" +
                            "public class Main {\n" +
                            "    public static void main(String[] args) {\n" +
                            "        Scanner sc = new Scanner(System.in);\n" +
                            "        System.out.println(\"PrimX Java Console Ready!\");\n" +
                            "        System.out.println(\"Enter your name:\");\n" +
                            "        String name = sc.nextLine();\n" +
                            "        System.out.println(\"Hello, \" + name + \"!\");\n" +
                            "    }\n" +
                            "}";
                            writeToFile(new File(javaDir, "Main.java"), mainClassContent);
                        }
                    }

                    view.post(new Runnable() {
                        @Override public void run() {
                            view.hideLoadingDialog();
                            openProject(projectDir);
                        }
                    });
                } catch (final Exception e) {
                    view.post(new Runnable() {
                        @Override public void run() {
                            view.hideLoadingDialog();
                            view.showCustomToast("Error generating project: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    public void writeToFile(File file, String content) throws IOException {
        if (file.getParentFile() != null && !file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        FileWriter writer = new FileWriter(file);
        writer.write(content);
        writer.flush();
        writer.close();
    }

    public static String detectProjectType(File dir) {
        if (dir == null || !dir.isDirectory()) return null;

        String name = dir.getName();
        if (name.equals("app") || name.equals("src") || name.equals("main") ||
        name.equals("java") || name.equals("res") || name.equals("layout") ||
        name.equals("values") || name.equals("mipmap") || name.equals("build") ||
        name.equals("bin") || name.equals("gen") || name.equals(".git") ||
        name.equals(".idea") || name.equals("__pycache__")) {
            return null;
        }

        File parent = dir.getParentFile();
        while (parent != null) {
            if (new File(parent, "build.gradle").exists() ||
            new File(parent, "AndroidManifest.xml").exists() ||
            new File(parent, "app").exists() ||
            new File(parent, "src").exists()) {
                return null;
            }
            String pPath = parent.getAbsolutePath();
            if (pPath.equals("/storage/emulated/0") || pPath.equals("/sdcard") || pPath.equals("/")) break;
            parent = parent.getParentFile();
        }

        boolean hasGradle = new File(dir, "build.gradle").exists() || new File(dir, "settings.gradle").exists() || new File(dir, "build.gradle.kts").exists();
        boolean hasAppFolder = new File(dir, "app").exists() && (new File(dir, "app/build.gradle").exists() || new File(dir, "app/src").exists());
        boolean isLegacyAIDE = new File(dir, "AndroidManifest.xml").exists() && new File(dir, "src").exists();
        if (hasGradle || hasAppFolder || isLegacyAIDE) return "Android";

        if (new File(dir, "main.py").exists() || new File(dir, "requirements.txt").exists()) return "Python";
        if (new File(dir, "index.html").exists() || new File(dir, "index.htm").exists()) return "Web";
        if (new File(dir, "Main.java").exists() || new File(dir, "src/Main.java").exists()) return "Java";

        return null;
    }

    public static boolean isProjectFolder(File dir) {
        return detectProjectType(dir) != null;
    }

    public void showOpenProjectDialog(final File projectFile, final LinearLayout childContainer, final int depth) {
        boolean isLight = Setting.isLight(view.getContext());
        int themeStyleId = view.getResources().getIdentifier(isLight ? "Theme.PrimX.Light" : "Theme.PrimX.Dark", "style", view.getContext().getPackageName());
        if (themeStyleId == 0) themeStyleId = android.R.style.Theme_NoTitleBar_Fullscreen;

        final Dialog dialog = new Dialog(view.getContext(), themeStyleId);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        int resId = view.getResources().getIdentifier("layout_custom_dialog", "layout", view.getContext().getPackageName());
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

            int titleId = view.getResources().getIdentifier("tvDialogTitle", "id", view.getContext().getPackageName());
            TextView tvTitle = titleId != 0 ? (TextView) dialog.findViewById(titleId) : null;

            int msgId = view.getResources().getIdentifier("tvDialogMessage", "id", view.getContext().getPackageName());
            TextView tvMessage = msgId != 0 ? (TextView) dialog.findViewById(msgId) : null;

            int cancelId = view.getResources().getIdentifier("btnCancel", "id", view.getContext().getPackageName());
            TextView btnCancel = cancelId != 0 ? (TextView) dialog.findViewById(cancelId) : null;

            int openId = view.getResources().getIdentifier("btnOpen", "id", view.getContext().getPackageName());
            TextView btnOpen = openId != 0 ? (TextView) dialog.findViewById(openId) : null;

            String pType = detectProjectType(projectFile);
            String typeLabel = pType != null ? pType + " Project" : "Project";
            if (tvTitle != null) tvTitle.setText("Open " + typeLabel);
            if (tvMessage != null) tvMessage.setText("Do you want to open '" + projectFile.getName() + "' project?");
            if (btnCancel != null) {
                btnCancel.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { dialog.dismiss(); }
                });
            }
            if (btnOpen != null) {
                btnOpen.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openProject(projectFile);
                        view.treeStyle.toggleFolder(childContainer, projectFile, depth);
                        dialog.dismiss();
                    }
                });
            }
            dialog.show(); com.primx.utils.UIEffectsManager.applyPremiumBlur(dialog);
        } else {
            openProject(projectFile);
            view.treeStyle.toggleFolder(childContainer, projectFile, depth);
        }
    }

    // 🔥 FIXED: build cancel होगा जब नया प्रोजेक्ट खुलेगा
    public void openProject(final File projectDir) {
        // पुराना build तुरंत रोक दो
        try {
            if (com.primx.compiler.Compiler.isCompiling) {
                com.primx.compiler.Compiler.cancelCurrentBuild();
            }
        } catch (Throwable ignored) {}

        view.showLoadingDialog("Opening Project...");

        view.executorService.execute(new Runnable() {
            @Override
            public void run() {
                view.currentOpenProjectPath = projectDir.getAbsolutePath();
                view.isProjectMode = true;
                view.prefs.edit()
                .putString("projectPath", view.currentOpenProjectPath)
                .putString("saved_errors", "")
                .apply();

                view.expandedFolders.clear();
                view.expandedFolders.add(projectDir.getAbsolutePath());
                File appDir = new File(projectDir, "app"), srcMainDir = new File(appDir, "src/main");
                if (srcMainDir.exists()) {
                    view.expandedFolders.add(appDir.getAbsolutePath());
                    view.expandedFolders.add(srcMainDir.getAbsolutePath());
                    view.expandedFolders.add(new File(srcMainDir, "java").getAbsolutePath());
                    view.expandedFolders.add(new File(srcMainDir, "res/layout").getAbsolutePath());
                    view.expandedFolders.add(new File(srcMainDir, "res/values").getAbsolutePath());
                }
                view.saveExpandedState();

                final List<File> targetFiles = new ArrayList<>();
                findTargetFiles(projectDir, targetFiles, 0);
                Collections.sort(targetFiles, new Comparator<File>() {
                    @Override
                    public int compare(File f1, File f2) {
                        return getFileWeight(f2.getName()) - getFileWeight(f1.getName());
                    }
                });
                final File primaryFile = !targetFiles.isEmpty() ? targetFiles.get(0) : null;

                view.post(new Runnable() {
                    @Override
                    public void run() {
                        if (view.ideListener != null) {
                            view.ideListener.onEditorClearRequested();
                            view.ideListener.onProjectOpened(projectDir);
                        }
                        view.showCustomToast("Project Opened: " + projectDir.getName());
                        if (primaryFile != null) {
                            view.activeFilePath = primaryFile.getAbsolutePath();
                            view.prefs.edit().putString("currentFile", view.activeFilePath).apply();
                            if (view.ideListener != null) view.ideListener.onFileOpened(primaryFile);
                        }
                        view.initFileManager();
                        view.hideLoadingDialog();
                    }
                });
            }
        });
    }

    public void findTargetFiles(File dir, List<File> targetFiles, int depth) {
        if (dir == null || depth > 5 || targetFiles.size() >= 5) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    String n = f.getName().toLowerCase();
                    if (n.equals("build") || n.equals("bin") || n.equals("gen") || n.equals(".git")
                    || n.equals(".gradle") || n.equals("primx_libs") || n.equals("node_modules")
                    || n.equals("cache")) {
                        continue;
                    }
                    findTargetFiles(f, targetFiles, depth + 1);
                } else {
                    String name = f.getName();
                    if (name.equals("MainActivity.java") || name.equals("Main.java")
                    || name.equals("activity_main.xml") || name.equals("main.py")
                    || name.equals("index.html") || name.equals("AndroidManifest.xml")
                    || name.equals("main.px")) { // 🔥 main.px add kiya gaya
                        targetFiles.add(f);
                    }
                }
            }
        }
    }

    public int getFileWeight(String name) {
        if (name.equals("MainActivity.java")) return 100;
        if (name.equals("Main.java")) return 95;
        if (name.equals("main.px")) return 92; // 🔥 main.px ka weight add kiya gaya
        if (name.equals("main.py")) return 90;
        if (name.equals("index.html")) return 85;
        if (name.equals("activity_main.xml")) return 80;
        if (name.equals("AndroidManifest.xml")) return 50;
        return 10;
    }
}