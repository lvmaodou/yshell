package com.yshell.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;

public class AppConfigStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(AppConfigStore.class);
    private static final AppConfigStore INSTANCE = new AppConfigStore();
    private static final String CONFIG_FILE = "config.json";

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Path configDir = Paths.get(System.getProperty("user.home"), ".yshell");
    private final Path configPath = configDir.resolve(CONFIG_FILE);
    private AppConfig config;

    private AppConfigStore() {
        load();
    }

    public static AppConfigStore getInstance() {
        return INSTANCE;
    }

    public synchronized AppConfig getConfig() {
        return config;
    }

    public Path getConfigPath() {
        return configPath;
    }

    public synchronized void save() {
        try {
            Files.createDirectories(configDir);
            Path temp = Files.createTempFile(configDir, "config-", ".tmp");
            mapper.writeValue(temp.toFile(), config);
            try {
                Files.move(temp, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("保存配置失败: {}", configPath, e);
        }
    }

    private void load() {
        try {
            Files.createDirectories(configDir);
            if (Files.exists(configPath)) {
                config = mapper.readValue(configPath.toFile(), AppConfig.class);
                normalize();
                return;
            }
            config = new AppConfig();
            normalize();
            save();
        } catch (Exception e) {
            LOGGER.error("加载配置失败，使用默认配置: {}", configPath, e);
            config = new AppConfig();
            normalize();
        }
    }

    private void normalize() {
        if (config == null) config = new AppConfig();
        config.version = Math.max(config.version, 1);
        if (config.appearance == null) config.appearance = new AppConfig.Appearance();
        if (config.update == null) config.update = new AppConfig.Update();
        if (config.terminal == null) config.terminal = new AppConfig.Terminal();
        if (config.editor == null) config.editor = new AppConfig.Editor();
        if (config.transfer == null) config.transfer = new AppConfig.Transfer();
        if (config.commands == null) config.commands = new AppConfig.Commands();
        if (config.layout == null) config.layout = new AppConfig.Layout();
        if (config.ai == null) config.ai = new AppConfig.Ai();
        if (config.ai.connections == null) config.ai.connections = new ArrayList<>();
        if (config.docker == null) config.docker = new AppConfig.Docker();
        if (config.docker.registries == null) config.docker.registries = new ArrayList<>();
        if (!"vs-light".equals(config.appearance.theme) && !"vs-dark".equals(config.appearance.theme)) {
            config.appearance.theme = "vs-dark";
        }
        config.terminal.defaultFontSize = clamp(config.terminal.defaultFontSize, 6, 22);
        config.terminal.scrollbackLines = clamp(config.terminal.scrollbackLines, 100, 100000);
        config.editor.defaultFontSize = clamp(config.editor.defaultFontSize, 8, 40);
        if (isBlank(config.terminal.defaultEncoding)) config.terminal.defaultEncoding = "UTF-8";
        if (isBlank(config.transfer.defaultDownloadDirectory)) {
            config.transfer.defaultDownloadDirectory = Paths.get(System.getProperty("user.home"), "Downloads", "Yshell").toString();
        }
        if (isBlank(config.transfer.uploadChooserDirectory)) {
            config.transfer.uploadChooserDirectory = System.getProperty("user.home");
        }
        if (isBlank(config.transfer.duplicateStrategy)) config.transfer.duplicateStrategy = "ASK";
        if (isBlank(config.ai.provider)) config.ai.provider = "OpenAI Compatible";
        if (config.ai.model == null) config.ai.model = "";
        if (config.ai.models == null) config.ai.models = "";
        if (config.ai.apiKey == null) config.ai.apiKey = "";
        if (isBlank(config.ai.baseUrl)) config.ai.baseUrl = AppConfig.AiModelConnection.OPENAI_BASE_URL;
        config.ai.temperature = clampDouble(config.ai.temperature);
        config.ai.maxOutputTokens = clamp(config.ai.maxOutputTokens, 256, 65536);
        migrateAiConnection();
        normalizeAiConnections();
        for (AppConfig.DockerRegistry registry : config.docker.registries) {
            if (registry == null) {
                continue;
            }
            if (registry.name == null) registry.name = "";
            if (registry.address == null) registry.address = "";
            if (registry.username == null) registry.username = "";
            if (registry.password == null) registry.password = "";
        }
    }

    private void migrateAiConnection() {
        if (!config.ai.connections.isEmpty()) {
            return;
        }
        AppConfig.AiModelConnection connection = new AppConfig.AiModelConnection();
        connection.id = java.util.UUID.randomUUID().toString();
        connection.name = isBlank(config.ai.model) ? "未命名连接" : config.ai.model;
        connection.apiFormat = "OPENAI_CHAT_COMPLETIONS";
        connection.baseUrl = config.ai.baseUrl;
        connection.apiKey = config.ai.apiKey;
        connection.model = config.ai.model;
        config.ai.connections.add(connection);
        config.ai.selectedConnectionId = connection.id;
    }

    private void normalizeAiConnections() {
        for (AppConfig.AiModelConnection connection : config.ai.connections) {
            if (connection == null) {
                continue;
            }
            if (isBlank(connection.id)) connection.id = java.util.UUID.randomUUID().toString();
            if (isBlank(connection.name)) connection.name = isBlank(connection.model) ? "未命名连接" : connection.model;
            if (!isSupportedAiApiFormat(connection.apiFormat)) {
                connection.apiFormat = "OPENAI_CHAT_COMPLETIONS";
            }
            if (isBlank(connection.baseUrl)) connection.baseUrl = defaultBaseUrlForFormat(connection.apiFormat);
            if (connection.apiKey == null) connection.apiKey = "";
            if (connection.model == null) connection.model = "";
        }
        config.ai.connections.removeIf(connection -> connection == null || isBlank(connection.id));
        if (config.ai.connections.isEmpty()) {
            migrateAiConnection();
        }
        boolean selectedExists = config.ai.connections.stream()
                .anyMatch(connection -> connection.id.equals(config.ai.selectedConnectionId));
        if (!selectedExists) {
            config.ai.selectedConnectionId = config.ai.connections.get(0).id;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clampDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(2.0, value));
    }

    private boolean isSupportedAiApiFormat(String value) {
        return "OPENAI_CHAT_COMPLETIONS".equals(value)
                || "OPENAI_RESPONSES".equals(value)
                || "ANTHROPIC_MESSAGES".equals(value)
                || "GEMINI_NATIVE".equals(value);
    }

    private String defaultBaseUrlForFormat(String apiFormat) {
        return switch (apiFormat) {
            case "ANTHROPIC_MESSAGES" -> AppConfig.AiModelConnection.ANTHROPIC_BASE_URL;
            case "GEMINI_NATIVE" -> AppConfig.AiModelConnection.GEMINI_BASE_URL;
            default -> AppConfig.AiModelConnection.OPENAI_BASE_URL;
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
