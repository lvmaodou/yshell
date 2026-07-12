package com.yshell.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yshell.config.AppConfigStore;
import com.yshell.model.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class CommandRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandRepository.class);
    private static final String APP_DIR = ".yshell";
    private static final String DATA_FILE_NAME = "commands.json";
    private static final String DEFAULT_RESOURCE = "/commands.json";

    private static CommandRepository instance;

    private final ObjectMapper objectMapper;
    private final Path dataFilePath;

    private CommandRepository() {
        objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        Path appDirPath = Paths.get(System.getProperty("user.home"), APP_DIR);
        dataFilePath = appDirPath.resolve(DATA_FILE_NAME);
    }

    public static synchronized CommandRepository getInstance() {
        if (instance == null) {
            instance = new CommandRepository();
        }
        return instance;
    }

    public List<Command> load() {
        if (!Files.exists(dataFilePath)) {
            return seedDefaults();
        }
        try {
            List<Command> commands = objectMapper.readValue(
                    dataFilePath.toFile(),
                    new TypeReference<>() {
                    }
            );
            normalize(commands);
            if (commands.isEmpty() && !isSeeded()) {
                return seedDefaults();
            }
            markSeeded();
            return commands;
        } catch (IOException e) {
            LOGGER.error("加载命令收藏失败: {}", dataFilePath, e);
            return new ArrayList<>();
        }
    }

    private List<Command> seedDefaults() {
        List<Command> defaults = loadDefaults();
        if (!defaults.isEmpty()) {
            save(defaults);
            markSeeded();
        }
        return defaults;
    }

    private List<Command> loadDefaults() {
        try (var input = CommandRepository.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (input == null) {
                return new ArrayList<>();
            }
            List<Command> commands = objectMapper.readValue(
                    input,
                    new TypeReference<>() {
                    }
            );
            normalize(commands);
            return commands;
        } catch (IOException e) {
            LOGGER.error("加载默认命令收藏失败: {}", DEFAULT_RESOURCE, e);
            return new ArrayList<>();
        }
    }

    private void markSeeded() {
        if (!isSeeded()) {
            AppConfigStore.getInstance().getConfig().commands.seeded = true;
            AppConfigStore.getInstance().save();
        }
    }

    private boolean isSeeded() {
        return AppConfigStore.getInstance().getConfig().commands.seeded;
    }

    public void save(List<Command> commands) {
        try {
            Path parent = dataFilePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            normalize(commands);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(dataFilePath.toFile(), commands);
        } catch (IOException e) {
            LOGGER.error("保存命令收藏失败: {}", dataFilePath, e);
        }
    }

    private void normalize(List<Command> commands) {
        if (commands == null) {
            return;
        }
        for (Command command : commands) {
            normalize(command);
        }
    }

    private void normalize(Command command) {
        if (command == null) {
            return;
        }
        if (command.getId() == null || command.getId().isBlank()) {
            command.setId(UUID.randomUUID().toString());
        }
        if (command.getType() == null || command.getType().isBlank()) {
            command.setType(command.getCommand() == null || command.getCommand().isBlank() ? "category" : "command");
        }
        if (command.getChildren() == null) {
            command.setChildren(new ArrayList<>());
        }
        for (Command child : command.getChildren()) {
            if (child != null && (child.getCategoryId() == null || child.getCategoryId().isBlank())) {
                child.setCategoryId(command.getId());
            }
            normalize(child);
        }
    }
}
