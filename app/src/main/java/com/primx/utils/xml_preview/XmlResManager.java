package com.primx.utils.xml_preview;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;

public class XmlResManager {
    public static class CachedRes {
        long stringsModified;
        long colorsModified;
        public HashMap<String, String> strings = new HashMap<>();
        public HashMap<String, String> colors = new HashMap<>();
    }

    private static final HashMap<String, CachedRes> RES_CACHE = new HashMap<>();

    public static synchronized CachedRes getOrLoadResources(File projectDir) {
        if (projectDir == null || !projectDir.exists()) return new CachedRes();
        String key = projectDir.getAbsolutePath();

        File stringsFile = findValuesFile(projectDir, "strings.xml");
        File colorsFile = findValuesFile(projectDir, "colors.xml");

        long sMod = stringsFile != null && stringsFile.exists() ? stringsFile.lastModified() : 0;
        long cMod = colorsFile != null && colorsFile.exists() ? colorsFile.lastModified() : 0;

        CachedRes cached = RES_CACHE.get(key);
        if (cached != null && cached.stringsModified == sMod && cached.colorsModified == cMod) return cached;

        CachedRes fresh = new CachedRes();
        fresh.stringsModified = sMod;
        fresh.colorsModified = cMod;

        if (stringsFile != null && stringsFile.exists()) parseXmlDictionary(stringsFile, fresh.strings, "string");
        if (colorsFile != null && colorsFile.exists()) parseXmlDictionary(colorsFile, fresh.colors, "color");

        RES_CACHE.put(key, fresh);
        return fresh;
    }

    private static void parseXmlDictionary(File f, HashMap<String, String> map, String tag) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("<" + tag + " name=\"")) {
                    int s = line.indexOf("name=\"") + 6;
                    int e = line.indexOf("\"", s);
                    if (s > 5 && e > s) {
                        String name = line.substring(s, e);
                        int vs = line.indexOf(">", e) + 1;
                        int ve = line.indexOf("</" + tag + ">", vs);
                        if (vs > 0 && ve > vs) map.put(name, line.substring(vs, ve).trim());
                    }
                }
            }
            br.close();
        } catch (Exception ignored) {}
    }

    public static File findValuesFile(File projectDir, String fileName) {
        if (projectDir == null || !projectDir.exists()) return null;
        File[] paths = {
            new File(projectDir, "app/src/main/res/values/" + fileName),
            new File(projectDir, "src/main/res/values/" + fileName),
            new File(projectDir, "res/values/" + fileName)
        };
        for (File f : paths) if (f.exists()) return f;
        return null;
    }

    public static File findLayoutFile(File projectDir, String layoutName) {
        if (projectDir == null || !projectDir.exists()) return null;
        File[] paths = {
            new File(projectDir, "app/src/main/res/layout/" + layoutName + ".xml"),
            new File(projectDir, "src/main/res/layout/" + layoutName + ".xml"),
            new File(projectDir, "res/layout/" + layoutName + ".xml")
        };
        for (File f : paths) if (f.exists()) return f;
        return null;
    }

    public static File findResourceImage(File projectDir, String resName) {
        if (projectDir == null || !projectDir.exists()) return null;
        String[] extensions = {".xml", ".png", ".jpg", ".jpeg", ".webp"};
        String[] folders = {"app/src/main/res/drawable", "app/src/main/res/mipmap", "res/drawable", "res/mipmap"};
        for (String fld : folders) {
            File dir = new File(projectDir, fld);
            if (dir.exists()) {
                for (String ext : extensions) {
                    File candidate = new File(dir, resName + ext);
                    if (candidate.exists()) return candidate;
                }
            }
        }
        return null;
    }

    public static String readFileToString(File f) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) != -1) sb.append(buf, 0, n);
            br.close();
            return sb.toString();
        } catch (Exception e) { return ""; }
    }
}
