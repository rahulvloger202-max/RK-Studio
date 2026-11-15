package com.primx.compiler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class AndroidX {

    public interface AndroidXListener {
        void onLog(String message);
    }

    private File projectDir;
    private File libsCacheDir;
    private File appFilesDir;
    private File globalLibsDir;
    private AndroidXListener listener;

    private static class ResolvedPom {
        HashMap<String, String> properties = new HashMap<>();
        HashMap<String, String> dependencyManagement = new HashMap<>();
        List<String[]> dependencies = new ArrayList<>();
    }

    private final HashMap<String, ResolvedPom> pomCache = new HashMap<>();

    private final String[] DEFAULT_REPOSITORIES = {
        "https://dl.google.com/dl/android/maven2/",
        "https://repo1.maven.org/maven2/",
        "https://jitpack.io/"
    };

    private static final long MIN_VALID_LIB_SIZE = 500;
    private static final long MIN_VALID_POM_SIZE = 50;
    private static final int MAX_PARENT_DEPTH = 15;

    public AndroidX(String toolsDirPath, String projectPath, AndroidXListener listener) {
        this.appFilesDir = new File(toolsDirPath).getParentFile();
        this.projectDir = new File(projectPath);
        this.listener = listener;
        this.libsCacheDir = new File(projectDir, "build/primx_libs");
        if (!libsCacheDir.exists()) libsCacheDir.mkdirs();

        // बस एक लोकल कैश फोल्डर ताकि बार-बार वही लाइब्रेरी डाउनलोड न हो
        this.globalLibsDir = new File(appFilesDir, "global_offline_libs");
        if (!globalLibsDir.exists()) globalLibsDir.mkdirs();
    }

    private void log(String msg) {
        if (listener != null) listener.onLog(msg);
    }

    private boolean isCancelled() {
        return Compiler.isCancelled();
    }

    // ============ FILE VALIDATION ============

    private boolean isValidLibFile(File f) {
        return f != null && f.exists() && f.length() >= MIN_VALID_LIB_SIZE;
    }

    private boolean isValidPomFile(File f) {
        return f != null && f.exists() && f.length() >= MIN_VALID_POM_SIZE;
    }

    private boolean isValidJarStructure(File f) {
        if (f == null || !f.exists() || f.length() < 100) return false;
        ZipFile zf = null;
        try {
            zf = new ZipFile(f);
            return zf.size() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (zf != null) try { zf.close(); } catch (Exception ignored) {}
        }
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    private String readFile(File f) throws Exception {
        BufferedReader br = new BufferedReader(new FileReader(f));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append("\n");
        br.close();
        return sb.toString();
    }

    // ============ VERSION COMPARISON & DEDUPLICATION ============

    private String cleanMavenVersion(String rawVersion) {
        if (rawVersion == null) return null;
        String v = rawVersion.trim();
        if (v.contains("$")) return v;
        if ((v.startsWith("[") || v.startsWith("(")) && (v.endsWith("]") || v.endsWith(")"))) {
            v = v.substring(1, v.length() - 1).trim();
            if (v.contains(",")) {
                String[] parts = v.split(",");
                for (String p : parts) {
                    if (!p.trim().isEmpty()) {
                        v = p.trim();
                        break;
                    }
                }
            }
        }
        return v;
    }

    private int compareVersions(String v1, String v2) {
        if (v1 == null && v2 == null) return 0;
        if (v1 == null) return -1;
        if (v2 == null) return 1;
        if (v1.equals(v2)) return 0;

        String[] split1 = v1.split("-", 2);
        String[] split2 = v2.split("-", 2);

        String[] nums1 = split1[0].split("\\.");
        String[] nums2 = split2[0].split("\\.");
        int maxLen = Math.max(nums1.length, nums2.length);

        for (int i = 0; i < maxLen; i++) {
            int n1 = 0;
            int n2 = 0;
            if (i < nums1.length) {
                try { n1 = Integer.parseInt(nums1[i].replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
            }
            if (i < nums2.length) {
                try { n2 = Integer.parseInt(nums2[i].replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
            }
            if (n1 != n2) return Integer.compare(n1, n2);
        }

        boolean hasQual1 = split1.length > 1 && !split1[1].isEmpty();
        boolean hasQual2 = split2.length > 1 && !split2[1].isEmpty();
        if (!hasQual1 && hasQual2) return 1;
        if (hasQual1 && !hasQual2) return -1;
        if (hasQual1 && hasQual2) {
            return split1[1].compareToIgnoreCase(split2[1]);
        }
        return 0;
    }

    private void cleanupDuplicateDiskFolders(List<File> activeLibraryDirs) {
        if (libsCacheDir == null || !libsCacheDir.exists()) return;
        File[] allChildren = libsCacheDir.listFiles();
        if (allChildren == null) return;

        Pattern nameVerPattern = Pattern.compile("^(.+)-(\\d.*)$");
        HashMap<String, String> bestVersionByName = new HashMap<>();
        HashMap<String, File> bestDirByName = new HashMap<>();

        for (File dir : activeLibraryDirs) {
            Matcher m = nameVerPattern.matcher(dir.getName());
            if (m.matches()) {
                String libName = m.group(1);
                String ver = m.group(2);
                String prevVer = bestVersionByName.get(libName);
                if (prevVer == null || compareVersions(ver, prevVer) > 0) {
                    bestVersionByName.put(libName, ver);
                    bestDirByName.put(libName, dir);
                }
            }
        }

        activeLibraryDirs.clear();
        for (File bestDir : bestDirByName.values()) {
            if (!activeLibraryDirs.contains(bestDir)) {
                activeLibraryDirs.add(bestDir);
            }
        }

        for (File child : allChildren) {
            if (!child.isDirectory()) continue;
            Matcher m = nameVerPattern.matcher(child.getName());
            if (m.matches()) {
                String libName = m.group(1);
                File keptDir = bestDirByName.get(libName);
                if (keptDir != null && !keptDir.getAbsolutePath().equals(child.getAbsolutePath())) {
                    log("-> Removing duplicate library version folder: " + child.getName());
                    deleteRecursive(child);
                }
            }
        }
    }

    // ============ REPOSITORIES ============

    private List<String> parseDynamicRepositories() {
        List<String> customRepos = new ArrayList<>();
        File rootGradle = new File(projectDir, "build.gradle");
        File appGradle = new File(projectDir, "app/build.gradle");
        Pattern mavenPattern = Pattern.compile("url\\s+['\"]([^'\"]+)['\"]");
        File[] gradleFiles = {rootGradle, appGradle};
        for (File gradleFile : gradleFiles) {
            if (gradleFile.exists()) {
                try {
                    BufferedReader br = new BufferedReader(new FileReader(gradleFile));
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.contains("maven") && line.contains("url")) {
                            Matcher matcher = mavenPattern.matcher(line);
                            if (matcher.find()) {
                                String customUrl = matcher.group(1);
                                if (!customUrl.endsWith("/")) customUrl += "/";
                                if (!customRepos.contains(customUrl)) {
                                    customRepos.add(customUrl);
                                    log("-> Found Custom Repository: " + customUrl);
                                }
                            }
                        }
                    }
                    br.close();
                } catch (Exception e) {
                    log("-> Warning: Failed to parse repositories from " + gradleFile.getName());
                }
            }
        }
        return customRepos;
    }

    private List<String> getAllRepositories() {
        List<String> allRepos = parseDynamicRepositories();
        for (String defaultRepo : DEFAULT_REPOSITORIES) {
            if (!allRepos.contains(defaultRepo)) allRepos.add(defaultRepo);
        }
        return allRepos;
    }

    // ============ LOCAL SEARCH ============

    private File findLocalDependency(File currentDir, String targetFileName) {
        if (currentDir == null || !currentDir.exists()) return null;
        File[] files = currentDir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory()) {
                if (f.getName().equals(".git") || f.getName().equals(".idea")
                || f.getName().equals("dex") || f.getName().equals("obj")) continue;
                if (f.getName().contains("-") && new File(f, "classes.jar").exists()) continue;
                File found = findLocalDependency(f, targetFileName);
                if (found != null) return found;
            } else {
                if (f.getName().equals(targetFileName)) return f;
            }
        }
        return null;
    }

    private File findOfflineLibSmartly(String libName) {
        File appLibsDir = new File(projectDir, "app/libs");
        if (appLibsDir.exists()) {
            File[] files = appLibsDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().contains(libName)
                    && (f.getName().endsWith(".aar")
                    || f.getName().endsWith(".jar")
                    || f.getName().endsWith(".zip"))) {
                        return f;
                    }
                }
            }
        }
        return null;
    }

    // ============ MAIN RESOLVER ============

    public List<File> resolveAndDownload() {
        List<File> libraryDirs = new ArrayList<>();
        File buildGradle = new File(projectDir, "app/build.gradle");

        if (!buildGradle.exists()) {
            log("-> build.gradle not found. Skipping dependencies.");
            return libraryDirs;
        }

        try {
            BufferedReader br = new BufferedReader(new FileReader(buildGradle));
            String line;
            Queue<String> dependenciesQueue = new LinkedList<>();
            Set<String> processedDeps = new HashSet<>();
            HashMap<String, String> resolvedVersions = new HashMap<>();
            HashMap<String, File> resolvedDirs = new HashMap<>();

            // सिर्फ build.gradle में लिखी लाइब्रेरीज को क्यू में डालना
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("implementation") || line.startsWith("api")
                || line.startsWith("compile")) {
                    String[] parts = line.split("['\"]");
                    if (parts.length > 1) {
                        String dep = parts[1].trim();
                        dependenciesQueue.add(dep);
                    }
                }
            }
            br.close();

            if (dependenciesQueue.isEmpty()) {
                log("-> No dependencies found in build.gradle.");
                return libraryDirs;
            }

            log("-> Resolving Dependencies directly from Maven (Just like Android Studio)...");
            List<String> universalServers = getAllRepositories();

            while (!dependenciesQueue.isEmpty()) {
                if (isCancelled()) {
                    log("-> [CANCEL] Stopped dependency resolution.");
                    return libraryDirs;
                }

                String dep = dependenciesQueue.poll();
                if (processedDeps.contains(dep)) continue;
                processedDeps.add(dep);

                String[] parts = dep.split(":");
                if (parts.length != 3) continue;

                String group = parts[0].trim();
                String name = parts[1].trim();
                String version = cleanMavenVersion(parts[2]);

                if (version == null || version.contains("$") || version.contains("[") || version.contains("(")) {
                    log("-> Skipping dynamic version: " + dep);
                    continue;
                }

                String existingVer = resolvedVersions.get(name);
                if (existingVer != null) {
                    int cmp = compareVersions(version, existingVer);
                    if (cmp <= 0) {
                        File olderDir = new File(libsCacheDir, name + "-" + version);
                        if (olderDir.exists() && !version.equals(existingVer)) {
                            deleteRecursive(olderDir);
                        }
                        continue;
                    }
                }

                File extractDir = new File(libsCacheDir, name + "-" + version);
                File classesJar = new File(extractDir, "classes.jar");

                boolean libReady = validateExtractedLib(extractDir, classesJar);
                boolean resolvedNow = false;

                if (libReady) {
                    log("-> Ready (Cached): " + name + ":" + version);
                    resolvedNow = true;
                } else {
                    if (extractDir.exists()) {
                        log("-> Corrupt/incomplete lib found, cleaning: " + name + ":" + version);
                        deleteRecursive(extractDir);
                    }
                    extractDir.mkdirs();

                    if (resolveLibraryFromSources(group, name, version, extractDir, classesJar, universalServers)) {
                        resolvedNow = true;
                    } else {
                        if (isCancelled()) return libraryDirs;
                        log("   [!] Skipped or Not Found: " + name + ":" + version);
                        deleteRecursive(extractDir);
                    }
                }

                if (resolvedNow) {
                    File previousDir = resolvedDirs.get(name);
                    if (previousDir != null && !previousDir.getAbsolutePath().equals(extractDir.getAbsolutePath())) {
                        log("-> Upgrading " + name + ": " + existingVer + " -> " + version);
                        libraryDirs.remove(previousDir);
                        deleteRecursive(previousDir);
                    }
                    resolvedVersions.put(name, version);
                    resolvedDirs.put(name, extractDir);
                    if (!libraryDirs.contains(extractDir)) libraryDirs.add(extractDir);
                }

                if (isCancelled()) return libraryDirs;

                File pomFile = ensurePomAvailable(group, name, version, universalServers);
                if (pomFile != null) {
                    List<String> transitive = parsePomDependencies(
                    pomFile, processedDeps, group, name, version, universalServers);
                    dependenciesQueue.addAll(transitive);
                } else {
                    log("   -> Note: POM unavailable for " + name + ":" + version);
                }
            }

            cleanupDuplicateDiskFolders(libraryDirs);

        } catch (Exception e) {
            log("\n[!] RESOLVER ERROR: " + e.getMessage());
        }

        return libraryDirs;
    }

    private boolean validateExtractedLib(File extractDir, File classesJar) {
        if (extractDir == null || !extractDir.exists()) return false;
        if (isValidJarStructure(classesJar)) return true;
        File manifest = new File(extractDir, "AndroidManifest.xml");
        if (manifest.exists() && manifest.length() > 0) return true;
        File res = new File(extractDir, "res");
        if (res.exists() && res.isDirectory()) {
            File[] kids = res.listFiles();
            if (kids != null && kids.length > 0) return true;
        }
        return false;
    }

    private boolean resolveLibraryFromSources(String group, String name, String version,
    File extractDir, File classesJar,
    List<String> repos) {
        String groupPath = group.replace(".", "/");
        String targetAarName = name + "-" + version + ".aar";
        String targetJarName = name + "-" + version + ".jar";

        File smartLib = findOfflineLibSmartly(name);
        if (isValidLibFile(smartLib)) {
            log("-> [LOCAL] Library loaded from app/libs: " + name);
            if (smartLib.getName().endsWith(".jar")) {
                try { copyFile(smartLib, classesJar); return true; } catch (Exception e) {}
            } else if (smartLib.getName().endsWith(".aar") || smartLib.getName().endsWith(".zip")) {
                if (extractAar(smartLib, extractDir)) return true;
            }
        }

        if (isCancelled()) return false;

        // सीधा local cache चेक करें
        File globalAar = new File(globalLibsDir, targetAarName);
        if (isValidLibFile(globalAar) && extractAar(globalAar, extractDir)) {
            log("-> [CACHE] Extracted: " + name);
            return true;
        }

        File globalJar = new File(globalLibsDir, targetJarName);
        if (isValidLibFile(globalJar)) {
            try {
                copyFile(globalJar, classesJar);
                log("-> [CACHE] Copied: " + name);
                return true;
            } catch (Exception e) {}
        }

        if (isCancelled()) return false;

        File manualAar = findLocalDependency(projectDir, targetAarName);
        if (isValidLibFile(manualAar) && extractAar(manualAar, extractDir)) {
            log("-> [PROJECT] Extracted: " + name);
            return true;
        }
        File manualJar = findLocalDependency(projectDir, targetJarName);
        if (isValidLibFile(manualJar)) {
            try {
                copyFile(manualJar, classesJar);
                log("-> [PROJECT] Copied: " + name);
                return true;
            } catch (Exception e) {}
        }

        log("-> [NETWORK] Downloading missing library: " + name + ":" + version + " ...");
        for (String repoUrl : repos) {
            if (isCancelled()) return false;

            if (!repoUrl.endsWith("/")) repoUrl += "/";
            String aarUrl = repoUrl + groupPath + "/" + name + "/" + version + "/" + targetAarName;
            String jarUrl = repoUrl + groupPath + "/" + name + "/" + version + "/" + targetJarName;

            File tmpAar = new File(libsCacheDir, targetAarName);
            if (downloadFileWithRetry(aarUrl, tmpAar) && isValidLibFile(tmpAar)) {
                if (extractAar(tmpAar, extractDir)) {
                    try { copyFile(tmpAar, new File(globalLibsDir, targetAarName)); } catch (Exception e) {}
                    tmpAar.delete();
                    log("-> Downloaded: " + name + " (.aar)");
                    return true;
                }
                tmpAar.delete();
            }

            if (isCancelled()) return false;

            File tmpJar = new File(libsCacheDir, targetJarName);
            if (downloadFileWithRetry(jarUrl, tmpJar) && isValidLibFile(tmpJar)) {
                try { copyFile(tmpJar, classesJar); } catch (Exception e) {}
                try { copyFile(tmpJar, new File(globalLibsDir, targetJarName)); } catch (Exception e) {}
                tmpJar.delete();
                log("-> Downloaded: " + name + " (.jar)");
                return true;
            }
        }
        return false;
    }

    private File ensurePomAvailable(String group, String name, String version, List<String> repos) {
        String pomName = name + "-" + version + ".pom";
        File globalPom = new File(globalLibsDir, pomName);
        File cachePom = new File(libsCacheDir, pomName);

        if (isValidPomFile(globalPom)) return globalPom;
        if (isValidPomFile(cachePom)) {
            try { copyFile(cachePom, globalPom); } catch (Exception e) {}
            return cachePom;
        }

        String groupPath = group.replace(".", "/");
        for (String repoUrl : repos) {
            if (isCancelled()) return null;
            if (!repoUrl.endsWith("/")) repoUrl += "/";
            String pomUrl = repoUrl + groupPath + "/" + name + "/" + version + "/" + pomName;
            if (downloadFileWithRetry(pomUrl, cachePom) && isValidPomFile(cachePom)) {
                try { copyFile(cachePom, globalPom); } catch (Exception e) {}
                return cachePom;
            }
            if (cachePom.exists()) cachePom.delete();
        }
        return null;
    }

    // ============================================================
    // RECURSIVE PARENT POM RESOLUTION
    // ============================================================

    private List<String> parsePomDependencies(File pomFile, Set<String> processed,
    String currentGroup, String currentName, String currentVersion,
    List<String> repos) {
        List<String> newDeps = new ArrayList<>();
        try {
            ResolvedPom resolved = loadPomChain(
            pomFile, currentGroup, currentName, currentVersion,
            repos, new HashSet<String>(), 0);

            for (String[] dep : resolved.dependencies) {
                String fullDep = dep[0] + ":" + dep[1] + ":" + dep[2];
                if (!processed.contains(fullDep)) {
                    newDeps.add(fullDep);
                    log("   + Transitive: " + dep[1] + ":" + dep[2]);
                }
            }
        } catch (Exception e) {
            log("   -> Error parsing POM: " + pomFile.getName() + " - " + e.getMessage());
        }
        return newDeps;
    }

    private ResolvedPom loadPomChain(File pomFile, String groupId, String artifactId, String version,
    List<String> repos, Set<String> visited, int depth) {
        ResolvedPom result = new ResolvedPom();
        if (pomFile == null || !isValidPomFile(pomFile) || depth > MAX_PARENT_DEPTH) return result;
        if (isCancelled()) return result;

        String cacheKey = pomFile.getAbsolutePath();
        if (pomCache.containsKey(cacheKey)) return pomCache.get(cacheKey);
        if (visited.contains(cacheKey)) return result;
        visited.add(cacheKey);

        try {
            String xmlStr = readFile(pomFile);

            if (groupId == null || groupId.isEmpty()) groupId = extractXmlTag(xmlStr, "groupId");
            if (artifactId == null || artifactId.isEmpty()) artifactId = extractXmlTag(xmlStr, "artifactId");
            if (version == null || version.isEmpty()) version = extractXmlTag(xmlStr, "version");

            String parentGroup = null, parentArtifact = null, parentVersion = null;
            Matcher pm = Pattern.compile("(?s)<parent>(.*?)</parent>").matcher(xmlStr);
            if (pm.find()) {
                String pBlock = pm.group(1);
                parentGroup = extractXmlTag(pBlock, "groupId");
                parentArtifact = extractXmlTag(pBlock, "artifactId");
                parentVersion = extractXmlTag(pBlock, "version");
            }

            HashMap<String, String> childOwnProps = parsePropertiesBlock(xmlStr);

            if (parentVersion != null && parentVersion.contains("${")) {
                parentVersion = resolveProperty(parentVersion, childOwnProps);
            }
            if (parentGroup != null && parentGroup.contains("${")) {
                parentGroup = resolveProperty(parentGroup, childOwnProps);
            }

            ResolvedPom parentChain = null;
            if (parentGroup != null && parentArtifact != null && parentVersion != null
            && !parentVersion.contains("$")
            && !(parentGroup.equals(groupId) && parentArtifact.equals(artifactId))) {
                File parentPom = ensurePomAvailable(parentGroup, parentArtifact, parentVersion, repos);
                if (parentPom != null) {
                    log("   -> Loading parent POM: " + parentGroup + ":" + parentArtifact + ":" + parentVersion);
                    parentChain = loadPomChain(parentPom, parentGroup, parentArtifact, parentVersion,
                    repos, visited, depth + 1);
                }
            }

            if (parentChain != null) {
                result.properties.putAll(parentChain.properties);
                result.dependencyManagement.putAll(parentChain.dependencyManagement);
            }

            if (groupId != null) {
                result.properties.put("project.groupId", groupId);
                result.properties.put("pom.groupId", groupId);
                result.properties.put("groupId", groupId);
            }
            if (artifactId != null) {
                result.properties.put("project.artifactId", artifactId);
                result.properties.put("pom.artifactId", artifactId);
                result.properties.put("artifactId", artifactId);
            }
            if (version != null) {
                result.properties.put("project.version", version);
                result.properties.put("pom.version", version);
                result.properties.put("version", version);
            }
            if (parentGroup != null) {
                result.properties.put("project.parent.groupId", parentGroup);
                result.properties.put("parent.groupId", parentGroup);
            }
            if (parentArtifact != null) {
                result.properties.put("project.parent.artifactId", parentArtifact);
                result.properties.put("parent.artifactId", parentArtifact);
            }
            if (parentVersion != null) {
                result.properties.put("project.parent.version", parentVersion);
                result.properties.put("parent.version", parentVersion);
            }

            for (String k : childOwnProps.keySet()) result.properties.put(k, childOwnProps.get(k));

            result.properties = resolveAllProperties(result.properties);

            HashMap<String, String> ownManaged = parseDependencyManagementBlock(
            xmlStr, result.properties, repos, new HashSet<String>());
            for (String k : ownManaged.keySet()) result.dependencyManagement.put(k, ownManaged.get(k));

            List<String[]> ownDeps = parseDependenciesBlock(
            xmlStr, result.properties, result.dependencyManagement);
            result.dependencies.addAll(ownDeps);

            if (parentChain != null) {
                for (String[] parentDep : parentChain.dependencies) {
                    boolean overridden = false;
                    for (String[] d : ownDeps) {
                        if (d[0].equals(parentDep[0]) && d[1].equals(parentDep[1])) {
                            overridden = true;
                            break;
                        }
                    }
                    if (!overridden) result.dependencies.add(parentDep);
                }
            }

            pomCache.put(cacheKey, result);
        } catch (Exception e) {
            log("   -> Error loading POM chain: " + pomFile.getName() + " - " + e.getMessage());
        }

        return result;
    }

    // ============ XML PARSING HELPERS ============

    private HashMap<String, String> parsePropertiesBlock(String xmlStr) {
        HashMap<String, String> props = new HashMap<>();
        if (xmlStr == null) return props;
        Matcher m = Pattern.compile("(?s)<properties>(.*?)</properties>").matcher(xmlStr);
        while (m.find()) {
            String block = m.group(1);
            Matcher pm = Pattern.compile("<([A-Za-z_][\\w.\\-]*)>([^<]*)</\\1>").matcher(block);
            while (pm.find()) {
                props.put(pm.group(1).trim(), pm.group(2).trim());
            }
        }
        return props;
    }

    private HashMap<String, String> parseDependencyManagementBlock(String xmlStr,
    HashMap<String, String> props,
    List<String> repos,
    Set<String> bomVisited) {
        HashMap<String, String> managed = new HashMap<>();
        if (xmlStr == null || isCancelled()) return managed;

        Matcher dm = Pattern.compile("(?s)<dependencyManagement>(.*?)</dependencyManagement>").matcher(xmlStr);
        while (dm.find()) {
            String block = dm.group(1);
            Matcher dep = Pattern.compile("(?s)<dependency>(.*?)</dependency>").matcher(block);
            while (dep.find()) {
                String d = dep.group(1);
                String g = resolveProperty(extractXmlTag(d, "groupId"), props);
                String a = resolveProperty(extractXmlTag(d, "artifactId"), props);
                String v = cleanMavenVersion(resolveProperty(extractXmlTag(d, "version"), props));
                String scope = extractXmlTag(d, "scope");
                String type = extractXmlTag(d, "type");

                if (g == null || a == null) continue;

                if ("import".equals(scope) && "pom".equals(type) && v != null) {
                    String bomKey = g + ":" + a + ":" + v;
                    if (!bomVisited.contains(bomKey)) {
                        bomVisited.add(bomKey);
                        File bomPom = ensurePomAvailable(g, a, v, repos);
                        if (bomPom != null) {
                            try {
                                String bomXml = readFile(bomPom);
                                HashMap<String, String> bomProps = parsePropertiesBlock(bomXml);
                                for (String k : props.keySet()) bomProps.put(k, props.get(k));
                                HashMap<String, String> bomManaged = parseDependencyManagementBlock(
                                bomXml, bomProps, repos, bomVisited);
                                for (String k : bomManaged.keySet()) {
                                    if (!managed.containsKey(k)) managed.put(k, bomManaged.get(k));
                                }
                                log("   -> Imported BOM: " + g + ":" + a + ":" + v);
                            } catch (Exception ignored) {}
                        }
                    }
                    continue;
                }

                if (v != null) managed.put(g + ":" + a, v);
            }
        }
        return managed;
    }

    private List<String[]> parseDependenciesBlock(String xmlStr,
    HashMap<String, String> props,
    HashMap<String, String> managed) {
        List<String[]> deps = new ArrayList<>();
        if (xmlStr == null) return deps;

        String cleaned = xmlStr.replaceAll("(?s)<dependencyManagement>.*?</dependencyManagement>", "");

        Matcher dm = Pattern.compile("(?s)<dependencies>(.*?)</dependencies>").matcher(cleaned);
        while (dm.find()) {
            String block = dm.group(1);
            Matcher dep = Pattern.compile("(?s)<dependency>(.*?)</dependency>").matcher(block);
            while (dep.find()) {
                String d = dep.group(1);
                String scope = extractXmlTag(d, "scope");
                String optional = extractXmlTag(d, "optional");
                if ("test".equals(scope) || "provided".equals(scope) || "system".equals(scope)) continue;
                if ("true".equalsIgnoreCase(optional)) continue;

                String g = resolveProperty(extractXmlTag(d, "groupId"), props);
                String a = resolveProperty(extractXmlTag(d, "artifactId"), props);
                String v = cleanMavenVersion(resolveProperty(extractXmlTag(d, "version"), props));
                String type = extractXmlTag(d, "type");

                if ("pom".equals(type)) continue;

                if (v == null && g != null && a != null) {
                    String mv = managed.get(g + ":" + a);
                    if (mv != null) v = cleanMavenVersion(resolveProperty(mv, props));
                }

                if (g == null || a == null || v == null) continue;
                if (v.contains("$") || v.contains("[") || v.contains("(")) continue;

                deps.add(new String[]{g, a, v});
            }
        }
        return deps;
    }

    private String resolveProperty(String value, HashMap<String, String> props) {
        if (value == null || !value.contains("${")) return value;
        int safety = 10;
        while (value.contains("${") && safety-- > 0) {
            boolean replaced = false;
            for (String key : props.keySet()) {
                String placeholder = "${" + key + "}";
                if (value.contains(placeholder)) {
                    value = value.replace(placeholder, props.get(key));
                    replaced = true;
                }
            }
            if (!replaced) break;
        }
        return value;
    }

    private HashMap<String, String> resolveAllProperties(HashMap<String, String> props) {
        HashMap<String, String> out = new HashMap<>(props);
        for (int pass = 0; pass < 3; pass++) {
            boolean changed = false;
            for (String key : new ArrayList<>(out.keySet())) {
                String oldVal = out.get(key);
                String newVal = resolveProperty(oldVal, out);
                if (!newVal.equals(oldVal)) {
                    out.put(key, newVal);
                    changed = true;
                }
            }
            if (!changed) break;
        }
        return out;
    }

    private String extractXmlTag(String xml, String tag) {
        if (xml == null) return null;
        Matcher m = Pattern.compile("<" + tag + ">([^<]*)</" + tag + ">").matcher(xml);
        if (m.find()) {
            String v = m.group(1).trim();
            return v.isEmpty() ? null : v;
        }
        return null;
    }

    // ============ IO / DOWNLOAD / EXTRACT ============

    private void copyFile(File src, File dst) throws Exception {
        if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
        InputStream in = new java.io.FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) > 0) {
            if (isCancelled()) {
                in.close();
                out.close();
                throw new Exception("BUILD_CANCELLED");
            }
            out.write(buf, 0, len);
        }
        in.close();
        out.close();
    }

    private boolean downloadFileWithRetry(String fileUrl, File targetFile) {
        int maxRetries = 2;
        if (targetFile.getParentFile() != null) targetFile.getParentFile().mkdirs();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (isCancelled()) {
                if (targetFile.exists()) targetFile.delete();
                return false;
            }

            HttpURLConnection connection = null;
            try {
                URL url = new URL(fileUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(true);
                connection.connect();

                int code = connection.getResponseCode();

                // Redirect handle
                int redirects = 0;
                while ((code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == HttpURLConnection.HTTP_MOVED_PERM
                || code == 307 || code == 308) && redirects < 5) {
                    String newUrl = connection.getHeaderField("Location");
                    connection.disconnect();
                    url = new URL(newUrl);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(30000);
                    connection.setInstanceFollowRedirects(true);
                    code = connection.getResponseCode();
                    redirects++;
                }

                if (code == HttpURLConnection.HTTP_OK) {
                    InputStream input = connection.getInputStream();
                    FileOutputStream output = new FileOutputStream(targetFile);
                    byte[] data = new byte[8192];
                    int count;
                    while ((count = input.read(data)) != -1) {
                        if (isCancelled()) {
                            try { output.close(); } catch (Exception ignored) {}
                            try { input.close(); } catch (Exception ignored) {}
                            if (targetFile.exists()) targetFile.delete();
                            return false;
                        }
                        output.write(data, 0, count);
                    }
                    output.flush();
                    output.close();
                    input.close();
                    if (targetFile.exists() && targetFile.length() > 0) return true;
                } else if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                    return false;
                }
            } catch (Exception e) {
                // Ignore and try next repo or retry
            } finally {
                if (connection != null) try { connection.disconnect(); } catch (Exception ignored) {}
            }
            if (targetFile.exists()) targetFile.delete();
        }
        return false;
    }

    private boolean extractAar(File aarFile, File targetDir) {
        try {
            if (!targetDir.exists()) targetDir.mkdirs();
            ZipInputStream zis = new ZipInputStream(new java.io.FileInputStream(aarFile));
            ZipEntry zipEntry;
            byte[] buffer = new byte[8192];
            boolean hasClassesJar = false;

            while ((zipEntry = zis.getNextEntry()) != null) {
                if (isCancelled()) {
                    try { zis.close(); } catch (Exception ignored) {}
                    return false;
                }
                File newFile = new File(targetDir, zipEntry.getName());
                if (zipEntry.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    newFile.getParentFile().mkdirs();
                    FileOutputStream fos = new FileOutputStream(newFile);
                    int len;
                    while ((len = zis.read(buffer)) > 0) fos.write(buffer, 0, len);
                    fos.close();
                    if (zipEntry.getName().equals("classes.jar") && newFile.length() > 0) {
                        hasClassesJar = true;
                    }
                }
                zis.closeEntry();
            }
            zis.close();

            return hasClassesJar
            || new File(targetDir, "res").exists()
            || new File(targetDir, "AndroidManifest.xml").exists();
        } catch (Exception e) {
            return false;
        }
    }

    // ============ CACHE CLEANUP (FOR SETTINGS MENU) ============

    public static void clearAllLibraryCaches(String toolsDirPath) {
        File appFilesDir = new File(toolsDirPath).getParentFile();
        deleteRecursiveStatic(new File(appFilesDir, "global_offline_libs"));
        deleteRecursiveStatic(new File(appFilesDir, "primx_master_libs.zip"));
        File downloads = new File(
        android.os.Environment.getExternalStoragePublicDirectory(
        android.os.Environment.DIRECTORY_DOWNLOADS), "primx_libs.zip");
        deleteRecursiveStatic(downloads);
    }

    private static void deleteRecursiveStatic(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursiveStatic(k);
        }
        f.delete();
    }
}