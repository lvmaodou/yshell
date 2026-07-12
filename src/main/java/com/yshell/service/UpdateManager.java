package com.yshell.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yshell.model.Manifest;
import com.yshell.model.UpdateDiff;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class UpdateManager {

    private static final String UPDATE_SERVER_URL = "http://127.0.0.1:8888/yshell/";
    private static final String LATEST_JSON = "latest.json";
    private static final boolean ENABLE_MOCK = false;
    private static final String APP_DIR_NAME = "app";
    private static final String RUNTIME_DIR_NAME = "runtime";
    private static final Object DOWNLOAD_LOCK = new Object();
    private static BackgroundDownload activeDownload;


    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String appDataDir;
    private final String installDir;
    private final String runtimeDir;

    public UpdateManager() {
        String os = System.getProperty("os.name").toLowerCase();
        String userHome = System.getProperty("user.home");

        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            appDataDir = (appData != null ? appData : userHome) + "\\YShell";
        } else if (os.contains("mac")) {
            appDataDir = userHome + "/Library/Application Support/YShell";
        } else {
            appDataDir = userHome + "/.yshell";
        }

        installDir = resolveInstallDir();
        runtimeDir = installDir + File.separator + RUNTIME_DIR_NAME;

        new File(appDataDir).mkdirs();
    }

    public Manifest checkLatestVersion() throws Exception {
        if (ENABLE_MOCK) {
            return createMockManifest();
        }
        URL url = URI.create(UPDATE_SERVER_URL + LATEST_JSON).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);

        if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
            try (InputStream is = conn.getInputStream()) {
                return objectMapper.readValue(is, Manifest.class);
            }
        }
        return null;
    }

    private Manifest createMockManifest() {
        Manifest manifest = new Manifest();
        manifest.setVersion("1.1.0");
        manifest.setReleaseDate(java.time.LocalDate.now().toString());
        manifest.setChangelog("Mock update");

        Manifest.AppInfo app = new Manifest.AppInfo();
        app.setFiles(new ArrayList<>());
        app.setDelete(new ArrayList<>());
        manifest.setApp(app);

        Manifest.LauncherInfo launcher = new Manifest.LauncherInfo();
        launcher.setUpdateRequired(false);
        launcher.setPlatforms(new HashMap<>());
        manifest.setLauncher(launcher);
        return manifest;
    }

    public Manifest getLocalManifest() {
        String version = resolveInstalledAppVersion();
        if (version == null || version.isBlank()) {
            return null;
        }

        Manifest manifest = new Manifest();
        manifest.setVersion(version);
        return manifest;
    }

    public UpdateDiff calculateDiff(Manifest remoteManifest) {
        UpdateDiff diff = new UpdateDiff();
        diff.setNewVersion(remoteManifest.getVersion());
        diff.setChangelog(remoteManifest.getChangelog());

        long totalSize = 0;
        if (shouldUpdateRuntime(remoteManifest)) {
            Manifest.RuntimePackage runtimePackage = getCurrentPlatformRuntime(remoteManifest.getRuntime());
            if (runtimePackage == null) {
                throw new IllegalStateException("No runtime package for current platform.");
            }
            diff.setNeedUpdateRuntime(true);
            totalSize += runtimePackage.getSize();
        }

        Manifest.FileInfo launcherFile = getCurrentPlatformLauncher(remoteManifest.getLauncher());
        if (launcherFile != null && shouldUpdateLauncher(remoteManifest.getLauncher(), launcherFile)) {
            diff.setNeedUpdateLauncher(true);
            diff.setLauncherFile(launcherFile);
            totalSize += launcherFile.getSize();
        }

        if (remoteManifest.getApp() != null && remoteManifest.getApp().getFiles() != null) {
            for (Manifest.FileInfo fileInfo : remoteManifest.getApp().getFiles()) {
                if (shouldUpdateFile(fileInfo)) {
                    diff.getFilesToUpdate().add(fileInfo);
                    totalSize += fileInfo.getSize();
                }
            }
        }

        if (remoteManifest.getApp() != null && remoteManifest.getApp().getDelete() != null) {
            diff.getFilesToDelete().addAll(remoteManifest.getApp().getDelete());
        }

        diff.setTotalDownloadSize(totalSize);
        return diff;
    }

    public File prepareUpdate(Manifest remoteManifest, UpdateDiff diff, ProgressCallback callback) throws Exception {
        File planDir = new File(appDataDir, "update-" + remoteManifest.getVersion());
        File stagingDir = new File(planDir, "staging");
        clearPendingUpdate();
        deleteDirectory(planDir);
        stagingDir.mkdirs();

        AtomicLong completed = new AtomicLong(0);

        try {
            String runtimeArchiveName = "";
            if (diff.isNeedUpdateRuntime()) {
                Manifest.RuntimePackage runtimePackage = getCurrentPlatformRuntime(remoteManifest.getRuntime());
                if (runtimePackage == null) {
                    throw new IllegalStateException("No runtime package for current platform.");
                }

                runtimeArchiveName = fileNameFromUrl(runtimePackage.getUrl());
                File runtimeArchive = new File(stagingDir, runtimeArchiveName);
                downloadAndVerify(runtimePackage.getUrl(), runtimeArchive, runtimePackage.getHash(), completed,
                        diff.getTotalDownloadSize(), callback);
                completed.addAndGet(runtimePackage.getSize());
            }

            if (diff.isNeedUpdateLauncher() && diff.getLauncherFile() != null) {
                Manifest.FileInfo launcherFile = diff.getLauncherFile();
                File downloadedFile = resolveStagingPath(stagingDir, launcherFile.getPath()).toFile();
                downloadAndVerify(launcherFile.getUrl(), downloadedFile, launcherFile.getHash(), completed,
                        diff.getTotalDownloadSize(), callback);
                completed.addAndGet(launcherFile.getSize());
            }

            for (Manifest.FileInfo fileInfo : diff.getFilesToUpdate()) {
                File downloadedFile = resolveStagingPath(stagingDir, fileInfo.getPath()).toFile();
                downloadAndVerify(fileInfo.getUrl(), downloadedFile, fileInfo.getHash(), completed,
                        diff.getTotalDownloadSize(), callback);
                completed.addAndGet(fileInfo.getSize());
            }

            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(stagingDir, LATEST_JSON), remoteManifest);
            writeUpdatePlan(planDir, stagingDir, diff, runtimeArchiveName);
            writePendingUpdate(planDir, remoteManifest.getVersion());
            return planDir;
        } catch (Exception e) {
            deleteDirectory(planDir);
            throw e;
        }
    }

    public boolean hasActiveDownload(String version) {
        synchronized (DOWNLOAD_LOCK) {
            return activeDownload != null
                    && Objects.equals(activeDownload.version, version)
                    && (activeDownload.running || activeDownload.completed);
        }
    }

    public boolean isActiveDownloadCompleted(String version) {
        synchronized (DOWNLOAD_LOCK) {
            return activeDownload != null
                    && Objects.equals(activeDownload.version, version)
                    && activeDownload.completed;
        }
    }

    public void observeActiveDownload(String version, ProgressCallback progressCallback,
                                      Consumer<File> completionCallback,
                                      Consumer<Exception> failureCallback) {
        BackgroundDownload download;
        synchronized (DOWNLOAD_LOCK) {
            if (activeDownload == null || !Objects.equals(activeDownload.version, version)) {
                return;
            }
            download = activeDownload;
            if (progressCallback != null) {
                download.progressCallbacks.add(progressCallback);
            }
            if (completionCallback != null) {
                download.completionCallbacks.add(completionCallback);
            }
            if (failureCallback != null) {
                download.failureCallbacks.add(failureCallback);
            }
        }

        if (progressCallback != null) {
            progressCallback.onProgress(download.downloaded, download.total);
        }
        if (download.completed && completionCallback != null) {
            completionCallback.accept(download.planDir);
        }
        if (download.failed && failureCallback != null) {
            failureCallback.accept(download.error);
        }
    }

    public void startPrepareUpdateAsync(Manifest remoteManifest, UpdateDiff diff,
                                        ProgressCallback progressCallback,
                                        Consumer<File> completionCallback,
                                        Consumer<Exception> failureCallback) {
        String version = remoteManifest.getVersion();
        BackgroundDownload download;
        boolean existingDownload = false;
        synchronized (DOWNLOAD_LOCK) {
            if (activeDownload != null
                    && Objects.equals(activeDownload.version, version)
                    && (activeDownload.running || activeDownload.completed)) {
                download = activeDownload;
                existingDownload = true;
                if (progressCallback != null) {
                    download.progressCallbacks.add(progressCallback);
                }
                if (completionCallback != null) {
                    download.completionCallbacks.add(completionCallback);
                }
                if (failureCallback != null) {
                    download.failureCallbacks.add(failureCallback);
                }
            } else {
                download = new BackgroundDownload(version, diff.getTotalDownloadSize());
                if (progressCallback != null) {
                    download.progressCallbacks.add(progressCallback);
                }
                if (completionCallback != null) {
                    download.completionCallbacks.add(completionCallback);
                }
                if (failureCallback != null) {
                    download.failureCallbacks.add(failureCallback);
                }
                activeDownload = download;
            }
        }

        if (existingDownload) {
            if (progressCallback != null) {
                progressCallback.onProgress(download.downloaded, download.total);
            }
            if (download.completed && completionCallback != null) {
                completionCallback.accept(download.planDir);
            }
            if (download.failed && failureCallback != null) {
                failureCallback.accept(download.error);
            }
            return;
        }

        BackgroundDownload task = download;
        Thread updateThread = new Thread(() -> {
            try {
                File planDir = prepareUpdate(remoteManifest, diff, (downloaded, total) -> {
                    task.downloaded = downloaded;
                    task.total = total;
                    for (ProgressCallback callback : task.progressCallbacks) {
                        callback.onProgress(downloaded, total);
                    }
                });

                task.planDir = planDir;
                task.downloaded = task.total;
                task.running = false;
                task.completed = true;
                for (Consumer<File> callback : task.completionCallbacks) {
                    callback.accept(planDir);
                }
            } catch (Exception e) {
                task.running = false;
                task.failed = true;
                task.error = e;
                synchronized (DOWNLOAD_LOCK) {
                    if (activeDownload == task) {
                        activeDownload = null;
                    }
                }
                for (Consumer<Exception> callback : task.failureCallbacks) {
                    callback.accept(e);
                }
            }
        }, "yshell-update-download");
        updateThread.setDaemon(true);
        updateThread.start();
    }

    public void clearActiveDownload(File planDir) {
        synchronized (DOWNLOAD_LOCK) {
            if (activeDownload != null && Objects.equals(activeDownload.planDir, planDir)) {
                activeDownload = null;
            }
        }
    }

    public void applyPreparedUpdate(File planDir) throws IOException {
        if (planDir == null || !new File(planDir, "update-plan.properties").exists()) {
            throw new FileNotFoundException("Update plan not found.");
        }
        setPlanRelaunch(planDir, true);
        startUpdater(planDir);
        clearPendingUpdate();
        clearActiveDownload(planDir);
        System.exit(0);
    }

    public void applyPreparedUpdateOnExit(File planDir) throws IOException {
        if (planDir == null || !new File(planDir, "update-plan.properties").exists()) {
            throw new FileNotFoundException("Update plan not found.");
        }
        setPlanRelaunch(planDir, false);
        startUpdater(planDir);
        clearPendingUpdate();
        clearActiveDownload(planDir);
    }

    public File getPendingUpdatePlanDir() {
        File pendingFile = getPendingUpdateFile();
        if (!pendingFile.exists()) {
            return null;
        }

        Properties props = new Properties();
        try (InputStream input = new FileInputStream(pendingFile)) {
            props.load(input);
            String planDirPath = props.getProperty("planDir");
            if (planDirPath == null || planDirPath.isBlank()) {
                clearPendingUpdate();
                return null;
            }

            File planDir = new File(planDirPath);
            if (new File(planDir, "update-plan.properties").exists()
                    && new File(planDir, "staging").exists()) {
                return planDir;
            }
        } catch (IOException ignored) {
        }

        clearPendingUpdate();
        return null;
    }

    private boolean shouldUpdateRuntime(Manifest remoteManifest) {
        if (remoteManifest.getRuntime() == null || remoteManifest.getRuntime().getJavaVersion() == null) {
            return false;
        }

        String installedJavaVersion = getInstalledRuntimeJavaVersion();
        if (!new File(runtimeDir).exists()
                || !remoteManifest.getRuntime().getJavaVersion().equals(installedJavaVersion)) {
            return true;
        }

        String remoteHash = remoteManifest.getRuntime().getHash();
        if (remoteHash == null || remoteHash.isBlank()) {
            return false;
        }

        try {
            return !remoteHash.equals(computeDirectoryHash(Paths.get(runtimeDir)));
        } catch (IOException e) {
            return true;
        }
    }

    private boolean shouldUpdateLauncher(Manifest.LauncherInfo launcher, Manifest.FileInfo launcherFile) {
        if (launcher == null || !launcher.isUpdateRequired()) {
            return false;
        }
        return shouldUpdateFile(launcherFile);
    }

    private boolean shouldUpdateFile(Manifest.FileInfo fileInfo) {
        if (fileInfo == null || fileInfo.getPath() == null || fileInfo.getHash() == null) {
            return false;
        }
        try {
            Path targetPath = resolveInstallPath(fileInfo.getPath());
            return !Files.exists(targetPath) || !fileInfo.getHash().equals(computeHash(targetPath.toFile()));
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid update path: " + fileInfo.getPath(), e);
        } catch (Exception e) {
            return true;
        }
    }

    private void downloadAndVerify(String relativeUrl, File destination, String expectedHash, AtomicLong completed,
                                   long totalSize, ProgressCallback callback) throws Exception {
        destination.getParentFile().mkdirs();
        downloadFile(UPDATE_SERVER_URL + relativeUrl, destination.getAbsolutePath(), (bytesDownloaded, totalBytes) -> {
            if (callback != null) {
                callback.onProgress(completed.get() + bytesDownloaded, totalSize);
            }
        });

        String actualHash = computeHash(destination);
        if (!expectedHash.equals(actualHash)) {
            throw new IOException("Hash mismatch for " + relativeUrl);
        }
    }

    private Path resolveInstallPath(String relativePath) throws IOException {
        Path root = Paths.get(installDir).toAbsolutePath().normalize();
        Path resolved = root.resolve(relativePath.replace('/', File.separatorChar)).normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("Invalid update path: " + relativePath);
        }
        return resolved;
    }

    private Path resolveStagingPath(File stagingDir, String relativePath) throws IOException {
        Path root = stagingDir.toPath().toAbsolutePath().normalize();
        Path resolved = root.resolve(relativePath.replace('/', File.separatorChar)).normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("Invalid staging path: " + relativePath);
        }
        return resolved;
    }

    private void writeUpdatePlan(File planDir, File stagingDir, UpdateDiff diff, String runtimeArchiveName) throws IOException {
        File propsFile = new File(planDir, "update-plan.properties");
        try (PrintWriter writer = new PrintWriter(new FileOutputStream(propsFile))) {
            writer.println("installDir=" + installDir);
            writer.println("launcherPath=" + resolveLauncherExecutable(System.getProperty("os.name").toLowerCase()).getAbsolutePath());
            writer.println("stagingDir=" + stagingDir.getAbsolutePath());
            writer.println("runtimeUpdate=" + diff.isNeedUpdateRuntime());
            writer.println("runtimeArchive=" + runtimeArchiveName);
            writer.println("relaunch=true");
        }

        File copyList = new File(planDir, "copy-files.tsv");
        try (PrintWriter writer = new PrintWriter(new FileOutputStream(copyList))) {
            if (diff.isNeedUpdateLauncher() && diff.getLauncherFile() != null) {
                resolveInstallPath(diff.getLauncherFile().getPath());
                writer.println(diff.getLauncherFile().getPath() + "\t" + diff.getLauncherFile().getPath());
            }
            for (Manifest.FileInfo fileInfo : diff.getFilesToUpdate()) {
                resolveInstallPath(fileInfo.getPath());
                writer.println(fileInfo.getPath() + "\t" + fileInfo.getPath());
            }
        }

        File deleteList = new File(planDir, "delete-files.txt");
        try (PrintWriter writer = new PrintWriter(new FileOutputStream(deleteList))) {
            for (String path : diff.getFilesToDelete()) {
                resolveInstallPath(path);
                writer.println(path);
            }
        }
    }

    private void startUpdater(File planDir) throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        File updaterScript = resolveUpdaterScript(os);
        long pid = ProcessHandle.current().pid();

        List<String> command = new ArrayList<>();
        if (os.contains("win")) {
            command.add("powershell.exe");
            command.add("-ExecutionPolicy");
            command.add("Bypass");
            command.add("-File");
            command.add(updaterScript.getAbsolutePath());
            command.add("-PlanDir");
            command.add(planDir.getAbsolutePath());
            command.add("-PidToWait");
            command.add(Long.toString(pid));
        } else {
            command.add("/bin/sh");
            command.add(updaterScript.getAbsolutePath());
            command.add(planDir.getAbsolutePath());
            command.add(Long.toString(pid));
        }

        new ProcessBuilder(command).directory(new File(installDir)).start();
    }

    private void writePendingUpdate(File planDir, String version) throws IOException {
        Properties props = new Properties();
        props.setProperty("planDir", planDir.getAbsolutePath());
        props.setProperty("version", version != null ? version : "");

        File pendingFile = getPendingUpdateFile();
        pendingFile.getParentFile().mkdirs();
        try (OutputStream output = new FileOutputStream(pendingFile)) {
            props.store(output, "YShell pending update");
        }
    }

    private void setPlanRelaunch(File planDir, boolean relaunch) throws IOException {
        File propsFile = new File(planDir, "update-plan.properties");
        List<String> lines = Files.readAllLines(propsFile.toPath());
        boolean found = false;
        List<String> updated = new ArrayList<>(lines.size() + 1);
        for (String line : lines) {
            if (line.startsWith("relaunch=")) {
                updated.add("relaunch=" + relaunch);
                found = true;
            } else {
                updated.add(line);
            }
        }
        if (!found) {
            updated.add("relaunch=" + relaunch);
        }
        Files.write(propsFile.toPath(), updated);
    }

    private File getPendingUpdateFile() {
        return new File(appDataDir, "pending-update.properties");
    }

    private void clearPendingUpdate() {
        File pendingFile = getPendingUpdateFile();
        if (pendingFile.exists()) {
            pendingFile.delete();
        }
    }

    private File resolveUpdaterScript(String os) {
        if (os.contains("win")) {
            return new File(installDir + File.separator + "updater", "update.ps1");
        }
        return new File(installDir + File.separator + "updater", "update.sh");
    }

    private String resolveInstallDir() {
        File location = resolveCodeSourceLocation();

        if (location.isFile()) {
            File parent = location.getParentFile();
            if (parent != null && APP_DIR_NAME.equals(parent.getName())) {
                File root = parent.getParentFile();
                if (root != null) {
                    return root.getAbsolutePath();
                }
            }
            if (parent != null) {
                return parent.getAbsolutePath();
            }
        }

        if (location.isDirectory()) {
            if (APP_DIR_NAME.equals(location.getName()) && location.getParentFile() != null) {
                return location.getParentFile().getAbsolutePath();
            }
            return location.getAbsolutePath();
        }

        return new File(".").getAbsoluteFile().getParentFile().getAbsolutePath();
    }

    private File resolveCodeSourceLocation() {
        try {
            return new File(UpdateManager.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            String classPath = System.getProperty("java.class.path", "");
            String firstEntry = classPath.split(java.util.regex.Pattern.quote(File.pathSeparator), 2)[0];
            return new File(firstEntry);
        }
    }

    private String resolveInstalledAppVersion() {
        String version = System.getProperty("jpackage.app-version");
        if (version != null && !version.isBlank()) {
            return version;
        }

        File jpackageState = new File(installDir + File.separator + APP_DIR_NAME, ".jpackage.xml");
        if (!jpackageState.exists()) {
            return null;
        }

        try {
            String content = Files.readString(jpackageState.toPath(), StandardCharsets.UTF_8);
            String startTag = "<app-version>";
            String endTag = "</app-version>";
            int start = content.indexOf(startTag);
            int end = content.indexOf(endTag);
            if (start >= 0 && end > start) {
                return content.substring(start + startTag.length(), end).trim();
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private File resolveLauncherExecutable(String os) {
        if (os.contains("win")) {
            return new File(installDir, "YShell.exe");
        }
        if (os.contains("mac")) {
            File macLauncher = new File(installDir + File.separator + "MacOS", "YShell");
            if (macLauncher.exists()) {
                return macLauncher;
            }
        }

        File rootLauncher = new File(installDir, "YShell");
        if (rootLauncher.exists()) {
            return rootLauncher;
        }
        return new File(installDir + File.separator + "bin", "YShell");
    }

    private Manifest.RuntimePackage getCurrentPlatformRuntime(Manifest.RuntimeInfo runtime) {
        if (runtime == null || runtime.getPlatforms() == null) {
            return null;
        }

        Map<String, Manifest.RuntimePackage> platforms = runtime.getPlatforms();
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return platforms.get("windows");
        }
        if (os.contains("mac")) {
            return platforms.get("macos");
        }
        return platforms.get("linux");
    }

    private Manifest.FileInfo getCurrentPlatformLauncher(Manifest.LauncherInfo launcher) {
        if (launcher == null || launcher.getPlatforms() == null) {
            return null;
        }

        Map<String, Manifest.FileInfo> platforms = launcher.getPlatforms();
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return platforms.get("windows");
        }
        if (os.contains("mac")) {
            return platforms.get("macos");
        }
        return platforms.get("linux");
    }

    private String getInstalledRuntimeJavaVersion() {
        File releaseFile = new File(runtimeDir, "release");
        if (!releaseFile.exists()) {
            return null;
        }

        try {
            List<String> lines = Files.readAllLines(releaseFile.toPath());
            for (String line : lines) {
                if (line.startsWith("JAVA_VERSION=")) {
                    String version = line.substring("JAVA_VERSION=".length()).replace("\"", "").trim();
                    int dot = version.indexOf('.');
                    return dot > 0 ? version.substring(0, dot) : version;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    private void downloadFile(String urlStr, String destPath, ProgressCallback callback) throws Exception {
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        int contentLength = conn.getContentLength();

        try (InputStream is = conn.getInputStream();
             FileOutputStream fos = new FileOutputStream(destPath)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalRead = 0;

            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
                totalRead += bytesRead;
                if (callback != null) {
                    callback.onProgress(totalRead, contentLength);
                }
            }
        }
    }

    private void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectory(file);
                }
            }
        }
        dir.delete();
    }

    private String computeHash(File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(Files.readAllBytes(file.toPath()));

            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String computeDirectoryHash(Path rootPath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path file : listRegularFiles(rootPath)) {
                String relativePath = rootPath.relativize(file).toString().replace(File.separatorChar, '/');
                digest.update(relativePath.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                try (InputStream input = Files.newInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                    }
                }
                digest.update((byte) 0);
            }
            return toHex(digest.digest());
        } catch (Exception e) {
            throw new IOException("Failed to compute runtime hash: " + rootPath, e);
        }
    }

    private List<Path> listRegularFiles(Path rootPath) throws IOException {
        try (var walk = Files.walk(rootPath)) {
            return walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> rootPath.relativize(path).toString()))
                    .toList();
        }
    }

    private String toHex(byte[] hashBytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String fileNameFromUrl(String url) {
        int slash = url.lastIndexOf('/');
        return slash >= 0 ? url.substring(slash + 1) : url;
    }

    private static class BackgroundDownload {
        private final String version;
        private final CopyOnWriteArrayList<ProgressCallback> progressCallbacks = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<Consumer<File>> completionCallbacks = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<Consumer<Exception>> failureCallbacks = new CopyOnWriteArrayList<>();
        private volatile boolean running = true;
        private volatile boolean completed;
        private volatile boolean failed;
        private volatile long downloaded;
        private volatile long total;
        private volatile File planDir;
        private volatile Exception error;

        private BackgroundDownload(String version, long total) {
            this.version = version;
            this.total = total;
        }
    }

    public interface ProgressCallback {
        void onProgress(long downloaded, long total);
    }

    public String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}
