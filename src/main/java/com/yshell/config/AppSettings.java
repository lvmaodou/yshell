package com.yshell.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class AppSettings {
    public enum DuplicateStrategy {
        ASK("询问"),
        OVERWRITE("覆盖"),
        SKIP("跳过"),
        RENAME("自动重命名");

        private final String label;

        DuplicateStrategy(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        public static DuplicateStrategy fromLabel(String label) {
            for (DuplicateStrategy strategy : values()) {
                if (strategy.label.equals(label) || strategy.name().equalsIgnoreCase(label)) {
                    return strategy;
                }
            }
            return ASK;
        }
    }

    private static final AppSettings INSTANCE = new AppSettings();
    private final AppConfigStore store = AppConfigStore.getInstance();

    private AppSettings() {
    }

    public static AppSettings getInstance() {
        return INSTANCE;
    }

    public boolean isStartupUpdateCheckEnabled() {
        return config().update.startupCheckEnabled;
    }

    public void setStartupUpdateCheckEnabled(boolean enabled) {
        config().update.startupCheckEnabled = enabled;
        store.save();
    }

    public int getTerminalDefaultFontSize() {
        return clamp(config().terminal.defaultFontSize, 6, 22);
    }

    public void setTerminalDefaultFontSize(int size) {
        config().terminal.defaultFontSize = clamp(size, 6, 22);
        store.save();
    }

    public String getTerminalDefaultEncoding() {
        return blankToDefault(config().terminal.defaultEncoding);
    }

    public void setTerminalDefaultEncoding(String encoding) {
        config().terminal.defaultEncoding = blankToDefault(encoding);
        store.save();
    }

    public int getTerminalDefaultBackspaceSequence() {
        return config().terminal.defaultBackspaceSequence;
    }

    public void setTerminalDefaultBackspaceSequence(int sequence) {
        config().terminal.defaultBackspaceSequence = sequence;
        store.save();
    }

    public int getTerminalDefaultDeleteSequence() {
        return config().terminal.defaultDeleteSequence;
    }

    public void setTerminalDefaultDeleteSequence(int sequence) {
        config().terminal.defaultDeleteSequence = sequence;
        store.save();
    }

    public int getTerminalScrollbackLines() {
        return clamp(config().terminal.scrollbackLines, 100, 100000);
    }

    public void setTerminalScrollbackLines(int lines) {
        config().terminal.scrollbackLines = clamp(lines, 100, 100000);
        store.save();
    }

    public int getEditorDefaultFontSize() {
        return clamp(config().editor.defaultFontSize, 8, 40);
    }

    public void setEditorDefaultFontSize(int size) {
        config().editor.defaultFontSize = clamp(size, 8, 40);
        store.save();
    }

    public Path getTransferDefaultDownloadDirectory() {
        return Paths.get(config().transfer.defaultDownloadDirectory);
    }

    public void setTransferDefaultDownloadDirectory(Path directory) {
        if (directory != null) {
            config().transfer.defaultDownloadDirectory = directory.toString();
            store.save();
        }
    }

    public Path getTransferUploadChooserDirectory() {
        return Paths.get(config().transfer.uploadChooserDirectory);
    }

    public void setTransferUploadChooserDirectory(Path directory) {
        if (directory != null) {
            config().transfer.uploadChooserDirectory = directory.toString();
            store.save();
        }
    }

    public DuplicateStrategy getTransferDuplicateStrategy() {
        String value = config().transfer.duplicateStrategy;
        try {
            return DuplicateStrategy.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            return DuplicateStrategy.ASK;
        }
    }

    public void setTransferDuplicateStrategy(DuplicateStrategy strategy) {
        config().transfer.duplicateStrategy = (strategy == null ? DuplicateStrategy.ASK : strategy).name();
        store.save();
    }

    public boolean isTransferCloseQueueWhenFinished() {
        return config().transfer.closeQueueWhenFinished;
    }

    public void setTransferCloseQueueWhenFinished(boolean enabled) {
        config().transfer.closeQueueWhenFinished = enabled;
        store.save();
    }

    public boolean isTransferClearFinishedWhenDone() {
        return config().transfer.clearFinishedWhenDone;
    }

    public void setTransferClearFinishedWhenDone(boolean enabled) {
        config().transfer.clearFinishedWhenDone = enabled;
        store.save();
    }

    public boolean isAiEnabled() {
        return config().ai.enabled;
    }

    public void setAiEnabled(boolean enabled) {
        config().ai.enabled = enabled;
        store.save();
    }

    public String getAiModel() {
        return config().ai.model;
    }

    public void setAiModel(String model) {
        config().ai.model = model == null ? "" : model;
        store.save();
    }

    public String getAiApiKey() {
        return config().ai.apiKey;
    }

    public void setAiApiKey(String apiKey) {
        config().ai.apiKey = apiKey == null ? "" : apiKey;
        store.save();
    }

    public String getAiBaseUrl() {
        return config().ai.baseUrl;
    }

    public void setAiBaseUrl(String baseUrl) {
        config().ai.baseUrl = baseUrl == null ? "" : baseUrl;
        store.save();
    }

    public List<AppConfig.DockerRegistry> getDockerRegistries() {
        return config().docker.registries;
    }

    public void setDockerRegistries(List<AppConfig.DockerRegistry> registries) {
        config().docker.registries = registries == null ? new ArrayList<>() : new ArrayList<>(registries);
        store.save();
    }

    public void save() {
        store.save();
    }

    private AppConfig config() {
        return store.getConfig();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String blankToDefault(String value) {
        return value == null || value.isBlank() ? "UTF-8" : value;
    }
}
