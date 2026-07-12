package com.yshell.logging;

import ch.qos.logback.core.PropertyDefinerBase;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class LogDirectoryPropertyDefiner extends PropertyDefinerBase {
    private static final String LOG_DIR_PROPERTY = "yshell.log.dir";
    private static final String LOG_DIR_ENV = "YSHELL_LOG_DIR";

    @Override
    public String getPropertyValue() {
        Path logDir = resolveConfiguredLogDir();
        if (logDir == null) {
            logDir = resolveInstallDir().resolve("log");
        }
        try {
            Files.createDirectories(logDir);
        } catch (Exception ignored) {
        }
        return logDir.toAbsolutePath().normalize().toString();
    }

    private Path resolveConfiguredLogDir() {
        String propertyValue = System.getProperty(LOG_DIR_PROPERTY);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return Paths.get(propertyValue);
        }
        String envValue = System.getenv(LOG_DIR_ENV);
        if (envValue != null && !envValue.isBlank()) {
            return Paths.get(envValue);
        }
        return null;
    }

    private Path resolveInstallDir() {
        Path source = resolveCodeSource();
        if (source == null) {
            return Paths.get(System.getProperty("user.dir", "."));
        }

        if (Files.isRegularFile(source)) {
            Path sourceDir = source.getParent();
            if (sourceDir != null && "app".equalsIgnoreCase(fileName(sourceDir))) {
                Path installDir = sourceDir.getParent();
                if (installDir != null) {
                    return installDir;
                }
            }
            return sourceDir != null ? sourceDir : Paths.get(System.getProperty("user.dir", "."));
        }

        if ("classes".equalsIgnoreCase(fileName(source))) {
            Path targetDir = source.getParent();
            if (targetDir != null && "target".equalsIgnoreCase(fileName(targetDir))) {
                Path projectDir = targetDir.getParent();
                if (projectDir != null) {
                    return projectDir;
                }
            }
        }

        return Paths.get(System.getProperty("user.dir", "."));
    }

    private Path resolveCodeSource() {
        try {
            URI uri = LogDirectoryPropertyDefiner.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI();
            return Paths.get(uri).toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String fileName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }
}
