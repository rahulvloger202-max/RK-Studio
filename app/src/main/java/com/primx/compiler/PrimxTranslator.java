package com.primx.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PrimxTranslator {

    public static class TranslationResult {
        public String javaCode = "";
        public String xmlCode = "";
    }

    enum TokenType { SCREEN_KEY, UI_ELEMENT, STRING, ARROW, BRACKET_OPEN, BRACKET_CLOSE, CURLY_OPEN, CURLY_CLOSE, UNKNOWN }
    
    static class Token { 
        TokenType type; String value; 
        Token(TokenType type, String value) { this.type = type; this.value = value; } 
    }

    public static TranslationResult translate(String pxCode) {
        TranslationResult result = new TranslationResult();
        List<Token> tokens = tokenize(pxCode);
        
        StringBuilder xml = new StringBuilder();
        StringBuilder java = new StringBuilder();
        
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            
            if (t.type == TokenType.SCREEN_KEY) {
                String screenName = tokens.get(i+2).value.replace("\"", "");
                
                xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
                xml.append("<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n");
                xml.append("    android:layout_width=\"match_parent\"\n    android:layout_height=\"match_parent\"\n");
                xml.append("    android:orientation=\"vertical\" android:gravity=\"center\">\n\n");
                
                // 🌟 FIX: Yahan package name ab 'com.px' ho gaya hai aur R import ho gaya hai
                java.append("package com.px;\n\n"); 
                java.append("import android.app.Activity;\nimport android.os.Bundle;\nimport android.widget.*;\nimport android.view.View;\nimport com.px.R;\n\n");
                
                java.append("public class MainActivity extends Activity {\n");
                java.append("    @Override\n");
                java.append("    protected void onCreate(Bundle savedInstanceState) {\n");
                java.append("        super.onCreate(savedInstanceState);\n");
                java.append("        setContentView(R.layout.main);\n\n");
            } 
            else if (t.type == TokenType.CURLY_CLOSE) {
                xml.append("</LinearLayout>\n");
                java.append("    }\n}\n");
            }
            else if (t.type == TokenType.UI_ELEMENT) {
                if (t.value.equals("Text")) {
                    String text = tokens.get(i+2).value.replace("\"", "");
                    xml.append("    <TextView android:layout_width=\"wrap_content\" android:layout_height=\"wrap_content\" android:text=\"").append(text).append("\" />\n");
                } 
                else if (t.value.equals("Button")) {
                    String btnText = tokens.get(i+2).value.replace("\"", "");
                    String id = "btn_" + btnText.toLowerCase().replace(" ", "");
                    
                    xml.append("    <Button android:id=\"@+id/").append(id).append("\" android:layout_width=\"wrap_content\" android:layout_height=\"wrap_content\" android:text=\"").append(btnText).append("\" />\n");
                    java.append("        Button ").append(id).append(" = findViewById(R.id.").append(id).append(");\n");
                    
                    if (i+4 < tokens.size() && tokens.get(i+4).type == TokenType.ARROW) {
                        String msg = tokens.get(i+7).value.replace("\"", "");
                        java.append("        ").append(id).append(".setOnClickListener(new View.OnClickListener() {\n");
                        java.append("            public void onClick(View v) {\n");
                        java.append("                Toast.makeText(MainActivity.this, \"").append(msg).append("\", Toast.LENGTH_SHORT).show();\n");
                        java.append("            }\n        });\n");
                    }
                }
            }
        }
        
        result.javaCode = java.toString();
        result.xmlCode = xml.toString();
        return result;
    }

    private static List<Token> tokenize(String input) {
        List<Token> tokens = new ArrayList<>();
        input = input.replace("->", " -> ").replace("(", " ( ").replace(")", " ) ").replace("{", " { ").replace("}", " } ");
        Matcher m = Pattern.compile("(\"[^\"]*\")|(\\S+)").matcher(input);
        
        while (m.find()) {
            String word = m.group().trim();
            if (word.isEmpty()) continue;
            
            if (word.equals("Screen")) tokens.add(new Token(TokenType.SCREEN_KEY, word));
            else if (word.equals("Button") || word.equals("Text") || word.equals("toast")) tokens.add(new Token(TokenType.UI_ELEMENT, word));
            else if (word.equals("->")) tokens.add(new Token(TokenType.ARROW, word));
            else if (word.equals("(")) tokens.add(new Token(TokenType.BRACKET_OPEN, word));
            else if (word.equals(")")) tokens.add(new Token(TokenType.BRACKET_CLOSE, word));
            else if (word.equals("{")) tokens.add(new Token(TokenType.CURLY_OPEN, word));
            else if (word.equals("}")) tokens.add(new Token(TokenType.CURLY_CLOSE, word));
            else if (word.startsWith("\"") && word.endsWith("\"")) tokens.add(new Token(TokenType.STRING, word));
            else tokens.add(new Token(TokenType.UNKNOWN, word));
        }
        return tokens;
    }
}
