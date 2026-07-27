package com.yshell.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yshell.model.SshKeyInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class SshKeyRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(SshKeyRepository.class);
    private static final String APP_DIR = ".yshell";
    private static final String DATA_FILE_NAME = "keys.json";

    private static SshKeyRepository instance;

    private final ObjectMapper objectMapper;
    private final Path dataFilePath;

    private SshKeyRepository() {
        objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        dataFilePath = Paths.get(System.getProperty("user.home"), APP_DIR, DATA_FILE_NAME);
    }

    public static synchronized SshKeyRepository getInstance() {
        if (instance == null) {
            instance = new SshKeyRepository();
        }
        return instance;
    }

    public List<SshKeyInfo> load() {
        if (!Files.exists(dataFilePath)) {
            return new ArrayList<>();
        }
        try {
            List<SshKeyInfo> keys = objectMapper.readValue(dataFilePath.toFile(), new TypeReference<>() {
            });
            if (normalize(keys)) {
                save(keys);
            }
            return keys;
        } catch (IOException e) {
            LOGGER.error("Load SSH keys failed: {}", dataFilePath, e);
            return new ArrayList<>();
        }
    }

    public void save(List<SshKeyInfo> keys) {
        try {
            Path parent = dataFilePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            normalize(keys);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(dataFilePath.toFile(), keys);
        } catch (IOException e) {
            LOGGER.error("Save SSH keys failed: {}", dataFilePath, e);
        }
    }

    public Optional<SshKeyInfo> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return load().stream().filter(key -> id.equals(key.getId())).findFirst();
    }

    public void upsert(SshKeyInfo key) {
        List<SshKeyInfo> keys = load();
        boolean updated = false;
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).getId().equals(key.getId())) {
                key.setModifiedTime(System.currentTimeMillis());
                keys.set(i, key);
                updated = true;
                break;
            }
        }
        if (!updated) {
            keys.add(key);
        }
        save(keys);
    }

    public void delete(String id) {
        List<SshKeyInfo> keys = load();
        keys.removeIf(key -> id != null && id.equals(key.getId()));
        save(keys);
    }

    private boolean normalize(List<SshKeyInfo> keys) {
        if (keys == null) {
            return false;
        }
        boolean changed = false;
        for (SshKeyInfo key : keys) {
            if (key.getId() == null || key.getId().isBlank()) {
                key.setId(UUID.randomUUID().toString().replace("-", ""));
                changed = true;
            }
            if (key.isSavePassphrase() || (key.getPassphrase() != null && !key.getPassphrase().isBlank())) {
                key.setSavePassphrase(false);
                key.setPassphrase("");
                changed = true;
            }
        }
        return changed;
    }
}
