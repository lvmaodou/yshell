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
        String model = config().ai.model;
        return model == null ? "" : model;
    }

    public void setAiModel(String model) {
        config().ai.model = model == null ? "" : model.trim();
        store.save();
    }

    public List<String> getAiModels() {
        return getAiConnections().stream().map(this::formatAiConnection).toList();
    }

    public String getAiModelsText() {
        return config().ai.models == null ? "" : config().ai.models;
    }

    public void setAiModelsText(String models) {
        config().ai.models = models == null ? "" : models.trim();
        store.save();
    }

    public String getAiApiKey() {
        return config().ai.apiKey == null ? "" : config().ai.apiKey;
    }

    public void setAiApiKey(String apiKey) {
        config().ai.apiKey = apiKey == null ? "" : apiKey;
        store.save();
    }

    public String getAiBaseUrl() {
        String baseUrl = config().ai.baseUrl;
        return baseUrl == null || baseUrl.isBlank() ? AppConfig.AiModelConnection.OPENAI_BASE_URL : baseUrl.trim();
    }

    public void setAiBaseUrl(String baseUrl) {
        config().ai.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        store.save();
    }

    public String getAiProvider() {
        String provider = config().ai.provider;
        return provider == null || provider.isBlank() ? "OpenAI Compatible" : provider;
    }

    public void setAiProvider(String provider) {
        config().ai.provider = provider == null ? "OpenAI Compatible" : provider.trim();
        store.save();
    }

    public boolean isAiStreamOutputEnabled() {
        return config().ai.streamOutput;
    }

    public void setAiStreamOutputEnabled(boolean enabled) {
        config().ai.streamOutput = enabled;
        store.save();
    }

    public boolean isAiThinkingEnabled() {
        return config().ai.thinkingEnabled;
    }

    public void setAiThinkingEnabled(boolean enabled) {
        config().ai.thinkingEnabled = enabled;
        store.save();
    }

    public double getAiTemperature() {
        return clampDouble(config().ai.temperature);
    }

    public void setAiTemperature(double temperature) {
        config().ai.temperature = clampDouble(temperature);
        store.save();
    }

    public int getAiMaxOutputTokens() {
        return clamp(config().ai.maxOutputTokens, 256, 65536);
    }

    public void setAiMaxOutputTokens(int maxOutputTokens) {
        config().ai.maxOutputTokens = clamp(maxOutputTokens, 256, 65536);
        store.save();
    }

    public List<AppConfig.AiModelConnection> getAiConnections() {
        return new ArrayList<>(config().ai.connections);
    }

    public void setAiConnections(List<AppConfig.AiModelConnection> connections) {
        config().ai.connections = connections == null ? new ArrayList<>() : new ArrayList<>(connections);
        if (config().ai.connections.isEmpty()) {
            AppConfig.AiModelConnection connection = defaultAiConnection();
            config().ai.connections.add(connection);
            config().ai.selectedConnectionId = connection.id;
        } else if (getSelectedAiConnection() == null) {
            config().ai.selectedConnectionId = config().ai.connections.get(0).id;
        }
        store.save();
    }

    public AppConfig.AiModelConnection getSelectedAiConnection() {
        String selectedId = config().ai.selectedConnectionId;
        for (AppConfig.AiModelConnection connection : config().ai.connections) {
            if (connection != null && connection.id != null && connection.id.equals(selectedId)) {
                return connection;
            }
        }
        return config().ai.connections.isEmpty() ? null : config().ai.connections.get(0);
    }

    public void setSelectedAiConnectionId(String id) {
        config().ai.selectedConnectionId = id == null ? "" : id;
        store.save();
    }

    public String getSelectedAiConnectionId() {
        return config().ai.selectedConnectionId == null ? "" : config().ai.selectedConnectionId;
    }

    public String formatAiConnection(AppConfig.AiModelConnection connection) {
        if (connection == null) {
            return "";
        }
        return connection.name == null || connection.name.isBlank() ? connection.model : connection.name;
    }

    public AppConfig.AiModelConnection defaultAiConnection() {
        AppConfig.AiModelConnection connection = new AppConfig.AiModelConnection();
        connection.id = java.util.UUID.randomUUID().toString();
        connection.name = "新建连接";
        connection.apiFormat = "OPENAI_CHAT_COMPLETIONS";
        connection.baseUrl = AppConfig.AiModelConnection.OPENAI_BASE_URL;
        connection.model = "";
        return connection;
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

    private static double clampDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(2.0, value));
    }

    private static String blankToDefault(String value) {
        return value == null || value.isBlank() ? "UTF-8" : value;
    }
}
