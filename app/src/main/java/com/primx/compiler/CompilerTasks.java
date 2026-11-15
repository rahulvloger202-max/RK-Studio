package com.primx.compiler;

import com.primx.compiler.Compiler;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class CompilerTasks {

    private Compiler compiler;
    private CompilerUtils utils;

    public CompilerTasks(Compiler compiler, CompilerUtils utils) {
        this.compiler = compiler;
        this.utils = utils;
    }

    public List<File> resolveGradleDependencies(File projectDir, final Compiler.CompileListener listener) {
        compiler.postProgress(listener, "#PROGRESS:10:Resolving Dependencies...", false);

        AndroidX androidX = new AndroidX(compiler.toolsDirPath, projectDir.getAbsolutePath(), new AndroidX.AndroidXListener() {
            @Override
            public void onLog(String message) {
                compiler.postProgress(listener, message, false);
            }
        });

        List<File> loadedLibraries = androidX.resolveAndDownload();
        if (loadedLibraries == null) loadedLibraries = new ArrayList<>();
        return loadedLibraries;
    }

    public boolean runAapt(File projectDir, List<File> libraryDirs, Compiler.CompileListener listener) {
        File resDir = utils.getSmartDir(projectDir, "res");
        File manifestFile = utils.getSmartDir(projectDir, "manifest");
        File buildDir = new File(projectDir, "build");
        File genDir = new File(buildDir, "gen");
        genDir.mkdirs();
        File compiledRes = new File(buildDir, "compiled_res.zip");
        File linkedRes = new File(buildDir, "linked_res.ap_");

        utils.ensureDummyAttrs(resDir);

        utils.forceExtractAsset("aapt2", listener);
        utils.forceExtractAsset("android.jar", listener);

        String aapt2Path = compiler.toolsDirPath + "/aapt2";
        new File(aapt2Path).setExecutable(true);

        List<String> existingRFiles = new ArrayList<>();
        utils.findFiles(genDir, "R.java", existingRFiles);

        long resMaxTime = Math.max(utils.getMaxModifiedTime(resDir), manifestFile.lastModified());
        if (linkedRes.exists() && compiledRes.exists() && !existingRFiles.isEmpty() && resMaxTime <= linkedRes.lastModified()) {
            compiler.postProgress(listener, "#PROGRESS:40:Resources untouched. Skipping AAPT2", false);
            return true;
        }

        List<String> compileCmd = new ArrayList<>();
        compileCmd.add(aapt2Path);
        compileCmd.add("compile");
        compileCmd.add("--dir");
        compileCmd.add(resDir.getAbsolutePath());
        compileCmd.add("--no-crunch");
        compileCmd.add("-o");
        compileCmd.add(compiledRes.getAbsolutePath());
        if (!utils.executeCommand(compileCmd.toArray(new String[0]), listener, true)) return false;

        List<String> compiledResZips = new ArrayList<>();
        compiledResZips.add(compiledRes.getAbsolutePath());

        if (libraryDirs != null) {
            for (File libDir : libraryDirs) {
                if (libDir.isDirectory()) {
                    File libRes = new File(libDir, "res");
                    if (libRes.exists()) {
                        File libCompiledZip = new File(libDir, "compiled_res.zip");
                        if (!libCompiledZip.exists()) {
                            List<String> libCompCmd = new ArrayList<>();
                            libCompCmd.add(aapt2Path);
                            libCompCmd.add("compile");
                            libCompCmd.add("--dir");
                            libCompCmd.add(libRes.getAbsolutePath());
                            libCompCmd.add("--no-crunch");
                            libCompCmd.add("-o");
                            libCompCmd.add(libCompiledZip.getAbsolutePath());
                            boolean success = utils.executeCommand(libCompCmd.toArray(new String[0]), listener, true);
                            if (!success && libCompiledZip.exists()) libCompiledZip.delete();
                        }
                        if (libCompiledZip.exists()) compiledResZips.add(libCompiledZip.getAbsolutePath());
                    }
                }
            }
        }

        List<String> linkCmd = new ArrayList<>();
        linkCmd.add(aapt2Path);
        linkCmd.add("link");
        linkCmd.add("-I");
        linkCmd.add(compiler.toolsDirPath + "/android.jar");
        linkCmd.add("--manifest");
        linkCmd.add(manifestFile.getAbsolutePath());
        linkCmd.add("--min-sdk-version");
        linkCmd.add("21");
        linkCmd.add("--target-sdk-version");
        linkCmd.add("33");
        linkCmd.add("--java");
        linkCmd.add(genDir.getAbsolutePath());
        linkCmd.add("--auto-add-overlay");

        // 🔥 YAHI WO JADUI CODE HAI JO ASSETS FOLDER KO APK KE ANDAR PACK KAREGA
        File assetsDir = new File(projectDir, "app/src/main/assets");
        if (!assetsDir.exists()) assetsDir = new File(projectDir, "src/main/assets");
        if (!assetsDir.exists()) assetsDir = new File(projectDir, "assets");

        if (assetsDir.exists() && assetsDir.isDirectory()) {
            linkCmd.add("-A");
            linkCmd.add(assetsDir.getAbsolutePath());
        }

        linkCmd.add("-o");
        linkCmd.add(linkedRes.getAbsolutePath());

        for (String zipPath : compiledResZips) {
            linkCmd.add("-R");
            linkCmd.add(zipPath);
        }

        boolean success = utils.executeCommand(linkCmd.toArray(new String[0]), listener, true);
        if (success) utils.generateLibraryRJava(genDir, libraryDirs, listener);
        return success;
    }

    public boolean runJavaCompiler(File projectDir, List<File> libraryDirs, Compiler.CompileListener listener) {
        utils.forceExtractAsset("ecj.jar", listener);
        utils.forceExtractAsset("android.jar", listener);
        utils.forceExtractAsset("core-lambda-stubs.jar", listener);

        String ecjPath = compiler.toolsDirPath + "/ecj.jar";
        String androidJarPath = compiler.toolsDirPath + "/android.jar";

        File srcDir = utils.getSmartDir(projectDir, "src");
        File buildDir = new File(projectDir, "build");
        if (!buildDir.exists()) buildDir.mkdirs();

        File genDir = new File(buildDir, "gen");
        File objDir = new File(buildDir, "obj");
        objDir.mkdirs();
        File cacheFile = new File(buildDir, "java_cache.txt");

        List<String> javaFiles = new ArrayList<>();
        utils.findFiles(srcDir, ".java", javaFiles);
        utils.findFiles(genDir, ".java", javaFiles);

        if (javaFiles.isEmpty()) {
            compiler.postProgress(listener, "[ERROR] Project System: No Java files found to compile!", true);
            return false;
        }

        List<String> existingClasses = new ArrayList<>();
        utils.findFiles(objDir, ".class", existingClasses);

        HashMap<String, Long> oldCache = new HashMap<>();
        if (cacheFile.exists() && !existingClasses.isEmpty()) {
            try {
                BufferedReader br = new BufferedReader(new FileReader(cacheFile));
                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split("::");
                    if (parts.length == 2) oldCache.put(parts[0], Long.parseLong(parts[1]));
                }
                br.close();
            } catch (Exception e) {}
        }

        HashMap<String, Long> newCache = new HashMap<>();
        List<String> filesToCompile = new ArrayList<>();
        boolean forceFullCompile = existingClasses.isEmpty();

        for (String jFile : javaFiles) {
            long modTime = new File(jFile).lastModified();
            newCache.put(jFile, modTime);
            if (!oldCache.containsKey(jFile) || oldCache.get(jFile) != modTime) {
                filesToCompile.add(jFile);
            }
        }

        for (String oldFile : oldCache.keySet()) {
            if (!newCache.containsKey(oldFile)) {
                forceFullCompile = true;
                break;
            }
        }

        if (forceFullCompile) {
            utils.deleteRecursively(objDir);
            objDir.mkdirs();
            filesToCompile.clear();
            filesToCompile.addAll(javaFiles);
        } else if (filesToCompile.isEmpty()) {
            compiler.postProgress(listener, "#PROGRESS:65:Java classes untouched. Skipping ECJ", false);
            return true;
        }

        StringBuilder classpathBuilder = new StringBuilder(androidJarPath);

        File lambdaStubs = new File(compiler.toolsDirPath, "core-lambda-stubs.jar");
        if (!lambdaStubs.exists()) lambdaStubs = new File(projectDir, "app/libs/core-lambda-stubs.jar");
        if (lambdaStubs.exists()) {
            classpathBuilder.append(File.pathSeparator).append(lambdaStubs.getAbsolutePath());
        }

        classpathBuilder.append(File.pathSeparator).append(objDir.getAbsolutePath());

        if (libraryDirs != null) {
            for (File libDir : libraryDirs) {
                if (libDir.isDirectory()) {
                    File classesJar = new File(libDir, "classes.jar");
                    if (classesJar.exists()) {
                        classpathBuilder.append(File.pathSeparator).append(classesJar.getAbsolutePath());
                    }
                } else if (libDir.getName().endsWith(".jar")) {
                    classpathBuilder.append(File.pathSeparator).append(libDir.getAbsolutePath());
                }
            }
        }

        File appLibsDir = new File(projectDir, "app/libs");
        if (!appLibsDir.exists()) appLibsDir = new File(projectDir, "libs");
        if (appLibsDir.exists()) {
            File[] libs = appLibsDir.listFiles();
            if (libs != null) {
                for (File libFile : libs) {
                    if (libFile.getName().endsWith(".jar") && !libFile.getName().equals("core-lambda-stubs.jar") && !libFile.getName().equals("ecj.jar")) {
                        classpathBuilder.append(File.pathSeparator).append(libFile.getAbsolutePath());
                    }
                }
            }
        }

        StringBuilder sourcepathBuilder = new StringBuilder(srcDir.getAbsolutePath());
        if (genDir.exists()) {
            sourcepathBuilder.append(File.pathSeparator).append(genDir.getAbsolutePath());
        }

        List<String> cmd = new ArrayList<>();
        cmd.add("dalvikvm");
        cmd.add(compiler.dynamicXmx);
        cmd.add("-cp");

        String customLangZip = utils.prepareCustomEcjMessages();
        if (customLangZip != null && new File(customLangZip).exists()) {
            cmd.add(customLangZip + File.pathSeparator + ecjPath);
        } else {
            cmd.add(ecjPath);
        }

        cmd.add("org.eclipse.jdt.internal.compiler.batch.Main");
        cmd.add("-1.8");
        cmd.add("-target");
        cmd.add("1.8");
        cmd.add("-proc:none");
        cmd.add("-d");
        cmd.add(objDir.getAbsolutePath());
        cmd.add("-classpath");
        cmd.add(classpathBuilder.toString());
        cmd.add("-sourcepath");
        cmd.add(sourcepathBuilder.toString());
        cmd.addAll(filesToCompile);

        try {
            Compiler.checkCancellation();
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            Compiler.activeProcess = process;

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            StringBuilder rawOutput = new StringBuilder();
            boolean hasStandardError = false;

            String currentFile = "";
            int currentLine = -1;
            String currentMessage = "";

            while ((line = reader.readLine()) != null) {
                if (Compiler.isCancelled()) {
                    try { process.destroy(); } catch (Throwable ignored) {}
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        try { process.destroyForcibly(); } catch (Throwable ignored) {}
                    }
                    break;
                }
                rawOutput.append(line).append("\n");
                String trimmed = line.trim();

                if (trimmed.isEmpty()) continue;
                if (trimmed.contains("problems (") && trimmed.contains("errors")) continue;

                if (trimmed.startsWith("----------")) {
                    if (!currentFile.isEmpty() && currentLine != -1 && !currentMessage.isEmpty()) {
                        boolean isWarn = currentMessage.toLowerCase().contains("never used")
                        || currentMessage.toLowerCase().contains("deprecated")
                        || currentMessage.toLowerCase().contains("warning")
                        || currentMessage.toLowerCase().contains("is not used");
                        if (isWarn) {
                            compiler.postProgress(listener, "#WARN:|" + currentFile + "|" + currentLine + "|" + currentMessage, false);
                        } else {
                            compiler.postProgress(listener, "#ERROR:|" + currentFile + "|" + currentLine + "|" + currentMessage, true);
                            hasStandardError = true;
                        }
                    }
                    currentFile = "";
                    currentLine = -1;
                    currentMessage = "";
                    continue;
                }

                Matcher mLine = Pattern.compile("\\(at line\\s*(\\d+)\\)").matcher(line);
                if (mLine.find()) {
                    try { currentLine = Integer.parseInt(mLine.group(1)); } catch (Exception e) {}

                    int inIdx = line.indexOf("in ");
                    int atIdx = line.lastIndexOf(" (at line");
                    if (inIdx != -1 && atIdx != -1 && atIdx > inIdx + 3) {
                        currentFile = line.substring(inIdx + 3, atIdx).trim();
                    }

                    if (currentMessage.isEmpty()) {
                        currentMessage = "Syntax Error: Missing brackets '{}', semicolon ';', or unknown symbol.";
                    }
                    continue;
                }

                if (trimmed.contains("Unable to retrieve") || trimmed.contains("Check compiler resources")) {
                    currentMessage = "Cannot resolve symbol/class. Missing library dependency.";
                    continue;
                }

                if (!trimmed.startsWith("^") && !line.contains("\t") && !line.startsWith(" ")) {
                    currentMessage = trimmed;
                }
            }

            if (!currentFile.isEmpty() && currentLine != -1 && !currentMessage.isEmpty() && !Compiler.isCancelled()) {
                compiler.postProgress(listener, "#ERROR:|" + currentFile + "|" + currentLine + "|" + currentMessage, true);
                hasStandardError = true;
            }

            int exitCode = process.waitFor();
            Compiler.activeProcess = null;

            if (Compiler.isCancelled()) throw new Exception("BUILD_CANCELLED");

            if (!hasStandardError && exitCode != 0) {
                String err = rawOutput.toString().trim();
                if (err.isEmpty()) err = "Compiler failed without explicit error. Exit Code: " + exitCode;
                compiler.postProgress(listener, "#ERROR:|Compiler Error|-1|" + err, true);
                return false;
            }

            boolean isSuccess = exitCode == 0;

            if (isSuccess) {
                try {
                    FileWriter fw = new FileWriter(cacheFile);
                    for (String key : newCache.keySet()) fw.write(key + "::" + newCache.get(key) + "\n");
                    fw.close();
                } catch (Exception e) {}
            }

            return isSuccess;
        } catch (Exception e) {
            if ("BUILD_CANCELLED".equals(e.getMessage())) throw new RuntimeException(e);
            compiler.postProgress(listener, "#ERROR:|Command Exception|-1|" + e.getMessage(), true);
            return false;
        }
    }

    public boolean runDexer(File projectDir, List<File> libraryDirs, Compiler.CompileListener listener) {
        utils.forceExtractAsset("d8.jar", listener);
        utils.forceExtractAsset("d8_1.dex", listener);
        utils.forceExtractAsset("d8_2.dex", listener);
        utils.forceExtractAsset("android.jar", listener);

        String d8Main = compiler.toolsDirPath + "/d8_1.dex";
        String d8Extra = compiler.toolsDirPath + "/d8_2.dex";
        String d8Classpath = d8Main + ":" + d8Extra;
        if (!new File(d8Main).exists()) {
            if (new File(compiler.toolsDirPath, "d8.dex").exists()) {
                d8Classpath = compiler.toolsDirPath + "/d8.dex";
            } else if (new File(compiler.toolsDirPath, "d8.jar").exists()) {
                d8Classpath = compiler.toolsDirPath + "/d8.jar";
            }
        }

        File objDir = new File(projectDir, "build/obj");
        File dexDir = new File(projectDir, "build/dex");
        if (!dexDir.exists()) dexDir.mkdirs();
        File generatedDex = new File(dexDir, "classes.dex");

        List<String> classFiles = new ArrayList<>();
        utils.findFiles(objDir, ".class", classFiles);

        if (classFiles.isEmpty()) {
            compiler.postProgress(listener, "[DEX ERROR] No .class files generated to Dex!", true);
            return false;
        }

        List<File> rawInputJars = new ArrayList<>();
        HashSet<String> addedJarPaths = new HashSet<>();

        if (libraryDirs != null) {
            for (File libFile : libraryDirs) {
                if (libFile.isDirectory()) {
                    if (libFile.getName().toLowerCase().contains("core-lambda-stubs")) continue;
                    File classesJar = new File(libFile, "classes.jar");
                    if (classesJar.exists() && addedJarPaths.add(classesJar.getAbsolutePath())) {
                        rawInputJars.add(classesJar);
                    }
                } else if (libFile.getName().endsWith(".jar")) {
                    String fName = libFile.getName().toLowerCase();
                    if (fName.contains("core-lambda-stubs") || fName.equals("ecj.jar") || fName.equals("android.jar")) {
                        continue;
                    }
                    if (addedJarPaths.add(libFile.getAbsolutePath())) {
                        rawInputJars.add(libFile);
                    }
                }
            }
        }

        File appLibsDir = new File(projectDir, "app/libs");
        if (!appLibsDir.exists()) appLibsDir = new File(projectDir, "libs");
        if (appLibsDir.exists()) {
            File[] libs = appLibsDir.listFiles();
            if (libs != null) {
                for (File libFile : libs) {
                    String fName = libFile.getName().toLowerCase();
                    if (fName.endsWith(".jar")
                    && !fName.contains("core-lambda-stubs")
                    && !fName.equals("ecj.jar")
                    && !fName.equals("android.jar")
                    && !fName.equals("d8.jar")) {
                        if (addedJarPaths.add(libFile.getAbsolutePath())) {
                            rawInputJars.add(libFile);
                        }
                    }
                }
            }
        }

        if (generatedDex.exists()) {
            long dexTime = generatedDex.lastModified();
            boolean needsDexing = false;
            for (String cFile : classFiles) {
                if (new File(cFile).lastModified() > dexTime) {
                    needsDexing = true;
                    break;
                }
            }
            if (!needsDexing) {
                for (File jar : rawInputJars) {
                    if (jar.lastModified() > dexTime) {
                        needsDexing = true;
                        break;
                    }
                }
            }
            if (!needsDexing) {
                compiler.postProgress(listener, "#PROGRESS:85:DEX untouched. Skipping D8", false);
                return true;
            }
        }

        File[] oldDexFiles = dexDir.listFiles();
        if (oldDexFiles != null) {
            for (File oldDex : oldDexFiles) {
                if (oldDex.getName().endsWith(".dex")) oldDex.delete();
            }
        }

        List<String> cmd = new ArrayList<>();
        cmd.add("dalvikvm");
        cmd.add(compiler.dynamicXmx);
        cmd.add("-Djava.util.concurrent.ForkJoinPool.common.parallelism=1");
        cmd.add("-Dcom.android.tools.r8.emitKotlinMetadata=false");
        cmd.add("-cp");
        cmd.add(d8Classpath);
        cmd.add("com.android.tools.r8.D8");

        int minApi = utils.getProjectMinSdk(projectDir);
        if (minApi < 21) minApi = 21;

        cmd.add("--release");
        cmd.add("--min-api");
        cmd.add(String.valueOf(minApi));
        cmd.add("--thread-count");
        cmd.add("1");
        cmd.add("--lib");
        cmd.add(compiler.toolsDirPath + "/android.jar");

        HashSet<String> seenClassEntries = new HashSet<>();
        String objRootPath = objDir.getAbsolutePath();
        for (String cFile : classFiles) {
            if (cFile.startsWith(objRootPath)) {
                String rel = cFile.substring(objRootPath.length()).replace("\\", "/");
                if (rel.startsWith("/")) rel = rel.substring(1);
                seenClassEntries.add(rel);
            }
        }

        for (File jarFile : rawInputJars) {
            File cleanJar = getCleanJarForDex(jarFile, seenClassEntries);
            if (cleanJar != null && cleanJar.exists()) {
                cmd.add(cleanJar.getAbsolutePath());
            }
        }

        cmd.add("--output");
        cmd.add(dexDir.getAbsolutePath());

        HashSet<String> addedClasses = new HashSet<>();
        for (String cFile : classFiles) {
            if (addedClasses.add(cFile)) {
                cmd.add(cFile);
            }
        }

        try {
            Compiler.checkCancellation();
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            Compiler.activeProcess = process;

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            boolean hadFatalError = false;
            StringBuilder dexErrorLog = new StringBuilder();

            while ((line = reader.readLine()) != null) {
                if (Compiler.isCancelled()) {
                    try { process.destroy(); } catch (Throwable ignored) {}
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        try { process.destroyForcibly(); } catch (Throwable ignored) {}
                    }
                    break;
                }
                if (line.contains("malformed kotlin.metadata") || line.contains("Info: Class")
                || line.contains("Exception occurred") || line.contains("com.android.tools.r8")
                || line.contains("was not found") || line.contains("Should never be called")
                || line.contains("ForkJoinPool")) continue;

                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.toLowerCase().contains("warning")) {
                    dexErrorLog.append(trimmed).append("\n");
                    if (trimmed.toLowerCase().contains("error:") || trimmed.toLowerCase().contains("fatal:")
                    || trimmed.toLowerCase().contains("exception")) {
                        hadFatalError = true;
                    }
                }
            }
            int exitCode = process.waitFor();
            Compiler.activeProcess = null;

            if (Compiler.isCancelled()) throw new Exception("BUILD_CANCELLED");

            if (exitCode != 0 || hadFatalError) {
                String finalErr = dexErrorLog.toString().trim();
                if (finalErr.isEmpty()) finalErr = "DEX Crashed! Exit Code: " + exitCode + "\n(Out of Memory or MultiDex Error)";
                compiler.postProgress(listener, "#ERROR:|DEX Build|-1|" + finalErr, true);
                return false;
            }
            return generatedDex.exists();

        } catch (Exception e) {
            if ("BUILD_CANCELLED".equals(e.getMessage())) throw new RuntimeException(e);
            compiler.postProgress(listener, "#ERROR:|System Process|-1|" + e.getMessage(), true);
            return false;
        }
    }

    private File getCleanJarForDex(File jarFile, HashSet<String> seenClassEntries) {
        if (jarFile == null || !jarFile.exists()) return null;
        if (jarFile.getName().toLowerCase().contains("core-lambda-stubs")) return null;

        ZipFile zf = null;
        try {
            zf = new ZipFile(jarFile);
            boolean needsFiltering = false;
            int validClassCount = 0;

            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory() && name.endsWith(".class")) {
                    if (name.equals("module-info.class") || name.startsWith("java/lang/invoke/")) {
                        needsFiltering = true;
                        continue;
                    }
                    if (seenClassEntries.contains(name)) {
                        needsFiltering = true;
                    } else {
                        validClassCount++;
                    }
                }
            }

            if (validClassCount == 0) {
                return null;
            }

            if (!needsFiltering) {
                entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!entry.isDirectory() && name.endsWith(".class")) {
                        seenClassEntries.add(name);
                    }
                }
                return jarFile;
            }

            File dedupJar = new File(jarFile.getParentFile(), jarFile.getName().replace(".jar", "_dedup.jar"));
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(dedupJar));
            byte[] buffer = new byte[8192];

            entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory()
                || !name.endsWith(".class")
                || name.equals("module-info.class")
                || name.startsWith("java/lang/invoke/")) {
                    continue;
                }
                if (seenClassEntries.add(name)) {
                    ZipEntry newEntry = new ZipEntry(name);
                    zos.putNextEntry(newEntry);
                    InputStream is = zf.getInputStream(entry);
                    int len;
                    while ((len = is.read(buffer)) > 0) {
                        zos.write(buffer, 0, len);
                    }
                    is.close();
                    zos.closeEntry();
                }
            }
            zos.close();
            return dedupJar;
        } catch (Exception e) {
            return jarFile;
        } finally {
            if (zf != null) {
                try { zf.close(); } catch (Exception ignored) {}
            }
        }
    }

    public String buildAndSignApk(File projectDir, Compiler.CompileListener listener) {
        File linkedRes = new File(projectDir, "build/linked_res.ap_");
        File dexFile = new File(projectDir, "build/dex/classes.dex");
        File apkDir = new File(projectDir, "build/apk");
        apkDir.mkdirs();

        File unsignedApk = new File(apkDir, "app-unsigned.apk");
        File alignedApk = new File(apkDir, "app-aligned.apk");
        File signedApk = new File(apkDir, "app-debug.apk");

        try {
            Compiler.checkCancellation();
            ZipFile zipFile = new ZipFile(linkedRes);
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(unsignedApk));

            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry inEntry = entries.nextElement();
                ZipEntry outEntry = new ZipEntry(inEntry.getName());
                outEntry.setMethod(inEntry.getMethod());
                outEntry.setTime(inEntry.getTime());
                if (inEntry.getMethod() == ZipEntry.STORED) {
                    outEntry.setSize(inEntry.getSize());
                    outEntry.setCrc(inEntry.getCrc());
                }
                zos.putNextEntry(outEntry);
                InputStream is = zipFile.getInputStream(inEntry);
                byte[] buffer = new byte[4096];
                int len;
                while ((len = is.read(buffer)) > 0) zos.write(buffer, 0, len);
                is.close();
                zos.closeEntry();
            }
            zipFile.close();

            if (dexFile.exists()) {
                zos.putNextEntry(new ZipEntry("classes.dex"));
                FileInputStream fis = new FileInputStream(dexFile);
                byte[] buffer = new byte[4096];
                int len;
                while ((len = fis.read(buffer)) > 0) zos.write(buffer, 0, len);
                fis.close();
                zos.closeEntry();
            }

            int dexIndex = 2;
            while (true) {
                File nextDex = new File(projectDir, "build/dex/classes" + dexIndex + ".dex");
                if (!nextDex.exists()) break;

                zos.putNextEntry(new ZipEntry("classes" + dexIndex + ".dex"));
                FileInputStream nf = new FileInputStream(nextDex);
                byte[] nbuf = new byte[4096];
                int nlen;
                while ((nlen = nf.read(nbuf)) > 0) zos.write(nbuf, 0, nlen);
                nf.close();
                zos.closeEntry();
                dexIndex++;
            }

            // 👇 Universal Native Libraries (.so) Packager (Fixed & Standardized)
            String[] possibleJniPaths = {
                "app/src/main/jniLibs",
                "app/src/main/libs",
                "app/jniLibs",
                "app/libs",
                "jniLibs",
                "libs"
            };

            for (String path : possibleJniPaths) {
                File jniLibsDir = new File(projectDir, path);

                if (jniLibsDir.exists() && jniLibsDir.isDirectory()) {
                    boolean foundAnySoFile = false;
                    File[] abiDirs = jniLibsDir.listFiles();

                    if (abiDirs != null) {
                        for (File abiDir : abiDirs) {
                            if (abiDir.isDirectory()) {
                                // Important: Check if folder is a valid ABI (like arm64-v8a, armeabi-v7a)
                                String abiName = abiDir.getName();
                                File[] soFiles = abiDir.listFiles();

                                if (soFiles != null) {
                                    for (File soFile : soFiles) {
                                        if (soFile.getName().endsWith(".so")) {
                                            foundAnySoFile = true;
                                            // 🔥 NAYA CODE: Yahan APK ke andar standard lib/<abi>/<file>.so structure banaya gaya hai
                                            String apkPath = "lib/" + abiName + "/" + soFile.getName();
                                            zos.putNextEntry(new ZipEntry(apkPath));

                                            FileInputStream fisSo = new FileInputStream(soFile);
                                            byte[] soBuffer = new byte[4096];
                                            int soLen;
                                            while ((soLen = fisSo.read(soBuffer)) > 0) {
                                                zos.write(soBuffer, 0, soLen);
                                            }
                                            fisSo.close();
                                            zos.closeEntry();
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (foundAnySoFile) {
                        compiler.postProgress(listener, "✓ Native C++ libraries packed successfully!", false);
                        break;
                    }
                }
            }
            // 👆 Universal Native Packaging Code Khatam
            zos.close();
        } catch (Exception e) {
            return null;
        }

        utils.forceExtractAsset("zipalign", listener);
        utils.forceExtractAsset("apksigner.jar", listener);
        utils.forceExtractAsset("testkey.pk8", listener);
        utils.forceExtractAsset("testkey.x509.pem", listener);

        String zipalignPath = compiler.toolsDirPath + "/zipalign";
        new File(zipalignPath).setExecutable(true);

        if (!utils.executeCommand(new String[]{zipalignPath, "-p", "-f", "4", unsignedApk.getAbsolutePath(), alignedApk.getAbsolutePath()}, listener, false)) return null;

        String apksignerPath = compiler.toolsDirPath + "/apksigner.jar";
        String pk8Path = compiler.toolsDirPath + "/testkey.pk8";
        String pemPath = compiler.toolsDirPath + "/testkey.x509.pem";

        if (!utils.executeCommand(new String[]{"dalvikvm", compiler.dynamicXmx, "-cp", apksignerPath, "com.android.apksigner.ApkSignerTool", "sign", "--key", pk8Path, "--cert", pemPath, "--out", signedApk.getAbsolutePath(), alignedApk.getAbsolutePath()}, listener, false)) return null;

        return signedApk.getAbsolutePath();
    }
}