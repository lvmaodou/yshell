package com.yshell.model.ai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class AiConversation {
    public String id;
    public String title = "新话题";
    public String hostKey = "";
    public String createdAt;
    public String updatedAt;
    public List<AiChatMessage> messages = new ArrayList<>();

    public AiConversation() {
    }

    public AiConversation(String id, String hostKey) {
        this.id = id == null ? "" : id;
        this.hostKey = hostKey == null ? "" : hostKey;
        this.createdAt = Instant.now().toString();
        this.updatedAt = this.createdAt;
    }

    public void touch() {
        updatedAt = Instant.now().toString();
    }

    public Instant createdInstant() {
        return parseInstant(createdAt);
    }

    public Instant updatedInstant() {
        return parseInstant(updatedAt);
    }

    private Instant parseInstant(String value) {
        try {
            return value == null || value.isBlank() ? null : Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }
}
