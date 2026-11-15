package com.primx.compiler;

import com.primx.compiler.Compiler;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class CompilerUtils {

    private Compiler compiler;

    public CompilerUtils(Compiler compiler) {
        this.compiler = compiler;
    }

    public boolean forceExtractAsset(String assetName, Compiler.CompileListener listener) {
        File outFile = new File(compiler.toolsDirPath, assetName);
        if (outFile.exists() && outFile.length() > 0) return true;

        try {
            InputStream is = compiler.context.getAssets().open(assetName);
            if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(outFile);
            byte[] buffer = new byte[8192];
            int length;
            while ((length = is.read(buffer)) > 0) fos.write(buffer, 0, length);
            fos.close();
            is.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String prepareCustomEcjMessages() {
        File langZip = new File(compiler.toolsDirPath, "ecj_lang.zip");
        try {
            if (!langZip.exists() || langZip.length() == 0) {
                InputStream is = compiler.context.getAssets().open("messages.properties");
                FileOutputStream fos = new FileOutputStream(langZip);
                ZipOutputStream zos = new ZipOutputStream(fos);

                ZipEntry entry = new ZipEntry("org/eclipse/jdt/internal/compiler/problem/messages.properties");
                zos.putNextEntry(entry);

                byte[] buffer = new byte[4096];
                int len;
                while ((len = is.read(buffer)) > 0) {
                    zos.write(buffer, 0, len);
                }

                zos.closeEntry();
                zos.close();
                fos.close();
                is.close();
            }
            return langZip.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    public long getMaxModifiedTime(File file) {
        if (!file.exists()) return 0;
        if (!file.isDirectory()) return file.lastModified();
        long max = 0;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                max = Math.max(max, getMaxModifiedTime(child));
            }
        }
        return max;
    }

    public void deleteRecursively(File fileOrDirectory) {
        if (fileOrDirectory == null || !fileOrDirectory.exists()) return;
        if (fileOrDirectory.isDirectory()) {
            File[] files = fileOrDirectory.listFiles();
            if (files != null) for (File child : files) deleteRecursively(child);
        }
        fileOrDirectory.delete();
    }

    public void ensureDummyAttrs(File resDir) {
        File valuesDir = new File(resDir, "values");
        if (!valuesDir.exists()) valuesDir.mkdirs();
        File attrsFile = new File(valuesDir, "primx_attrs.xml");
        try {
            FileOutputStream fos = new FileOutputStream(attrsFile);
            String attrsContent = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<resources>\n" +
            "<!-- Minimal Default Theme for Blank Projects -->\n" +
            "<style name=\"Theme.AppCompat\" parent=\"@android:style/Theme.Material\"/>\n" +
            "<style name=\"Theme.MaterialComponents\" parent=\"@android:style/Theme.Material\"/>\n" +
            "<attr name=\"colorPrimary\" format=\"color\"/>\n" +
            "<attr name=\"colorPrimaryDark\" format=\"color\"/>\n" +
            "<attr name=\"colorAccent\" format=\"color\"/>\n" +
            "</resources>";
            fos.write(attrsContent.getBytes());
            fos.close();
        } catch (Exception e) {}
    }

    public boolean executeCommand(String[] command, final Compiler.CompileListener listener, final boolean isXmlCompile) {
        try {
            Compiler.checkCancellation();
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            Compiler.activeProcess = process;

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            boolean hadError = false;

            while ((line = reader.readLine()) != null) {
                if (Compiler.isCancelled()) {
                    try { process.destroy(); } catch (Throwable ignored) {}
                    break;
                }
                if (!line.toLowerCase().contains("warning") && !line.trim().isEmpty()) {
                    boolean isErr = line.toLowerCase().contains("error") ||
                        line.toLowerCase().contains("failed") ||
                        line.contains("Exception in thread") ||
                        line.trim().startsWith("at ");

                    if (isErr) {
                        hadError = true;
                        if (isXmlCompile && line.contains("error:")) {
                            try {
                                int errIdx = line.indexOf("error:");
                                String beforeErr = line.substring(0, errIdx).trim();
                                String errorMsg = line.substring(errIdx + 6).trim();
                                if (beforeErr.endsWith(":")) beforeErr = beforeErr.substring(0, beforeErr.length() - 1);
                                int lastColon = beforeErr.lastIndexOf(':');
                                if (lastColon != -1) {
                                    String filePath = beforeErr.substring(0, lastColon).trim();
                                    int lineNumber = Integer.parseInt(beforeErr.substring(lastColon + 1).trim());
                                    compiler.postProgress(listener, "#ERROR:|" + filePath + "|" + lineNumber + "|" + errorMsg, true);
                                    continue;
                                }
                            } catch (Exception e) {}
                        }
                        compiler.postProgress(listener, "#ERROR:|Build System|-1|" + line, true);
                    }
                }
            }
            int exitCode = process.waitFor();
            Compiler.activeProcess = null;
            Compiler.checkCancellation();
            return !hadError && exitCode == 0;
        } catch (Exception e) {
            if ("BUILD_CANCELLED".equals(e.getMessage())) return false;
            compiler.postProgress(listener, "#ERROR:|System Output|-1|" + e.getMessage(), true);
            return false;
        }
    }

    public void findFiles(File dir, String extension, List<String> list) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) findFiles(f, extension, list);
                else if (f.getName().endsWith(extension)) list.add(f.getAbsolutePath());
            }
        }
    }

    public File getSmartDir(File projectDir, String type) {
        File f = new File(projectDir, "app/src/main/" + (type.equals("manifest") ? "AndroidManifest.xml" : type.equals("src") ? "java" : "res"));
        if (!f.exists()) f = new File(projectDir, "src/main/" + (type.equals("manifest") ? "AndroidManifest.xml" : type.equals("src") ? "java" : "res"));
        if (!f.exists()) f = new File(projectDir, type.equals("manifest") ? "AndroidManifest.xml" : type);
        return f;
    }

    public String extractPackageName(File manifestFile) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(manifestFile));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("package=")) {
                    int start = line.indexOf("package=\"") + 9;
                    if (start > 8) {
                        int end = line.indexOf("\"", start);
                        if (end > start) {
                            br.close();
                            return line.substring(start, end);
                        }
                    }
                }
            }
            br.close();
        } catch (Exception e) {}
        return null;
    }

    public void generateLibraryRJava(File genDir, List<File> libraryDirs, Compiler.CompileListener listener) {
        List<String> rFiles = new ArrayList<>();
        findFiles(genDir, "R.java", rFiles);
        if (rFiles.isEmpty()) return;

        File mainRJava = new File(rFiles.get(0));
        try {
            StringBuilder rContent = new StringBuilder();
            BufferedReader br = new BufferedReader(new FileReader(mainRJava));
            String line;
            String originalPackageLine = "";
            while ((line = br.readLine()) != null) {
                if (line.startsWith("package ") && line.endsWith(";")) originalPackageLine = line;
                rContent.append(line).append("\n");
            }
            br.close();

            if (originalPackageLine.isEmpty()) return;
            String rawContent = rContent.toString();

            if (libraryDirs != null) {
                for (File libDir : libraryDirs) {
                    if (!libDir.isDirectory()) continue;
                    File manifest = new File(libDir, "AndroidManifest.xml");
                    if (manifest.exists()) {
                        String pkgName = extractPackageName(manifest);
                        if (pkgName != null && !pkgName.isEmpty() && !originalPackageLine.contains(pkgName)) {
                            String newPackageLine = "package " + pkgName + ";";
                            String newContent = rawContent.replace(originalPackageLine, newPackageLine);
                            File targetDir = new File(genDir, pkgName.replace(".", "/"));
                            targetDir.mkdirs();
                            FileOutputStream fos = new FileOutputStream(new File(targetDir, "R.java"));
                            fos.write(newContent.getBytes());
                            fos.flush();
                            fos.close();
                        }
                    }
                }
            }
        } catch (Exception e) {}
    }

    public int getProjectMinSdk(File projectDir) {
        File buildGradle = new File(projectDir, "app/build.gradle");
        if (!buildGradle.exists()) buildGradle = new File(projectDir, "build.gradle");
        if (buildGradle.exists()) {
            try {
                BufferedReader br = new BufferedReader(new FileReader(buildGradle));
                String line;
                Pattern p = Pattern.compile("minSdk(?:Version)?\\s*[=\\s]\\s*(\\d+)");
                while ((line = br.readLine()) != null) {
                    Matcher m = p.matcher(line);
                    if (m.find()) {
                        int parsed = Integer.parseInt(m.group(1));
                        br.close();
                        return Math.max(21, parsed);
                    }
                }
                br.close();
            } catch (Exception ignored) {}
        }
        return 21;
    }
}