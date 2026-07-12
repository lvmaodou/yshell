package com.yshell.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yshell.model.Manifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ManifestGenerator {
    private static final Logger LOGGER = LoggerFactory.getLogger(ManifestGenerator.class);

    private static final String APP_PREFIX = "app/";
    private static final String UPDATER_PREFIX = "updater/";
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Manifest generateManifest(String version, String javaVersion, String appDir,
                                     String runtimeDir, String updaterDir, String outputDir,
                                     String changelog) throws Exception {
        Manifest manifest = new Manifest();
        manifest.setVersion(version);
        manifest.setReleaseDate(java.time.LocalDate.now().toString());
        manifest.setChangelog(changelog);
        manifest.setMinUpdatableVersion(version);
        manifest.setRuntime(generateRuntimeInfo(version, javaVersion, runtimeDir, outputDir));
        manifest.setApp(generateAppInfo(version, appDir, updaterDir, outputDir));
        manifest.setLauncher(generateLauncherInfo());
        return manifest;
    }

    private Manifest.RuntimeInfo generateRuntimeInfo(String version, String javaVersion,
                                                     String runtimeDir, String outputDir) throws IOException {
        if (runtimeDir == null || !new File(runtimeDir).exists()) {
            return null;
        }

        Path runtimePath = Paths.get(runtimeDir);
        Manifest.RuntimeInfo runtime = new Manifest.RuntimeInfo();
        runtime.setJavaVersion(resolveRuntimeJavaVersion(runtimePath, javaVersion));
        runtime.setHash(computeDirectoryHash(runtimePath));
        runtime.setSize(computeDirectorySize(runtimePath));
        runtime.setPlatforms(generateRuntimePackages(version, runtimePath, outputDir));

        return runtime;
    }

    private Map<String, Manifest.RuntimePackage> generateRuntimePackages(String version, Path runtimePath,
                                                                         String outputDir) throws IOException {
        Map<String, Manifest.RuntimePackage> platforms = new HashMap<>();
        if (outputDir == null || outputDir.isBlank()) {
            return platforms;
        }

        String platform = currentPlatformKey();
        String fileName = "YShell-runtime-" + version + "-" + platform + ".zip";
        Path zipPath = Paths.get(outputDir, fileName);
        createRuntimeZip(runtimePath, zipPath);

        Manifest.RuntimePackage runtimePackage = new Manifest.RuntimePackage();
        runtimePackage.setUrl(version + "/" + fileName);
        runtimePackage.setHash(computeHash(zipPath.toFile()));
        runtimePackage.setSize(Files.size(zipPath));
        platforms.put(platform, runtimePackage);
        return platforms;
    }

    private String currentPlatformKey() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac")) {
            return "macos";
        }
        return "linux";
    }

    private void createRuntimeZip(Path runtimePath, Path zipPath) throws IOException {
        Files.createDirectories(zipPath.getParent());
        Files.deleteIfExists(zipPath);

        try (ZipOutputStream zipOutput = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (Path file : listRegularFiles(runtimePath)) {
                String relativePath = runtimePath.relativize(file).toString().replace(File.separatorChar, '/');
                ZipEntry entry = new ZipEntry("runtime/" + relativePath);
                entry.setTime(Files.getLastModifiedTime(file).toMillis());
                zipOutput.putNextEntry(entry);
                Files.copy(file, zipOutput);
                zipOutput.closeEntry();
            }
        }
    }

    private Manifest.AppInfo generateAppInfo(String version, String appDir, String updaterDir, String outputDir) throws Exception {
        Path appPath = Paths.get(appDir);
        if (!Files.exists(appPath)) {
            throw new IllegalArgumentException("App directory not found: " + appDir);
        }

        Manifest.AppInfo app = new Manifest.AppInfo();
        List<Manifest.FileInfo> files = new ArrayList<>();
        addFiles(files, appPath, APP_PREFIX, version + "/");

        if (updaterDir != null && !updaterDir.isBlank()) {
            Path updaterPath = Paths.get(updaterDir);
            if (Files.exists(updaterPath)) {
                addFiles(files, updaterPath, UPDATER_PREFIX, version + "/");
            }
        }

        Path launcherPath = Paths.get(outputDir, "YShell.exe");
        if (Files.exists(launcherPath)) {
            addFile(files, launcherPath, "YShell.exe", version + "/");
        }

        app.setFiles(files);
        app.setDelete(new ArrayList<>());
        return app;
    }

    private void addFiles(List<Manifest.FileInfo> files, Path rootPath, String prefix, String urlPrefix) throws IOException {
        try (Stream<Path> walk = Files.walk(rootPath)) {
            List<Path> regularFiles = walk.filter(Files::isRegularFile)
                    .toList();

            for (Path file : regularFiles) {
                String relativePath = rootPath.relativize(file).toString().replace(File.separatorChar, '/');
                String updatePath = prefix + relativePath;
                addFile(files, file, updatePath, urlPrefix);
            }
        }
    }

    private void addFile(List<Manifest.FileInfo> files, Path file, String updatePath, String urlPrefix) throws IOException {
        Manifest.FileInfo fileInfo = new Manifest.FileInfo();
        fileInfo.setPath(updatePath);
        fileInfo.setUrl(urlPrefix + updatePath);
        fileInfo.setHash(computeHash(file.toFile()));
        fileInfo.setSize(Files.size(file));
        files.add(fileInfo);
    }

    private String resolveRuntimeJavaVersion(Path runtimePath, String fallback) {
        Path releaseFile = runtimePath.resolve("release");
        if (!Files.exists(releaseFile)) {
            return fallback;
        }

        try {
            for (String line : Files.readAllLines(releaseFile)) {
                if (line.startsWith("JAVA_VERSION=")) {
                    String version = line.substring("JAVA_VERSION=".length()).replace("\"", "").trim();
                    int dot = version.indexOf('.');
                    return dot > 0 ? version.substring(0, dot) : version;
                }
            }
        } catch (IOException ignored) {
        }
        return fallback;
    }

    private String computeDirectoryHash(Path rootPath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path file : listRegularFiles(rootPath)) {
                String relativePath = rootPath.relativize(file).toString().replace(File.separatorChar, '/');
                digest.update(relativePath.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                try (var input = Files.newInputStream(file)) {
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

    private long computeDirectorySize(Path rootPath) throws IOException {
        long size = 0;
        for (Path file : listRegularFiles(rootPath)) {
            size += Files.size(file);
        }
        return size;
    }

    private List<Path> listRegularFiles(Path rootPath) throws IOException {
        try (Stream<Path> walk = Files.walk(rootPath)) {
            return walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> rootPath.relativize(path).toString()))
                    .toList();
        }
    }

    private Manifest.LauncherInfo generateLauncherInfo() {
        Manifest.LauncherInfo launcher = new Manifest.LauncherInfo();
        launcher.setUpdateRequired(false);
        launcher.setPlatforms(new HashMap<>());
        return launcher;
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

    private String toHex(byte[] hashBytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public void writeManifest(Manifest manifest, String outputDir) throws IOException {
        File dir = new File(outputDir);
        dir.mkdirs();

        File latestFile = new File(dir, "latest.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(latestFile, manifest);

        System.out.println("Latest manifest: " + latestFile.getAbsolutePath());
    }

    private void prepareVersionedReleaseDirectory(String version, String appDir, String updaterDir,
                                                  String outputDir) throws IOException {
        Path outputPath = Paths.get(outputDir);
        Path versionPath = outputPath.resolve(version);
        deleteDirectory(versionPath);
        Files.createDirectories(versionPath);

        copyDirectory(Paths.get(appDir), versionPath.resolve("app"));

        if (updaterDir != null && !updaterDir.isBlank()) {
            Path source = Paths.get(updaterDir);
            if (Files.exists(source)) {
                copyDirectory(source, versionPath.resolve("updater"));
            }
        }

        Path launcherPath = outputPath.resolve("YShell.exe");
        if (Files.exists(launcherPath)) {
            Files.copy(launcherPath, versionPath.resolve("YShell.exe"), StandardCopyOption.REPLACE_EXISTING);
        }

        try (Stream<Path> files = Files.list(outputPath)) {
            for (Path file : files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("YShell-runtime-" + version + "-"))
                    .toList()) {
                Files.copy(file, versionPath.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(file);
            }
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        if (!Files.exists(source)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path relativePath = source.relativize(path);
                Path targetPath = target.resolve(relativePath);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    Files.copy(path, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path item : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Usage: java -cp <classpath> com.yshell.service.ManifestGenerator <version> <javaVersion> <appDir> [runtimeDir] [changelog] [outputDir] [updaterScript] [updaterScriptName]");
            System.exit(1);
        }

        try {
            String version = args[0];
            String javaVersion = args[1];
            String appDir = args[2];
            String runtimeDir = args.length > 3 ? args[3] : null;
            String changelog = args.length > 4 ? args[4] : "";
            String outputDir = args.length > 5 ? args[5] : "target/manifest";
            String updaterScript = args.length > 6 ? args[6] : null;
            String updaterScriptName = args.length > 7 ? args[7] : null;

            ManifestGenerator generator = new ManifestGenerator();
            String updaterDir = generator.copyUpdaterScript(outputDir, updaterScript, updaterScriptName);
            Manifest manifest = generator.generateManifest(version, javaVersion, appDir, runtimeDir, updaterDir, outputDir, changelog);
            generator.prepareVersionedReleaseDirectory(version, appDir, updaterDir, outputDir);
            generator.writeManifest(manifest, outputDir);
        } catch (Exception e) {
            LOGGER.error("ManifestGenerator error", e);
            System.exit(1);
        }
    }

    private String copyUpdaterScript(String outputDir, String updaterScript, String updaterScriptName) throws IOException {
        if (updaterScript == null || updaterScript.isBlank()
                || updaterScriptName == null || updaterScriptName.isBlank()) {
            return null;
        }

        Path source = Paths.get(updaterScript);
        if (!Files.exists(source)) {
            return null;
        }

        Path updaterDir = Paths.get(outputDir, "updater");
        Files.createDirectories(updaterDir);
        Files.copy(source, updaterDir.resolve(updaterScriptName), StandardCopyOption.REPLACE_EXISTING);
        return updaterDir.toString();
    }
}
