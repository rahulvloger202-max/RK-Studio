package com.primx.utils.xml_preview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import org.w3c.dom.Element;

import java.io.File;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class XmlAttrApplier {

    public static void applyLayoutParams(Context context, View view, Element elem, ViewGroup parent, XmlUtils.IdResolver idResolver) {
        int width = XmlUtils.parseDim(context, XmlUtils.getAttr(elem, "layout_width"), ViewGroup.LayoutParams.WRAP_CONTENT);
        int height = XmlUtils.parseDim(context, XmlUtils.getAttr(elem, "layout_height"), ViewGroup.LayoutParams.WRAP_CONTENT);

        if (width == 0) width = ViewGroup.LayoutParams.MATCH_PARENT;
        if (height == 0) height = ViewGroup.LayoutParams.WRAP_CONTENT;

        ViewGroup.LayoutParams lp;
        if (parent instanceof LinearLayout) {
            float weight = 0f;
            try { weight = Float.parseFloat(XmlUtils.getAttr(elem, "layout_weight")); } catch (Exception ignored) {}
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(width, height, weight);
            String lg = XmlUtils.getAttr(elem, "layout_gravity");
            if (!lg.isEmpty()) llp.gravity = XmlUtils.parseGravity(lg);
            lp = llp;
        } else if (parent instanceof RelativeLayout) {
            RelativeLayout.LayoutParams rlp = new RelativeLayout.LayoutParams(width, height);
            applyRelativeRules(elem, rlp, idResolver);
            lp = rlp;
        } else if (parent instanceof FrameLayout) {
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(width, height);
            String lg = XmlUtils.getAttr(elem, "layout_gravity");
            if (!lg.isEmpty()) flp.gravity = XmlUtils.parseGravity(lg);
            lp = flp;
        } else {
            lp = new ViewGroup.MarginLayoutParams(width, height);
        }

        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
            int margin = XmlUtils.parseDim(context, XmlUtils.getAttr(elem, "layout_margin"), -1);
            if (margin >= 0) {
                mlp.setMargins(margin, margin, margin, margin);
            } else {
                int left = XmlUtils.parseDim(context, XmlUtils.firstNonEmpty(XmlUtils.getAttr(elem, "layout_marginLeft"), XmlUtils.getAttr(elem, "layout_marginStart")), 0);
                int top = XmlUtils.parseDim(context, XmlUtils.getAttr(elem, "layout_marginTop"), 0);
                int right = XmlUtils.parseDim(context, XmlUtils.firstNonEmpty(XmlUtils.getAttr(elem, "layout_marginRight"), XmlUtils.getAttr(elem, "layout_marginEnd")), 0);
                int bottom = XmlUtils.parseDim(context, XmlUtils.getAttr(elem, "layout_marginBottom"), 0);
                mlp.setMargins(left, top, right, bottom);
            }
        }
        view.setLayoutParams(lp);
    }

    private static void applyRelativeRules(Element elem, RelativeLayout.LayoutParams rlp, XmlUtils.IdResolver idResolver) {
        if ("true".equalsIgnoreCase(XmlUtils.getAttr(elem, "layout_centerInParent"))) rlp.addRule(RelativeLayout.CENTER_IN_PARENT);
        if ("true".equalsIgnoreCase(XmlUtils.getAttr(elem, "layout_centerHorizontal"))) rlp.addRule(RelativeLayout.CENTER_HORIZONTAL);
        if ("true".equalsIgnoreCase(XmlUtils.getAttr(elem, "layout_centerVertical"))) rlp.addRule(RelativeLayout.CENTER_VERTICAL);
        String below = XmlUtils.getAttr(elem, "layout_below");
        if (!below.isEmpty() && idResolver.hasId(below)) rlp.addRule(RelativeLayout.BELOW, idResolver.getId(below));
        String toRightOf = XmlUtils.firstNonEmpty(XmlUtils.getAttr(elem, "layout_toRightOf"), XmlUtils.getAttr(elem, "layout_toEndOf"));
        if (!toRightOf.isEmpty() && idResolver.hasId(toRightOf)) rlp.addRule(RelativeLayout.RIGHT_OF, idResolver.getId(toRightOf));
    }

    public static void applyPaddingAndBackground(Context context, View view, Element elem, HashMap<String, String> colorsMap, String tag, File projectDir) {
        int pad = XmlUtils.parseDim(context, XmlUtils.getAttr(elem, "padding"), -1);
        if (pad >= 0) view.setPadding(pad, pad, pad, pad);
        else {
            int left = XmlUtils.parseDim(context, XmlUtils.firstNonEmpty(XmlUtils.getAttr(elem, "paddingLeft"), XmlUtils.getAttr(elem, "paddingStart")), view.getPaddingLeft());
            int top = XmlUtils.parseDim(context, XmlUtils.getAttr(elem, "paddingTop"), view.getPaddingTop());
            int right = XmlUtils.parseDim(context, XmlUtils.firstNonEmpty(XmlUtils.getAttr(elem, "paddingRight"), XmlUtils.getAttr(elem, "paddingEnd")), view.getPaddingRight());
            int bottom = XmlUtils.parseDim(context, XmlUtils.getAttr(elem, "paddingBottom"), view.getPaddingBottom());
            view.setPadding(left, top, right, bottom);
        }

        if (tag.endsWith("CardView")) {
            GradientDrawable cardBg = new GradientDrawable();
            String cardColor = XmlUtils.firstNonEmpty(XmlUtils.getAttr(elem, "cardBackgroundColor"), XmlUtils.getAttr(elem, "app:cardBackgroundColor"));
            Integer parsed = XmlUtils.parseColor(cardColor, colorsMap);
            cardBg.setColor(parsed != null ? parsed : XmlUtils.dialogBgColor(context));
            String radius = XmlUtils.firstNonEmpty(XmlUtils.getAttr(elem, "cardCornerRadius"), XmlUtils.getAttr(elem, "app:cardCornerRadius"));
            cardBg.setCornerRadius(XmlUtils.parseDim(context, radius.isEmpty() ? "4dp" : radius, 0));
            view.setBackground(cardBg);
        } else {
            String bgStr = XmlUtils.firstNonEmpty(XmlUtils.getAttr(elem, "background"), XmlUtils.getAttr(elem, "backgroundTint"));
            if (!bgStr.isEmpty()) {
                Integer color = XmlUtils.parseColor(bgStr, colorsMap);
                if (color != null) {
                    view.setBackgroundColor(color);
                } else if (bgStr.startsWith("@drawable/") || bgStr.startsWith("@mipmap/")) {
                    Drawable d = loadDrawable(context, projectDir, bgStr);
                    if (d != null) view.setBackground(d);
                }
            }
        }
    }

    public static void configureTextView(Context context, TextView tv, Element elem, HashMap<String, String> stringsMap, HashMap<String, String> colorsMap) {
        String text = XmlUtils.firstNonEmpty(XmlUtils.getAttr(elem, "text"), XmlUtils.getAttr(elem, "hint"));
        if (!text.isEmpty()) {
            tv.setText(text.startsWith("@string/") && stringsMap.containsKey(text.substring(8)) ? stringsMap.get(text.substring(8)) : text);
        } else if (tv instanceof Button) tv.setText("Button");

        Integer c = XmlUtils.parseColor(XmlUtils.getAttr(elem, "textColor"), colorsMap);
        if (c != null) tv.setTextColor(c);

        String textSize = XmlUtils.getAttr(elem, "textSize");
        if (!textSize.isEmpty()) {
            try { tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, Float.parseFloat(textSize.replace("sp", ""))); } catch (Exception e){}
        }

        String style = XmlUtils.getAttr(elem, "textStyle").toLowerCase();
        if ("bold".equals(style)) tv.setTypeface(null, Typeface.BOLD);
        else if ("italic".equals(style)) tv.setTypeface(null, Typeface.ITALIC);

        if ("true".equalsIgnoreCase(XmlUtils.getAttr(elem, "textAllCaps"))) tv.setAllCaps(true);
        if ("true".equalsIgnoreCase(XmlUtils.getAttr(elem, "singleLine"))) tv.setSingleLine(true);
    }

    public static void configureImageView(Context context, ImageView iv, Element elem, File projectDir) {
        String scaleType = XmlUtils.getAttr(elem, "scaleType");
        if ("centerCrop".equalsIgnoreCase(scaleType)) iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        else if ("fitCenter".equalsIgnoreCase(scaleType)) iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        else if ("fitXY".equalsIgnoreCase(scaleType)) iv.setScaleType(ImageView.ScaleType.FIT_XY);

        String src = XmlUtils.firstNonEmpty(XmlUtils.getAttr(elem, "src"), XmlUtils.getAttr(elem, "app:srcCompat"));
        
        if (src.startsWith("#") || src.startsWith("@color/") || src.startsWith("@android:color/")) {
            Integer parsedColor = XmlUtils.parseColor(src, null); 
            if (parsedColor != null) {
                iv.setBackgroundColor(parsedColor);
            }
        } else if (src.startsWith("@mipmap/") || src.startsWith("@drawable/")) {
            Drawable d = loadDrawable(context, projectDir, src);
            if (d != null) {
                iv.setImageDrawable(d);
            }
        }
        
        // 🔥 ICON TINT FEATURE
        String tint = XmlUtils.firstNonEmpty(XmlUtils.getAttr(elem, "tint"), XmlUtils.getAttr(elem, "app:tint"));
        if (!tint.isEmpty()) {
            Integer tintColor = XmlUtils.parseColor(tint, null);
            if (tintColor != null) {
                iv.setColorFilter(tintColor, android.graphics.PorterDuff.Mode.SRC_IN);
            }
        }
    }

    // ========================================================
    // 🔥 SUPER XML PARSER (NO LIBRARY NEEDED!)
    // ========================================================
    
    private static Drawable loadDrawable(Context context, File projectDir, String resPath) {
        if (projectDir == null || resPath == null) return null;
        String resName = resPath.substring(resPath.indexOf('/') + 1);
        File imgFile = XmlResManager.findResourceImage(projectDir, resName);
        if (imgFile != null && imgFile.exists()) {
            if (imgFile.getName().endsWith(".xml")) {
                try {
                    String xmlContent = XmlResManager.readFileToString(imgFile);
                    
                    // 1. अगर यह Vector Icon है (SVG)
                    if (xmlContent.contains("<vector")) {
                        Drawable vector = parseVectorXml(xmlContent);
                        if (vector != null) return vector;
                    }
                    
                    // 2. अगर यह Shape (Background Box/Line) है
                    if (xmlContent.contains("<shape")) {
                        Drawable shape = parseShapeXml(xmlContent);
                        if (shape != null) return shape;
                    }
                    
                } catch (Exception e) {
                    return null;
                }
            } else {
                // PNG, JPG इमेजेस
                Bitmap bmp = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                if (bmp != null) return new android.graphics.drawable.BitmapDrawable(context.getResources(), bmp);
            }
        }
        return null;
    }

    // 🚀 VECTOR XML PARSER (REAL ICONS VIA REFLECTION)
    private static Drawable parseVectorXml(String xml) {
        try {
            float vWidth = 24f, vHeight = 24f;
            Matcher mw = Pattern.compile("android:viewportWidth\\s*=\\s*\"([0-9.]+)\"").matcher(xml);
            if (mw.find()) vWidth = Float.parseFloat(mw.group(1));
            Matcher mh = Pattern.compile("android:viewportHeight\\s*=\\s*\"([0-9.]+)\"").matcher(xml);
            if (mh.find()) vHeight = Float.parseFloat(mh.group(1));

            final float viewportWidth = vWidth;
            final float viewportHeight = vHeight;

            class VectorPath {
                android.graphics.Path path;
                int fillColor = Color.TRANSPARENT;
            }
            
            final java.util.List<VectorPath> paths = new java.util.ArrayList<>();
            Matcher pMatcher = Pattern.compile("<path([^>]+)>").matcher(xml);
            
            while (pMatcher.find()) {
                String pTag = pMatcher.group(1);
                Matcher dMatcher = Pattern.compile("android:pathData\\s*=\\s*\"([^\"]+)\"").matcher(pTag);
                if (dMatcher.find()) {
                    String pathData = dMatcher.group(1);
                    android.graphics.Path path = createPathFromData(pathData);
                    
                    if (path != null) {
                        VectorPath vp = new VectorPath();
                        vp.path = path;
                        Matcher fc = Pattern.compile("android:fillColor\\s*=\\s*\"([^\"]+)\"").matcher(pTag);
                        if (fc.find()) {
                            try { vp.fillColor = Color.parseColor(fc.group(1)); } catch (Exception e) { vp.fillColor = Color.BLACK; }
                        } else {
                            vp.fillColor = Color.BLACK; 
                        }
                        paths.add(vp);
                    }
                }
            }

            if (!paths.isEmpty()) {
                return new Drawable() {
                    @Override
                    public void draw(android.graphics.Canvas canvas) {
                        android.graphics.Rect bounds = getBounds();
                        canvas.save();
                        canvas.scale(bounds.width() / viewportWidth, bounds.height() / viewportHeight);
                        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                        for (VectorPath vp : paths) {
                            if (vp.fillColor != Color.TRANSPARENT) {
                                paint.setStyle(android.graphics.Paint.Style.FILL);
                                paint.setColor(vp.fillColor);
                                canvas.drawPath(vp.path, paint);
                            }
                        }
                        canvas.restore();
                    }
                    @Override public void setAlpha(int alpha) {}
                    @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) {}
                    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
                };
            }
        } catch (Exception e) {}
        
        return null;
    }

    // 🔥 SMART REFLECTION HACK (Bypasses the "PathParser cannot be resolved" compile error!)
    private static android.graphics.Path createPathFromData(String pathData) {
        try { // 1. AndroidX लाइब्रेरी (आमतौर पर हर प्रोजेक्ट में होती है)
            Class<?> cls = Class.forName("androidx.core.graphics.PathParser");
            return (android.graphics.Path) cls.getMethod("createPathFromPathData", String.class).invoke(null, pathData);
        } catch (Throwable e1) {
            try { // 2. Public API 24+
                Class<?> cls = Class.forName("android.graphics.PathParser");
                return (android.graphics.Path) cls.getMethod("createPathFromPathData", String.class).invoke(null, pathData);
            } catch (Throwable e2) {
                try { // 3. Hidden API 21+
                    Class<?> cls = Class.forName("android.util.PathParser");
                    return (android.graphics.Path) cls.getMethod("createPathFromPathData", String.class).invoke(null, pathData);
                } catch (Throwable e3) {
                    return null;
                }
            }
        }
    }

    // 🚀 SHAPE XML PARSER (For Background Boxes/Corners/Lines)
    private static Drawable parseShapeXml(String xml) {
        try {
            GradientDrawable gd = new GradientDrawable();
            
            Matcher cMatcher = Pattern.compile("<solid[^>]+android:color\\s*=\\s*\"([^\"]+)\"").matcher(xml);
            if (cMatcher.find()) {
                try { gd.setColor(Color.parseColor(cMatcher.group(1))); } catch(Exception e){}
            } else {
                gd.setColor(Color.TRANSPARENT);
            }
            
            Matcher rMatcher = Pattern.compile("<corners[^>]+android:radius\\s*=\\s*\"([0-9.]+)[a-zA-Z]+\"").matcher(xml);
            if (rMatcher.find()) {
                float r = Float.parseFloat(rMatcher.group(1));
                gd.setCornerRadius(r * 2.5f); 
            }
            
            Matcher sTagMatcher = Pattern.compile("<stroke([^>]+)>").matcher(xml);
            if (sTagMatcher.find()) {
                String sAttrs = sTagMatcher.group(1);
                Matcher widthM = Pattern.compile("android:width\\s*=\\s*\"([0-9.]+)[a-zA-Z]+\"").matcher(sAttrs);
                Matcher colorM = Pattern.compile("android:color\\s*=\\s*\"([^\"]+)\"").matcher(sAttrs);
                if (widthM.find() && colorM.find()) {
                    float w = Float.parseFloat(widthM.group(1));
                    int c = Color.BLACK;
                    try { c = Color.parseColor(colorM.group(1)); } catch(Exception e){}
                    gd.setStroke((int)(w * 2.5f), c);
                }
            }
            return gd;
        } catch (Exception e) {}
        return null;
    }
}
