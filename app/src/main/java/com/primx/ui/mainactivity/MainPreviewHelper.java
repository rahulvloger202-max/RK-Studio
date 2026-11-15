package com.primx.ui.mainactivity;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.primx.utils.xml_preview.XmlPreviewEngine;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainPreviewHelper {

    private MainActivity activity;

    public MainPreviewHelper(MainActivity activity) {
        this.activity = activity;
    }

    // =========================================================================
    // 🔥 ANDROID MANIFEST PERMISSIONS MANAGER (PRO FEATURE)
    // =========================================================================
    
    public void showPermissionManager(final File manifestFile) {
        if (manifestFile == null || !manifestFile.exists()) return;

        final Dialog dialog = new Dialog(activity, android.R.style.Theme_NoTitleBar_Fullscreen);
        
        int bgColor = activity.resolveThemeColor("ide_bg_color");
        if (bgColor == 0) bgColor = Color.parseColor("#1E1E1E");
        
        int textColor = activity.resolveThemeColor("ide_text_primary");
        if (textColor == 0) textColor = Color.WHITE;

        int cardColor = activity.resolveThemeColor("ide_dialog_bg_color");
        if (cardColor == 0) cardColor = Color.parseColor("#2D2D2D");

        int accentColor = Color.parseColor("#3574F0"); // Premium Blue

        RelativeLayout rootLayout = new RelativeLayout(activity);
        rootLayout.setBackgroundColor(bgColor);

        // 1. Toolbar / Header
        LinearLayout toolbar = new LinearLayout(activity);
        toolbar.setId(View.generateViewId());
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setBackgroundColor(cardColor);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) toolbar.setElevation(8f);
        int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
        toolbar.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(activity);
        title.setText("Permissions Manager");
        title.setTextColor(textColor);
        title.setTextSize(18f);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        toolbar.addView(title, titleParams);

        TextView btnClose = new TextView(activity);
        btnClose.setText("X");
        btnClose.setTextColor(Color.WHITE);
        btnClose.setTextSize(14f);
        btnClose.setTypeface(null, Typeface.BOLD);
        btnClose.setGravity(Gravity.CENTER);
        GradientDrawable redCircle = new GradientDrawable();
        redCircle.setShape(GradientDrawable.OVAL);
        redCircle.setColor(Color.parseColor("#E74C3C"));
        btnClose.setBackground(redCircle);
        int closeSize = (int)(30 * activity.getResources().getDisplayMetrics().density);
        btnClose.setLayoutParams(new LinearLayout.LayoutParams(closeSize, closeSize));
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        toolbar.addView(btnClose);

        RelativeLayout.LayoutParams toolbarParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toolbarParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        rootLayout.addView(toolbar, toolbarParams);

        // 2. Scrollable Content Area (Existing Permissions)
        ScrollView scrollView = new ScrollView(activity);
        RelativeLayout.LayoutParams scrollParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        scrollParams.addRule(RelativeLayout.BELOW, toolbar.getId());
        
        LinearLayout contentLayout = new LinearLayout(activity);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setPadding(pad, pad, pad, (int)(100 * activity.getResources().getDisplayMetrics().density));

        TextView tvSub = new TextView(activity);
        tvSub.setText("Active Permissions (Tap to remove):");
        tvSub.setTextColor(Color.GRAY);
        tvSub.setPadding(0, 0, 0, pad);
        contentLayout.addView(tvSub);

        // FlowLayout for Chips
        FlowLayout flowLayout = new FlowLayout(activity);
        String xmlContent = activity.codeCanvas.getText();
        List<String> existingPerms = extractPermissions(xmlContent);

        if (existingPerms.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("No permissions added yet.");
            empty.setTextColor(textColor);
            contentLayout.addView(empty);
        } else {
            for (final String perm : existingPerms) {
                TextView chip = new TextView(activity);
                chip.setText(perm.replace("android.permission.", "") + "  ✕");
                chip.setTextColor(Color.WHITE);
                chip.setTextSize(12f);
                chip.setPadding((int)(12 * activity.getResources().getDisplayMetrics().density), (int)(8 * activity.getResources().getDisplayMetrics().density), (int)(12 * activity.getResources().getDisplayMetrics().density), (int)(8 * activity.getResources().getDisplayMetrics().density));
                
                GradientDrawable chipBg = new GradientDrawable();
                chipBg.setColor(Color.parseColor("#4CAF50")); // Green for active
                chipBg.setCornerRadius(50f);
                chip.setBackground(chipBg);
                
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        removePermissionFromManifest(perm);
                        dialog.dismiss();
                        showPermissionManager(manifestFile); // Reload
                    }
                });
                flowLayout.addView(chip);
            }
            contentLayout.addView(flowLayout);
        }
        
        scrollView.addView(contentLayout);
        rootLayout.addView(scrollView, scrollParams);

        // 3. Floating Action Button (+)
        TextView fab = new TextView(activity);
        fab.setText("+");
        fab.setTextColor(Color.WHITE);
        fab.setTextSize(24f);
        fab.setTypeface(null, Typeface.BOLD);
        fab.setGravity(Gravity.CENTER);
        
        GradientDrawable fabBg = new GradientDrawable();
        fabBg.setShape(GradientDrawable.OVAL);
        fabBg.setColor(accentColor);
        fab.setBackground(fabBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) fab.setElevation(12f);

        int fabSize = (int)(60 * activity.getResources().getDisplayMetrics().density);
        RelativeLayout.LayoutParams fabParams = new RelativeLayout.LayoutParams(fabSize, fabSize);
        fabParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        fabParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        fabParams.setMargins(0, 0, pad, pad);
        
        fab.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showAddPermissionDialog(manifestFile, dialog);
            }
        });
        rootLayout.addView(fab, fabParams);

        dialog.setContentView(rootLayout);
        dialog.show();
    }

    private void showAddPermissionDialog(final File manifestFile, final Dialog parentDialog) {
        final Dialog addDialog = new Dialog(activity);
        addDialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (addDialog.getWindow() != null) {
            // पारदर्शी बैकग्राउंड ताकि हमारे गोल कोने (rounded corners) साफ दिखें
            addDialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            // स्क्रीन की चौड़ाई का 95% और ऊंचाई का 85% लेगा (बड़ा डायलॉग)
            int w = (int)(activity.getResources().getDisplayMetrics().widthPixels * 0.95);
            int h = (int)(activity.getResources().getDisplayMetrics().heightPixels * 0.85);
            addDialog.getWindow().setLayout(w, h);
        }
        
        float density = activity.getResources().getDisplayMetrics().density;
        int cardColor = activity.resolveThemeColor("ide_dialog_bg_color");
        if (cardColor == 0) cardColor = Color.parseColor("#2D2D2D");
        int bgColor = activity.resolveThemeColor("ide_bg_color");
        if (bgColor == 0) bgColor = Color.parseColor("#1E1E1E");
        int textColor = activity.resolveThemeColor("ide_text_primary");
        if (textColor == 0) textColor = Color.WHITE;
        int accentColor = Color.parseColor("#3574F0");

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(cardColor);
        rootBg.setCornerRadius(24 * density); // शानदार गोल कोने
        root.setBackground(rootBg);
        int pad = (int) (16 * density);
        root.setPadding(pad, pad, pad, pad);

        // Header Title
        TextView title = new TextView(activity);
        title.setText("All Permissions");
        title.setTextColor(textColor);
        title.setTextSize(20f);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, 0, 0, pad);
        root.addView(title);

        // Custom Permission Button (शानदार UI के साथ)
        TextView btnCustom = new TextView(activity);
        btnCustom.setText("+ Create Custom Permission");
        btnCustom.setTextColor(Color.WHITE);
        btnCustom.setTextSize(14f);
        btnCustom.setTypeface(null, Typeface.BOLD);
        btnCustom.setGravity(Gravity.CENTER);
        btnCustom.setPadding(0, (int)(12*density), 0, (int)(12*density));
        GradientDrawable customBg = new GradientDrawable();
        customBg.setColor(accentColor);
        customBg.setCornerRadius(12 * density);
        btnCustom.setBackground(customBg);
        LinearLayout.LayoutParams customParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        customParams.setMargins(0, 0, 0, pad);
        btnCustom.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showCustomPermissionDialog(manifestFile, parentDialog, addDialog);
            }
        });
        root.addView(btnCustom, customParams);

        // Search Box (गोल कोनों वाला)
        final EditText searchBox = new EditText(activity);
        searchBox.setHint("Search permissions...");
        searchBox.setHintTextColor(Color.GRAY);
        searchBox.setTextColor(textColor);
        searchBox.setSingleLine(true);
        searchBox.setPadding((int)(16*density), (int)(10*density), (int)(16*density), (int)(10*density));
        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setColor(bgColor);
        searchBg.setCornerRadius(12 * density);
        searchBox.setBackground(searchBg);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchParams.setMargins(0, 0, 0, pad);
        root.addView(searchBox, searchParams);

        // Comprehensive Vertical List
        ScrollView scroll = new ScrollView(activity);
        final LinearLayout listContainer = new LinearLayout(activity);
        listContainer.setOrientation(LinearLayout.VERTICAL);

        String[] common = {
            "INTERNET", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE", "CHANGE_WIFI_STATE",
            "CAMERA", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE",
            "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION",
            "RECORD_AUDIO", "MODIFY_AUDIO_SETTINGS",
            "BLUETOOTH", "BLUETOOTH_ADMIN", "BLUETOOTH_CONNECT", "BLUETOOTH_SCAN", "BLUETOOTH_ADVERTISE",
            "READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS",
            "READ_CALENDAR", "WRITE_CALENDAR",
            "CALL_PHONE", "READ_PHONE_STATE", "READ_CALL_LOG", "WRITE_CALL_LOG",
            "SEND_SMS", "RECEIVE_SMS", "READ_SMS",
            "VIBRATE", "WAKE_LOCK", "FOREGROUND_SERVICE", "SYSTEM_ALERT_WINDOW",
            "RECEIVE_BOOT_COMPLETED", "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            "REQUEST_INSTALL_PACKAGES", "USE_BIOMETRIC", "USE_FINGERPRINT",
            "POST_NOTIFICATIONS", "WRITE_SETTINGS", "NFC", "FLASHLIGHT"
        };

        for (final String c : common) {
            final TextView item = new TextView(activity);
            item.setText(c);
            item.setTextColor(textColor);
            item.setTextSize(14f);
            item.setPadding(pad, (int)(14*density), pad, (int)(14*density));
            
            // Bottom Border (हर परमिशन के नीचे एक हल्की सी लाइन)
            GradientDrawable itemBg = new GradientDrawable();
            itemBg.setColor(Color.TRANSPARENT);
            itemBg.setStroke(1, Color.parseColor("#22888888"));
            item.setBackground(itemBg);

            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    addPermissionToManifest("android.permission." + c);
                    addDialog.dismiss();
                    parentDialog.dismiss();
                    showPermissionManager(manifestFile);
                }
            });
            listContainer.addView(item);
        }
        scroll.addView(listContainer);
        root.addView(scroll);

        // Search Logic (लाइव फ़िल्टरिंग)
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                String query = s.toString().toLowerCase();
                for (int i = 0; i < listContainer.getChildCount(); i++) {
                    View v = listContainer.getChildAt(i);
                    if (v instanceof TextView) {
                        String text = ((TextView)v).getText().toString().toLowerCase();
                        v.setVisibility(text.contains(query) ? View.VISIBLE : View.GONE);
                    }
                }
            }
        });

        addDialog.setContentView(root);
        addDialog.show();
    }

    private void showCustomPermissionDialog(final File manifestFile, final Dialog mainDialog, final Dialog listDialog) {
        final Dialog customDialog = new Dialog(activity);
        customDialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (customDialog.getWindow() != null) {
            customDialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }

        float density = activity.getResources().getDisplayMetrics().density;
        int cardColor = activity.resolveThemeColor("ide_dialog_bg_color");
        if (cardColor == 0) cardColor = Color.parseColor("#2D2D2D");
        int bgColor = activity.resolveThemeColor("ide_bg_color");
        if (bgColor == 0) bgColor = Color.parseColor("#1E1E1E");
        int textColor = activity.resolveThemeColor("ide_text_primary");
        if (textColor == 0) textColor = Color.WHITE;

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(cardColor);
        rootBg.setCornerRadius(20 * density);
        root.setBackground(rootBg);
        int pad = (int) (20 * density);
        root.setPadding(pad, pad, pad, pad);
        root.setLayoutParams(new ViewGroup.LayoutParams((int)(320 * density), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(activity);
        title.setText("New Custom Permission");
        title.setTextColor(textColor);
        title.setTextSize(18f);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        final EditText input = new EditText(activity);
        input.setHint("e.g. com.myapp.PERMISSION");
        input.setHintTextColor(Color.GRAY);
        input.setTextColor(textColor);
        input.setSingleLine(true);
        input.setPadding((int)(12*density), (int)(12*density), (int)(12*density), (int)(12*density));
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(bgColor);
        inputBg.setCornerRadius(10 * density);
        input.setBackground(inputBg);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputParams.setMargins(0, pad, 0, pad);
        root.addView(input, inputParams);

        TextView btnAdd = new TextView(activity);
        btnAdd.setText("ADD PERMISSION");
        btnAdd.setTextColor(Color.WHITE);
        btnAdd.setGravity(Gravity.CENTER);
        btnAdd.setPadding(0, (int)(12*density), 0, (int)(12*density));
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.parseColor("#3574F0"));
        btnBg.setCornerRadius(10 * density);
        btnAdd.setBackground(btnBg);
        btnAdd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String p = input.getText().toString().trim();
                if (!p.isEmpty()) {
                    addPermissionToManifest(p);
                    customDialog.dismiss();
                    listDialog.dismiss();
                    mainDialog.dismiss();
                    showPermissionManager(manifestFile);
                }
            }
        });
        root.addView(btnAdd);

        customDialog.setContentView(root);
        customDialog.show();
    }

    private List<String> extractPermissions(String xml) {
        List<String> list = new ArrayList<>();
        if (xml == null) return list;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("<uses-permission[^>]*android:name\\s*=\\s*\"([^\"]+)\"");
        java.util.regex.Matcher m = p.matcher(xml);
        while (m.find()) {
            list.add(m.group(1));
        }
        return list;
    }

    private void addPermissionToManifest(String permName) {
        String xml = activity.codeCanvas.getText();
        if (xml.contains("\"" + permName + "\"")) {
            activity.showCustomToast("Permission already exists!");
            return;
        }
        
        String tag = "\n    <uses-permission android:name=\"" + permName + "\" />";
        int insertIndex = -1;
        
        int lastPerm = xml.lastIndexOf("</uses-permission>");
        if (lastPerm != -1) insertIndex = xml.indexOf(">", lastPerm) + 1;
        else {
            lastPerm = xml.lastIndexOf("<uses-permission");
            if (lastPerm != -1) insertIndex = xml.indexOf(">", lastPerm) + 1;
        }
        
        if (insertIndex == -1) insertIndex = xml.indexOf("<application");
        if (insertIndex == -1) insertIndex = xml.indexOf(">", xml.indexOf("<manifest")) + 1;
        
        if (insertIndex != -1) {
            xml = xml.substring(0, insertIndex) + tag + xml.substring(insertIndex);
            activity.codeCanvas.setText(xml);
            if (activity.fileManager != null) activity.fileManager.saveCurrentFile(false);
            activity.showCustomToast("Added: " + permName);
        }
    }

    private void removePermissionFromManifest(String permName) {
        String xml = activity.codeCanvas.getText();
        String regex = "\\s*<uses-permission[^>]*android:name\\s*=\\s*\"" + permName + "\"[^>]*>\\s*(</uses-permission>)?";
        xml = xml.replaceAll(regex, "");
        activity.codeCanvas.setText(xml);
        if (activity.fileManager != null) activity.fileManager.saveCurrentFile(false);
        activity.showCustomToast("Removed: " + permName);
    }


    // 🚀 Custom FlowLayout for Auto-wrapping Chips
    private class FlowLayout extends ViewGroup {
        int padH, padV;
        public FlowLayout(Context context) {
            super(context);
            padH = (int)(8 * context.getResources().getDisplayMetrics().density);
            padV = (int)(8 * context.getResources().getDisplayMetrics().density);
        }
        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
            int height = MeasureSpec.getSize(heightMeasureSpec) - getPaddingTop() - getPaddingBottom();
            int count = getChildCount();
            int xpos = getPaddingLeft();
            int ypos = getPaddingTop();
            int childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            int line_height = 0;
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() != GONE) {
                    child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), childHeightMeasureSpec);
                    int childw = child.getMeasuredWidth();
                    line_height = Math.max(line_height, child.getMeasuredHeight() + padV);
                    if (xpos + childw > width) {
                        xpos = getPaddingLeft();
                        ypos += line_height;
                    }
                    xpos += childw + padH;
                }
            }
            if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) height = ypos + line_height;
            else if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST) height = Math.min(height, ypos + line_height);
            setMeasuredDimension(width, height);
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int count = getChildCount();
            int width = r - l;
            int xpos = getPaddingLeft();
            int ypos = getPaddingTop();
            int line_height = 0;
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() != GONE) {
                    int childw = child.getMeasuredWidth();
                    int childh = child.getMeasuredHeight();
                    line_height = Math.max(line_height, childh + padV);
                    if (xpos + childw > width) {
                        xpos = getPaddingLeft();
                        ypos += line_height;
                    }
                    child.layout(xpos, ypos, xpos + childw, ypos + childh);
                    xpos += childw + padH;
                }
            }
        }
    }


    // =========================================================================
    // 🔥 EXISTING PREVIEW METHODS (DO NOT REMOVE)
    // =========================================================================

    public void showXmlPreview(final File xmlFile) {
        if (xmlFile == null || !xmlFile.exists()) return;

        final Dialog previewDialog = new Dialog(activity, android.R.style.Theme_NoTitleBar_Fullscreen);

        android.widget.LinearLayout root = new android.widget.LinearLayout(activity);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundColor(android.graphics.Color.parseColor("#F5F5F5"));

        android.widget.RelativeLayout toolbar = new android.widget.RelativeLayout(activity);
        toolbar.setBackgroundColor(android.graphics.Color.WHITE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            toolbar.setElevation(8f);
        }

        int pad = (int) (12 * activity.getResources().getDisplayMetrics().density);
        toolbar.setPadding(pad, pad, pad, pad);

        ImageView fileIcon = new ImageView(activity);
        fileIcon.setId(View.generateViewId());
        fileIcon.setImageResource(activity.getResources().getIdentifier("ic_file_xml", "drawable", activity.getPackageName()));
        android.widget.RelativeLayout.LayoutParams iconParams = new android.widget.RelativeLayout.LayoutParams((int)(24 * activity.getResources().getDisplayMetrics().density), (int)(24 * activity.getResources().getDisplayMetrics().density));
        iconParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        toolbar.addView(fileIcon, iconParams);

        TextView tvTitle = new TextView(activity);
        tvTitle.setText(xmlFile.getName());
        tvTitle.setTextColor(android.graphics.Color.parseColor("#333333"));
        tvTitle.setTextSize(16f);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setSingleLine(true);
        android.widget.RelativeLayout.LayoutParams titleParams = new android.widget.RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        titleParams.addRule(android.widget.RelativeLayout.RIGHT_OF, fileIcon.getId());
        titleParams.setMargins((int)(8 * activity.getResources().getDisplayMetrics().density), 0, 0, 0);
        toolbar.addView(tvTitle, titleParams);

        TextView btnClose = new TextView(activity);
        btnClose.setText("X");
        btnClose.setTextColor(android.graphics.Color.WHITE);
        btnClose.setTextSize(14f);
        btnClose.setTypeface(null, android.graphics.Typeface.BOLD);
        btnClose.setGravity(Gravity.CENTER);

        android.graphics.drawable.GradientDrawable redCircle = new android.graphics.drawable.GradientDrawable();
        redCircle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        redCircle.setColor(android.graphics.Color.parseColor("#E74C3C"));
        btnClose.setBackground(redCircle);

        android.widget.RelativeLayout.LayoutParams closeParams = new android.widget.RelativeLayout.LayoutParams((int)(30 * activity.getResources().getDisplayMetrics().density), (int)(30 * activity.getResources().getDisplayMetrics().density));
        closeParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_RIGHT);
        closeParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);

        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { previewDialog.dismiss(); }
        });
        toolbar.addView(btnClose, closeParams);

        root.addView(toolbar, new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(activity);
        divider.setBackgroundColor(android.graphics.Color.parseColor("#DDDDDD"));
        root.addView(divider, new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2));

        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.setLayoutParams(new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
        scroll.setFillViewport(true);

        final FrameLayout previewContainer = new FrameLayout(activity);
        previewContainer.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        scroll.addView(previewContainer);
        root.addView(scroll);

        previewDialog.setContentView(root);
        previewDialog.show();

        renderXmlToContainer(previewContainer, xmlFile);
    }

    private void renderXmlToContainer(final FrameLayout previewContainer, final File xmlFile) {
        if (previewContainer == null) return;
        previewContainer.removeAllViews();

        ProgressBar pb = new ProgressBar(activity);
        FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pbLp.gravity = Gravity.CENTER;
        previewContainer.addView(pb, pbLp);

        final String xmlContent = (activity.codeCanvas != null) ? activity.codeCanvas.getText() : null;
        final File projDir = (!MainActivity.currentProjectPath.isEmpty()) ? new File(MainActivity.currentProjectPath) : null;

        previewContainer.post(new Runnable() {
            @Override
            public void run() {
                try {
                    View rendered = XmlPreviewEngine.inflate(activity, xmlFile, xmlContent, projDir);
                    previewContainer.removeAllViews();
                    if (rendered != null) {
                        if (rendered.getParent() != null) {
                            ((ViewGroup) rendered.getParent()).removeView(rendered);
                        }
                        previewContainer.addView(rendered);
                    } else {
                        TextView err = new TextView(activity);
                        err.setText("Unable to render XML layout");
                        err.setTextColor(Color.RED);
                        err.setGravity(Gravity.CENTER);
                        previewContainer.addView(err);
                    }
                } catch (Exception e) {
                    previewContainer.removeAllViews();
                    TextView err = new TextView(activity);
                    err.setText("Render Error: " + e.getMessage());
                    err.setTextColor(Color.RED);
                    err.setGravity(Gravity.CENTER);
                    previewContainer.addView(err);
                }
            }
        });
    }

    public void showWebPreview(final File htmlFile) {
        final Dialog webDialog = new Dialog(activity, activity.getAppThemeStyleId());
        int layoutId = activity.getResources().getIdentifier("dialog_web_preview", "layout", activity.getPackageName());
        if (layoutId == 0) return;
        webDialog.setContentView(layoutId);

        int titleId = activity.getResources().getIdentifier("tvWebTitle", "id", activity.getPackageName());
        TextView tvTitle = webDialog.findViewById(titleId);
        if (tvTitle != null) tvTitle.setText("Web Preview: " + MainActivity.currentProjectName);

        int closeId = activity.getResources().getIdentifier("btnWebClose", "id", activity.getPackageName());
        TextView btnClose = webDialog.findViewById(closeId);
        if (btnClose != null) btnClose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { webDialog.dismiss(); }
        });

        int containerId = activity.getResources().getIdentifier("webContainer", "id", activity.getPackageName());
        FrameLayout webContainer = webDialog.findViewById(containerId);

        final WebView webView = new WebView(activity);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        if (Build.VERSION.SDK_INT >= 16) {
            settings.setAllowFileAccessFromFileURLs(true);
            settings.setAllowUniversalAccessFromFileURLs(true);
        }
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webContainer.addView(webView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        int refreshId = activity.getResources().getIdentifier("btnWebRefresh", "id", activity.getPackageName());
        TextView btnRefresh = webDialog.findViewById(refreshId);
        if (btnRefresh != null) btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { webView.reload(); }
        });

        webDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                webView.loadUrl("about:blank");
                webView.destroy();
            }
        });

        webView.loadUrl("file://" + htmlFile.getAbsolutePath());
        webDialog.show(); com.primx.utils.UIEffectsManager.applyPremiumBlur(webDialog);
    }
}
