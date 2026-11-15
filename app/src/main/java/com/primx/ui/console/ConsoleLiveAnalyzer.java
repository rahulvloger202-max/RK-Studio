package com.primx.ui.console;


import android.content.Context;
import android.content.SharedPreferences;

import com.primx.ui.mainactivity.MainActivity;

import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import dalvik.system.DexClassLoader;

public class ConsoleLiveAnalyzer {

    private final Console console;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicLong analysisGeneration = new AtomicLong(0);

    public File lastProjectDir;
    public String lastToolsDirPath;
    public String lastDynamicXmx;

    private File cachedAndroidJar;
    private File cachedLambdaJar;
    private File cachedEcjJar;

    private static final Pattern ECJ_HEADER_PATTERN = Pattern.compile(
    "^\\d+\\.\\s+(ERROR|WARNING)\\s+in\\s+(.+?)\\s+\\(at line\\s+(\\d+)\\)\\s*$"
    );

    public ConsoleLiveAnalyzer(Console console) {
        this.console = console;
    }

    public void triggerQuickAnalysis(final File activeFile, final String unsavedContent) {
        File projDir = resolveProjectDir(activeFile);
        if (projDir != null) {
            this.lastProjectDir = projDir;
        }
        startLiveProjectAnalysisInternal(this.lastProjectDir, this.lastToolsDirPath, this.lastDynamicXmx, activeFile, unsavedContent);
    }

    public void startLiveProjectAnalysis(final File projectDir, final String toolsDirPath, final String dynamicXmx, final File activeFile) {
        if (projectDir != null) {
            this.lastProjectDir = projectDir;
        } else {
            this.lastProjectDir = resolveProjectDir(activeFile);
        }
        if (toolsDirPath != null && !toolsDirPath.isEmpty()) {
            this.lastToolsDirPath = toolsDirPath;
        }
        if (dynamicXmx != null && !dynamicXmx.isEmpty()) {
            this.lastDynamicXmx = dynamicXmx;
        }
        startLiveProjectAnalysisInternal(this.lastProjectDir, this.lastToolsDirPath, this.lastDynamicXmx, activeFile, null);
    }

