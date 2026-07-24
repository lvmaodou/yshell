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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AiConversationRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiConversationRepository.class);
    private static final AiConversationRepository INSTANCE = new AiConversationRepository();
    private static final int MAX_CONVERSATIONS_PER_HOST = 50;

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

    public synchronized List<AiConversation> list(String hostKey) {
        String normalizedHostKey = normalizeHostKey(hostKey);
        if (normalizedHostKey.isBlank()) {
            return List.of();
        }
        sortAndTrim();
        return conversations.stream()
                .filter(conversation -> normalizedHostKey.equals(normalizeHostKey(conversation.hostKey)))
                .toList();
    }

    public synchronized AiConversation create() {
        return create("");
    }

    public synchronized AiConversation create(String hostKey) {
        AiConversation conversation = new AiConversation(java.util.UUID.randomUUID().toString(), normalizeHostKey(hostKey));
        conversations.add(0, conversation);
        save();
        return conversation;
    }

    public synchronized void upsert(AiConversation conversation) {
        if (conversation == null || conversation.id == null || conversation.id.isBlank()) {
            return;
        }
        conversation.hostKey = normalizeHostKey(conversation.hostKey);
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

    public synchronized void clear() {
        conversations.clear();
        try {
            Files.deleteIfExists(historyPath);
        } catch (Exception e) {
            LOGGER.warn("clear ai conversations failed: {}", historyPath, e);
        }
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
            conversation.hostKey = normalizeHostKey(conversation.hostKey);
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
        Map<String, Integer> conversationCounts = new HashMap<>();
        List<AiConversation> retained = new ArrayList<>();
        for (AiConversation conversation : conversations) {
            String hostKey = normalizeHostKey(conversation.hostKey);
            int count = conversationCounts.getOrDefault(hostKey, 0);
            if (count < MAX_CONVERSATIONS_PER_HOST) {
                retained.add(conversation);
                conversationCounts.put(hostKey, count + 1);
            }
        }
        conversations = retained;
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

    private String normalizeHostKey(String hostKey) {
        if (hostKey == null) {
            return "";
        }
        String normalized = hostKey.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.length() > 1 && normalized.startsWith("[") && normalized.endsWith("]")) {
            return normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }
}
