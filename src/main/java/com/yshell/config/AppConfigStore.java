package com.yshell.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

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

    public Path getConfigDir() {
        return configDir;
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

    public synchronized void reload() {
        load();
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
        if (config.ai.model == null) config.ai.model = "";
        if (config.ai.apiKey == null) config.ai.apiKey = "";
        if (config.ai.baseUrl == null) config.ai.baseUrl = "";
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

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
