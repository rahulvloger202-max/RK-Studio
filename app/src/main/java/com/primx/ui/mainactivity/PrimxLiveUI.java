// _________________________________________________________
// 🚀⬇️⬇️⬇️ MASTER UNIVERSAL ENGINE (NO HARDCODING) ⬇️⬇️⬇️🚀

package com.primx.ui.mainactivity;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;

public class PrimxLiveUI {

    public static void showLivePreview(final Activity activity, File jsonFile) {
        final Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        ScrollView scrollView = new ScrollView(activity);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));

        final LinearLayout appScreen = new LinearLayout(activity);
        appScreen.setOrientation(LinearLayout.VERTICAL);
        appScreen.setPadding(50, 80, 50, 60);
        appScreen.setBackgroundColor(Color.parseColor("#121212")); // Default Dark
        scrollView.addView(appScreen);

        final HashMap<String, View> memory = new HashMap<>();

        try {
            // Hum .px file nahi, .json orders read kar rahe hain
            BufferedReader br = new BufferedReader(new FileReader(jsonFile));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            JSONArray uiElements = new JSONArray(sb.toString());

            // Loop chalega aur jo JSON bolega, wahi screen par draw hoga
            for (int i = 0; i < uiElements.length(); i++) {
                JSONObject item = uiElements.getJSONObject(i);
                String type = item.optString("type", "");
                String value = item.optString("value", "");
                String id = item.optString("id", "");

                if (type.equals("Screen")) {
                    appScreen.setBackgroundColor(Color.parseColor(value));
                } 
                else if (type.equals("Title")) {
                    TextView tv = new TextView(activity);
                    tv.setText(value);
                    tv.setTextSize(32);
                    tv.setTextColor(Color.parseColor("#00E5FF"));
                    tv.setPadding(0, 0, 0, 40);
                    appScreen.addView(tv);
                    if (!id.isEmpty()) memory.put(id, tv);
                } 
                else if (type.equals("Input")) {
                    EditText et = new EditText(activity);
                    et.setHint(value);
                    et.setTextColor(Color.WHITE);
                    et.setHintTextColor(Color.GRAY);
                    appScreen.addView(et);
                    if (!id.isEmpty()) memory.put(id, et);
                } 
                else if (type.equals("Button")) {
                    Button btn = new Button(activity);
                    btn.setText(value);
                    btn.setBackgroundColor(Color.parseColor("#2962FF"));
                    btn.setTextColor(Color.WHITE);
                    
                    final String action = item.optString("action", "");
                    btn.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            // Agar aage koi script chalani hui toh JSON action yahan pass hoga
                            Toast.makeText(activity, "Action: " + action, Toast.LENGTH_SHORT).show();
                        }
                    });
                    appScreen.addView(btn);
                    if (!id.isEmpty()) memory.put(id, btn);
                }
            }
        } catch (Exception e) {
            Toast.makeText(activity, "PrimX Engine Ready! Python se orders ka wait kar raha hai...", Toast.LENGTH_SHORT).show();
        }

        Button closeBtn = new Button(activity);
        closeBtn.setText("CLOSE APP");
        closeBtn.setBackgroundColor(Color.RED);
        closeBtn.setTextColor(Color.WHITE);
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        appScreen.addView(closeBtn);

        dialog.setContentView(scrollView);
        dialog.show();
    }
}
// 🚀⬆️⬆️⬆️ MASTER UNIVERSAL ENGINE END ⬆️⬆️⬆️🚀
// _________________________________________________________
