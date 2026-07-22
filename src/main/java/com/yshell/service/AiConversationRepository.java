package com.yshell.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yshell.logging.LogDirectoryPropertyDefiner;
import com.yshell.model.ai.AiConversation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AiConversationRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiConversationRepository.class);
    private static final AiConversationRepository INSTANCE = new AiConversationRepository();
    private static final int MAX_CONVERSATIONS = 50;

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Path historyDir;
    private final Path historyPath;
    private List<AiConversation> conversations = new ArrayList<>();

    private AiConversationRepository() {
        Path logDir = Paths.get(new LogDirectoryPropertyDefiner().getPropertyValue());
        Path baseDir = logDir.getParent() == null ? logDir : logDir.getParent();
        historyDir = baseDir.resolve("ai-history");
        historyPath = historyDir.resolve("conversations.json");
        load();
    }

    public static AiConversationRepository getInstance() {
        return INSTANCE;
    }

    public synchronized List<AiConversation> list() {
        sortAndTrim();
        return new ArrayList<>(conversations);
    }

    public synchronized AiConversation create() {
        AiConversation conversation = new AiConversation(java.util.UUID.randomUUID().toString());
        conversations.add(0, conversation);
        save();
        return conversation;
    }

    public synchronized void upsert(AiConversation conversation) {
        if (conversation == null || conversation.id == null || conversation.id.isBlank()) {
            return;
        }
        conversations.removeIf(existing -> conversation.id.equals(existing.id));
        conversations.add(0, conversation);
        save();
    }

    public synchronized void delete(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        conversations.removeIf(existing -> id.equals(existing.id));
        save();
    }

    private void load() {
        try {
            Files.createDirectories(historyDir);
            if (Files.exists(historyPath)) {
                conversations = mapper.readValue(historyPath.toFile(), new TypeReference<>() {
                });
            }
            normalize();
        } catch (Exception e) {
            LOGGER.warn("load ai conversations failed: {}", historyPath, e);
            conversations = new ArrayList<>();
        }
    }

    private void normalize() {
        if (conversations == null) {
            conversations = new ArrayList<>();
        }
        String now = Instant.now().toString();
        for (AiConversation conversation : conversations) {
            if (conversation.id == null || conversation.id.isBlank()) {
                conversation.id = java.util.UUID.randomUUID().toString();
            }
            if (isBlank(conversation.createdAt) || parseInstant(conversation.createdAt) == null) {
                conversation.createdAt = now;
            }
            if (isBlank(conversation.updatedAt) || parseInstant(conversation.updatedAt) == null) {
                conversation.updatedAt = conversation.createdAt;
            }
            if (conversation.title == null || conversation.title.isBlank()) {
                conversation.title = "新话题";
            }
            if (conversation.messages == null) {
                conversation.messages = new ArrayList<>();
            }
        }
        sortAndTrim();
    }

    private void save() {
        try {
            Files.createDirectories(historyDir);
            sortAndTrim();
            Path temp = Files.createTempFile(historyDir, "conversations-", ".tmp");
            mapper.writeValue(temp.toFile(), conversations);
            try {
                Files.move(temp, historyPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, historyPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LOGGER.warn("save ai conversations failed: {}", historyPath, e);
        }
    }

    private void sortAndTrim() {
        conversations.sort(Comparator.comparing((AiConversation item) ->
                instantOrDefault(item.updatedAt)).reversed());
        if (conversations.size() > MAX_CONVERSATIONS) {
            conversations = new ArrayList<>(conversations.subList(0, MAX_CONVERSATIONS));
        }
    }

    private Instant instantOrDefault(String value) {
        Instant instant = parseInstant(value);
        return instant == null ? Instant.EPOCH : instant;
    }

    private Instant parseInstant(String value) {
        try {
            return isBlank(value) ? null : Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