        private void startLiveProjectAnalysisInternal(final File projectDir, final String toolsDirPath, final String dynamicXmx, final File activeFile, final String unsavedContent) {
        final long myGen = analysisGeneration.incrementAndGet();

        executor.submit(new Runnable() {
            @Override
            public void run() {
                List<Console.ErrorItem> fastErrors = new ArrayList<>();
                boolean skipHeavyBoss = false;

                if (activeFile != null && unsavedContent != null) {
                    List<Console.ErrorItem> quickCheck = FastSyntaxParser.analyze(activeFile.getAbsolutePath(), unsavedContent);
                    if (quickCheck != null) {
                        fastErrors.addAll(quickCheck);
                        if (!fastErrors.isEmpty()) {
                            skipHeavyBoss = true;
                        }
                    }
                }

                if (skipHeavyBoss) {
                    if (myGen == analysisGeneration.get()) {
                        console.applyLiveAnalysis(activeFile, fastErrors);
                    }
                    return;
                }

                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }

                if (myGen != analysisGeneration.get()) return;

                File targetProject = projectDir != null ? projectDir : resolveProjectDir(activeFile);
                if (targetProject == null || !targetProject.exists()) return;

                lastProjectDir = targetProject;
                if (activeFile != null) {
                    console.lastActiveFile = activeFile;
                }

                List<Console.ErrorItem> allErrors = new ArrayList<>();
                allErrors.addAll(fastErrors);

                analyzeAllXmlFiles(targetProject, activeFile, unsavedContent, allErrors);
                if (myGen != analysisGeneration.get()) return;

                analyzeAllJavaFilesWithEcj(targetProject, activeFile, unsavedContent, allErrors);
                if (myGen != analysisGeneration.get()) return;

                console.applyLiveAnalysis(activeFile, allErrors);
            }
        });
    }

    private File resolveProjectDir(File activeFile) {
        if (MainActivity.currentProjectPath != null && !MainActivity.currentProjectPath.isEmpty()) {
            File f = new File(MainActivity.currentProjectPath);
            if (f.exists() && f.isDirectory()) return f;
        }
        try {
            SharedPreferences prefs = console.getContext().getSharedPreferences("EditorPrefs", Context.MODE_PRIVATE);
            String savedProj = prefs.getString("projectPath", "");
            if (savedProj != null && !savedProj.isEmpty()) {
                File f = new File(savedProj);
                if (f.exists() && f.isDirectory()) return f;
            }
        } catch (Exception ignored) {}

        if (activeFile != null) {
            File curr = activeFile.isDirectory() ? activeFile : activeFile.getParentFile();
            while (curr != null) {
                File appDir = new File(curr, "app");
                File srcDir = new File(curr, "src");
                File manifest = new File(curr, "src/main/AndroidManifest.xml");
                if ((appDir.exists() && appDir.isDirectory()) || manifest.exists() || (srcDir.exists() && new File(curr, "build.gradle").exists())) {
                    if (curr.getName().equals("app") && curr.getParentFile() != null) {
                        return curr.getParentFile();
                    }
                    return curr;
                }
                curr = curr.getParentFile();
            }
        }
        return lastProjectDir;
    }

    private void analyzeAllXmlFiles(File projectDir, File activeFile, String unsavedContent, List<Console.ErrorItem> outErrors) {
        List<File> xmlFiles = new ArrayList<>();
        collectFilesByExtension(projectDir, ".xml", xmlFiles);

        String activeCanon = activeFile != null ? Console.canon(activeFile.getAbsolutePath()) : "";

        for (File xmlFile : xmlFiles) {
            String path = xmlFile.getAbsolutePath();
            if (path.contains("/build/") || path.contains("/.git/") || path.contains("/.gradle/")) {
                continue;
            }
            boolean isUnsavedActive = !activeCanon.isEmpty() && Console.canon(path).equals(activeCanon) && unsavedContent != null;
            String content = isUnsavedActive ? unsavedContent : readFileText(xmlFile);
            validateSingleXml(path, content, outErrors);
        }

        // If active file is a newly created XML not yet found in scan
        if (activeFile != null && activeFile.getName().toLowerCase().endsWith(".xml")) {
            boolean alreadyChecked = false;
            for (File f : xmlFiles) {
                if (Console.canon(f.getAbsolutePath()).equals(activeCanon)) {
                    alreadyChecked = true;
                    break;
                }
            }
            if (!alreadyChecked) {
                String content = unsavedContent != null ? unsavedContent : readFileText(activeFile);
                validateSingleXml(activeFile.getAbsolutePath(), content, outErrors);
            }
        }
    }

    private void validateSingleXml(final String filePath, String xmlContent, final List<Console.ErrorItem> outErrors) {
        if (xmlContent == null) return;
        if (xmlContent.trim().isEmpty()) {
            outErrors.add(new Console.ErrorItem(filePath, 1, 0, 1, "XML file is empty", false, false));
            return;
        }
        final String[] lines = xmlContent.split("\n", -1);
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            SAXParser parser = factory.newSAXParser();
            parser.parse(new InputSource(new StringReader(xmlContent)), new DefaultHandler() {
                @Override
                public void warning(SAXParseException e) {
                    addSaxIssue(filePath, lines, e, true, outErrors);
                }

                @Override
                public void error(SAXParseException e) {
                    addSaxIssue(filePath, lines, e, false, outErrors);
                }

                @Override
                public void fatalError(SAXParseException e) {
                    addSaxIssue(filePath, lines, e, false, outErrors);
                }
            });
        } catch (SAXParseException se) {
            addSaxIssue(filePath, lines, se, false, outErrors);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "XML Syntax Error";
            outErrors.add(new Console.ErrorItem(filePath, 1, 0, 1, msg, false, false));
        }
    }

    private void addSaxIssue(String filePath, String[] lines, SAXParseException e, boolean isWarn, List<Console.ErrorItem> outErrors) {
        int line = Math.max(1, e.getLineNumber());
        int colStart = 0;
        int colLen = 1;
        if (line - 1 >= 0 && line - 1 < lines.length) {
            String lineStr = lines[line - 1];
            int trimmedStart = lineStr.indexOf(lineStr.trim());
            if (trimmedStart >= 0) {
                colStart = trimmedStart;
                colLen = Math.max(1, lineStr.trim().length());
            }
        }
        String msg = e.getMessage() != null ? e.getMessage() : "XML Syntax Error";
        for (Console.ErrorItem existing : outErrors) {
            if (existing.filePath.equals(filePath) && existing.line == line && existing.message.equals(msg)) {
                return;
            }
        }
        outErrors.add(new Console.ErrorItem(filePath, line, colStart, colLen, msg, isWarn, false));
    }

    private void analyzeAllJavaFilesWithEcj(File projectDir, File activeFile, String unsavedContent, List<Console.ErrorItem> outErrors) {
        List<File> javaFiles = new ArrayList<>();
        collectFilesByExtension(projectDir, ".java", javaFiles);

        if (javaFiles.isEmpty()) return;

        ensureSdkToolsReady();

        // Map for temporary unsaved RAM buffer override
        File tempBufferFile = null;
        String activeCanon = activeFile != null ? Console.canon(activeFile.getAbsolutePath()) : "";
        Map<String, String> tempToRealPathMap = new HashMap<>();

        List<String> sourceFilePaths = new ArrayList<>();
        try {
            File cacheRoot = new File(console.getContext().getCacheDir(), "primx_live_ram");
            if (!cacheRoot.exists()) cacheRoot.mkdirs();

            for (File jf : javaFiles) {
                String path = jf.getAbsolutePath();
                if (path.contains("/.git/") || path.contains("/.gradle/")) continue;

                if (!activeCanon.isEmpty() && Console.canon(path).equals(activeCanon) && unsavedContent != null) {
                    // 🔥 NAYA LOGIC: Project ka exact structure mirror karo
                    String relativePath = path.substring(projectDir.getAbsolutePath().length());
                    tempBufferFile = new File(cacheRoot.getAbsolutePath() + "/mirror" + relativePath);

                    if (tempBufferFile.getParentFile() != null) {
                        tempBufferFile.getParentFile().mkdirs();
                    }

                    writeFileText(tempBufferFile, unsavedContent);

                    String tempCanon = Console.canon(tempBufferFile.getAbsolutePath());
                    tempToRealPathMap.put(tempCanon, path);
                    tempToRealPathMap.put(tempBufferFile.getAbsolutePath(), path);
                    tempToRealPathMap.put(tempBufferFile.getName(), path); // Fail-safe mapping

                    sourceFilePaths.add(tempBufferFile.getAbsolutePath());
                } else {
                    sourceFilePaths.add(path);
                }
            }

            if (sourceFilePaths.isEmpty()) return;

            // If R.java does not exist yet in build/generated, generate a lightweight RAM stub R.java from res/
            boolean hasRJava = false;
            for (String sp : sourceFilePaths) {
                if (sp.endsWith("/R.java") || sp.endsWith("\\R.java")) {
                    hasRJava = true;
                    break;
                }
            }
            if (!hasRJava) {
                File stubR = generateStubRJavaIfNeeded(projectDir, new File(cacheRoot, "gen"));
                if (stubR != null && stubR.exists()) {
                    sourceFilePaths.add(stubR.getAbsolutePath());
                }
            }

            // Build Classpath (android-30.jar / android.jar + core-lambda-stubs.jar + project libs)
            StringBuilder cpBuilder = new StringBuilder();
            if (cachedAndroidJar != null && cachedAndroidJar.exists()) {
                cpBuilder.append(cachedAndroidJar.getAbsolutePath());
            }
            if (cachedLambdaJar != null && cachedLambdaJar.exists()) {
                if (cpBuilder.length() > 0) cpBuilder.append(File.pathSeparator);
                cpBuilder.append(cachedLambdaJar.getAbsolutePath());
            }

            // Include any .jar files in project's libs/ or app/libs/ or build/
            List<File> projectJars = new ArrayList<>();
            collectFilesByExtension(projectDir, ".jar", projectJars);
            for (File jar : projectJars) {
                if (cpBuilder.length() > 0) cpBuilder.append(File.pathSeparator);
                cpBuilder.append(jar.getAbsolutePath());
            }

            List<String> args = new ArrayList<>();
            args.add("-1.8");
            args.add("-proc:none");
            args.add("-d");
            args.add("none");
            args.add("-encoding");
            args.add("UTF-8");
            if (cpBuilder.length() > 0) {
                args.add("-bootclasspath");
                args.add(cpBuilder.toString());
                args.add("-classpath");
                args.add(cpBuilder.toString());
            }
            args.addAll(sourceFilePaths);

            String ecjOutput = runEcjInProcess(args.toArray(new String[0]));
            if (ecjOutput != null && !ecjOutput.trim().isEmpty()) {
                parseEcjOutput(ecjOutput, tempToRealPathMap, outErrors);
            }
        } catch (Exception ignored) {
        } finally {
            if (tempBufferFile != null && tempBufferFile.exists()) {
                tempBufferFile.delete();
            }
        }
    }

    private String runEcjInProcess(String[] args) {
        StringWriter outSw = new StringWriter();
        StringWriter errSw = new StringWriter();
        PrintWriter outPw = new PrintWriter(outSw);
        PrintWriter errPw = new PrintWriter(errSw);

        try {
            // 1. Direct load from app/libs/ecj-jdt-core.jar in RAM
            Class<?> mainClass = null;
            try {
                mainClass = Class.forName("org.eclipse.jdt.internal.compiler.batch.Main");
            } catch (ClassNotFoundException e) {
                // 2. Fallback: load from assets/ecj.jar via DexClassLoader
                if (cachedEcjJar != null && cachedEcjJar.exists()) {
                    File optDir = new File(console.getContext().getCodeCacheDir(), "ecj_dex");
                    if (!optDir.exists()) optDir.mkdirs();
                    DexClassLoader loader = new DexClassLoader(
                    cachedEcjJar.getAbsolutePath(),
                    optDir.getAbsolutePath(),
                    null,
                    console.getContext().getClassLoader()
                    );
                    mainClass = loader.loadClass("org.eclipse.jdt.internal.compiler.batch.Main");
                }
            }

            if (mainClass != null) {
                Constructor<?> targetCtor = null;
                Object mainInstance = null;
                for (Constructor<?> ctor : mainClass.getConstructors()) {
                    Class<?>[] paramTypes = ctor.getParameterTypes();
                    if (paramTypes.length == 5) {
                        targetCtor = ctor;
                        mainInstance = targetCtor.newInstance(outPw, errPw, Boolean.FALSE, null, null);
                        break;
                    } else if (paramTypes.length == 3) {
                        targetCtor = ctor;
                        mainInstance = targetCtor.newInstance(outPw, errPw, Boolean.FALSE);
                        break;
                    }
                }
                if (mainInstance != null) {
                    Method compileMethod = mainClass.getMethod("compile", String[].class);
                    compileMethod.invoke(mainInstance, new Object[]{args});
                }
            }
        } catch (Exception ignored) {
        } finally {
            outPw.flush();
            errPw.flush();
        }
        return errSw.toString() + "\n" + outSw.toString();
    }

    private void parseEcjOutput(String rawOutput, Map<String, String> tempToRealPathMap, List<Console.ErrorItem> outErrors) {
        String[] lines = rawOutput.replace("\r\n", "\n").split("\n");
        int i = 0;
        String currentFile = "";

        while (i < lines.length) {
            String line = lines[i].trim();

            // 🔥 Track standard file header if present
            Matcher standardM = ECJ_HEADER_PATTERN.matcher(line);
            if (standardM.matches()) {
                currentFile = standardM.group(2).trim();
            }

            // 🔥 Identify start of ANY error block (Standard or Fallback ECJ formats)
            boolean isStandardStart = standardM.matches();
            boolean isFallbackStart1 = line.startsWith("Missing message:") && line.contains("(at line ");
            boolean isFallbackStart2 = line.matches("^\\d+\\s+\\(at line\\s+\\d+\\)$");

            if (isStandardStart || isFallbackStart1 || isFallbackStart2) {
                int lineNumber = 1;
                // Treat "never used", "deprecated", or "warning" as Warnings
                boolean isWarn = line.toLowerCase().contains("warning") || line.toLowerCase().contains("never used") || line.toLowerCase().contains("deprecated");

                // Extract line number robustly
                Matcher lineM = Pattern.compile(".*\\(at line\\s+(\\d+)\\).*").matcher(line);
                if (lineM.matches()) {
                    lineNumber = Integer.parseInt(lineM.group(1));
                }

                String rawPath = currentFile;
                if (rawPath.isEmpty()) {
                    rawPath = console.lastActiveFile != null ? console.lastActiveFile.getAbsolutePath() : "Unknown.java";
                }

                String resolvedPath = rawPath;
                if (tempToRealPathMap.containsKey(rawPath)) {
                    resolvedPath = tempToRealPathMap.get(rawPath);
                } else if (tempToRealPathMap.containsKey(Console.canon(rawPath))) {
                    resolvedPath = tempToRealPathMap.get(Console.canon(rawPath));
                } else {
                    // 🔥 Fallback: Agar compiler ne sirf file ka naam diya hai toh use real file se map karo
                    for (String key : tempToRealPathMap.keySet()) {
                        if (key.endsWith("/" + rawPath) || key.equals(rawPath)) {
                            resolvedPath = tempToRealPathMap.get(key);
                            break;
                        }
                    }
                }

                if (resolvedPath.contains("primx_live_ram/gen") && resolvedPath.endsWith("R.java")) {
                    i++;
                    continue;
                }

                i++;
                String codeLine = (i < lines.length) ? lines[i] : "";
                i++;
                String caretLine = (i < lines.length) ? lines[i] : "";

                int colStart = -1;
                int colLen = -1;

                if (caretLine.contains("^")) {
                    colStart = caretLine.indexOf('^');
                    colLen = caretLine.lastIndexOf('^') - colStart + 1;
                    int offset = codeLine.startsWith("\t") ? 1 : 0;
                    colStart = Math.max(0, colStart - offset);
                    i++;
                }

                // 🔥 Collect message until next error header
                StringBuilder msgBuilder = new StringBuilder();
                while (i < lines.length) {
                    String msgLine = lines[i].trim();
                    if (msgLine.startsWith("----------")) {
                        break;
                    }
                    // Stop appending if we hit the header of the NEXT error
                    if (ECJ_HEADER_PATTERN.matcher(msgLine).matches() ||
                    (msgLine.startsWith("Missing message:") && msgLine.contains("(at line ")) ||
                    msgLine.matches("^\\d+\\s+\\(at line\\s+\\d+\\)$")) {
                        i--; // Step back so the outer loop processes the new error
                        break;
                    }
                    if (msgBuilder.length() > 0) msgBuilder.append(" ");
                    msgBuilder.append(msgLine);
                    i++;
                }

                String finalMsg = console.formatErrorMessage(msgBuilder.toString());
                if (finalMsg.isEmpty()) finalMsg = "Unresolved Compilation Problem / Syntax Error";

                boolean duplicate = false;
                for (Console.ErrorItem item : outErrors) {
                    if (item.filePath.equals(resolvedPath) && item.line == lineNumber && item.message.equals(finalMsg)) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    outErrors.add(new Console.ErrorItem(resolvedPath, lineNumber, colStart, colLen, finalMsg, isWarn, false));
                }
            }
            i++;
        }
    }

    private synchronized void ensureSdkToolsReady() {
        Context ctx = console.getContext();
        File sdkDir = new File(ctx.getFilesDir(), "live_sdk_tools");
        if (!sdkDir.exists()) sdkDir.mkdirs();

        // 1. Locate or extract naya android.jar (ab ye API 31+ ko pehle check karega)
        if (cachedAndroidJar == null || !cachedAndroidJar.exists() || cachedAndroidJar.length() < 1000000L) {
            File candidate = findExistingToolFile("android.jar", "android-30.jar");
            if (candidate != null) {
                cachedAndroidJar = candidate;
            } else {
                File target = new File(sdkDir, "android.jar");
                if (!extractAssetIfPresent(ctx, "android.jar", target)) {
                    target = new File(sdkDir, "android-30.jar");
                    extractAssetIfPresent(ctx, "android-30.jar", target);
                }
                if (target.exists() && target.length() > 1000000L) {
                    cachedAndroidJar = target;
                }
            }
        }

        // 2. Locate or extract core-lambda-stubs.jar
        if (cachedLambdaJar == null || !cachedLambdaJar.exists()) {
            File candidate = findExistingToolFile("core-lambda-stubs.jar");
            if (candidate != null) {
                cachedLambdaJar = candidate;
            } else {
                File target = new File(sdkDir, "core-lambda-stubs.jar");
                if (extractAssetIfPresent(ctx, "core-lambda-stubs.jar", target)) {
                    cachedLambdaJar = target;
                }
            }
        }

        // 3. Locate or extract ecj-jdt-core.jar
        if (cachedEcjJar == null || !cachedEcjJar.exists()) {
            File candidate = findExistingToolFile("ecj-jdt-core.jar");
            if (candidate != null) {
                cachedEcjJar = candidate;
            } else {
                File target = new File(sdkDir, "ecj-jdt-core.jar");
                if (extractAssetIfPresent(ctx, "ecj-jdt-core.jar", target)) {
                    cachedEcjJar = target;
                }
            }
        }
    }
    private File findExistingToolFile(String... names) {
        Context ctx = console.getContext();
        List<File> searchDirs = new ArrayList<>();
        if (lastToolsDirPath != null && !lastToolsDirPath.isEmpty()) {
            searchDirs.add(new File(lastToolsDirPath));
        }
        searchDirs.add(ctx.getFilesDir());
        searchDirs.add(new File(ctx.getFilesDir(), "tools"));
        searchDirs.add(new File(ctx.getFilesDir(), "bin"));
        searchDirs.add(new File(ctx.getFilesDir(), "live_sdk_tools"));

        for (File dir : searchDirs) {
            if (dir == null || !dir.exists()) continue;
            for (String n : names) {
                File f = new File(dir, n);
                if (f.exists() && f.isFile() && f.length() > 1024L) {
                    return f;
                }
            }
        }
        return null;
    }

    private boolean extractAssetIfPresent(Context ctx, String assetName, File destFile) {
        if (destFile.exists()) {
            if (destFile.length() > 1024L) return true;
            destFile.delete(); // Force delete if size is suspicious
        }

        File optDir = new File(ctx.getCodeCacheDir(), "ecj_live_dex");
        if (optDir.exists() && optDir.isDirectory()) {
            File[] files = optDir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
        }

        InputStream is = null;
        FileOutputStream fos = null;
        try {
            is = ctx.getAssets().open(assetName);
            if (destFile.getParentFile() != null && !destFile.getParentFile().exists()) {
                destFile.getParentFile().mkdirs();
            }
            fos = new FileOutputStream(destFile);
            byte[] buf = new byte[65536];
            int len;
            while ((len = is.read(buf)) != -1) {
                fos.write(buf, 0, len);
            }
            fos.flush();
            return true;
        } catch (Exception e) {
            if (destFile.exists()) destFile.delete();
            return false;
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignored) {}
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
        }
    }

    private File generateStubRJavaIfNeeded(File projectDir, File genRootDir) {
        try {
            File manifest = findManifestFile(projectDir);
            if (manifest == null || !manifest.exists()) return null;

            String manifestText = readFileText(manifest);
            Matcher pkgMatcher = Pattern.compile("package\\s*=\\s*\"([^\"]+)\"").matcher(manifestText);
            if (!pkgMatcher.find()) return null;
            String pkgName = pkgMatcher.group(1).trim();
            if (pkgName.isEmpty()) return null;

            File resDir = new File(manifest.getParentFile(), "res");
            if (!resDir.exists() || !resDir.isDirectory()) return null;

            Map<String, Set<String>> resMap = new HashMap<>();
            String[] categories = {"id", "layout", "drawable", "mipmap", "string", "color", "style", "dimen", "attr", "anim", "xml", "menu", "raw"};
            for (String cat : categories) resMap.put(cat, new HashSet<String>());

            File[] subDirs = resDir.listFiles();
            if (subDirs != null) {
                Pattern idPattern = Pattern.compile("@\\+?id/([a-zA-Z0-9_\\.]+)");
                Pattern valPattern = Pattern.compile("<(string|color|style|dimen|attr|id|item)\\s+[^>]*name\\s*=\\s*\"([^\"]+)\"");

                for (File sub : subDirs) {
                    if (!sub.isDirectory()) continue;
                    String folderName = sub.getName().toLowerCase();
                    String baseType = folderName.contains("-") ? folderName.substring(0, folderName.indexOf('-')) : folderName;

                    File[] files = sub.listFiles();
                    if (files == null) continue;
                    for (File rf : files) {
                        if (!rf.isFile()) continue;
                        String fName = rf.getName();
                        int dot = fName.indexOf('.');
                        String entryName = (dot > 0 ? fName.substring(0, dot) : fName).replace('.', '_').replace('-', '_');

                        if (resMap.containsKey(baseType) && !baseType.equals("values")) {
                            resMap.get(baseType).add(entryName);
                        }

                        if (fName.endsWith(".xml")) {
                            String xml = readFileText(rf);
                            Matcher idM = idPattern.matcher(xml);
                            while (idM.find()) {
                                resMap.get("id").add(idM.group(1).replace('.', '_').replace('-', '_'));
                            }
                            if (baseType.equals("values")) {
                                Matcher valM = valPattern.matcher(xml);
                                while (valM.find()) {
                                    String tag = valM.group(1);
                                    String valName = valM.group(2).replace('.', '_').replace('-', '_');
                                    if (resMap.containsKey(tag)) {
                                        resMap.get(tag).add(valName);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            File pkgDir = new File(genRootDir, pkgName.replace('.', '/'));
            if (!pkgDir.exists()) pkgDir.mkdirs();
            File rFile = new File(pkgDir, "R.java");

            StringBuilder sb = new StringBuilder();
            sb.append("package ").append(pkgName).append(";\n");
            sb.append("public final class R {\n");
            int idCounter = 0x7f010001;
            for (String cat : categories) {
                sb.append("    public static final class ").append(cat).append(" {\n");
                for (String item : resMap.get(cat)) {
                    if (isValidJavaIdentifier(item)) {
                        sb.append("        public static int ").append(item).append(" = ").append(idCounter++).append(";\n");
                    }
                }
                sb.append("    }\n");
            }
            sb.append("}\n");
            writeFileText(rFile, sb.toString());
            return rFile;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isValidJavaIdentifier(String s) {
        if (s == null || s.isEmpty()) return false;
        if (!Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        }
        return true;
    }

    private File findManifestFile(File projectDir) {
        File m1 = new File(projectDir, "app/src/main/AndroidManifest.xml");
        if (m1.exists()) return m1;
        File m2 = new File(projectDir, "src/main/AndroidManifest.xml");
        if (m2.exists()) return m2;
        File m3 = new File(projectDir, "AndroidManifest.xml");
        if (m3.exists()) return m3;
        return null;
    }

    private void collectFilesByExtension(File dir, String ext, List<File> out) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (f.isDirectory()) {
                if (name.equals(".git") || name.equals(".gradle") || name.equals("build")) continue;
                collectFilesByExtension(f, ext, out);
            } else if (name.toLowerCase().endsWith(ext)) {
                out.add(f);
            }
        }
    }

    private String readFileText(File file) {
        StringBuilder sb = new StringBuilder();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Exception ignored) {
        } finally {
            try { if (br != null) br.close(); } catch (Exception ignored) {}
        }
        return sb.toString();
    }

    private void writeFileText(File file, String content) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            fos.write(content.getBytes("UTF-8"));
            fos.flush();
        } catch (Exception ignored) {
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
        }
    }
    public void verifyEngineSetup(final android.app.Activity activity) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureSdkToolsReady();
                    if (cachedEcjJar == null || !cachedEcjJar.exists()) {
                        throw new Exception("ecj-jdt-core.jar is missing from tools directory!");
                    }
                    if (cachedAndroidJar == null || !cachedAndroidJar.exists()) {
                        throw new Exception("android-30.jar is missing from tools directory!");
                    }

                    File optDir = new File(activity.getCodeCacheDir(), "ecj_live_dex");
                    if (!optDir.exists()) optDir.mkdirs();

                    dalvik.system.DexClassLoader loader = new dalvik.system.DexClassLoader(
                    cachedEcjJar.getAbsolutePath(),
                    optDir.getAbsolutePath(),
                    null,
                    activity.getClassLoader()
                    );

                    Class<?> mainClass = loader.loadClass("org.eclipse.jdt.internal.compiler.batch.Main");

                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            android.widget.Toast.makeText(activity, "🟢 Live Engine Started in RAM!", android.widget.Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Throwable e) {
                    final String errorMsg = "Engine Init Failed:\n" + e.toString() + "\nCause: " + (e.getCause() != null ? e.getCause().toString() : "None");
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(activity);
                            builder.setTitle("Live Analyzer Error");
                            final android.widget.EditText input = new android.widget.EditText(activity);
                            input.setText(errorMsg);
                            input.setTextIsSelectable(true);
                            builder.setView(input);
                            builder.setPositiveButton("Copy", new android.content.DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(android.content.DialogInterface dialog, int which) {
                                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                                    android.content.ClipData clip = android.content.ClipData.newPlainText("LiveError", errorMsg);
                                    clipboard.setPrimaryClip(clip);
                                    android.widget.Toast.makeText(activity, "Error Copied!", android.widget.Toast.LENGTH_SHORT).show();
                                }
                            });
                            builder.setNegativeButton("Close", null);
                            builder.setCancelable(false);
                            builder.show();
                        }
                    });
                }
            }
        }).start();
    }

}
