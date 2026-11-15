package com.primx.utils.xml_preview;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;

import com.primx.R;

import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.HashMap;

public class XmlUtils {

    public static class IdResolver {
        private int nextId = 1000;
        private HashMap<String, Integer> idMap = new HashMap<>();

        public void collectIds(Element elem) {
            String idStr = getAttr(elem, "id");
            if (!idStr.isEmpty()) {
                String clean = cleanId(idStr);
                if (!idMap.containsKey(clean)) {
                    int id = nextId++;
                    idMap.put(clean, id);
                    idMap.put("@+id/" + clean, id);
                    idMap.put("@id/" + clean, id);
                    idMap.put(idStr, id);
                }
            }
            NodeList list = elem.getChildNodes();
            for (int i = 0; i < list.getLength(); i++) {
                Node n = list.item(i);
                if (n.getNodeType() == Node.ELEMENT_NODE) collectIds((Element) n);
            }
        }

        public boolean hasId(String idStr) { return idMap.containsKey(idStr) || idMap.containsKey(cleanId(idStr)); }

        public int getId(String idStr) {
            if (idMap.containsKey(idStr)) return idMap.get(idStr);
            String clean = cleanId(idStr);
            if (idMap.containsKey(clean)) return idMap.get(clean);
            int id = nextId++;
            idMap.put(clean, id);
            idMap.put(idStr, id);
            return id;
        }

        private String cleanId(String idStr) {
            if (idStr.startsWith("@+id/")) return idStr.substring(5);
            if (idStr.startsWith("@id/")) return idStr.substring(4);
            return idStr;
        }
    }

    public static String getAttr(Element elem, String name) {
        if (elem.hasAttribute("android:" + name)) return elem.getAttribute("android:" + name);
        if (elem.hasAttribute("app:" + name)) return elem.getAttribute("app:" + name);
        if (elem.hasAttribute(name)) return elem.getAttribute(name);
        NamedNodeMap map = elem.getAttributes();
        if (map != null) {
            for (int i = 0; i < map.getLength(); i++) {
                Node n = map.item(i);
                if (n.getNodeName().equals(name) || n.getNodeName().endsWith(":" + name)) return n.getNodeValue();
            }
        }
        return "";
    }

    public static String firstNonEmpty(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : (b != null ? b : "");
    }

    public static Integer parseColor(String val, HashMap<String, String> colorsMap) {
        if (val == null || val.trim().isEmpty()) return null;
        String v = val.trim();
        if (v.startsWith("@color/") && colorsMap != null && colorsMap.containsKey(v.substring(7))) {
            v = colorsMap.get(v.substring(7));
        }
        if (v.startsWith("@android:color/")) {
            String colName = v.substring(15).toLowerCase();
            if ("white".equals(colName)) return Color.WHITE;
            if ("black".equals(colName)) return Color.BLACK;
            if ("transparent".equals(colName)) return Color.TRANSPARENT;
            return Color.parseColor("#263238");
        }
        try {
            if (v.startsWith("#")) {
                if (v.length() == 4) v = "#" + v.charAt(1) + v.charAt(1) + v.charAt(2) + v.charAt(2) + v.charAt(3) + v.charAt(3);
                else if (v.length() == 5) v = "#" + v.charAt(1) + v.charAt(1) + v.charAt(2) + v.charAt(2) + v.charAt(3) + v.charAt(3) + v.charAt(4) + v.charAt(4);
                return Color.parseColor(v);
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static int parseDim(Context context, String val, int defaultVal) {
        if (val == null || val.trim().isEmpty()) return defaultVal;
        String v = val.trim().toLowerCase();
        if ("match_parent".equals(v) || "fill_parent".equals(v)) return ViewGroup.LayoutParams.MATCH_PARENT;
        if ("wrap_content".equals(v)) return ViewGroup.LayoutParams.WRAP_CONTENT;

        try {
            if (v.endsWith("dp") || v.endsWith("dip")) {
                float num = Float.parseFloat(v.replaceAll("[a-z]", ""));
                return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, num, context.getResources().getDisplayMetrics());
            }
            if (v.endsWith("sp")) {
                float num = Float.parseFloat(v.replace("sp", ""));
                return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, num, context.getResources().getDisplayMetrics());
            }
            if (v.endsWith("px")) return (int) Float.parseFloat(v.replace("px", ""));
            float num = Float.parseFloat(v);
            return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, num, context.getResources().getDisplayMetrics());
        } catch (Exception ignored) {}
        return defaultVal;
    }

    public static int parseGravity(String val) {
        if (val == null || val.trim().isEmpty()) return Gravity.NO_GRAVITY;
        String[] parts = val.toLowerCase().split("\\|");
        int result = 0;
        for (String p : parts) {
            p = p.trim();
            if ("center".equals(p)) result |= Gravity.CENTER;
            else if ("center_vertical".equals(p)) result |= Gravity.CENTER_VERTICAL;
            else if ("center_horizontal".equals(p)) result |= Gravity.CENTER_HORIZONTAL;
            else if ("top".equals(p)) result |= Gravity.TOP;
            else if ("bottom".equals(p)) result |= Gravity.BOTTOM;
            else if ("left".equals(p) || "start".equals(p)) result |= Gravity.LEFT;
            else if ("right".equals(p) || "end".equals(p)) result |= Gravity.RIGHT;
        }
        return result != 0 ? result : Gravity.NO_GRAVITY;
    }

    // ============================================================
    // 🔥 THEME COLOR RESOLVERS (Light/Dark auto-adapt)
    // ============================================================
    public static int themeColor(Context ctx, int attrId, int fallback) {
        if (ctx == null) return fallback;
        TypedValue tv = new TypedValue();
        if (ctx.getTheme().resolveAttribute(attrId, tv, true)) {
            if (tv.resourceId != 0) {
                try {
                    if (Build.VERSION.SDK_INT >= 23) return ctx.getResources().getColor(tv.resourceId, ctx.getTheme());
                    return ctx.getResources().getColor(tv.resourceId);
                } catch (Exception e) { return fallback; }
            }
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            }
        }
        return fallback;
    }

    public static int bgColor(Context c)        { return themeColor(c, R.attr.ide_bg_color,        Color.parseColor("#F5F5F5")); }
    public static int dialogBgColor(Context c)  { return themeColor(c, R.attr.ide_dialog_bg_color, Color.WHITE); }
    public static int textPrimary(Context c)    { return themeColor(c, R.attr.ide_text_primary,    Color.parseColor("#212121")); }
    public static int textSecondary(Context c)  { return themeColor(c, R.attr.ide_text_secondary,  Color.parseColor("#757575")); }
    public static int iconTint(Context c)       { return themeColor(c, R.attr.ide_icon_tint,       Color.parseColor("#424242")); }
    public static int strokeColor(Context c)    { return themeColor(c, R.attr.ide_stroke_color,    Color.parseColor("#3574F0")); }
    public static int borderColor(Context c)    { return themeColor(c, R.attr.ide_border_color,    Color.parseColor("#40000000")); }

    /** Accent color with custom alpha (0..255) */
    public static int strokeWithAlpha(Context c, int alpha) {
        int s = strokeColor(c);
        return Color.argb(alpha, Color.red(s), Color.green(s), Color.blue(s));
    }
}
