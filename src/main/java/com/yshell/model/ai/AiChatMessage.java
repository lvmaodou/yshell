package com.yshell.model.ai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class AiChatMessage {
    public String id;
    public String role;
    public String content;
    public String thinking;
    public String model;
    public String createdAt;
    public List<AiImageAttachment> images;

    public AiChatMessage() {
    }

    public AiChatMessage(String id, String role, String content, String thinking, String model,
                         List<AiImageAttachment> images) {
        this.id = id == null ? "" : id;
        this.role = role == null ? "user" : role;
        this.content = content == null ? "" : content;
        this.thinking = thinking == null ? "" : thinking;
        this.model = model == null ? "" : model;
        this.createdAt = Instant.now().toString();
        this.images = images == null ? new ArrayList<>() : new ArrayList<>(images);
    }
}
